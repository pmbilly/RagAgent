package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agent.AgentConfig;
import com.ragagent.agent.AgentConsts;
import com.ragagent.agent.AgentEngine;
import com.ragagent.agent.AgentPrompts;
import com.ragagent.agent.AgentToolNames;
import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.ErrorData;
import com.ragagent.event.EventType;
import com.ragagent.event.MemoryRecalledData;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.model.service.ModelService;
import com.ragagent.rerank.Reranker;
import com.ragagent.agent.domain.AgentStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.UsedMemory;

import static com.ragagent.session.service.SessionKnowledgeQaService.SearchTargetView;

/**
 * agent 问答 service 面（对照 Go session_agent_qa.go 全文 + agent_history.go 的
 * 历史装载 + agent_service.go 的 CreateAgentEngine 装配路径）。
 *
 * <p>装配边界（对照 agent_service.go L180-295，每一条的取舍已在报告备案）：
 * sandbox/MCP/browser/skills 的生产接线在 dev 部署（无 docker、无 MCP 服务、
 * 无 browser 集成）与 Go 的 nil/disable 分支一致——工具注册的硬门控逐条保留；
 * 检索工具族（knowledge_search 等）在检索执行面缺失时注册同样无产出，
 * 因此 dev 路径注册的核心是 thinking/todo_write/工具白名单可达集。</p>
 */
