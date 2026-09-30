package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.common.prompt.MessageAttachmentsPrompt;
import com.ragagent.common.session.PipelineUsedMemoryView;
import com.ragagent.knowledge.dto.faq.FaqEntry;
import com.ragagent.knowledge.dto.faq.FaqEntryPage;
import com.ragagent.knowledge.service.FaqEntryQueryService;
import com.ragagent.agent.AgentConfig;
import com.ragagent.agent.AgentEngine;
import com.ragagent.agent.AgentPrompts;
import com.ragagent.agent.tools.McpExposure;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.WikiRouteResolver;
import com.ragagent.agent.tools.WikiScope;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.payload.ErrorData;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.MemoryRecalledData;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.model.service.ModelService;
import com.ragagent.rerank.Reranker;

import com.ragagent.model.service.ModelRuntimeConfigs;
import com.ragagent.session.support.PipelineViews;

/**
 * agent 问答 service 面（对照 Go session_agent_qa.go 全文 + agent_history.go 的
 * 历史装载 + agent_service.go 的 CreateAgentEngine 装配路径）。
 *
 * <p>装配边界（对照 agent_service.go L180-295，每一条的取舍已在报告备案）：
 * MCP 目录随 {@code registerMcpTools} 接线（Go L211 调用 / L297-372 实现）；
 * sandbox/browser/skills 的生产接线在 dev 部署（无 docker、无 browser 集成）与
 * Go 的 nil/disable 分支一致——工具注册的硬门控逐条保留；
 * 检索工具族（knowledge_search 等）在检索执行面缺失时注册同样无产出，
 * 因此 dev 路径注册的核心是 thinking/todo_write/工具白名单可达集。</p>
 */
