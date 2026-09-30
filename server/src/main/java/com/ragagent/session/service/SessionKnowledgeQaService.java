package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.EventManager;
import com.ragagent.chatpipeline.PipelineBuilder;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelineProgress;
import com.ragagent.chatpipeline.PipelineProgress.StageProgress;
import com.ragagent.chatpipeline.PluginError;
import com.ragagent.chatpipeline.SummaryConfig;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.settings.ConversationProperties;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventBusInterface;
import com.ragagent.event.EventType;
import com.ragagent.event.AgentFinalAnswerData;
import com.ragagent.event.AgentReferencesData;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import com.ragagent.modelcontext.Registry;
import com.ragagent.retrieval.domain.SearchResult;

import static com.ragagent.session.service.QaSupport.TagScope;
import com.ragagent.chatpipeline.PipelinePorts;

/**
 * 知识问答 service 面（对照 Go internal/application/service/session_knowledge_qa.go
 * + session_qa_helpers.go；chat_pipeline 的调用方）。
 *
 * <p>已知差异（本批装配边界，均已备案）：检索执行面未翻译（hybridSearch adapter 空）、
 * web_fetch/网页抓取在 RAG 路径可用（websearch 执行面波 4.4 已有）。Langfuse span
 * 为 no-op seam（4.6a 备案），span 生命周期调用点保留。</p>
 *
 * <p>例外说明(§14.5):约 1,000 行略超 800——KnowledgeQA/KnowledgeQAByEvent/SearchKnowledge
 * 三条入口流是单一状态机,解析与降级已拆至 SessionQaResolution/SessionQaFallback。</p>

 */