@Service
public class SessionAgentQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentQaService.class);

    /** 对照 agent_service.go L30（MaxIterations 上限）。 */
    private static final int MAX_ITERATIONS = 100;

    private static final int AGENT_HISTORY_FETCH_MULTIPLIER = 2;
    private static final int AGENT_HISTORY_FETCH_MIN = 20;

    private final MessageService messageService;
    private final ModelService modelService;
    private final MemoryService memoryService;
    private final SessionKnowledgeQaService knowledgeQa;
    private final com.ragagent.agentm.service.CustomAgentService customAgentService;
    private final com.ragagent.agentm.service.BuiltinAgentRegistry builtinAgentRegistry;
    private final SessionSandboxExecutionService sandboxExecution;
    private final SessionAttachmentStagingService attachmentStaging;
    private final AgentToolBackends toolBackends;
    /** 并发闸门（对照 Go container 的 chat 工厂注入；null 会让 ConcurrencyChatClient NPE）。 */
    private final com.ragagent.llm.limiter.ConcurrencyGovernor concurrencyGovernor;
    private final org.springframework.beans.factory.ObjectProvider<com.ragagent.llm.ollama.OllamaService>
            ollamaService;
    public SessionAgentQaService(MessageService messageService,
            ModelService modelService,
            MemoryService memoryService,
            SessionKnowledgeQaService knowledgeQa,
            com.ragagent.agentm.service.CustomAgentService customAgentService,
            com.ragagent.agentm.service.BuiltinAgentRegistry builtinAgentRegistry,
            SessionSandboxExecutionService sandboxExecution,
            SessionAttachmentStagingService attachmentStaging,
            AgentToolBackends toolBackends,
            com.ragagent.llm.limiter.ConcurrencyGovernor concurrencyGovernor,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.llm.ollama.OllamaService>
                    ollamaService) {
        this.concurrencyGovernor = concurrencyGovernor;
        this.ollamaService = ollamaService;
        this.messageService = messageService;
        this.modelService = modelService;
        this.memoryService = memoryService;
        this.knowledgeQa = knowledgeQa;
        this.customAgentService = customAgentService;
        this.builtinAgentRegistry = builtinAgentRegistry;
        this.sandboxExecution = sandboxExecution;
        this.attachmentStaging = attachmentStaging;
        this.toolBackends = toolBackends;
    }

    // ==================================================================
    // AgentQA（session_agent_qa.go L20-287）
    // ==================================================================

    public void agentQA(QaSupport.QaRequest req, EventBus eventBus) {
        String sessionId = req.session.getId();
        if (req.agentConfig == null) {
            log.warn("Custom agent not provided for session: {}", sessionId);
            throw new RuntimeException("custom agent configuration is required for agent QA");
        }

        long agentTenantId = knowledgeQa.resolveRetrievalTenantId(req);
        log.info("Start agent-based question answering, session ID: {}, agent tenant ID: {}, query: {}",
                sessionId, agentTenantId, req.query);

        // EnsureDefaults（Go L65；config 树在 parseQARequest 已跑一遍，这里再钉一次）
        AgentConfigJson.ensureDefaults(req.agentConfig);

        // Build AgentConfig
        QaAgentConfig agentConfig = buildAgentConfig(req, agentTenantId);

        // 回合租约（Go session_agent_qa.go L168 的 holdSandboxTurn）：防技能镜像
        // 变更在回合中途重建 VM；staging 与引擎执行都在租约窗口内。
        try (var sandboxTurnLease = sandboxExecution.holdSandboxTurn(
                agentTenantId, sessionId, agentConfig.getSandboxConfigId())) {
            // 附件 staging（Go session_agent_qa.go L172-196）：把会话持久附件
            // 物化进沙箱 /workspace/input；staged 清单在查询组合时注入提示。
            // Go 侧 staging 失败即回合失败——这里同样让异常上抛。
            List<SessionAttachmentStagingService.StagedSessionAttachment> stagedAttachments =
                    new ArrayList<>();
            if (attachmentStaging.sessionSandboxInputStore(
                    agentTenantId, sessionId, agentConfig.getSandboxConfigId()) != null) {
                stagedAttachments = attachmentStaging.stageSessionAttachments(
                        agentTenantId, sessionId, agentConfig.getSandboxConfigId(),
                        messageService.getSessionAttachments(sessionId));
            }
            // VLM runtime field
            String vlm = req.agentConfig.path("vlm_model_id").asText("");
            if (!vlm.isEmpty()) {
                agentConfig.setVlmModelId(vlm);
            }

            // Resolve model ID
            String effectiveModelId = knowledgeQa.resolveChatModelId(req, agentConfig.getKnowledgeBases(),
                    agentConfig.getKnowledgeIds());
            if (effectiveModelId.isEmpty()) {
                throw new RuntimeException("summary model (model_id) is not configured in custom agent settings");
            }
            LlmChatClient summaryModel = chatModel(effectiveModelId);

            boolean supportsVision = false;
            int modelContextWindow = 0;
            try {
                var info = modelService.getModelByID(effectiveModelId);
                if (info != null && info.getParameters() != null) {
                    supportsVision = info.getParameters().isSupportsVision();
                    modelContextWindow = info.getParameters().getContextWindow();
                }
            } catch (RuntimeException e) {
                // Go: err != nil → 零值
            }
            agentConfig.setChatModelSupportsVision(supportsVision);
            // AgentMaxContextTokens（types/agent.go L24-32）：显式设置 > 模型声明 > 缺省
            agentConfig.setMaxContextTokens(agentConfig.getMaxContextTokens() > 0
                    ? agentConfig.getMaxContextTokens()
                    : (modelContextWindow > 0 ? modelContextWindow
                            : com.ragagent.agent.AgentBudgets.DEFAULT_MAX_CONTEXT_TOKENS));
            log.info("Agent context window: {} tokens (model {} declares {})",
                    agentConfig.getMaxContextTokens(), effectiveModelId, modelContextWindow);

            // Rerank model only when knowledge_search can run
            Reranker rerankModel = null;
            if (agentRequiresRerankModel(req.agentConfig)) {
                String rerankModelId = req.agentConfig.path("rerank_model_id").asText("");
                if (rerankModelId.isEmpty()) {
                    throw new RuntimeException("rerank model is not configured: please set rerank_model_id on the agent");
                }
                rerankModel = rerankModel(rerankModelId);
            } else {
                log.info("knowledge_search is unavailable for the effective agent scope, "
                        + "skipping rerank model initialization");
            }

            // Multi-turn history（agent_history.go LoadAgentHistory）
            List<ChatMessage> llmContext = new ArrayList<>();
            if (agentConfig.isMultiTurnEnabled()) {
                int historyTurns = agentConfig.getHistoryTurns() <= 0 ? 5 : agentConfig.getHistoryTurns();
                try {
                    llmContext = loadAgentHistory(sessionId, historyTurns);
                } catch (RuntimeException e) {
                    log.warn("Failed to load agent history from DB: {}, continuing without history", e.toString());
                    llmContext = new ArrayList<>();
                }
                log.info("Loaded {} history messages from DB (turns={})", llmContext.size(), historyTurns);
            } else {
                log.info("Multi-turn disabled for this agent, running without history");
            }

            // Create agent engine（agent_service.go CreateAgentEngine 装配）
            AgentEngine engine = createAgentEngine(agentConfig, summaryModel, rerankModel, eventBus,
                    sessionId, req.assistantMessageId);

            // Memory recall
            if (memoryService != null && req.agentConfig.path("memory_enabled").asBoolean(false)) {
                var recall = memoryService.recall(req.query);
                if (recall != null && recall.prompt() != null && !recall.prompt().isEmpty()) {
                    engine.setMemoryPrompt(recall.prompt());
                    List<UsedMemory> used = new ArrayList<>();
                    if (recall.items() != null) {
                        for (var item : recall.items()) {
                            UsedMemory um = new UsedMemory();
                            um.setId(item.getId());
                            um.setContent(item.getContent());
                            used.add(um);
                        }
                    }
                    Event evt = new Event();
                    evt.setType(EventType.EVENT_MEMORY_RECALLED);
                    evt.setSessionId(sessionId);
                    evt.setData(new MemoryRecalledData(used));
                    try {
                        eventBus.emit(evt);
                    } catch (RuntimeException e) {
                        log.warn("Failed to emit memory recalled event: {}", e.toString());
                    }
                    log.info("Injected {} long-term memories into agent context", used.size());
                }
            }

            // Steer sink
            if (req.steerSink != null) {
                engine.setSteerSink(req.steerSink);
            }

            // Query composition（Go L241-263）
            String agentQuery = req.query;
            List<String> agentImageUrls = new ArrayList<>();
            if (supportsVision && req.imageUrls != null && !req.imageUrls.isEmpty()) {
                agentImageUrls = req.imageUrls;
                log.info("Agent model supports vision, passing {} image(s) directly", agentImageUrls.size());
            } else if (!req.imageDescription.isEmpty()) {
                agentQuery = req.query + "\n\n[用户上传图片内容]\n" + req.imageDescription;
                log.info("Agent model does not support vision, appending image description ({} chars)",
                        req.imageDescription.length());
            }
            if (!req.quotedContext.isEmpty()) {
                agentQuery += "\n\n" + req.quotedContext;
            }
            if (!req.attachments.isEmpty()) {
                agentQuery += com.ragagent.chatpipeline.MessageAttachmentsPrompt.build(req.attachments);
                log.info("Appended {} attachment(s) to agent query", req.attachments.size());
            }

            // Execute（Go L272-284：失败 emit error 事件后返回 nil）
            try {
                engine.execute(sessionId, req.assistantMessageId, agentQuery, llmContext, agentImageUrls);
            } catch (RuntimeException e) {
                log.error("Agent execution failed: {}", e.toString());
                Event evt = new Event();
                evt.setType(EventType.EVENT_ERROR);
                evt.setSessionId(sessionId);
                ErrorData errData = new ErrorData();
                errData.setError(e.getMessage());
                errData.setStage("agent_execution");
                errData.setSessionId(sessionId);
                evt.setData(errData);
                eventBus.emit(evt);
            }
            // sandbox staged 附件提示（Go L261-264）
            if (!stagedAttachments.isEmpty()) {
                agentQuery += SessionAttachmentStagingService.buildSandboxAttachmentsPrompt(stagedAttachments);
                log.info("Appended {} staged sandbox attachment path(s) to agent query",
                        stagedAttachments.size());
            }
        }
    }

    // ==================================================================
    // buildAgentConfig（session_agent_qa.go L291-430）
    // ==================================================================

    private QaAgentConfig buildAgentConfig(QaSupport.QaRequest req, long agentTenantId) {
        ObjectNode c = AgentConfigJson.ensureDefaults(req.agentConfig);
        QaAgentConfig ac = new QaAgentConfig();
        ac.setMaxIterations(c.path("max_iterations").asInt(0));
        ac.setTemperature(c.path("temperature").asDouble(0.7));
        ac.setWebSearchEnabled(c.path("web_search_enabled").asBoolean(false) && req.webSearchEnabled);
        ac.setLocalBrowserEnabled(req.localBrowserEnabled);
        ac.setWebSearchMaxResults(c.path("web_search_max_results").asInt(0));
        ac.setWebSearchProviderId(c.path("web_search_provider_id").asText(""));
        ac.setMultiTurnEnabled(c.path("multi_turn_enabled").asBoolean(true));
        ac.setHistoryTurns(c.path("history_turns").asInt(0));
        ac.setMemoryEnabled(c.path("memory_enabled").asBoolean(false));
        ac.setMcpSelectionMode(c.path("mcp_selection_mode").asText(""));
        ac.setMcpAuthWaitTimeout(c.path("mcp_auth_wait_timeout").asInt(0));
        JsonNode thinking = c.get("thinking");
        ac.setThinking(thinking != null && thinking.isBoolean() ? thinking.asBoolean() : null);
        ac.setCitationEnabled(c.path("citation_enabled").asBoolean(true));
        ac.setRetrieveKbOnlyWhenMentioned(c.path("retrieve_kb_only_when_mentioned").asBoolean(false));
        ac.setLlmCallTimeout(c.path("llm_call_timeout").asInt(0));
        ac.setMaxCompletionTokens(c.path("max_completion_tokens").asInt(0));
        ac.setRetainRetrievalHistory(c.path("retain_retrieval_history").asBoolean(false));
        ac.setSharedAgentReadOnly(req.sharedAgentReadOnly);

        // Sandbox config（L326 的 configureSkillsFromAgent 前段）
        ac.setSandboxConfigId(c.path("sandbox_config_id").asText(""));

        // skills 配置（configureSkillsFromAgent，Go L616-647）
        String skillsMode = c.path("skills_selection_mode").asText("");
        switch (skillsMode) {
            case "all" -> {
                ac.setSkillsEnabled(true);
                ac.setAllowedSkills(null);
                log.info("SkillsSelectionMode=all: using installed sandbox skills");
            }
            case "selected" -> {
                List<String> selected = stringListOf(c.get("selected_skills"));
                if (!selected.isEmpty()) {
                    ac.setSkillsEnabled(true);
                    ac.setAllowedSkills(selected);
                    log.info("SkillsSelectionMode=selected: enabled {} selected skills: {}", selected.size(), selected);
                } else {
                    ac.setSkillsEnabled(false);
                    log.info("SkillsSelectionMode=selected but no skills selected: skills disabled");
                }
            }
            case "none", "" -> {
                ac.setSkillsEnabled(false);
                log.info("SkillsSelectionMode={}: skills disabled", skillsMode);
            }
            default -> {
                ac.setSkillsEnabled(false);
                log.warn("Unknown SkillsSelectionMode={}: skills disabled", skillsMode);
            }
        }

        // 然后并入本轮沙箱镜像里已安装的技能（Go L333-343：skillsForRun 以会话
        // 已 pin 的配置为准——与沙箱解析同路径；行集已按 ready/enabled/快照生效收窄）
        var runSkills = sandboxExecution.skillsForRun(
                agentTenantId, req.session.getId(), ac.getSandboxConfigId());
        ac.setTenantSkills(runSkills.rows());
        if (!runSkills.rows().isEmpty()) {
            log.info("Sandbox config {} offers {} installed skill(s) to this run",
                    runSkills.configId(), runSkills.rows().size());
        }

        // Resolve knowledge bases
        var kb = knowledgeQa.resolveKnowledgeBases(req);
        ac.setKnowledgeBases(kb.kbIds());
        ac.setKnowledgeIds(kb.knowledgeIds());

        // Allowed tools
        List<String> allowed = stringListOf(c.get("allowed_tools"));
        ac.setAllowedTools(allowed.isEmpty()
                ? new ArrayList<>(com.ragagent.agent.tools.ToolDefinitions.defaultAllowedTools())
                : allowed);

        // Per-request skill/MCP scope（Go L364-366）
        applyPerRequestSkillScope(ac, skillsMode, req.skillNames);
        applyPerRequestMcpScope(ac, stringListOf(c.get("mcp_services")),
                req.sharedAgentReadOnly, req.mcpServiceIds);

        // Custom system prompt（Go L369-372）
        Prompts prompts = resolveAgentPrompts(req);
        if (!prompts.system().isEmpty()) {
            ac.setUseCustomSystemPrompt(true);
            ac.setSystemPrompt(prompts.system());
        }

        log.info("Custom agent config applied: MaxIterations={}, Temperature={}, AllowedTools={}, WebSearchEnabled={}",
                ac.getMaxIterations(), ac.getTemperature(), ac.getAllowedTools(), ac.isWebSearchEnabled());

        // Web search max results（tenant 兜底 5）
        if (ac.getWebSearchMaxResults() == 0) {
            ac.setWebSearchMaxResults(5);
        }
        // web_search provider 默认解析（Go L387-390）由 handler 层 web repo 完成，dev 空。

        log.info("Merged agent config from tenant {} and session {}", agentTenantId, req.session.getId());

        // Search targets（Go L407-424）
        List<SearchTargetView> targets;
        try {
            targets = knowledgeQa.buildSearchTargets(agentTenantId, ac.getKnowledgeBases(),
                    ac.getKnowledgeIds(), req.tagScopes);
        } catch (RuntimeException e) {
            throw new RuntimeException("build search targets: " + e.getMessage(), e);
        }
        ac.setSearchTargets(new SearchTargets(SessionKnowledgeQaService.SearchTargetView.toPipeline(targets)));
        log.info("Agent search targets built: {} targets", targets.size());

        return ac;
    }

    private record Prompts(String system, String context) {}

    private Prompts resolveAgentPrompts(QaSupport.QaRequest req) {
        ObjectNode c = req.agentConfig;
        String system = c.path("system_prompt").asText("");
        String context = c.path("context_template").asText("");
        boolean agentMode = SessionKnowledgeQaService.isAgentMode(c);
        if (system.isEmpty()) {
            String id = c.path("system_prompt_id").asText("");
            if (!id.isEmpty()) {
                String resolved = templateContentByIdAndFile(id,
                        agentMode ? "agent_system_prompt.yaml" : "system_prompt.yaml");
                if (resolved != null) {
                    system = resolved;
                }
            }
        }
        if (context.isEmpty()) {
            String id = c.path("context_template_id").asText("");
            if (!id.isEmpty()) {
                String resolved = templateContentByIdAndFile(id, "context_template.yaml");
                if (resolved != null) {
                    context = resolved;
                }
            }
        }
        return new Prompts(system, context);
    }

    /** applyPerRequestSkillScope（Go L464-487）。 */
    private static void applyPerRequestSkillScope(QaAgentConfig ac, String skillsMode, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        if ("none".equals(skillsMode) || skillsMode.isEmpty()) {
            log.warn("Ignoring @skill mention: agent skills selection is disabled (mode={})", skillsMode);
            return;
        }
        if (!ac.isSkillsEnabled()) {
            return;
        }
        List<String> allowed = ac.getAllowedSkills() == null ? new ArrayList<>() : ac.getAllowedSkills();
        ac.setPinnedSkillNames(pinPreservingRequestOrder(requested, allowed));
        log.info("Applied per-request @skill scope: requested={} effective={} pinned={}",
                requested, allowed, ac.getPinnedSkillNames());
    }

    /** applyPerRequestMCPScope（Go L492-518）。 */
    private static void applyPerRequestMcpScope(QaAgentConfig ac, List<String> agentPresetMcps,
            boolean isSharedAgent, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        if ("none".equals(ac.getMcpSelectionMode())) {
            log.warn("Ignoring @MCP mention: agent MCP selection is disabled (mode=none)");
            return;
        }
        List<String> mentioned = dedupPreservingOrder(requested);
        var scope = resolvePerRequestMcpScope(mentioned, agentPresetMcps, ac.getMcpSelectionMode(), isSharedAgent);
        if (scope.effective().isEmpty()) {
            log.warn("Ignoring @MCP scope outside agent preset: requested={} agent={} shared={}",
                    requested, agentPresetMcps, isSharedAgent);
            return;
        }
        ac.setPinnedMcpServiceIds(scope.effective());
        log.info("Applied per-request @MCP priority: requested={} mode={} pinned={}",
                requested, ac.getMcpSelectionMode(), scope.effective());
    }

    private record McpScope(List<String> effective, String mode) {}

    private static McpScope resolvePerRequestMcpScope(List<String> mentioned, List<String> agentMcps,
            String selectionMode, boolean isSharedAgent) {
        if (mentioned.isEmpty()) {
            return new McpScope(new ArrayList<>(), selectionMode);
        }
        if (isSharedAgent) {
            mentioned = intersectPreservingRequestOrder(mentioned, agentMcps);
            if (mentioned.isEmpty()) {
                return new McpScope(new ArrayList<>(), selectionMode);
            }
        }
        List<String> effective = new ArrayList<>();
        switch (selectionMode) {
            case "none" -> {
                return new McpScope(new ArrayList<>(), selectionMode);
            }
            case "selected" -> effective = intersectPreservingRequestOrder(mentioned, agentMcps);
            case "all", "" -> effective = new ArrayList<>(mentioned);
            default -> effective = new ArrayList<>(mentioned);
        }
        if (effective.isEmpty()) {
            return new McpScope(new ArrayList<>(), selectionMode);
        }
        return new McpScope(effective, "selected");
    }

    private static List<String> intersectPreservingRequestOrder(List<String> requested, List<String> allowed) {
        var allowedSet = new java.util.LinkedHashSet<>(allowed);
        List<String> result = new ArrayList<>();
        var seen = new java.util.LinkedHashSet<String>();
        for (String value : requested) {
            if (value.isEmpty() || seen.contains(value) || !allowedSet.contains(value)) {
                continue;
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }

    private static List<String> pinPreservingRequestOrder(List<String> requested, List<String> allowed) {
        boolean allowedAll = allowed.isEmpty();
        var allowedSet = new java.util.LinkedHashSet<>(allowed);
        List<String> result = new ArrayList<>();
        var seen = new java.util.LinkedHashSet<String>();
        for (String value : requested) {
            if (value.isEmpty() || seen.contains(value)) {
                continue;
            }
            if (!allowedAll && !allowedSet.contains(value)) {
                continue;
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }

    private static List<String> dedupPreservingOrder(List<String> values) {
        List<String> result = new ArrayList<>();
        var seen = new java.util.LinkedHashSet<String>();
        for (String value : values) {
            if (value.isEmpty() || seen.contains(value)) {
                continue;
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }

    /** agentRequiresRerankModel（org 包 AgentShareService L368 同源；agent/tools 的判定）。 */
    private static boolean agentRequiresRerankModel(ObjectNode c) {
        List<String> allowed = new ArrayList<>();
        JsonNode arr = c.get("allowed_tools");
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> allowed.add(n.asText()));
        }
        if (allowed.isEmpty()) {
            return true; // DefaultAllowedTools 含 knowledge_search
        }
        return allowed.contains(ToolDefinitions.TOOL_KNOWLEDGE_SEARCH);
    }

    // ==================================================================
    // CreateAgentEngine（agent_service.go L180-295 的 dev 可达集）
    // ==================================================================

    private AgentEngine createAgentEngine(QaAgentConfig config, LlmChatClient chatModel, Reranker rerankModel,
            EventBus eventBus, String sessionId, String assistantMessageId) {
        log.info("Creating agent engine with custom EventBus");

        // 1. Validate config（ValidateConfig）
        if (config.getMaxIterations() < 0) {
            config.setMaxIterations(AgentConfig.UNLIMITED_MAX_ITERATIONS);
        } else if (config.getMaxIterations() == 0) {
            config.setMaxIterations(5);
        } else if (config.getMaxIterations() > MAX_ITERATIONS) {
            throw new RuntimeException(
                    "invalid agent config: max iterations too high: " + config.getMaxIterations() + " (max " + MAX_ITERATIONS + ")");
        }
        if (chatModel == null) {
            throw new RuntimeException("chat model is nil after initialization");
        }

        // Local browser：browserSkill 集成未装配 → 与 Go 的 Enabled()==false 分支一致
        if (config.isLocalBrowserEnabled()) {
            throw new RuntimeException("local browser is unavailable for this turn; "
                    + "enable the browser integration or update the input-bar selection");
        }

        // 2. Build tool registry
        ToolRegistry toolRegistry = new ToolRegistry();
        if (config.getMaxToolOutputChars() > 0) {
            toolRegistry.setMaxToolOutputSize(config.getMaxToolOutputChars());
        }
        registerTools(toolRegistry, config, rerankModel);
        // registerMCPTools：MCP 服务面在 dev 无启用的服务 → Go 的 ListMCPServices 返回
        // 空集同形（服务注册/发现面为 4.1 既有包，装配随 embed/im QA 面）

        // 沙箱执行面（Go agent_service.go：registerSandboxShellIfAllowed L216 →
        // registerSandboxFileTools L217 → initializeSkillsManager L272）：解析会话
        // 沙箱、注册 shell_exec 与文件工具、构建 skills 管理器并绑定。失败降级为
        // 无沙箱回合（工具不注册）。
        long sandboxTenantId = com.ragagent.common.context.TenantContext.currentTenantId() == null
                ? 0L : com.ragagent.common.context.TenantContext.currentTenantId();
        sandboxExecution.registerSandboxShellIfAllowed(toolRegistry, sandboxTenantId,
                sessionId, config);
        sandboxExecution.registerSandboxFileTools(toolRegistry, sandboxTenantId,
                sessionId, config);
        sandboxExecution.initializeSkillsManager(sandboxTenantId, sessionId, config, toolRegistry);
        toolRegistry.prepareMcpTools();

        // 3. Resolve KB / selected doc metadata（resolveKBAndDocInfos；失败回落 IDs-only）
        List<AgentPrompts.KnowledgeBaseInfo> kbInfos = new ArrayList<>();
        for (String kbId : kbScopeIds(config)) {
            kbInfos.add(new AgentPrompts.KnowledgeBaseInfo(kbId, kbId, "document", "", 0,
                    new ArrayList<>(), new ArrayList<>()));
        }
        List<AgentPrompts.SelectedDocumentInfo> selectedDocs = new ArrayList<>();

        // 4. System prompt template
        String systemPromptTemplate = "";
        if (config.useCustomSystemPrompt() || !config.getSystemPrompt().isEmpty()) {
            // Go 的 ResolveSystemPrompt(template, webSearchEnabled)：自定义模板即终选模板
            systemPromptTemplate = config.getSystemPrompt();
        }

        // 5. Create engine
        AgentEngine engine = new AgentEngine(config, chatModel, toolRegistry, eventBus,
                kbInfos, selectedDocs, sessionId, systemPromptTemplate);
        engine.setAppConfig(new com.ragagent.agent.AgentPromptTemplates.TemplatesConfig(new ArrayList<>()));
        // pinned mentions（resolvePinnedMCPServiceInfos / resolvePinnedSkillInfos）
        List<AgentPrompts.PinnedMCPServiceInfo> pinnedMcp = new ArrayList<>();
        if (config.getPinnedMcpServiceIds() != null) {
            for (String id : config.getPinnedMcpServiceIds()) {
                if (id != null && !id.isEmpty()) {
                    pinnedMcp.add(new AgentPrompts.PinnedMCPServiceInfo(false, id, id, "", new ArrayList<>()));
                }
            }
        }
        List<AgentPrompts.PinnedSkillInfo> pinnedSkills = new ArrayList<>();
        if (config.getPinnedSkillNames() != null) {
            for (String name : config.getPinnedSkillNames()) {
                if (name != null && !name.isEmpty()) {
                    pinnedSkills.add(new AgentPrompts.PinnedSkillInfo(name, ""));
                }
            }
        }
        engine.setPinnedMentions(pinnedMcp, pinnedSkills);

        // Skills manager（offerSkills：TenantSkills/SkillDirs 在 dev 均为空 → 不启用）
        // VLM image describer（GetVLMModel：VLM 模型运行时随模型面）
        if (!config.getVlmModelId().isEmpty()) {
            try {
                var vlmModel = modelService.getModelByID(config.getVlmModelId());
                if (vlmModel != null && vlmModel.getParameters() != null) {
                    // 已知差异（备案）：Java LlmChatClient 无 Predict(bytes,prompt) 形态
                    // （VLM 图像描述运行时随模型面收口）。Go 在 GetVLMModel 失败时同样
                    // 只记警告继续——此处与其失败分支同形。
                    log.warn("VLM model {} resolved but image describer seam is not wired in Java",
                            config.getVlmModelId());
                }
            } catch (RuntimeException e) {
                log.warn("Failed to load VLM model {} for tool image fallback: {}",
                        config.getVlmModelId(), e.toString());
            }
        }

        return engine;
    }

    private static List<String> kbScopeIds(QaAgentConfig config) {
        List<String> ids = new ArrayList<>(config.getKnowledgeBases());
        if (ids.isEmpty() && config.getSearchTargets() != null) {
            for (var t : config.getSearchTargets().list()) {
                ids.add(t.knowledgeBaseId());
            }
        }
        return ids;
    }

    /** registerTools（agent_service.go L837-1141 的注册面；工具集与硬门控逐条保留）。 */
    private void registerTools(ToolRegistry registry, QaAgentConfig config, Reranker rerankModel) {
        List<String> allowedTools = new ArrayList<>(config.getAllowedTools().isEmpty()
                ? com.ragagent.agent.tools.ToolDefinitions.defaultAllowedTools()
                : config.getAllowedTools());
        if (config.isSharedAgentReadOnly()) {
            allowedTools = filterSharedAgentWriteTools(allowedTools);
        }

        // Capability detection from SearchTargets（Go L869-896）
        boolean hasVectorKb = false;
        List<String> wikiKbIds = new ArrayList<>();
        if (config.getSearchTargets() != null) {
            for (var target : config.getSearchTargets().list()) {
                String kbId = target.knowledgeBaseId();
                if (kbId == null || kbId.isEmpty()) {
                    continue;
                }
                try {
                    var kb = knowledgeQa.findKnowledgeBase(kbId);
                    if (kb != null && kb.getIndexingStrategy() != null) {
                        if (kb.getIndexingStrategy().isVectorEnabled() || kb.getIndexingStrategy().isKeywordEnabled()) {
                            hasVectorKb = true;
                        }
                        if (kb.getIndexingStrategy().isWikiEnabled()) {
                            wikiKbIds.add(kb.getId());
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Go: continue
                }
            }
        }
        boolean hasWikiKb = !wikiKbIds.isEmpty();
        boolean hasKnowledge = !config.getKnowledgeBases().isEmpty() || !config.getKnowledgeIds().isEmpty()
                || (config.getSearchTargets() != null
                        && com.ragagent.agent.tools.SearchTarget.SearchTargets
                                .hasKnowledgeRetrievalScope(config.getSearchTargets(), List.of(), List.of()));

        // KB 工具过滤（Go L899-936）
        if (!hasKnowledge) {
            List<String> filtered = new ArrayList<>();
            List<String> kbTools = List.of(
                    ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                    ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                    ToolDefinitions.TOOL_GET_DOCUMENT_INFO, ToolDefinitions.TOOL_DATABASE_QUERY,
                    ToolDefinitions.TOOL_DATA_ANALYSIS, ToolDefinitions.TOOL_DATA_SCHEMA,
                    ToolDefinitions.TOOL_WIKI_READ_PAGE, ToolDefinitions.TOOL_WIKI_SEARCH,
                    ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, ToolDefinitions.TOOL_WIKI_FLAG_ISSUE,
                    ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                    ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE,
                    ToolDefinitions.TOOL_WIKI_READ_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE);
            List<String> effectiveKbTools = new ArrayList<>(kbTools);
            if (!config.isWebSearchEnabled()) {
                effectiveKbTools.add(ToolDefinitions.TOOL_TODO_WRITE);
            }
            for (String toolName : allowedTools) {
                if (!effectiveKbTools.contains(toolName)) {
                    filtered.add(toolName);
                }
            }
            allowedTools = filtered;
            log.info("Pure Agent Mode: Knowledge base tools filtered out, remaining: {}", allowedTools);
        }

        // Web 工具跟运行时开关（Go L939-945）
        allowedTools.remove(ToolDefinitions.TOOL_WEB_SEARCH);
        allowedTools.remove(ToolDefinitions.TOOL_WEB_FETCH);
        if (config.isWebSearchEnabled()) {
            allowedTools.add(ToolDefinitions.TOOL_WEB_SEARCH);
            allowedTools.add(ToolDefinitions.TOOL_WEB_FETCH);
        }

        // memory 工具跟开关（Go L956-962）
        allowedTools.remove(ToolDefinitions.TOOL_SEARCH_MEMORY);

        // 硬安全网（Go L994-1023）
        List<String> ragToolSet = List.of(
                ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                ToolDefinitions.TOOL_GET_DOCUMENT_INFO, ToolDefinitions.TOOL_DATABASE_QUERY);
        List<String> allWikiToolSet = List.of(
                ToolDefinitions.TOOL_WIKI_READ_PAGE, ToolDefinitions.TOOL_WIKI_SEARCH,
                ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, ToolDefinitions.TOOL_WIKI_FLAG_ISSUE,
                ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE,
                ToolDefinitions.TOOL_WIKI_READ_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE);
        if (!hasWikiKb) {
            allowedTools.removeIf(allWikiToolSet::contains);
        }
        if (!hasVectorKb) {
            allowedTools.removeIf(ragToolSet::contains);
        }

        // Dedup 保序（Go L1026）
        allowedTools = new ArrayList<>(new java.util.LinkedHashSet<>(allowedTools));

        // Register each allowed tool（Go L1030-1137）
        for (String toolName : allowedTools) {
            com.ragagent.agent.tools.AgentTool toolToRegister = null;
            switch (toolName) {
                case ToolDefinitions.TOOL_THINKING ->
                        toolToRegister = new com.ragagent.agent.tools.SequentialThinkingTool();
                case ToolDefinitions.TOOL_TODO_WRITE ->
                        toolToRegister = new com.ragagent.agent.tools.TodoWriteTool();
                // 知识检索族（2026-09-23 接线批）：seam → 真实服务经 AgentToolBackends
                case ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                        ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS,
                        ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                        ToolDefinitions.TOOL_GET_DOCUMENT_INFO ->
                        toolToRegister = toolBackends.createKbTool(toolName,
                                config.getSearchTargets(), rerankModel);
                case ToolDefinitions.TOOL_SHELL_EXEC, ToolDefinitions.TOOL_READ_FILE,
                        ToolDefinitions.LEGACY_TOOL_READ_SKILL, ToolDefinitions.LEGACY_TOOL_EXECUTE_SKILL_SCRIPT,
                        ToolDefinitions.TOOL_LIST_SANDBOX_FILES, ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE,
                        ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, ToolDefinitions.TOOL_EDIT_SANDBOX_FILE -> {
                    // Bound to the resolved sandbox manager in the file/shell registration
                    // steps（Go 同款 continue；dev 无 sandbox → 恒走此分支跳过）
                }
                default -> log.warn("Unknown tool: {}", toolName);
            }
            if (toolToRegister != null) {
                if (!toolToRegister.getName().equals(toolName)) {
                    log.warn("Tool name mismatch: expected {}, got {}", toolName, toolToRegister.getName());
                }
                registry.registerTool(toolToRegister);
            }
        }
        log.info("Registered {} tools", registry.listTools().size());
    }

    /** filterSharedAgentWriteTools（agent_service.go L1146-1162）。 */
    private static List<String> filterSharedAgentWriteTools(List<String> allowed) {
        List<String> sourceWrites = List.of(
                ToolDefinitions.TOOL_WIKI_FLAG_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE,
                ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE);
        List<String> filtered = new ArrayList<>();
        for (String name : allowed) {
            if (!sourceWrites.contains(name)) {
                filtered.add(name);
            }
        }
        return filtered;
    }

    private LlmChatClient chatModel(String modelId) {
        var model = modelService.getModelByID(modelId);
        if (model == null) {
            return null;
        }
        var p = model.getParameters();
        var config = com.ragagent.llm.domain.ChatConfig.fromModel(model,
                p == null ? null : p.getAppId(), p == null ? null : p.getAppSecret());
        // ⚠️ 2026-09-23 修复：governor/ollama 曾传 null——并发闸门装配（95a49c4）后
        // ConcurrencyChatClient 必调 gateNamedN，agent 路径任何 LLM 调用都会 NPE。
        return com.ragagent.llm.chat.LlmChatClients.create(config,
                ollamaService.getIfAvailable(), concurrencyGovernor);
    }

    private Reranker rerankModel(String modelId) {
        var model = modelService.getModelByID(modelId);
        var p = model == null ? null : model.getParameters();
        var config = com.ragagent.rerank.RerankerConfig.configFromModel(model,
                p == null ? null : p.getAppId(), p == null ? null : p.getAppSecret());
        return com.ragagent.rerank.RerankerFactory.newReranker(config);
    }

    // ==================================================================
    // LoadAgentHistory（agent_history.go L50-186 + 构造族）
    // ==================================================================

    public List<ChatMessage> loadAgentHistory(String sessionId, int maxRounds) {
        if (maxRounds <= 0) {
            return new ArrayList<>();
        }
        int fetchLimit = Math.max(maxRounds * AGENT_HISTORY_FETCH_MULTIPLIER, AGENT_HISTORY_FETCH_MIN);
        List<Message> rows = messageService.getRecentMessages(sessionId, fetchLimit);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        // 按 requestID 分轮
        Map<String, Turn> turns = new LinkedHashMap<>();
        for (Message msg : rows) {
            Turn t = turns.computeIfAbsent(msg.getRequestId(), k -> new Turn());
            if ("user".equals(msg.getRole())) {
                t.users.add(msg);
                if (t.createdAt == null || msg.getCreatedAt().isBefore(t.createdAt)) {
                    t.createdAt = msg.getCreatedAt();
                }
            } else if ("assistant".equals(msg.getRole())) {
                t.assistant = msg;
            }
        }
        List<Turn> completeTurns = new ArrayList<>();
        for (Turn t : turns.values()) {
            if (!t.users.isEmpty() && t.assistant != null && t.assistant.isCompleted()) {
                t.users.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
                completeTurns.add(t);
            }
        }
        completeTurns.sort((a, b) -> a.createdAt.compareTo(b.createdAt));
        if (completeTurns.size() > maxRounds) {
            completeTurns = new ArrayList<>(completeTurns.subList(completeTurns.size() - maxRounds,
                    completeTurns.size()));
        }
        List<ChatMessage> out = new ArrayList<>();
        for (Turn t : completeTurns) {
            out.add(buildUserHistoryMessage(t.users.get(0)));
            out.addAll(buildTurnBodyMessages(t.assistant, t.users.subList(1, t.users.size())));
        }
        return out;
    }

    private static final class Turn {
        final List<Message> users = new ArrayList<>();
        Message assistant;
        java.time.OffsetDateTime createdAt;
    }

    /** buildTurnBodyMessages（agent_history.go L157-186）。 */
    private static List<ChatMessage> buildTurnBodyMessages(Message assistant, List<Message> midRunUsers) {
        if (midRunUsers.isEmpty()) {
            return buildAssistantHistoryMessages(assistant);
        }
        List<ChatMessage> out = new ArrayList<>();
        Map<String, Message> usersById = new LinkedHashMap<>();
        for (Message user : midRunUsers) {
            usersById.put(user.getId(), user);
        }
        boolean hasBoundaries = false;
        if (assistant.getAgentSteps() != null) {
            for (AgentStep step : assistant.getAgentSteps()) {
                hasBoundaries = hasBoundaries || (step.getUserMessagesBefore() != null
                        && !step.getUserMessagesBefore().isEmpty());
            }
        }
        List<Message> pending = new ArrayList<>(midRunUsers);
        final int[] next = {0};
        Runnable drain = () -> {};
        if (assistant.getAgentSteps() != null) {
            for (AgentStep step : assistant.getAgentSteps()) {
                if (step.getUserMessagesBefore() != null) {
                    for (String id : step.getUserMessagesBefore()) {
                        Message user = usersById.get(id);
                        if (user != null) {
                            out.add(steeredUserMessage(user));
                            usersById.remove(user.getId());
                        }
                    }
                }
                while (!hasBoundaries && next[0] < pending.size()
                        && step.getTimestamp() != null
                        && pending.get(next[0]).getCreatedAt().isBefore(step.getTimestamp())) {
                    Message user = pending.get(next[0]);
                    out.add(steeredUserMessage(user));
                    usersById.remove(user.getId());
                    next[0]++;
                }
                out.addAll(buildAgentStepMessages(step));
            }
        }
        for (Message user : midRunUsers) {
            if (usersById.containsKey(user.getId())) {
                out.add(steeredUserMessage(user));
                usersById.remove(user.getId());
            }
        }
        ChatMessage finalMsg = finalAnswerHistoryMessage(assistant);
        if (finalMsg != null) {
            out.add(finalMsg);
        }
        return out;
    }

    private static ChatMessage steeredUserMessage(Message user) {
        ChatMessage msg = buildUserHistoryMessage(user);
        msg.setContent(steerMessageContent(msg.getContent()));
        return msg;
    }

    /** buildUserHistoryMessage（agent_history.go L187-196）。 */
    private static ChatMessage buildUserHistoryMessage(Message m) {
        String content = m.getContent();
        if (m.getImages() != null) {
            StringBuilder captions = new StringBuilder();
            for (var img : m.getImages()) {
                if (img != null && img.getCaption() != null && !img.getCaption().isEmpty()) {
                    if (captions.length() > 0) {
                        captions.append('\n');
                    }
                    captions.append(img.getCaption());
                }
            }
            if (captions.length() > 0) {
                content += "\n\n[用户上传图片内容]\n" + captions;
            }
        }
        if (m.getAttachments() != null && !m.getAttachments().isEmpty()) {
            content += com.ragagent.chatpipeline.MessageAttachmentsPrompt.build(m.getAttachments());
        }
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent(content);
        return msg;
    }

    /** buildAssistantHistoryMessages（agent_history.go L204-218）。 */
    private static List<ChatMessage> buildAssistantHistoryMessages(Message m) {
        List<ChatMessage> msgs = new ArrayList<>();
        if (m.getAgentSteps() != null) {
            for (AgentStep step : m.getAgentSteps()) {
                msgs.addAll(buildAgentStepMessages(step));
            }
        }
        ChatMessage finalMsg = finalAnswerHistoryMessage(m);
        if (finalMsg != null) {
            msgs.add(finalMsg);
        }
        return msgs;
    }

    /** buildAgentStepMessages（agent_history.go L225-266）。 */
    private static List<ChatMessage> buildAgentStepMessages(AgentStep step) {
        List<com.ragagent.agent.domain.ToolCall> nonTerminalCalls = filterNonTerminalToolCalls(step.getToolCalls());
        if (nonTerminalCalls.isEmpty()) {
            if (step.isIntermediateAnswer() && step.getThought() != null && !step.getThought().isBlank()) {
                ChatMessage msg = new ChatMessage();
                msg.setRole("assistant");
                msg.setContent(step.getThought());
                msg.setReasoningContent(step.getReasoningContent());
                return new ArrayList<>(List.of(msg));
            }
            return new ArrayList<>();
        }
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(step.getThought());
        assistantMsg.setReasoningContent(step.getReasoningContent());
        List<ToolCall> chatCalls = new ArrayList<>();
        for (var tc : nonTerminalCalls) {
            ToolCall c = new ToolCall();
            c.setId(tc.getId());
            c.setType("function");
            c.setFunction(new com.ragagent.llm.domain.FunctionCall(
                    tc.getName(), toJsonString(tc.getArgs())));
            c.setProviderMetadata(tc.getProviderMetadata());
            chatCalls.add(c);
        }
        assistantMsg.setToolCalls(chatCalls);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(assistantMsg);
        for (var tc : nonTerminalCalls) {
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setRole("tool");
            toolMsg.setContent(com.ragagent.agent.tools.ToolResultPersist.compactToolOutputForHistory(
                    tc.getName(), tc.getResult()));
            toolMsg.setToolCallId(tc.getId());
            toolMsg.setName(tc.getName());
            msgs.add(toolMsg);
        }
        return msgs;
    }

    private static String toJsonString(Object args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** finalAnswerHistoryMessage（agent_history.go L272-289）。 */
    private static ChatMessage finalAnswerHistoryMessage(Message m) {
        String content = m.getContent() == null ? "" : m.getContent()
                .replaceAll("(?s)<think>.*?</think>", "");
        content = content.replace("\n\n本轮生成的文件: ![", "\n\n该历史消息生成的文件: ![")
                .replace("\n\nFile generated this turn: ![", "\n\nFile generated in that historical turn: ![");
        content = content.trim();
        if (content.isEmpty()) {
            return null;
        }
        ChatMessage msg = new ChatMessage();
        msg.setRole("assistant");
        msg.setContent(content);
        return msg;
    }

    /** filterNonTerminalToolCalls（agent_history.go L301-313）。 */
    private static List<com.ragagent.agent.domain.ToolCall> filterNonTerminalToolCalls(
            List<com.ragagent.agent.domain.ToolCall> calls) {
        List<com.ragagent.agent.domain.ToolCall> out = new ArrayList<>();
        if (calls == null) {
            return out;
        }
        for (var tc : calls) {
            if ("final_answer".equals(tc.getName()) || isPipelineToolCallId(tc.getId())) {
                continue;
            }
            out.add(tc);
        }
        return out;
    }

    /** types.IsPipelineToolCallID（pipeline 阶段工具调用前缀）。 */
    private static boolean isPipelineToolCallId(String id) {
        return id != null && (id.startsWith("attachment_parsing") || id.startsWith("web_search")
                || id.startsWith("knowledge_search") || id.startsWith("image_analysis")
                || id.startsWith("references"));
    }

    private static List<String> stringListOf(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    /** 只给模型输入加投递上下文（types.SteerMessageContent；引擎包内静态的同款实现）。 */
    private static String steerMessageContent(String content) {
        return "<steer_message>\n" + content + "\n</steer_message>\n<continue_task>\n"
                + "This is guidance for the task in progress. Apply it and continue unfinished work "
                + "unless the user explicitly changes or cancels the task.\n</continue_task>";
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper YAML_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String templateContentByIdAndFile(String id, String file) {
        try (java.io.InputStream in = SessionAgentQaService.class.getClassLoader()
                .getResourceAsStream("agentm/prompt_templates/" + file)) {
            if (in == null) {
                return null;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            com.fasterxml.jackson.databind.JsonNode root = YAML_JSON.valueToTree(raw);
            com.fasterxml.jackson.databind.JsonNode list = root.get("templates");
            if (list == null || !list.isArray()) {
                return null;
            }
            for (com.fasterxml.jackson.databind.JsonNode t : list) {
                if (id.equals(t.path("id").asText(""))) {
                    return t.path("content").asText("");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
