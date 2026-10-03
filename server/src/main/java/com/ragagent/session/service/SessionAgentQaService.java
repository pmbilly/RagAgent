package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.common.prompt.MessageAttachmentsPrompt;
import com.ragagent.common.session.PipelineUsedMemoryView;
import com.ragagent.knowledge.service.FaqEntryQueryService;
import com.ragagent.agent.AgentEngine;
import com.ragagent.agent.management.service.AgentConfigJson;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.payload.ErrorData;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.MemoryRecalledData;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.model.service.ModelService;
import com.ragagent.rerank.Reranker;

import com.ragagent.model.service.ModelRuntimeConfigs;
import com.ragagent.session.support.PipelineViews;

/**
 * agent 问答 service 面。
 *
 * <p>装配边界：
 * MCP 目录随 {@code registerMcpTools} 接线；
 * sandbox/browser/skills 的生产接线在 dev 部署（无 docker、无 browser 集成）走
 * 未启用分支——工具注册的硬门控逐条保留；
 * 检索工具族（knowledge_search 等）在检索执行面缺失时注册同样无产出，
 * 因此 dev 路径注册的核心是 thinking/todo_write/工具白名单可达集。</p>
 */
@Service
public class SessionAgentQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentQaService.class);

    /** agent 引擎的最大迭代次数上限。 */
    static final int MAX_ITERATIONS = 100;

    static final int AGENT_HISTORY_FETCH_MULTIPLIER = 2;
    static final int AGENT_HISTORY_FETCH_MIN = 20;

    private final MessageService messageService;
    private final ModelService modelService;
    private final MemoryService memoryService;
    private final SessionKnowledgeQaService knowledgeQa;
    private final AgentToolBackends toolBackends;

    /** 历史/消息装配簇。 */
    private final AgentHistoryAssembler historyAssembler;

    /** 配置装配簇。 */
    private final AgentConfigAssembler configAssembler;

    /** 引擎/工具装配簇。 */
    private final AgentEngineAssembler engineAssembler;
    private final com.ragagent.storage.service.ResourceCatalogService resourceCatalog;
    private final javax.sql.DataSource dataSource;
    private final ArtifactCollectorWiring artifactCollectorWiring;
    private final com.ragagent.knowledge.service.KnowledgeService knowledgeService;
    private final FaqEntryQueryService faqService;
    /** 并发闸门（chat 工厂注入；null 会让 ConcurrencyChatClient NPE）。 */
    private final com.ragagent.llm.limiter.ConcurrencyGovernor concurrencyGovernor;
    private final org.springframework.beans.factory.ObjectProvider<com.ragagent.llm.ollama.OllamaService>
            ollamaService;
    /** MCP 服务面（mcpServiceService/mcpManager/toolApprovalGate）。 */
    private final com.ragagent.mcp.service.McpServiceService mcpServiceService;
    private final com.ragagent.mcp.service.McpMetadataService mcpMetadataService;
    private final com.ragagent.mcp.protocol.McpClientManager mcpClientManager;
    private final com.ragagent.common.approval.Gate toolApprovalGate;
    /** 工具图片 VLM 描述器装配。 */
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
        this.engineAssembler =
                new AgentEngineAssembler(
                        memoryService,
                        knowledgeQa,
                        toolBackends,
                        artifactCollectorWiring,
                        knowledgeService,
                        faqService,
                        mcpServiceService,
                        mcpMetadataService,
                        mcpClientManager,
                        toolApprovalGate,
                        resourceCatalog,
                        dataSource,
                        vlmDescriberWiring);
    }

    // ==================================================================
    // AgentQA
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
            String vlm = req.agentConfig.path("vlmModelId").asText("");
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
            // 上下文 token 上限：显式设置 > 模型声明 > 缺省
            agentConfig.setMaxContextTokens(agentConfig.getMaxContextTokens() > 0
                    ? agentConfig.getMaxContextTokens()
                    : (modelContextWindow > 0 ? modelContextWindow
                            : com.ragagent.agent.AgentBudgets.DEFAULT_MAX_CONTEXT_TOKENS));
            log.info("Agent context window: {} tokens (model {} declares {})",
                    agentConfig.getMaxContextTokens(), effectiveModelId, modelContextWindow);

            // Rerank model only when knowledge_search can run
            Reranker rerankModel = null;
            if (AgentConfigAssembler.agentRequiresRerankModel(req.agentConfig)) {
                String rerankModelId = req.agentConfig.path("rerankModelId").asText("");
                if (rerankModelId.isEmpty()) {
                    throw new RuntimeException("rerank model is not configured: please set rerank_model_id on the agent");
                }
                rerankModel = rerankModel(rerankModelId);
            } else {
                log.info("knowledge_search is unavailable for the effective agent scope, "
                        + "skipping rerank model initialization");
            }

            // Multi-turn history 装载
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

            // Create agent engine
            AgentEngine engine = engineAssembler.createAgentEngine(agentConfig, summaryModel, rerankModel, eventBus,
                    sessionId, req.assistantMessageId);

            // Memory recall
            if (memoryService != null && req.agentConfig.path("memoryEnabled").asBoolean(false)) {
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
    // buildAgentConfig
    // ==================================================================


    // ==================================================================
    // CreateAgentEngine
    // ==================================================================


    // ── resolveKBAndDocInfos ────────


    /**
     * web 抓取页的完整快照面。
     * web_search 共享会话 web_fetch 的快照缓存（WithPageReader）；read_file 未被
     * 沙箱路径注册时以 web:// 读取范围注册（描述随来源变化）。存储写字节面
     * 未落地——经
     * {@link AgentWebPages} 的接缝落保存失败分支，见类 Javadoc。
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
    // LoadAgentHistory
    // ==================================================================


}