@Service
public class SessionAgentQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentQaService.class);

    /** 对照 agent_service.go L30（MaxIterations 上限）。 */
    private static final int MAX_ITERATIONS = 100;

    static final int AGENT_HISTORY_FETCH_MULTIPLIER = 2;
    static final int AGENT_HISTORY_FETCH_MIN = 20;

    private final MessageService messageService;
    private final ModelService modelService;
    private final MemoryService memoryService;
    private final SessionKnowledgeQaService knowledgeQa;
    private final AgentToolBackends toolBackends;

    /** 历史/消息装配簇（§14.9c 刀 1）。 */
    private final AgentHistoryAssembler historyAssembler;

    /** 配置装配簇（§14.9c 刀 2）。 */
    private final AgentConfigAssembler configAssembler;
    private final com.ragagent.storage.service.ResourceCatalogService resourceCatalog;
    private final javax.sql.DataSource dataSource;
    private final ArtifactCollectorWiring artifactCollectorWiring;
    private final com.ragagent.knowledge.service.KnowledgeService knowledgeService;
    private final FaqEntryQueryService faqService;
    /** 并发闸门（对照 Go container 的 chat 工厂注入；null 会让 ConcurrencyChatClient NPE）。 */
    private final com.ragagent.llm.limiter.ConcurrencyGovernor concurrencyGovernor;
    private final org.springframework.beans.factory.ObjectProvider<com.ragagent.llm.ollama.OllamaService>
            ollamaService;
    /** MCP 服务面（Go agentService 的 mcpServiceService/mcpManager/toolApprovalGate）。 */
    private final com.ragagent.mcp.service.McpServiceService mcpServiceService;
    private final com.ragagent.mcp.service.McpMetadataService mcpMetadataService;
    private final com.ragagent.mcp.protocol.McpClientManager mcpClientManager;
    private final com.ragagent.common.approval.Gate toolApprovalGate;
    /** 工具图片 VLM 描述器装配（对照 agent_service.go L246-256 的 SetImageDescriber 段）。 */
    private final VlmDescriberWiring vlmDescriberWiring;
    /** 指令型技能的宿主目录（选项 B；weknora.skills.host-dirs，逗号分隔）。 */
    private final List<String> hostSkillDirs;

    public SessionAgentQaService(MessageService messageService,
            ModelService modelService,
            MemoryService memoryService,
            SessionKnowledgeQaService knowledgeQa,
            AgentToolBackends toolBackends,
            com.ragagent.storage.service.ResourceCatalogService resourceCatalog,
            javax.sql.DataSource dataSource,
            ArtifactCollectorWiring artifactCollectorWiring,
            com.ragagent.knowledge.service.KnowledgeService knowledgeService,
            FaqEntryQueryService faqService,
            com.ragagent.llm.limiter.ConcurrencyGovernor concurrencyGovernor,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.llm.ollama.OllamaService>
                    ollamaService,
            com.ragagent.mcp.service.McpServiceService mcpServiceService,
            com.ragagent.mcp.service.McpMetadataService mcpMetadataService,
            com.ragagent.mcp.protocol.McpClientManager mcpClientManager,
            com.ragagent.common.approval.Gate toolApprovalGate,
            VlmDescriberWiring vlmDescriberWiring,
            @org.springframework.beans.factory.annotation.Value(
                    "${weknora.skills.host-dirs:}") String hostSkillDirs) {
        this.vlmDescriberWiring = vlmDescriberWiring;
        this.hostSkillDirs = parseHostSkillDirs(hostSkillDirs);
        this.concurrencyGovernor = concurrencyGovernor;
        this.ollamaService = ollamaService;
        this.mcpServiceService = mcpServiceService;
        this.mcpMetadataService = mcpMetadataService;
        this.mcpClientManager = mcpClientManager;
        this.toolApprovalGate = toolApprovalGate;
        this.messageService = messageService;
        this.modelService = modelService;
        this.memoryService = memoryService;
        this.knowledgeQa = knowledgeQa;
        this.toolBackends = toolBackends;
        this.historyAssembler = new AgentHistoryAssembler(messageService);
        this.resourceCatalog = resourceCatalog;
        this.dataSource = dataSource;
        this.artifactCollectorWiring = artifactCollectorWiring;
        this.knowledgeService = knowledgeService;
        this.faqService = faqService;
        this.configAssembler = new AgentConfigAssembler(knowledgeQa, this.hostSkillDirs);
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
        QaAgentConfig agentConfig = configAssembler.buildAgentConfig(req, agentTenantId);

        {
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
            if (AgentConfigAssembler.agentRequiresRerankModel(req.agentConfig)) {
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
                    llmContext = historyAssembler.loadAgentHistory(sessionId, historyTurns);
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
                    List<PipelineUsedMemoryView> used = new ArrayList<>();
                    if (recall.items() != null) {
                        for (var item : recall.items()) {
                            // 与 chatpipeline 的投影一致：kind 缺省空串
                            used.add(new PipelineUsedMemoryView(item.getId(), null,
                                    item.getContent()));
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
            // 用户停止的取消源（此前 seam 零调用方，stop 只翻 SSE 开关、引擎照跑）
            if (req.cancellationProbe != null) {
                engine.setCancellationSource(req.cancellationProbe);
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
                agentQuery += MessageAttachmentsPrompt.build(
                        PipelineViews.ofAttachments(req.attachments));
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
        }
    }

    // ==================================================================
    // buildAgentConfig（session_agent_qa.go L291-430）
    // ==================================================================


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

        // 2. Build tool registry
        ToolRegistry toolRegistry = new ToolRegistry();
        if (config.getMaxToolOutputChars() > 0) {
            toolRegistry.setMaxToolOutputSize(config.getMaxToolOutputChars());
        }
        registerTools(toolRegistry, config, rerankModel, sessionId);
        // registerMCPTools（Go agent_service.go L211 → L297-372）：按 agent 配置的
        // mcp_selection_mode 注册受限 MCP 目录（按需发现，不连上游、不广告完整 schema）
        registerMcpTools(toolRegistry, config);

        // 指令型技能（选项 B）：Manager 只做 SKILL.md 三级注入（元数据/正文/资源），
        // 模型凭指令用现有工具执行；shell/文件注入与沙箱镜像源已随沙箱退役。
        com.ragagent.agent.skills.Manager skillsManager = null;
        if (config.isSkillsEnabled()) {
            skillsManager = new com.ragagent.agent.skills.Manager(
                    new com.ragagent.agent.skills.Manager.ManagerConfig(
                            config.getSkillDirs(), config.getAllowedSkills(), true));
            try {
                skillsManager.initialize();
            } catch (Exception e) {
                throw new IllegalStateException("failed to initialize skills: " + e.getMessage(), e);
            }
            log.info("Instructional skills enabled: {} skill(s) from host dirs {}",
                    skillsManager.getAllMetadata() == null ? 0 : skillsManager.getAllMetadata().size(),
                    config.getSkillDirs());
        }
        registerWebPageFiles(toolRegistry, config, sessionId, assistantMessageId);
        toolRegistry.prepareMcpTools();

        // 3. Resolve KB / selected doc metadata（resolveKBAndDocInfos；失败回落 IDs-only）
        List<AgentPrompts.KnowledgeBaseInfo> kbInfos = getKnowledgeBaseInfos(config);
        List<AgentPrompts.SelectedDocumentInfo> selectedDocs = getSelectedDocumentInfos(config);

        // 4. System prompt template
        String systemPromptTemplate = "";
        if (config.useCustomSystemPrompt() || !config.getSystemPrompt().isEmpty()) {
            // Go 的 ResolveSystemPrompt(template, webSearchEnabled)：自定义模板即终选模板
            systemPromptTemplate = config.getSystemPrompt();
        }

        // 5. Create engine
        AgentEngine engine = new AgentEngine(config, chatModel, toolRegistry, eventBus,
                kbInfos, selectedDocs, sessionId, systemPromptTemplate);
        // 对照 Go：cfg.PromptTemplates 启动时装载 vendored yaml——RAG/pure 两个 base
        // 模板由此区分（此前塞空配置，带 KB 的 agent 缺 RAG 开头段 ≈860 字符）。
        engine.setAppConfig(new com.ragagent.agent.AgentPromptTemplates.TemplatesConfig(
                com.ragagent.agent.AgentPromptTemplates.loadAgentSystemPromptTemplates()));
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

        // 指令型技能注入（Level 1 元数据进系统提示词；Level 2/3 由引擎按需读取）
        if (skillsManager != null) {
            engine.setSkillsManager(skillsManager);
        }

        // 工具图片 VLM 描述器（agent_service.go L246-256）：GetVLMModel 成功则
        // SetImageDescriber；失败只记警告继续——引擎随后对无描述能力走 "cannot view"。
        if (!config.getVlmModelId().isEmpty()) {
            try {
                engine.setImageDescriber(vlmDescriberWiring.create(config.getVlmModelId()));
                log.info("VLM image describer set for tool result analysis (model: {})",
                        config.getVlmModelId());
            } catch (RuntimeException e) {
                log.warn("Failed to load VLM model {} for tool image fallback: {}",
                        config.getVlmModelId(), e.toString());
            }
        }

        return engine;
    }

    /**
     * registerMCPTools（Go agent_service.go L297-372）：从本租户的启用服务注册受限
     * MCP 目录（discover_mcp_tools / call_mcp_tool），不连接上游、不广告完整 schema；
     * 具体工具定义在模型调用 discover 时按需列举。
     *
     * <p>身份与装载参数说明（Java 无 ctx 的显式化，见 McpExposure/McpOAuthSupport 备案）：
     * {@code hasToolExecContext=false}——装配发生在引擎准备阶段，此处没有 per-turn 的
     * ToolExecContext；OAuth 服务无快照时给出"先去授权"的方向，与 Go 无 ToolExecContext
     * 的调用同形。失败只记警告，不影响引擎创建（对照 Go 的 warn 分支）。</p>
     */
    private void registerMcpTools(ToolRegistry toolRegistry, QaAgentConfig config) {
        long tenantId = TenantContext.currentTenantId() == null ? 0L : TenantContext.currentTenantId();
        if (tenantId == 0) {
            // 对照 Go 的 tenantID==0 直接 return；Java 侧补一条日志——此前该分支
            // 完全静默，装配线程丢租户时表现为"MCP 工具凭空消失"（排查成本高）。
            log.info("Skipping MCP registration: no tenant in execution context");
            return;
        }
        String mcpMode = config.getMcpSelectionMode() == null || config.getMcpSelectionMode().isEmpty()
                ? "all" : config.getMcpSelectionMode();
        if ("none".equals(mcpMode)) {
            log.info("MCP services disabled by agent config (mode: none)");
            return;
        }

        List<McpService> services;
        try {
            if ("selected".equals(mcpMode)) {
                List<String> selected = config.getMcpServices();
                if (selected == null || selected.isEmpty()) {
                    log.info("MCP services disabled by agent config (mode: selected, no services)");
                    return;
                }
                services = mcpServiceService.listMCPServicesByIDs(tenantId, selected);
                log.info("Using {} selected MCP services from agent config", services.size());
            } else {
                services = mcpServiceService.listMCPServices(tenantId);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to list MCP services: {}", e.toString());
            return;
        }

        List<McpService> enabled = new ArrayList<>();
        for (McpService service : services) {
            if (service != null && service.isEnabled()) {
                enabled.add(service);
            }
        }
        if (enabled.isEmpty()) {
            return;
        }

        try {
            int registered = McpExposure.registerMcpTools(
                    toolRegistry,
                    enabled,
                    mcpClientManager,
                    toolApprovalGate,
                    config.getMcpAuthWaitTimeout(),
                    tenantId,
                    mcpServiceService::getMCPServiceByID,
                    new McpExposure.McpMetadataIO(
                            mcpMetadataService::getMCPMetadata,
                            mcpMetadataService::persistMCPMetadata),
                    false,
                    toolApprovalGate::requestOAuthAndWait);
            log.info("Registered {} MCP service(s) for on-demand discovery", registered);
        } catch (Exception e) {
            log.warn("Failed to register MCP directory: {}", e.toString());
        }
    }

    // ── resolveKBAndDocInfos（agent_service.go L368-394 + L1184-1327）────────

    /** 对照 knowledgeBaseScopesForPrompt：KnowledgeBases 优先，否则 SearchTargets 全集。 */
    private record KbScopes(List<String> kbIds, Map<String, Long> kbTenantMap) {
    }

    private static KbScopes knowledgeBaseScopesForPrompt(QaAgentConfig config) {
        Map<String, Long> tenantMap = config.getSearchTargets() == null
                ? Map.of() : config.getSearchTargets().getKbTenantMap();
        if (config.getKnowledgeBases() != null && !config.getKnowledgeBases().isEmpty()) {
            return new KbScopes(config.getKnowledgeBases(), tenantMap);
        }
        return new KbScopes(config.getSearchTargets() == null
                ? List.of() : config.getSearchTargets().getAllKnowledgeBaseIds(), tenantMap);
    }

    /**
     * 对照 getKnowledgeBaseInfos：真实 KB 元数据（名称/描述/类型/文档数/最近文档/
     * capabilities）进 system prompt 与 runtime_context。单库失败回落 ID-only 占位；
     * 临时库（__chat_history__ 等）跳过。
     */
    private List<AgentPrompts.KnowledgeBaseInfo> getKnowledgeBaseInfos(QaAgentConfig config) {
        KbScopes scopes = knowledgeBaseScopesForPrompt(config);
        if (scopes.kbIds().isEmpty()) {
            return new ArrayList<>();
        }
        List<AgentPrompts.KnowledgeBaseInfo> kbInfos = new ArrayList<>();
        for (String kbId : scopes.kbIds()) {
            com.ragagent.knowledge.domain.KnowledgeBase kb;
            try {
                kb = knowledgeQa.findKnowledgeBase(kbId);
            } catch (RuntimeException e) {
                kb = null;
            }
            if (kb == null) {
                log.warn("Failed to get knowledge base {}, using IDs only for prompt", kbId);
                kbInfos.add(new AgentPrompts.KnowledgeBaseInfo(kbId, kbId, "document", "", 0,
                        List.of(), List.of()));
                continue;
            }
            // 跳过隐藏/系统托管的知识库（__chat_history__ 等）
            if (kb.isIsTemporary()) {
                log.debug("Skipping temporary knowledge base {} ({}) from prompt", kb.getId(), kb.getName());
                continue;
            }
            int docCount = 0;
            List<AgentPrompts.RecentDocInfo> recentDocs = new ArrayList<>();
            // FAQ 库：条目列表；否则/失败回落通用 knowledge 列表（completed 过滤，top 10）
            if ("faq".equals(kb.getType())) {
                try {
                    FaqEntryPage page = faqService.listEntries(kbId, 1, 10, null, 0, "", "", "", null);
                    docCount = page.total() > Integer.MAX_VALUE ? Integer.MAX_VALUE
                            : (int) page.total();
                    List<FaqEntry> entries = page.items();
                    if (entries != null) {
                        for (var entry : entries) {
                            if (recentDocs.size() >= 10) {
                                break;
                            }
                            recentDocs.add(new AgentPrompts.RecentDocInfo(
                                    entry.chunkId(), entry.knowledgeBaseId(), entry.knowledgeId(),
                                    entry.standardQuestion(), "", "", 0, "faq",
                                    entry.createdAt() == null ? ""
                                            : entry.createdAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE),
                                    entry.standardQuestion(), entry.similarQuestions(), entry.answers()));
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to list FAQ entries for {}: {}", kbId, e.getMessage());
                }
            }
            // 对照 Go：非 FAQ 或 FAQ 列表为空/失败 → 回落通用 knowledge 列表
            if (!"faq".equals(kb.getType()) || recentDocs.isEmpty()) {
                try {
                    var page = knowledgeService.listKnowledge(kbId, 1, 10, null, "completed",
                            null, null, false);
                    docCount = (int) Math.max(page.getTotal(), 0);
                    if (page.getRecords() != null) {
                        for (com.ragagent.knowledge.domain.Knowledge k : page.getRecords()) {
                            if (k == null || recentDocs.size() >= 10) {
                                break;
                            }
                            recentDocs.add(new AgentPrompts.RecentDocInfo(
                                    "", kb.getId(), nz(k.getId()), nz(k.getTitle()), nz(k.getDescription()),
                                    nz(k.getFileName()), k.getFileSize() == null ? 0 : k.getFileSize(),
                                    nz(k.getFileType()),
                                    k.getCreatedAt() == null ? ""
                                            : k.getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE),
                                    "", null, null));
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to list knowledge for {}: {}", kbId, e.getMessage());
                }
            }
            String kbType = kb.getType() == null || kb.getType().isEmpty() ? "document" : kb.getType();
            kbInfos.add(new AgentPrompts.KnowledgeBaseInfo(kb.getId(), nz(kb.getName()), kbType,
                    nz(kb.getDescription()), docCount, kbRetrievalCapabilities(kb), recentDocs));
        }
        return kbInfos;
    }

    /** 对照 kbRetrievalCapabilities：wiki / chunks（vector 或 keyword 开启）。 */
    private static List<String> kbRetrievalCapabilities(com.ragagent.knowledge.domain.KnowledgeBase kb) {
        List<String> caps = new ArrayList<>(2);
        if (kb.getIndexingStrategy() != null) {
            if (kb.getIndexingStrategy().isWikiEnabled()) {
                caps.add("wiki");
            }
            if (kb.getIndexingStrategy().isVectorEnabled() || kb.getIndexingStrategy().isKeywordEnabled()) {
                caps.add("chunks");
            }
        }
        return caps;
    }

    /** 对照 getSelectedDocumentInfos：@ 提及文档的元数据（缺失逐条跳过）。 */
    private List<AgentPrompts.SelectedDocumentInfo> getSelectedDocumentInfos(QaAgentConfig config) {
        List<String> ids = config.getKnowledgeIds();
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<AgentPrompts.SelectedDocumentInfo> selectedDocs = new ArrayList<>();
        for (String kid : ids) {
            com.ragagent.knowledge.domain.Knowledge k;
            try {
                k = knowledgeService.getKnowledgeByIdOnly(kid);
            } catch (RuntimeException e) {
                k = null;
            }
            if (k == null) {
                log.warn("Selected knowledge {} not found", kid);
                continue;
            }
            selectedDocs.add(new AgentPrompts.SelectedDocumentInfo(nz(k.getId()),
                    nz(k.getKnowledgeBaseId()), nz(k.getTitle()), nz(k.getFileName()), nz(k.getFileType())));
        }
        return selectedDocs;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    /**
     * registerWebPageFiles（agent_web_pages.go L108-159）：web 抓取页的完整快照面。
     * web_search 共享会话 web_fetch 的快照缓存（WithPageReader）；read_file 未被
     * 沙箱路径注册时以 web:// 读取范围注册（描述随来源变化）。存储写字节面
     * （FileService.SaveBytes/ResourceCatalog.Bind 生产实现）未翻译——经
     * {@link AgentWebPages} 的接缝落 Go 的 save-failure 分支，见类 Javadoc。
     */
    /** weknora.skills.host-dirs（逗号分隔）→ 目录列表；空白项丢弃。 */
    private static List<String> parseHostSkillDirs(String raw) {
        List<String> dirs = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String dir : raw.split(",")) {
                String clean = dir == null ? "" : dir.strip();
                if (!clean.isEmpty()) {
                    dirs.add(clean);
                }
            }
        }
        return List.copyOf(dirs);
    }

    private void registerWebPageFiles(ToolRegistry registry, QaAgentConfig config,
            String sessionId, String assistantMessageId) {
        if (config == null || !config.isWebSearchEnabled()) {
            return;
        }
        com.ragagent.agent.tools.AgentTool raw = registry.getTool(ToolDefinitions.TOOL_WEB_FETCH);
        if (!(raw instanceof com.ragagent.agent.tools.WebFetchTool fetch)) {
            return;
        }
        // 对照 Go L119-123：web_search 是**另一次** GetTool，与 fetch 是两个实例
        if (registry.getTool(ToolDefinitions.TOOL_WEB_SEARCH)
                instanceof com.ragagent.agent.tools.WebSearchTool search) {
            search.withPageReader(fetch);
        }
        // handler 已把会话存储钉到 owner 租户（对照 SandboxTenantIDFromContext）
        Long ctxTenant = com.ragagent.common.context.TenantContext.currentTenantId();
        long tenantId = ctxTenant == null ? 0L : ctxTenant;
        if (tenantId == 0 || sessionId == null || sessionId.isEmpty()
                || assistantMessageId == null || assistantMessageId.isEmpty()) {
            return;
        }
        // 生产存储接缝（对照 Go files 回调 = 装饰后的全局 FileService + catalog.Bind）
        AgentWebPages pages = new AgentWebPages(dataSource, resourceCatalog,
                artifactCollectorWiring.webPageStore(), artifactCollectorWiring.webPageBinding(),
                tenantId, com.ragagent.session.domain.SessionOwnerIds.currentSessionOwnerId(),
                sessionId, assistantMessageId);
        fetch.withPageSource(pages);
    }

    /** registerTools（agent_service.go L837-1141 的注册面；工具集与硬门控逐条保留）。 */
    private void registerTools(ToolRegistry registry, QaAgentConfig config, Reranker rerankModel,
            String sessionId) {
        List<String> allowedTools = new ArrayList<>(config.getAllowedTools().isEmpty()
                ? com.ragagent.agent.tools.ToolDefinitions.defaultAllowedTools()
                : config.getAllowedTools());
        if (config.isSharedAgentReadOnly()) {
            allowedTools = filterSharedAgentWriteTools(allowedTools);
        }

        // Capability detection from SearchTargets（Go L869-896）
        boolean hasVectorKb = false;
        List<String> detectedWikiKbIds = new ArrayList<>();
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
                            detectedWikiKbIds.add(kb.getId());
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Go: continue
                }
            }
        }
        // Go L886-895：dedup → 由 SearchTargets 解析出带 doc/tag 窄化的 scope → **再用 scope
        // 重建 KB 清单**。hasWikiKb 必须看窄化后的结果：畸形空 target 不会变成整库授权，
        // 因而该 KB 也不该挂 wiki 工具。
        List<WikiScope> wikiScopes = detectedWikiKbIds.isEmpty()
                ? List.of()
                : WikiScope.newWikiScopesFromSearchTargets(config.getSearchTargets(), detectedWikiKbIds);
        List<String> wikiKbIds = new ArrayList<>();
        for (WikiScope scope : wikiScopes) {
            wikiKbIds.add(scope.knowledgeBaseId());
        }
        boolean hasWikiKb = !wikiKbIds.isEmpty();
        // Go L870：一个引擎一个 WikiRouteResolver，wiki 十件共享（search 见过的 slug
        // 会偏置 read_page 的查找序）
        WikiRouteResolver wikiRoutes = new WikiRouteResolver();
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

        // memory 工具跟开关（Go L956-962）：先摘，可用才挂回（"关掉"与"没存过"要答得不同）
        allowedTools.remove(ToolDefinitions.TOOL_SEARCH_MEMORY);
        if (memoryService.memoryAvailable()) {
            allowedTools.add(ToolDefinitions.TOOL_SEARCH_MEMORY);
        } else {
            log.info("search_memory not registered: long-term memory is off for this request");
        }

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
        String toolOwnerId = com.ragagent.session.domain.SessionOwnerIds.currentSessionOwnerId();
        for (String toolName : allowedTools) {
            com.ragagent.agent.tools.AgentTool toolToRegister = null;
            switch (toolName) {
                case ToolDefinitions.TOOL_THINKING ->
                        toolToRegister = new com.ragagent.agent.tools.SequentialThinkingTool();
                case ToolDefinitions.TOOL_TODO_WRITE ->
                        toolToRegister = new com.ragagent.agent.tools.TodoWriteTool();
                // 检索/会话/记忆/DB 族（2026-09-23 接线批）：seam → 真实服务经 AgentToolBackends
                case ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, ToolDefinitions.TOOL_GREP_CHUNKS,
                        ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS,
                        ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                        ToolDefinitions.TOOL_GET_DOCUMENT_INFO,
                        ToolDefinitions.TOOL_SEARCH_CONVERSATIONS,
                        ToolDefinitions.TOOL_SEARCH_MEMORY, ToolDefinitions.TOOL_DATABASE_QUERY,
                        ToolDefinitions.TOOL_DATA_SCHEMA, ToolDefinitions.TOOL_DATA_ANALYSIS ->
                        toolToRegister = toolBackends.createTool(toolName,
                                config.getSearchTargets(), rerankModel, toolOwnerId, sessionId);
                // wiki 族 10 件（2026-09-23 接线批·切片 2c）：Go L1093-1116
                case ToolDefinitions.TOOL_WIKI_READ_PAGE, ToolDefinitions.TOOL_WIKI_SEARCH,
                        ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, ToolDefinitions.TOOL_WIKI_FLAG_ISSUE,
                        ToolDefinitions.TOOL_WIKI_WRITE_PAGE, ToolDefinitions.TOOL_WIKI_REPLACE_TEXT,
                        ToolDefinitions.TOOL_WIKI_RENAME_PAGE, ToolDefinitions.TOOL_WIKI_DELETE_PAGE,
                        ToolDefinitions.TOOL_WIKI_READ_ISSUE, ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE ->
                        toolToRegister = toolBackends.createWikiTool(toolName,
                                config.getSearchTargets(), wikiScopes, wikiKbIds, wikiRoutes);
                // web 两件（2026-09-23 接线批·切片 2d）：Go L1071-1082
                case ToolDefinitions.TOOL_WEB_SEARCH, ToolDefinitions.TOOL_WEB_FETCH ->
                        toolToRegister = toolBackends.createWebTool(toolName,
                                config.getWebSearchMaxResults(), config.getWebSearchProviderId());
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
        var config = ModelRuntimeConfigs.chatConfig(model,
                p == null ? null : p.getAppId(), p == null ? null : p.getAppSecret());
        // ⚠️ 2026-09-23 修复：governor/ollama 曾传 null——并发闸门装配（95a49c4）后
        // ConcurrencyChatClient 必调 gateNamedN，agent 路径任何 LLM 调用都会 NPE。
        return com.ragagent.llm.chat.LlmChatClients.create(config,
                ollamaService.getIfAvailable(), concurrencyGovernor);
    }

    private Reranker rerankModel(String modelId) {
        var model = modelService.getModelByID(modelId);
        var p = model == null ? null : model.getParameters();
        var config = ModelRuntimeConfigs.rerankerConfig(model,
                p == null ? null : p.getAppId(), p == null ? null : p.getAppSecret());
        return com.ragagent.rerank.RerankerFactory.newReranker(config);
    }

    // ==================================================================
    // LoadAgentHistory（agent_history.go L50-186 + 构造族）
    // ==================================================================


}