@Service
public class SessionKnowledgeQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionKnowledgeQaService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    final EventManager eventManager;
    final ConversationProperties cfg;
    final ModelService modelService;
    final KnowledgeService knowledgeService;
    final KnowledgeBaseService knowledgeBaseService;
    final PipelinePorts.ModelService pipelineModelService;
    final com.ragagent.auth.service.TenantService tenantService;
    final com.ragagent.websearch.mapper.WebSearchProviderRepository webSearchProviderRepository;
    final javax.sql.DataSource dataSource;

    /** 解析/降级协作者(构造期装配)。 */
    final SessionQaResolution resolution;
    final SessionQaFallback fallback;

    public SessionKnowledgeQaService(EventManager eventManager,
            ConversationProperties cfg,
            ModelService modelService,
            KnowledgeService knowledgeService,
            KnowledgeBaseService knowledgeBaseService,
            PipelinePorts.ModelService pipelineModelService,
            com.ragagent.auth.service.TenantService tenantService,
            com.ragagent.websearch.mapper.WebSearchProviderRepository webSearchProviderRepository,
            javax.sql.DataSource dataSource) {
        this.eventManager = eventManager;
        this.cfg = cfg;
        this.modelService = modelService;
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.pipelineModelService = pipelineModelService;
        this.tenantService = tenantService;
        this.webSearchProviderRepository = webSearchProviderRepository;
        this.dataSource = dataSource;
        this.resolution = new SessionQaResolution(this);
        this.fallback = new SessionQaFallback(this);
    }

    /**
     * 对照 Go knowledge.go L842-849 的 ListKnowledgeIDsByTagIDs（仓储 SQL：
     * repository/knowledge.go L1057-1073 —— DISTINCT knowledge id JOIN 关系表）。
     * 纯新增：知识域既有 service 不动。
     */
    // ── seam 委托:实现随 Resolution 协作者(外部消费面不变) ──

    public static boolean isAgentMode(ObjectNode c) {
        return SessionQaResolution.isAgentMode(c);
    }

    public long resolveRetrievalTenantId(QaSupport.QaRequest req) {
        return resolution.resolveRetrievalTenantId(req);
    }

    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds,
            List<String> knowledgeIds) {
        return resolution.resolveChatModelId(req, knowledgeBaseIds, knowledgeIds);
    }

    public SessionQaResolution.MentionScope restrictMentionsToAgentScope(
            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg,
            long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {
        return resolution.restrictMentionsToAgentScope(agent, agentCfg, sessionTenantId, kbIds, knowledgeIds);
    }

    public KnowledgeResolution resolveKnowledgeBases(QaSupport.QaRequest req) {
        return resolution.resolveKnowledgeBases(req);
    }

    public List<SearchTargetView> buildSearchTargets(long tenantId, List<String> knowledgeBaseIds,
            List<String> knowledgeIds, List<TagScope> tagScopes) {
        return resolution.buildSearchTargets(tenantId, knowledgeBaseIds, knowledgeIds, tagScopes);
    }

    public KnowledgeBase findKnowledgeBase(String kbId) {
        return resolution.findKnowledgeBase(kbId);
    }

    public List<String> listKnowledgeIdsByTagIds(long tenantId, String kbId, List<String> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tagIds.size(); i++) {
            if (i > 0) {
                in.append(',');
            }
            in.append('?');
        }
        String sql = "SELECT DISTINCT k.id FROM knowledges k "
                + "JOIN knowledge_tag_relations ktr ON k.id = ktr.knowledge_id "
                + "WHERE k.tenant_id = ? AND k.knowledge_base_id = ? AND ktr.tag_id IN (" + in + ")";
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, kbId);
            for (int i = 0; i < tagIds.size(); i++) {
                ps.setString(3 + i, tagIds.get(i));
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return ids;
    }

    // ==================================================================
    // KnowledgeQA（session_knowledge_qa.go L23-238）
    // ==================================================================

    public void knowledgeQA(QaSupport.QaRequest req, EventBus eventBus) {
        String sessionId = req.session.getId();
        log.info("Knowledge base question answering parameters, session ID: {}, query: {}, webSearchEnabled: {}",
                sessionId, req.query, req.webSearchEnabled);

        // 对照 Go L38-47：qa.setup span 包住请求装配段（KB/模型解析、检索目标构建、
        // agent 覆盖应用）——补上 trace 开始到首个阶段观测之间的可见空档
        com.ragagent.tracing.langfuse.Span setupSpan =
                com.ragagent.tracing.langfuse.LangfuseManager.get().startSpan(
                        new com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions(
                                "qa.setup", null,
                                java.util.Map.of("session_id", sessionId == null ? "" : sessionId)));

        // Resolve knowledge bases using shared helper
        KnowledgeResolution kb = resolution.resolveKnowledgeBases(req);

        // Resolve chat model ID using shared helper
        String chatModelId = resolution.resolveChatModelId(req, kb.kbIds, kb.knowledgeIds);

        // Initialize ChatManage defaults from config.yaml
        SummaryConfig summaryConfig = new SummaryConfig();
        summaryConfig.setPrompt(cfg.getSummaryPrompt());
        summaryConfig.setContextTemplate(cfg.getSummaryContextTemplate());
        summaryConfig.setTemperature(cfg.getSummaryTemperature());
        summaryConfig.setNoMatchPrefix(cfg.getSummaryNoMatchPrefix());
        summaryConfig.setMaxCompletionTokens(cfg.getSummaryMaxCompletionTokens());
        String fallbackStrategy = cfg.getFallbackStrategy();
        if (fallbackStrategy == null || fallbackStrategy.isEmpty()) {
            fallbackStrategy = "fixed";
            log.info("Fallback strategy not set, using default: {}", fallbackStrategy);
        }

        // Resolve chat model vision capability and VLM model ID for image routing
        boolean chatModelSupportsVision = false;
        String vlmModelId = "";
        if (!chatModelId.isEmpty()) {
            try {
                Model chatModelInfo = modelService.getModelByID(chatModelId);
                if (chatModelInfo != null) {
                    chatModelSupportsVision = chatModelInfo.getParameters() != null && chatModelInfo.getParameters().isSupportsVision();
                }
            } catch (RuntimeException e) {
                // Go: err != nil → 留 false
            }
        }
        if (req.agentConfig != null) {
            vlmModelId = req.agentConfig.path("vlm_model_id").asText("");
        }

        // Resolve retrieval tenant scope using shared helper
        long retrievalTenantId = resolution.resolveRetrievalTenantId(req);

        // Build unified search targets（computed once）
        List<SearchTargetView> searchTargets;
        try {
            searchTargets = resolution.buildSearchTargets(retrievalTenantId, kb.kbIds, kb.knowledgeIds, req.tagScopes);
        } catch (RuntimeException e) {
            throw new RuntimeException("build search targets: " + e.getMessage(), e);
        }

        log.info("Creating chat manage object, knowledge base IDs: {}, knowledge IDs: {}, chat model ID: {}, search targets: {}",
                kb.kbIds, kb.knowledgeIds, chatModelId, searchTargets.size());

        ChatManage chatManage = new ChatManage();
        chatManage.setQuery(req.query);
        chatManage.setSessionId(sessionId);
        chatManage.setUserId(SessionService.sessionUserIDForLookup());
        chatManage.setMaxRounds(cfg.getMaxRounds());
        chatManage.setKnowledgeBaseIds(kb.kbIds);
        chatManage.setKnowledgeIds(kb.knowledgeIds);
        chatManage.setSearchTargets(SearchTargetView.toPipeline(searchTargets));
        chatManage.setVectorThreshold(cfg.getVectorThreshold());
        chatManage.setKeywordThreshold(cfg.getKeywordThreshold());
        chatManage.setEmbeddingTopK(cfg.getEmbeddingTopK());
        chatManage.setRerankTopK(cfg.getRerankTopK());
        chatManage.setRerankThreshold(cfg.getRerankThreshold());
        chatManage.setChatModelId(chatModelId);
        chatManage.setSummaryConfig(summaryConfig);
        chatManage.setFallbackStrategy(fallbackStrategy);
        chatManage.setFallbackResponse(cfg.getFallbackResponse());
        chatManage.setFallbackPrompt(cfg.getFallbackPrompt());
        chatManage.setEnableRewrite(cfg.isEnableRewrite());
        chatManage.setEnableQueryExpansion(cfg.isEnableQueryExpansion());
        chatManage.setRewritePromptSystem(cfg.getRewritePromptSystem());
        chatManage.setRewritePromptUser(cfg.getRewritePromptUser());
        chatManage.setWebSearchEnabled(req.webSearchEnabled);
        chatManage.setWebSearchProviderId(resolveWebSearchProviderId(req, retrievalTenantId));
        chatManage.setWebSearchMaxResults(resolveWebSearchMaxResults(req));
        chatManage.setWebFetchEnabled(resolveWebFetchEnabled(req));
        chatManage.setWebFetchTopN(resolveWebFetchTopN(req));
        chatManage.setTenantId(retrievalTenantId);
        chatManage.setImages(req.imageUrls);
        chatManage.setVlmModelId(vlmModelId);
        chatManage.setChatModelSupportsVision(chatModelSupportsVision);
        chatManage.setAttachments(req.attachments);
        chatManage.setLanguage(currentLanguage());
        chatManage.setRewriteQuery(req.query);
        chatManage.setImageDescription(req.imageDescription);
        chatManage.setQuotedContext(req.quotedContext);
        chatManage.setEventBus(eventBus.asEventBusInterface());
        chatManage.setMessageId(req.assistantMessageId);
        chatManage.setUserMessageId(req.userMessageId);

        // Apply custom agent overrides
        resolution.applyAgentOverridesToChatManage(req, chatManage);

        // Pipeline 选择（Go L167-207）
        boolean hasKb = hasKnowledgeRetrievalScope(searchTargets, kb.kbIds, kb.knowledgeIds);
        boolean needsRag = hasKb || req.webSearchEnabled;
        boolean hasHistory = chatManage.getMaxRounds() > 0;

        List<String> pipeline;
        if (!needsRag) {
            // Pure chat — no retrieval needed.
            String userContent = req.query;
            if (!req.imageDescription.isEmpty() && !chatModelSupportsVision) {
                userContent += "\n\n[用户上传图片内容]\n" + req.imageDescription;
            }
            if (!req.quotedContext.isEmpty()) {
                userContent += "\n\n" + req.quotedContext;
            }
            if (!req.attachments.isEmpty()) {
                userContent += com.ragagent.session.MessageAttachmentsPrompt.build(req.attachments);
            }
            chatManage.setUserContent(userContent);

            pipeline = PipelineBuilder.builder()
                    .addIf(hasHistory, PipelineEventType.LOAD_HISTORY)
                    .add(PipelineEventType.MEMORY_RECALL)
                    .add(PipelineEventType.CHAT_COMPLETION_STREAM)
                    .build();
        } else {
            // RAG — dynamically assembled.
            pipeline = PipelineBuilder.builder()
                    .addIf(hasHistory, PipelineEventType.LOAD_HISTORY)
                    .add(PipelineEventType.MEMORY_RECALL)
                    .add(PipelineEventType.QUERY_UNDERSTAND)
                    .add(PipelineEventType.CHUNK_SEARCH_PARALLEL)
                    .add(PipelineEventType.CHUNK_RERANK)
                    .addIf(req.webSearchEnabled, PipelineEventType.WEB_FETCH)
                    .add(PipelineEventType.CHUNK_MERGE)
                    .add(PipelineEventType.FILTER_TOP_K)
                    .addIf(chatManage.isDataAnalysisEnabled(), PipelineEventType.DATA_ANALYSIS)
                    .add(PipelineEventType.INTO_CHAT_MESSAGE)
                    .add(PipelineEventType.CHAT_COMPLETION_STREAM)
                    .build();
        }

        log.info("Assembled pipeline ({} stages), hasKB={}, webSearch={}, history={}",
                pipeline.size(), hasKb, req.webSearchEnabled, hasHistory);

        // 对照 Go L213：进入 QA 事件处理前打上「按会话属主租户查」标记——管线内的会话/
        // 消息查询由此走**租户范围**（共享 agent 场景下当前主体不是属主，带 user 范围会查不到）。
        // 清理在请求收尾处（KnowledgeQaController 的 TenantContext.clear() 旁）。
        SessionLookupScope.mark();

        // 对照 Go L218-222：setup span 收尾（stages / KB 列表 / 检索目标数）
        java.util.Map<String, Object> setupOutput = new java.util.LinkedHashMap<>();
        setupOutput.put("stages", pipeline.size());
        setupOutput.put("knowledge_base_ids", kb.kbIds);
        setupOutput.put("search_targets", searchTargets.size());
        setupSpan.finish(setupOutput, null, null);

        // Trigger（session tenant 设定 + sessionID 传播在 Java 侧由 TenantContext 承担）
        knowledgeQAByEvent(chatManage, pipeline);
        log.info("Knowledge base question answering initiated");
    }

    // ==================================================================
    // KnowledgeQAByEvent（Go L669-806）
    // ==================================================================

    public void knowledgeQAByEvent(ChatManage chatManage, List<String> eventList) {
        log.info("Start processing knowledge base question answering through events");
        log.info("Knowledge base question answering parameters, session ID: {}, query: {}",
                chatManage.getSessionId(), chatManage.getQuery());

        List<String> methods = new ArrayList<>(eventList);
        log.info("Trigger event list: {}", methods);

        long pipelineStart = System.currentTimeMillis();
        String lastRetrievalStage = PipelineProgress.lastConsolidatedRetrievalStage(eventList, chatManage);
        StageProgress retrievalProgress = null;
        long retrievalStart = 0;
        StageProgress understandProgress = null;
        long understandStart = 0;
        for (String eventType : eventList) {
            long stageStart = System.currentTimeMillis();
            // 对照 Go L698-712：阶段 span 包住本阶段；CHAT_COMPLETION_STREAM 跳过——
            // 该阶段的 chat.completion.stream generation 已覆盖完整时长，再套一层
            // 会产出"视觉上超出父节点"的子观测
            com.ragagent.tracing.langfuse.Span stageSpan = null;
            if (!PipelineEventType.CHAT_COMPLETION_STREAM.equals(eventType)) {
                stageSpan = com.ragagent.tracing.langfuse.LangfuseManager.get().startSpan(
                        new com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions(
                                "pipeline." + eventType, null,
                                java.util.Map.of("event_type", eventType,
                                        "session_id", chatManage.getSessionId() == null
                                                ? "" : chatManage.getSessionId())));
            }
            if (PipelineEventType.QUERY_UNDERSTAND.equals(eventType)
                    && PipelineProgress.shouldEmitQueryUnderstandProgress(chatManage)) {
                understandStart = stageStart;
                understandProgress = PipelineProgress.beginQueryUnderstandProgress(chatManage);
            }
            if (PipelineProgress.isConsolidatedRetrievalStage(eventType, chatManage) && retrievalProgress == null) {
                retrievalStart = stageStart;
                retrievalProgress = PipelineProgress.beginRetrievalProgress(chatManage);
            }
            // Emit references before answer streaming（complete 关流前必达）
            if (PipelineEventType.CHAT_COMPLETION_STREAM.equals(eventType)) {
                emitKnowledgeReferencesEvent(chatManage);
            }
            PluginError err = eventManager.trigger(eventType, chatManage);
            if (understandProgress != null && PipelineEventType.QUERY_UNDERSTAND.equals(eventType)) {
                PipelineProgress.endQueryUnderstandProgress(chatManage, understandProgress,
                        understandStart, err);
                understandProgress = null;
            }
            if (retrievalProgress != null
                    && PipelineProgress.shouldCloseRetrievalProgress(eventType, lastRetrievalStage, err)) {
                PipelineProgress.endRetrievalProgress(chatManage, retrievalProgress,
                        retrievalStart, err);
                retrievalProgress = null;
            }
            long stageDuration = System.currentTimeMillis() - stageStart;

            // 对照 Go L746-753：阶段 span 收尾（输出时长；SEARCH_NOTHING 不算错误）
            if (stageSpan != null) {
                String stageErr = err != null && err != PluginError.SEARCH_NOTHING
                        ? (err.err != null ? err.err.getMessage() : err.description) : null;
                stageSpan.finish(java.util.Map.of("duration_ms", stageDuration), null, stageErr);
            }

            // 用户停止：先于 ErrSearchNothing 判定（Go L764-771）
            if (cancelled()) {
                PipelineLog.warn("Pipeline", "stage_cancelled", Map.of(
                        "event", eventType, "duration_ms", stageDuration, "reason", "context canceled"));
                throw new RuntimeException("context canceled");
            }

            if (err == PluginError.SEARCH_NOTHING) {
                PipelineLog.warn("Pipeline", "stage_fallback", Map.of(
                        "event", eventType, "duration_ms", stageDuration,
                        "reason", "search_nothing", "strategy", chatManage.getFallbackStrategy()));
                fallback.handleFallbackResponse(chatManage);
                return;
            }

            if (err != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("event", eventType);
                f.put("duration_ms", stageDuration);
                f.put("error_type", err.errorType);
                f.put("description", err.description);
                PipelineLog.error("Pipeline", "stage_failed", f);
                throw err.err != null ? new RuntimeException(err.err) : new RuntimeException(err.description);
            }

            PipelineLog.info("Pipeline", "stage_complete", Map.of(
                    "event", eventType, "duration_ms", stageDuration));
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("session_id", chatManage.getSessionId());
        f.put("total_stages", eventList.size());
        f.put("total_duration_ms", System.currentTimeMillis() - pipelineStart);
        PipelineLog.info("Pipeline", "all_stages_complete", f);
    }

    /** 虚拟线程取消探测（对照 ctx.Err() != nil；stop 链路 4.6d 接线）。 */
    private static boolean cancelled() {
        return Thread.currentThread().isInterrupted();
    }

    // ==================================================================
    // SearchKnowledge（Go L811-927）
    // ==================================================================

    public List<SearchResult> searchKnowledge(List<String> knowledgeBaseIds, List<String> knowledgeIds,
            List<TagScope> tagScopes, String query) {
        log.info("Start knowledge base search without LLM summary");
        Long tenantIdBoxed = TenantContext.currentTenantId();
        if (tenantIdBoxed == null) {
            throw new RuntimeException("workspace ID not found in context");
        }
        long tenantId = tenantIdBoxed;

        List<SearchTargetView> searchTargets;
        try {
            searchTargets = resolution.buildSearchTargets(tenantId, knowledgeBaseIds, knowledgeIds, tagScopes);
        } catch (RuntimeException e) {
            throw new RuntimeException("build search targets: " + e.getMessage(), e);
        }

        if (searchTargets.isEmpty()) {
            log.warn("No search targets available, returning empty results");
            return new ArrayList<>();
        }

        // Create default retrieval parameters — prefer tenant RetrievalConfig
        String userId = SessionService.sessionUserIDForLookup();
        RetrievalConfigView rc = new RetrievalConfigView(null);
        try {
            var tenant = tenantService.getTenantById(tenantId);
            if (tenant != null) {
                rc = new RetrievalConfigView(tenant.getRetrievalConfig());
            }
        } catch (RuntimeException e) {
            // Go: err2 != nil → rc 保持 nil（GetEffective* 兜底）
        }

        ChatManage chatManage = new ChatManage();
        chatManage.setQuery(query);
        chatManage.setUserId(userId);
        // Go 侧插件从 ctx 取 tenant；Java 无 ctx，经 ChatManage 传递（对照 QA 路径
        // retrievalTenantId 的同款赋值）——漏了它 Merge 阶段 faq_enrich/expand 全跳过，
        // knowledge-search 响应的 content 就少了前后文扩块，与 Go 逐字节对不上。
        chatManage.setTenantId(tenantId);
        chatManage.setKnowledgeBaseIds(knowledgeBaseIds);
        chatManage.setKnowledgeIds(knowledgeIds);
        chatManage.setSearchTargets(SearchTargetView.toPipeline(searchTargets));
        chatManage.setMaxRounds(cfg.getMaxRounds());
        chatManage.setEmbeddingTopK(rc.embeddingTopK());
        chatManage.setVectorThreshold(rc.vectorThreshold());
        chatManage.setKeywordThreshold(rc.keywordThreshold());
        chatManage.setRerankTopK(rc.rerankTopK());
        chatManage.setRerankThreshold(rc.rerankThreshold());
        chatManage.setRewriteQuery(query);

        // Use rerank model from RetrievalConfig if set, otherwise first available
        if (rc.rerankModelId() != null && !rc.rerankModelId().isEmpty()) {
            chatManage.setRerankModelId(rc.rerankModelId());
        } else {
            try {
                for (Model model : modelService.listModels()) {
                    if (model == null) {
                        continue;
                    }
                    if ("Rerank".equals(model.getType())) {
                        chatManage.setRerankModelId(model.getId());
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.error("Failed to get models: {}", e.toString());
                throw e;
            }
        }

        List<String> searchEvents = List.of(
                PipelineEventType.CHUNK_SEARCH,
                PipelineEventType.CHUNK_RERANK,
                PipelineEventType.CHUNK_MERGE,
                PipelineEventType.FILTER_TOP_K);

        log.info("Trigger search event list: {}", searchEvents);

        for (String event : searchEvents) {
            log.info("Starting to trigger search event: {}", event);
            // 对照 Go L898-906：search_knowledge 流的阶段 span（恒开，含 SEARCH_NOTHING）
            com.ragagent.tracing.langfuse.Span stageSpan =
                    com.ragagent.tracing.langfuse.LangfuseManager.get().startSpan(
                            new com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions(
                                    "pipeline." + event, null,
                                    java.util.Map.of("event_type", event,
                                            "flow", "search_knowledge")));
            PluginError err = eventManager.trigger(event, chatManage);

            // 对照 Go L907-911：SEARCH_NOTHING 不算错误；其余带 err.Err 收尾
            String stageErr = err != null && err != PluginError.SEARCH_NOTHING
                    ? (err.err != null ? err.err.getMessage() : err.description) : null;
            stageSpan.finish(null, null, stageErr);

            if (err == PluginError.SEARCH_NOTHING) {
                log.warn("Event {} triggered, search result is empty", event);
                return new ArrayList<>();
            }
            if (err != null) {
                log.error("Event triggering failed, event: {}, error type: {}, description: {}, error: {}",
                        event, err.errorType, err.description, err.err);
                // 对照 Go：return nil, err.Err → handler NewInternalServerError(err.Error())
                String msg = err.err != null ? err.err.getMessage() : err.description;
                throw BizException.internal(msg);
            }
            log.info("Event {} triggered successfully", event);
        }

        log.info("Knowledge base search completed, found {} results",
                chatManage.getMergeResult() == null ? 0 : chatManage.getMergeResult().size());
        return chatManage.getMergeResult() == null ? new ArrayList<>() : chatManage.getMergeResult();
    }

    // ==================================================================
    // 共享 QA helpers（session_qa_helpers.go）
    // ==================================================================

    /**
     * 判定骨架（对照 Go {@code access.KBPermissions.Check} 的③步，见 {@link #callerCanReadKb}）：
     * 作用域租户为 0 / 属主租户为 0 ⇒ 否；同租户 ⇒ 是；否则交给共享判定。
     * 抽成静态纯函数以便脱离 Spring 上下文做回归（API-key 作用域与共享判定在调用方装配）。
     */
    static boolean kbReadableByCaller(Long scopeTenantId, long ownerTenantId,
                                      java.util.function.BooleanSupplier sharedPermitsViewer) {
        if (scopeTenantId == null || scopeTenantId == 0 || ownerTenantId == 0) {
            return false;
        }
        if (scopeTenantId == ownerTenantId) {
            return true;
        }
        return sharedPermitsViewer != null && sharedPermitsViewer.getAsBoolean();
    }

    // ==================================================================
    // fallback（Go L929-1196）
    // ==================================================================

    static String trailTrim(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** renderFallbackPrompt（Go L1055-1076）。 */
    String renderFallbackPrompt(ChatManage chatManage) {
        String query = chatManage.getQuery();
        String rq = chatManage.getRewriteQuery() == null ? "" : chatManage.getRewriteQuery().trim();
        if (!rq.isEmpty()) {
            query = rq;
        }
        String kbDocuments = buildKbDocumentListing(chatManage);
        String result = com.ragagent.common.prompt.AgentPromptPlaceholders.renderPromptPlaceholders(chatManage.getFallbackPrompt(), Map.of(
                "query", query,
                "language", chatManage.getLanguage(),
                "kb_documents", kbDocuments));
        if (!chatManage.getImageDescription().isEmpty() && !chatManage.isChatModelSupportsVision()) {
            result += "\n\n[用户上传图片内容]\n" + chatManage.getImageDescription();
        }
        if (!chatManage.getQuotedContext().isEmpty()) {
            result += "\n\n" + chatManage.getQuotedContext();
        }
        return result;
    }

    /** buildKBDocumentListing（Go L1081-1146）。 */
    private String buildKbDocumentListing(ChatManage chatManage) {
        Set<String> kbIds = new LinkedHashSet<>();
        if (chatManage.getSearchTargets() != null) {
            for (var t : chatManage.getSearchTargets()) {
                kbIds.add(t.knowledgeBaseId());
            }
        }
        kbIds.addAll(chatManage.getKnowledgeBaseIds());
        if (kbIds.isEmpty()) {
            return "";
        }
        final int maxDocuments = 50;
        StringBuilder b = new StringBuilder();
        int total = 0;
        for (String kbId : kbIds) {
            if (total >= maxDocuments) {
                break;
            }
            List<Knowledge> knowledges;
            try {
                knowledges = knowledgeService.listKnowledge(kbId, 1, 10000, null, null, null, null, false).getRecords();
            } catch (RuntimeException e) {
                log.warn("buildKBDocumentListing: failed to list knowledge for KB {}: {}", kbId, e.toString());
                continue;
            }
            for (Knowledge k : knowledges) {
                if (total >= maxDocuments) {
                    break;
                }
                if (!"enabled".equals(k.getEnableStatus())) {
                    continue;
                }
                String title = k.getTitle();
                if (title == null || title.isEmpty()) {
                    title = k.getFileName();
                }
                if (title == null || title.isEmpty()) {
                    continue;
                }
                b.append("- ").append(title);
                if (k.getFileType() != null && !k.getFileType().isEmpty()) {
                    b.append(" (").append(k.getFileType()).append(")");
                }
                if (k.getDescription() != null && !k.getDescription().isEmpty()) {
                    String desc = k.getDescription();
                    if (desc.codePointCount(0, desc.length()) > 100) {
                        desc = substringByCodePoints(desc, 100) + "...";
                    }
                    b.append(": ").append(desc);
                }
                b.append("\n");
                total++;
            }
        }
        if (b.length() == 0) {
            return "";
        }
        if (total >= maxDocuments) {
            b.append(String.format("... (showing first %d documents)%n", maxDocuments));
        }
        return b.toString();
    }

    private static String substringByCodePoints(String s, int max) {
        int i = 0;
        int cp = 0;
        while (i < s.length() && cp < max) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            cp++;
        }
        return s.substring(0, i);
    }

    /** consumeFallbackStream（Go L1149-1197）。 */
    void consumeFallbackStream(ChatManage chatManage,
            java.util.concurrent.BlockingQueue<StreamResponse> responseChan, Registry modelContext) {
        String fallbackId = com.ragagent.event.EventIds.generateEventID("fallback");
        EventBusInterface eventBus = chatManage.getEventBus();
        StringBuilder finalContent = new StringBuilder();
        boolean streamCompleted = false;
        var decoder = modelContext.streamDecoder();
        // 生产者异常/中断时不投终态元素（RemoteApiChat 的 catch-return 路径）——
        // 无上限的 poll 空转每次回退泄漏一个自旋虚拟线程。连续空读超时即收束
        // （主流路径 takeQuietly 120s 同款兜底思想）。
        int emptyPolls = 0;

        while (true) {
            StreamResponse response;
            try {
                response = responseChan.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (response == null) {
                if (++emptyPolls >= 120) {
                    log.warn("fallback stream produced no terminal frame after {}s, giving up",
                            emptyPolls);
                    break;
                }
                continue; // Java 无 channel 关闭：done 收束约定由生产者保证
            }
            emptyPolls = 0;
            if (response.getResponseType() == ResponseType.ANSWER) {
                String content = decoder.feed(response.getContent());
                if (response.isDone()) {
                    content += decoder.flush();
                }
                finalContent.append(content);
                Event evt = new Event();
                evt.setId(fallbackId);
                evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
                evt.setSessionId(chatManage.getSessionId());
                AgentFinalAnswerData data = new AgentFinalAnswerData(content, response.isDone(), true);
                evt.setData(data);
                try {
                    eventBus.emit(evt);
                } catch (RuntimeException e) {
                    log.error("Failed to emit fallback answer chunk event: {}", e.toString());
                }
                if (response.isDone()) {
                    com.ragagent.llm.domain.ChatResponse cr = new com.ragagent.llm.domain.ChatResponse();
                    cr.setContent(finalContent.toString());
                    chatManage.setChatResponse(cr);
                    streamCompleted = true;
                    log.info("Fallback streaming response completed");
                    break;
                }
            }
        }
        if (!streamCompleted) {
            log.warn("Fallback stream closed without completion, emitting final event with fixed response");
            emitFallbackAnswer(chatManage, chatManage.getFallbackResponse());
        }
    }

    /** emitKnowledgeReferencesEvent（Go L1204-1219）。 */
    private static void emitKnowledgeReferencesEvent(ChatManage chatManage) {
        if (chatManage == null || chatManage.getEventBus() == null
                || chatManage.getMergeResult() == null || chatManage.getMergeResult().isEmpty()) {
            return;
        }
        log.info("Emitting references event with {} results (pre-answer)", chatManage.getMergeResult().size());
        Event evt = new Event();
        evt.setId(com.ragagent.event.EventIds.generateEventID("references"));
        evt.setType(EventType.EVENT_AGENT_REFERENCES);
        evt.setSessionId(chatManage.getSessionId());
        evt.setData(new AgentReferencesData(chatManage.getMergeResult(), 0));
        try {
            chatManage.getEventBus().emit(evt);
        } catch (RuntimeException e) {
            log.error("Failed to emit references event: {}", e.toString());
        }
    }

    /** emitFallbackAnswer（Go L1222-1245）。 */
    void emitFallbackAnswer(ChatManage chatManage, String content) {
        EventBusInterface eventBus = chatManage.getEventBus();
        if (eventBus == null) {
            return;
        }
        if (!chatManage.citationsEnabled()) {
            Registry registry = new Registry(false);
            content = registry.decodeOutputText(content);
        }
        String fallbackId = com.ragagent.event.EventIds.generateEventID("fallback");
        Event evt = new Event();
        evt.setId(fallbackId);
        evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
        evt.setSessionId(chatManage.getSessionId());
        evt.setData(new AgentFinalAnswerData(content, true, true));
        try {
            eventBus.emit(evt);
            log.info("Fallback answer event emitted successfully");
        } catch (RuntimeException e) {
            log.error("Failed to emit fallback answer event: {}", e.toString());
        }
    }

    // ==================================================================
    // web search 解析（Go L1249-1291）
    // ==================================================================

    private String resolveWebSearchProviderId(QaSupport.QaRequest req, long tenantId) {
        if (req.agentConfig != null) {
            String providerId = req.agentConfig.path("web_search_provider_id").asText("");
            if (!providerId.isEmpty()) {
                return providerId;
            }
        }
        try {
            for (var provider : webSearchProviderRepository.list(tenantId)) {
                if (provider != null && provider.isDefault() && provider.getId() != null
                        && !provider.getId().isEmpty()) {
                    return provider.getId();
                }
            }
        } catch (RuntimeException ignored) {
            // Go: err != nil → 落空
        }
        return "";
    }

    private boolean resolveWebFetchEnabled(QaSupport.QaRequest req) {
        if (req.agentConfig != null) {
            return req.agentConfig.path("web_fetch_enabled").asBoolean(false);
        }
        return false;
    }

    private int resolveWebFetchTopN(QaSupport.QaRequest req) {
        if (req.agentConfig != null) {
            int topN = req.agentConfig.path("web_fetch_top_n").asInt(0);
            if (topN > 0) {
                return topN;
            }
        }
        return 3;
    }

    private int resolveWebSearchMaxResults(QaSupport.QaRequest req) {
        if (req.agentConfig != null) {
            int max = req.agentConfig.path("web_search_max_results").asInt(0);
            if (max > 0) {
                return max;
            }
        }
        // 租户缺省分支（对照 Go session_knowledge_qa.go L1285-1288：ctx TenantInfo →
        // EffectiveWebSearchConfig(tenant.WebSearchConfig).MaxResults；2026-09-25 评审批接线）
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        if (tid != null) {
            try {
                com.ragagent.auth.domain.Tenant tenant = tenantService.getTenantById(tid);
                if (tenant != null && tenant.getWebSearchConfig() != null
                        && !tenant.getWebSearchConfig().isNull()) {
                    com.ragagent.auth.domain.tenantconfig.WebSearchConfig cfg =
                            JSON.treeToValue(tenant.getWebSearchConfig(),
                                    com.ragagent.auth.domain.tenantconfig.WebSearchConfig.class);
                    int max = cfg.getMaxResults();
                    if (max > 0) {
                        return max;
                    }
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("resolveWebSearchMaxResults tenant config parse failed: {}", e.getMessage());
            }
        }
        return 10; // types.DefaultWebSearchMaxResults
    }

    // ==================================================================
    // 纯函数族（Go L617-666）
    // ==================================================================

    public static Map<String, List<String>> mergeTagScopesByKb(List<TagScope> scopes) {
        Map<String, List<String>> byKb = new LinkedHashMap<>();
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        if (scopes == null) {
            return byKb;
        }
        for (TagScope scope : scopes) {
            if (scope.knowledgeBaseId.isEmpty()) {
                continue;
            }
            Set<String> seenTags = seen.computeIfAbsent(scope.knowledgeBaseId, k -> new LinkedHashSet<>());
            for (String tagId : scope.tagIds) {
                if (tagId.isEmpty() || seenTags.contains(tagId)) {
                    continue;
                }
                seenTags.add(tagId);
                byKb.computeIfAbsent(scope.knowledgeBaseId, k -> new ArrayList<>()).add(tagId);
            }
        }
        return byKb;
    }

    public static List<String> uniqueNonEmptyStrings(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.isEmpty() || seen.contains(value)) {
                continue;
            }
            seen.add(value);
            out.add(value);
        }
        return out;
    }

    public static List<String> intersectStrings(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> rightSet = new LinkedHashSet<>(right);
        List<String> out = new ArrayList<>();
        for (String value : left) {
            if (rightSet.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /** 对照 types.HasKnowledgeRetrievalScope（Java 侧以 target 视图判）。 */
    public static boolean hasKnowledgeRetrievalScope(List<SearchTargetView> targets,
            List<String> kbIds, List<String> knowledgeIds) {
        if (!kbIds.isEmpty() || !knowledgeIds.isEmpty()) {
            return true;
        }
        for (SearchTargetView t : targets) {
            if (!t.tagIds.isEmpty() || !t.knowledgeIds.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static List<String> stringListOf(JsonNode arr) {
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

    static long requireTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant ID not found in context");
        }
        return tid;
    }

    /** 当前语言（Go types.LanguageNameFromContext；dev 缺省 en-US）。 */
    static String currentLanguage() {
        return "en-US";
    }

    // ==================================================================
    // 轻量视图
    // ==================================================================

    /** resolveKnowledgeBases 的二元返回。 */
    public record KnowledgeResolution(List<String> kbIds, List<String> knowledgeIds) {}

    /** SearchTarget 的 service 面视图（对照 types.SearchTarget；管线面经 toPipeline 转换）。 */
    public static final class SearchTargetView {
        public String type = "";
        public String knowledgeBaseId = "";
        public long tenantId;
        public List<String> knowledgeIds = new ArrayList<>();
        public List<String> tagIds = new ArrayList<>();
        public List<String> scopeTagIds = new ArrayList<>();
        public boolean disableRecallThresholds;

        public List<com.ragagent.agent.tools.SearchTarget> toPipeline() {
            return toPipelineList();
        }

        public List<com.ragagent.agent.tools.SearchTarget> toPipelineList() {
            List<com.ragagent.agent.tools.SearchTarget> out = new ArrayList<>();
            out.add(asPipelineTarget());
            return out;
        }

        public com.ragagent.agent.tools.SearchTarget asPipelineTarget() {
            return new com.ragagent.agent.tools.SearchTarget(type, knowledgeBaseId, tenantId,
                    knowledgeIds, tagIds, scopeTagIds, disableRecallThresholds);
        }

        public static List<com.ragagent.agent.tools.SearchTarget> toPipeline(List<SearchTargetView> views) {
            List<com.ragagent.agent.tools.SearchTarget> out = new ArrayList<>();
            if (views == null) {
                return out;
            }
            for (SearchTargetView v : views) {
                out.add(v.asPipelineTarget());
            }
            return out;
        }
    }

    /** RetrievalConfig 的读取视图（GetEffective* 兜底，Go types.RetrievalConfig）。 */
    private record RetrievalConfigView(JsonNode raw) {
        String rerankModelId() { return raw == null ? null : raw.path("rerank_model_id").asText(null); }
        int embeddingTopK() { return effInt("embedding_top_k", 30); }
        double vectorThreshold() { return effDouble("vector_threshold", 0.2); }
        double keywordThreshold() { return effDouble("keyword_threshold", 0.3); }
        int rerankTopK() { return effInt("rerank_top_k", 30); }
        double rerankThreshold() { return effDouble("rerank_threshold", 0.3); }
        private int effInt(String f, int d) { return raw == null || raw.path(f).asInt(0) <= 0 ? d : raw.path(f).asInt(); }
        private double effDouble(String f, double d) {
            return raw == null || raw.path(f).asDouble(-1) < 0 ? d : raw.path(f).asDouble();
        }
    }
}
