package com.ragagent.session.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.EventManager;
import com.ragagent.chatpipeline.PipelineBuilder;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.chatpipeline.PipelineProgress;
import com.ragagent.chatpipeline.PipelineProgress.StageProgress;
import com.ragagent.chatpipeline.PluginError;
import com.ragagent.chatpipeline.ReferencesSupport;
import com.ragagent.chatpipeline.SummaryConfig;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.config.ConversationProperties;
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
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import com.ragagent.modelcontext.Registry;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.Session;

import static com.ragagent.session.service.QaSupport.TagScope;

/**
 * 知识问答 service 面（对照 Go internal/application/service/session_knowledge_qa.go
 * + session_qa_helpers.go；chat_pipeline 的调用方）。
 *
 * <p>已知差异（本批装配边界，均已备案）：检索执行面未翻译（hybridSearch adapter 空）、
 * web_fetch/网页抓取在 RAG 路径可用（websearch 执行面波 4.4 已有）。Langfuse span
 * 为 no-op seam（4.6a 备案），span 生命周期调用点保留。</p>
 */
@Service
public class SessionKnowledgeQaService {

    private static final Logger log = LoggerFactory.getLogger(SessionKnowledgeQaService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventManager eventManager;
    private final ConversationProperties cfg;
    private final ModelService modelService;
    private final KnowledgeService knowledgeService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final PipelinePorts.ModelService pipelineModelService;
    private final com.ragagent.auth.service.TenantService tenantService;
    private final com.ragagent.websearch.mapper.WebSearchProviderRepository webSearchProviderRepository;
    private final javax.sql.DataSource dataSource;
    /** 共享 KB 列表（对照 Go 的 kbShareService；ObjectProvider 装配避免跨域硬依赖）。 */
    private final org.springframework.beans.factory.ObjectProvider<
            com.ragagent.org.service.KbShareService> kbShareService;

    public SessionKnowledgeQaService(EventManager eventManager,
            ConversationProperties cfg,
            ModelService modelService,
            KnowledgeService knowledgeService,
            KnowledgeBaseService knowledgeBaseService,
            PipelinePorts.ModelService pipelineModelService,
            com.ragagent.auth.service.TenantService tenantService,
            com.ragagent.websearch.mapper.WebSearchProviderRepository webSearchProviderRepository,
            javax.sql.DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<
                    com.ragagent.org.service.KbShareService> kbShareService) {
        this.eventManager = eventManager;
        this.cfg = cfg;
        this.modelService = modelService;
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.pipelineModelService = pipelineModelService;
        this.tenantService = tenantService;
        this.webSearchProviderRepository = webSearchProviderRepository;
        this.dataSource = dataSource;
        this.kbShareService = kbShareService;
    }

    /**
     * 对照 Go knowledge.go L842-849 的 ListKnowledgeIDsByTagIDs（仓储 SQL：
     * repository/knowledge.go L1057-1073 —— DISTINCT knowledge id JOIN 关系表）。
     * 纯新增：知识域既有 service 不动。
     */
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
        KnowledgeResolution kb = resolveKnowledgeBases(req);

        // Resolve chat model ID using shared helper
        String chatModelId = resolveChatModelId(req, kb.kbIds, kb.knowledgeIds);

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
        long retrievalTenantId = resolveRetrievalTenantId(req);

        // Build unified search targets（computed once）
        List<SearchTargetView> searchTargets;
        try {
            searchTargets = buildSearchTargets(retrievalTenantId, kb.kbIds, kb.knowledgeIds, req.tagScopes);
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
        applyAgentOverridesToChatManage(req, chatManage);

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
                userContent += com.ragagent.chatpipeline.MessageAttachmentsPrompt.build(req.attachments);
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
                handleFallbackResponse(chatManage);
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
            searchTargets = buildSearchTargets(tenantId, knowledgeBaseIds, knowledgeIds, tagScopes);
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

    /** resolveKnowledgeBases（Go L21-60）。 */
    public KnowledgeResolution resolveKnowledgeBases(QaSupport.QaRequest req) {
        List<String> kbIds = new ArrayList<>(req.knowledgeBaseIds);
        List<String> knowledgeIds = new ArrayList<>(req.knowledgeIds);
        List<String> requestedKbIds = new ArrayList<>(req.knowledgeBaseIds);
        boolean hasExplicitMention = !kbIds.isEmpty() || !knowledgeIds.isEmpty() || !req.tagScopes.isEmpty();

        if (hasExplicitMention) {
            log.info("Using request-specified targets: kbs={}, docs={}", kbIds, knowledgeIds);
            // 共享 agent（agent 属于另一租户）：@mention 必须收敛到 agent 的允许范围，
            // 防止调用方注入范围外的 KB/知识 id（对照 Go L38-43）
            if (req.agentRow != null && req.session != null
                    && req.agentRow.getTenantId() != req.session.getTenantId()) {
                MentionScope scope = restrictMentionsToAgentScope(req.agentRow, req.agentConfig,
                        req.session.getTenantId(), kbIds, knowledgeIds);
                kbIds = scope.kbIds();
                knowledgeIds = scope.knowledgeIds();
                req.tagScopes = restrictTagScopesToAgentScope(req.agentRow, req.agentConfig,
                        req.session.getTenantId(), req.tagScopes);
            }
        } else if (req.agentConfig != null
                && req.agentConfig.path("retrieve_kb_only_when_mentioned").asBoolean(false)) {
            kbIds = new ArrayList<>();
            knowledgeIds = new ArrayList<>();
            log.info("RetrieveKBOnlyWhenMentioned is enabled and no @ mention found, "
                    + "KB retrieval disabled for this request");
        } else if (req.agentConfig != null) {
            kbIds = resolveKnowledgeBasesFromAgent(req.agentRow, req.agentConfig,
                    req.session.getTenantId());
        }

        // API-Key KB 白名单（Go AuthorizeTenantAPIKeyKnowledgeTargets + Filter*）。
        // 拒绝形态是 BizException（波 1 通道）。
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeTargets(requestedKbIds, req.knowledgeIds);
        kbIds = com.ragagent.apikey.domain.TenantAPIKeyScope.filterKnowledgeBases(requestedKbIds, kbIds);
        return new KnowledgeResolution(kbIds, knowledgeIds);
    }

    /** @mention 收敛结果（对照 Go 的两个多返回值 helper）。 */
    public record MentionScope(List<String> kbIds, List<String> knowledgeIds) {}

    /**
     * 对照 Go {@code restrictMentionsToAgentScope}（session_qa_helpers.go L295-340）：
     * 把 @mention 的 KB/知识收窄到共享 agent 的允许范围——允许集为空则**全部拦下**；
     * 知识按其所属 KB 是否在允许集内判定（批量取按 **agent 的租户**查）。
     */
    public MentionScope restrictMentionsToAgentScope(
            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg,
            long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {
        List<String> allowed = resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);
        if (allowed.isEmpty()) {
            log.warn("Shared agent has no allowed KBs, blocking all @mentions");
            return new MentionScope(new ArrayList<>(), new ArrayList<>());
        }
        Set<String> allowedSet = new HashSet<>(allowed);

        List<String> filteredKbs = new ArrayList<>();
        for (String id : kbIds) {
            if (allowedSet.contains(id)) {
                filteredKbs.add(id);
            } else {
                log.warn("Blocking @mentioned KB {}: not in shared agent's allowed scope", id);
            }
        }

        List<String> filteredKnowledge = knowledgeIds;
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            List<Knowledge> rows;
            try {
                rows = knowledgeService.getKnowledgeBatch(agent.getTenantId(), knowledgeIds);
            } catch (RuntimeException e) {
                log.warn("Failed to validate knowledge IDs against agent scope: {}, blocking all",
                        e.toString());
                rows = null;
            }
            filteredKnowledge = new ArrayList<>();
            if (rows != null) {
                for (Knowledge k : rows) {
                    if (k != null && allowedSet.contains(k.getKnowledgeBaseId())) {
                        filteredKnowledge.add(k.getId());
                    } else if (k != null) {
                        log.warn("Blocking @mentioned knowledge {} (KB {}): not in shared agent's allowed scope",
                                k.getId(), k.getKnowledgeBaseId());
                    }
                }
            }
        }
        return new MentionScope(filteredKbs, filteredKnowledge);
    }

    /**
     * 对照 Go {@code restrictTagScopesToAgentScope}（session_qa_helpers.go L62-86）：
     * 按允许 KB 集过滤 tag 范围；空输入返回空列表（Go 返回 nil）。
     */
    public List<QaSupport.TagScope> restrictTagScopesToAgentScope(
            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg,
            long sessionTenantId, List<QaSupport.TagScope> tagScopes) {
        if (tagScopes == null || tagScopes.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> allowed = resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);
        Set<String> allowedSet = new HashSet<>(allowed);
        List<QaSupport.TagScope> filtered = new ArrayList<>();
        for (QaSupport.TagScope scope : tagScopes) {
            if (allowedSet.contains(scope.knowledgeBaseId)) {
                filtered.add(scope);
            } else {
                log.warn("Blocking @mentioned tag scope for KB {}: not in shared agent's allowed scope",
                        scope.knowledgeBaseId);
            }
        }
        return filtered;
    }

    /**
     * resolveKnowledgeBasesFromAgent（Go L335-433）：能力过滤 + "all" 模式下**非共享
     * agent** 才并入调用方可见的共享 KB（D 批已接线；共享 agent 时显式跳过并入）。
     */
    public List<String> resolveKnowledgeBasesFromAgent(
            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId) {
        if (agentCfg == null) {
            return new ArrayList<>();
        }
        String mode = agentCfg.path("kb_selection_mode").asText("");
        switch (mode) {
            case "all" -> {
                // 能力过滤（DeriveKBFilterForAgent）：取 tool 能力面判定，非 wiki/rerank 工具
                // 只要求 vector/keyword。
                List<KnowledgeBase> allKbs = knowledgeBaseService.listKnowledgeBases(null);
                List<String> kbIds = new ArrayList<>();
                Set<String> kbIdSet = new LinkedHashSet<>();
                int ownSkipped = 0;
                for (KnowledgeBase kb : allKbs) {
                    if (kbSatisfiesAgentRequirements(kb, agentCfg)) {
                        kbIds.add(kb.getId());
                        kbIdSet.add(kb.getId());
                    } else {
                        ownSkipped++;
                    }
                }

                // 对照 Go L377-410：**非**共享 agent 才并入调用方可见的共享 KB——
                // 共享 agent（会话租户 ≠ agent 租户）并入会把其它组织的 KB 泄漏进检索范围
                boolean isSharedAgent = sessionTenantId != 0 && sessionTenantId != agent.getTenantId();
                int sharedSkipped = 0;
                com.ragagent.org.service.KbShareService shareService = kbShareService.getIfAvailable();
                String callerUserId = com.ragagent.common.context.TenantContext.currentUserId();
                if (!isSharedAgent && shareService != null
                        && callerUserId != null && !callerUserId.isEmpty()) {
                    Long callerTenant = com.ragagent.common.context.TenantContext.currentTenantId();
                    try {
                        List<com.ragagent.org.service.KbShareService.SharedKbInfo> shared =
                                shareService.listSharedKnowledgeBases(
                                        callerTenant == null ? 0 : callerTenant,
                                        com.ragagent.org.service.OrganizationService.callerTenantRole());
                        for (com.ragagent.org.service.KbShareService.SharedKbInfo info : shared) {
                            if (info == null || info.knowledgeBase() == null
                                    || kbIdSet.contains(info.knowledgeBase().getId())) {
                                continue;
                            }
                            if (!kbSatisfiesAgentRequirements(info.knowledgeBase(), agentCfg)) {
                                sharedSkipped++;
                                continue;
                            }
                            kbIds.add(info.knowledgeBase().getId());
                            kbIdSet.add(info.knowledgeBase().getId());
                        }
                    } catch (RuntimeException e) {
                        log.warn("Failed to list shared knowledge bases: {}", e.toString());
                    }
                } else if (isSharedAgent) {
                    log.info("Shared agent detected (session tenant {} != agent tenant {}): "
                            + "skipping user's shared KBs", sessionTenantId, agent.getTenantId());
                }
                if (ownSkipped + sharedSkipped > 0) {
                    log.info("KBSelectionMode=all: tool-capability filter removed {} own + {} shared KBs",
                            ownSkipped, sharedSkipped);
                }
                log.info("KBSelectionMode=all: loaded {} knowledge bases (own + shared)", kbIds.size());
                return kbIds;
            }
            case "selected" -> {
                List<String> configured = stringListOf(agentCfg.get("knowledge_bases"));
                log.info("KBSelectionMode=selected: using {} configured knowledge bases", configured.size());
                return configured;
            }
            case "none" -> {
                log.info("KBSelectionMode=none: no knowledge bases configured");
                return new ArrayList<>();
            }
            default -> {
                List<String> configured = stringListOf(agentCfg.get("knowledge_bases"));
                if (!configured.isEmpty()) {
                    log.info("KBSelectionMode not set: using {} configured knowledge bases", configured.size());
                }
                return configured;
            }
        }
    }

    private static boolean kbSatisfiesAgentRequirements(KnowledgeBase kb, ObjectNode agentCfg) {
        if (kb == null) {
            return false;
        }
        var st = kb.getIndexingStrategy();
        return st.isVectorEnabled() || st.isKeywordEnabled() || st.isWikiEnabled();
    }

    /** resolveChatModelID（Go session_qa_helpers.go L97-141）。 */
    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds,
            List<String> knowledgeIds) {
        String summaryModelId = req.summaryModelId == null ? "" : req.summaryModelId.trim();
        String configuredAgentModelId = "";
        if (req.agentConfig != null) {
            configuredAgentModelId = req.agentConfig.path("model_id").asText("").trim();
            if (configuredAgentModelId.isEmpty()
                    && !com.ragagent.agent.AgentConfig.BUILTIN_SKILL_INSTALLER_ID.equals(req.agentRow.getId())
                    && !"builtin-wiki-fixer".equals(req.agentRow.getId())) {
                throw new RuntimeException("chat model is not configured: please set model_id on agent "
                        + req.agentRow.getId());
            }
            if (!configuredAgentModelId.isEmpty()) {
                Model model = findModel(configuredAgentModelId);
                if (model == null || !"KnowledgeQA".equals(model.getType())) {
                    throw new RuntimeException("configured chat model " + configuredAgentModelId
                            + " is unavailable for agent " + req.agentRow.getId());
                }
            }
        }

        if (!summaryModelId.isEmpty()) {
            Model model = findModel(summaryModelId);
            if (model != null && "KnowledgeQA".equals(model.getType())) {
                log.info("Using request's summary model override: {}", summaryModelId);
                return summaryModelId;
            }
            log.warn("Request provided invalid summary model ID {}, falling back", summaryModelId);
        }
        if (!configuredAgentModelId.isEmpty()) {
            log.info("Using custom agent's model_id: {}", configuredAgentModelId);
            return configuredAgentModelId;
        }
        return selectChatModelId(req.session, knowledgeBaseIds, knowledgeIds);
    }

    private Model findModel(String id) {
        try {
            return modelService.getModelByID(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** selectChatModelID（Go L246-323）。 */
    private String selectChatModelId(Session session, List<String> knowledgeBaseIds, List<String> knowledgeIds) {
        List<String> kbIds = new ArrayList<>(knowledgeBaseIds);
        if (kbIds.isEmpty() && !knowledgeIds.isEmpty()) {
            long tenantId = requireTenantId();
            try {
                List<Knowledge> knowledgeList = knowledgeService.getKnowledgeBatchWithSharedAccess(
                        tenantId, knowledgeIds);
                Set<String> kbIdSet = new LinkedHashSet<>();
                for (Knowledge k : knowledgeList) {
                    if (k != null && k.getKnowledgeBaseId() != null && !k.getKnowledgeBaseId().isEmpty()) {
                        kbIdSet.add(k.getKnowledgeBaseId());
                    }
                }
                kbIds.addAll(kbIdSet);
                log.info("Derived {} knowledge base IDs from {} knowledge IDs for model selection",
                        kbIds.size(), knowledgeIds.size());
            } catch (RuntimeException e) {
                log.warn("Failed to get knowledge batch for model selection: {}", e.toString());
            }
        }
        if (!kbIds.isEmpty()) {
            for (String kbId : kbIds) {
                KnowledgeBase kb = findKb(kbId);
                if (kb != null && kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty()) {
                    Model model = findModel(kb.getSummaryModelId());
                    if (model != null && "remote".equals(model.getSource())) {
                        log.info("Using Remote summary model from knowledge base");
                        return kb.getSummaryModelId();
                    }
                }
            }
            KnowledgeBase kb = findKb(kbIds.get(0));
            if (kb == null) {
                throw new RuntimeException("failed to get knowledge base " + kbIds.get(0) + ": record not found");
            }
            if (kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty()) {
                log.info("Using summary model from first knowledge base {}: {}", kbIds.get(0), kb.getSummaryModelId());
                return kb.getSummaryModelId();
            }
        }

        List<Model> models = modelService.listModels();
        for (Model model : models) {
            if (model != null && "KnowledgeQA".equals(model.getType())) {
                log.info("Using first available KnowledgeQA model: {}", model.getId());
                return model.getId();
            }
        }
        throw new RuntimeException("no chat model ID available: no knowledge bases configured and no available models");
    }

    /** 包内装配面的 KB 只读查询（GetKnowledgeBaseByIDOnly）。 */
    public KnowledgeBase findKnowledgeBase(String kbId) {
        return findKb(kbId);
    }

    private KnowledgeBase findKb(String kbId) {
        try {
            return knowledgeBaseService.getAllTenantById(kbId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** resolveRetrievalTenantID（Go L145-163）。 */
    public long resolveRetrievalTenantId(QaSupport.QaRequest req) {
        long retrievalTenantId = req.session.getTenantId();
        if (req.agentRow != null && req.agentRow.getTenantId() != null && req.agentRow.getTenantId() != 0) {
            retrievalTenantId = req.agentRow.getTenantId();
            log.info("Using agent tenant {} for retrieval scope", retrievalTenantId);
        } else {
            Long ctxTenant = TenantContext.currentTenantId();
            if (ctxTenant != null && ctxTenant != 0) {
                retrievalTenantId = ctxTenant;
            }
        }
        return retrievalTenantId;
    }

    /** buildSearchTargets（Go L441-615）。 */
    public List<SearchTargetView> buildSearchTargets(long tenantId, List<String> knowledgeBaseIds,
            List<String> knowledgeIds, List<TagScope> tagScopes) {
        List<SearchTargetView> targets = new ArrayList<>();
        Map<String, List<String>> tagIdsByKb = mergeTagScopesByKb(tagScopes);

        Map<String, Long> kbTenantMap = new LinkedHashMap<>();
        Set<String> fullKbSet = new LinkedHashSet<>();

        List<String> kbIdsToFetch = new ArrayList<>(knowledgeBaseIds);
        kbIdsToFetch.addAll(tagIdsByKb.keySet());
        kbIdsToFetch = uniqueNonEmptyStrings(kbIdsToFetch);

        Map<String, KnowledgeBase> kbById = new LinkedHashMap<>();
        if (!kbIdsToFetch.isEmpty()) {
            List<KnowledgeBase> kbs = new ArrayList<>();
            for (String id : kbIdsToFetch) {
                KnowledgeBase kb = knowledgeBaseService.getAllTenantById(id);
                if (kb != null) {
                    kbs.add(kb);
                }
            }
            for (KnowledgeBase kb : kbs) {
                if (kb != null) {
                    kbById.put(kb.getId(), kb);
                }
            }
        }
        // resolveKBTenant：直接共享 KB 的租户解析依赖 org 共享读面（波 3 已有 KB share；
        // Go 的 permissions.Check 对非共享 KB 落 caller tenant）。Java 等价：KB 行存在
        // 即归 KB 自己的租户（跨租户 KB 的访问在 parse 面由上层可见性拒绝）。
        record Resolved(long tenant) {}
        java.util.function.Function<String, Long> resolveKbTenant = kbId -> {
            Long cached = kbTenantMap.get(kbId);
            if (cached != null && cached != 0) {
                return cached;
            }
            KnowledgeBase kb = kbById.get(kbId);
            if (kb == null) {
                // Go resolveKBTenant：kb 元数据缺失时租户回落 caller（未知 KB 仍成 target，
                // 检索插件内报 1003 → 500 信封；A/B 场景 kse-unknown-kb 依赖这一形态）
                kbTenantMap.put(kbId, tenantId);
                return tenantId;
            }
            kbTenantMap.put(kbId, kb.getTenantId());
            return kb.getTenantId();
        };

        for (String kbId : knowledgeBaseIds) {
            fullKbSet.add(kbId);
            long kbTenant = resolveKbTenant.apply(kbId);
            if (kbTenant == 0) {
                continue;
            }
            if (tagIdsByKb.get(kbId) != null && !tagIdsByKb.get(kbId).isEmpty()) {
                continue;
            }
            SearchTargetView t = new SearchTargetView();
            t.type = "knowledge_base";
            t.knowledgeBaseId = kbId;
            t.tenantId = kbTenant;
            targets.add(t);
        }

        Map<String, List<String>> kbToKnowledgeIds = new LinkedHashMap<>();
        if (!knowledgeIds.isEmpty()) {
            List<Knowledge> knowledgeList;
            try {
                knowledgeList = knowledgeService.getKnowledgeBatchWithSharedAccess(tenantId, knowledgeIds);
            } catch (RuntimeException e) {
                log.warn("Failed to get knowledge batch for search targets: {}", e.toString());
                return targets; // Return what we have, don't fail
            }
            for (Knowledge k : knowledgeList) {
                if (k == null || k.getKnowledgeBaseId() == null || k.getKnowledgeBaseId().isEmpty()) {
                    continue;
                }
                if (!kbTenantMap.containsKey(k.getKnowledgeBaseId()) || kbTenantMap.get(k.getKnowledgeBaseId()) == 0) {
                    kbTenantMap.put(k.getKnowledgeBaseId(), k.getTenantId());
                }
                if (fullKbSet.contains(k.getKnowledgeBaseId())
                        && (tagIdsByKb.get(k.getKnowledgeBaseId()) == null
                                || tagIdsByKb.get(k.getKnowledgeBaseId()).isEmpty())) {
                    continue;
                }
                kbToKnowledgeIds.computeIfAbsent(k.getKnowledgeBaseId(), x -> new ArrayList<>()).add(k.getId());
            }
            for (Map.Entry<String, List<String>> e : kbToKnowledgeIds.entrySet()) {
                String kbId = e.getKey();
                if (tagIdsByKb.get(kbId) != null && !tagIdsByKb.get(kbId).isEmpty()) {
                    continue;
                }
                Long kbTenantBoxed = kbTenantMap.get(kbId);
                long kbTenant = kbTenantBoxed == null || kbTenantBoxed == 0 ? tenantId : kbTenantBoxed;
                SearchTargetView t = new SearchTargetView();
                t.type = "knowledge";
                t.knowledgeBaseId = kbId;
                t.tenantId = kbTenant;
                t.knowledgeIds = e.getValue();
                t.disableRecallThresholds = true;
                targets.add(t);
            }
        }

        for (Map.Entry<String, List<String>> e : tagIdsByKb.entrySet()) {
            String kbId = e.getKey();
            List<String> tagIds = e.getValue();
            if (kbId.isEmpty() || tagIds.isEmpty()) {
                continue;
            }
            long kbTenant = resolveKbTenant.apply(kbId);
            if (kbTenant == 0) {
                continue;
            }
            KnowledgeBase kb = kbById.get(kbId);
            List<String> explicitKnowledgeIds = uniqueNonEmptyStrings(
                    kbToKnowledgeIds.getOrDefault(kbId, new ArrayList<>()));

            boolean useDocumentTagResolution = kb == null || !"faq".equals(kb.getType());
            if (kb == null) {
                log.warn("Knowledge base metadata missing for tag scope, kb_id={}, using document tag resolution", kbId);
            }
            if (useDocumentTagResolution) {
                List<String> tagKnowledgeIds;
                tagKnowledgeIds = listKnowledgeIdsByTagIds(kbTenant, kbId, tagIds);
                if (!explicitKnowledgeIds.isEmpty()) {
                    tagKnowledgeIds = intersectStrings(tagKnowledgeIds, explicitKnowledgeIds);
                }
                tagKnowledgeIds = uniqueNonEmptyStrings(tagKnowledgeIds);
                if (tagKnowledgeIds.isEmpty()) {
                    continue;
                }
                SearchTargetView t = new SearchTargetView();
                t.type = "knowledge";
                t.knowledgeBaseId = kbId;
                t.tenantId = kbTenant;
                t.knowledgeIds = tagKnowledgeIds;
                t.scopeTagIds = new ArrayList<>(tagIds);
                t.disableRecallThresholds = true;
                targets.add(t);
                continue;
            }

            SearchTargetView t = new SearchTargetView();
            t.type = "knowledge_base";
            t.knowledgeBaseId = kbId;
            t.tenantId = kbTenant;
            t.tagIds = new ArrayList<>(tagIds);
            t.scopeTagIds = new ArrayList<>(tagIds);
            t.disableRecallThresholds = true;
            if (!explicitKnowledgeIds.isEmpty()) {
                t.type = "knowledge";
                t.knowledgeIds = explicitKnowledgeIds;
                t.disableRecallThresholds = true;
            }
            targets.add(t);
        }

        log.info("Built {} search targets: {} full KB, {} partial/tag KB, kbTenantMap={}",
                targets.size(), knowledgeBaseIds.size(), targets.size() - knowledgeBaseIds.size(), kbTenantMap);
        return targets;
    }

    /** applyAgentOverridesToChatManage（Go session_qa_helpers.go L170-289）。 */
    private void applyAgentOverridesToChatManage(QaSupport.QaRequest req, ChatManage cm) {
        if (req.agentConfig == null) {
            return;
        }
        ObjectNode c = AgentConfigJson.ensureDefaults(req.agentConfig);
        Prompts prompts = resolveCustomAgentPrompts(req.agentRow, c);
        if (!prompts.system.isEmpty()) {
            cm.getSummaryConfig().setPrompt(prompts.system);
            log.info("Using custom agent's system_prompt");
        }
        if (!prompts.context.isEmpty()) {
            cm.getSummaryConfig().setContextTemplate(prompts.context);
            log.info("Using custom agent's context_template");
        }
        double temperature = c.path("temperature").asDouble(-1);
        if (temperature >= 0) {
            cm.getSummaryConfig().setTemperature(temperature);
        }
        int maxCompletionTokens = c.path("max_completion_tokens").asInt(0);
        if (maxCompletionTokens > 0) {
            cm.getSummaryConfig().setMaxCompletionTokens(maxCompletionTokens);
        }
        JsonNode thinking = c.get("thinking");
        cm.getSummaryConfig().setThinking(thinking != null && thinking.isBoolean() ? thinking.asBoolean() : null);
        cm.setCitationEnabled(c.path("citation_enabled").asBoolean(true));

        int embeddingTopK = c.path("embedding_top_k").asInt(0);
        if (embeddingTopK > 0) {
            cm.setEmbeddingTopK(embeddingTopK);
        }
        double keywordThreshold = c.path("keyword_threshold").asDouble(0);
        if (keywordThreshold > 0) {
            cm.setKeywordThreshold(keywordThreshold);
        }
        double vectorThreshold = c.path("vector_threshold").asDouble(0);
        if (vectorThreshold > 0) {
            cm.setVectorThreshold(vectorThreshold);
        }
        int rerankTopK = c.path("rerank_top_k").asInt(0);
        if (rerankTopK > 0) {
            cm.setRerankTopK(rerankTopK);
        }
        cm.setRerankThreshold(c.path("rerank_threshold").asDouble(0));
        String rerankModelId = c.path("rerank_model_id").asText("");
        if (!rerankModelId.isEmpty()) {
            cm.setRerankModelId(rerankModelId);
        }

        cm.setEnableRewrite(c.path("enable_rewrite").asBoolean(false));
        cm.setEnableQueryExpansion(c.path("enable_query_expansion").asBoolean(false));
        String rwSys = c.path("rewrite_prompt_system").asText("");
        if (!rwSys.isEmpty()) {
            cm.setRewritePromptSystem(rwSys);
        }
        String rwUser = c.path("rewrite_prompt_user").asText("");
        if (!rwUser.isEmpty()) {
            cm.setRewritePromptUser(rwUser);
        }
        String quModel = c.path("query_understand_model_id").asText("");
        if (!quModel.isEmpty()) {
            cm.setQueryUnderstandModelId(quModel);
        }

        String fallbackStrategy = c.path("fallback_strategy").asText("");
        if (!fallbackStrategy.isEmpty()) {
            cm.setFallbackStrategy(fallbackStrategy);
        }
        String fallbackResponse = c.path("fallback_response").asText("");
        if (!fallbackResponse.isEmpty()) {
            cm.setFallbackResponse(fallbackResponse);
        }
        String fallbackPrompt = c.path("fallback_prompt").asText("");
        if (!fallbackPrompt.isEmpty()) {
            cm.setFallbackPrompt(fallbackPrompt);
        }

        int webSearchMaxResults = c.path("web_search_max_results").asInt(0);
        if (webSearchMaxResults > 0) {
            cm.setWebSearchMaxResults(webSearchMaxResults);
        }

        int historyTurns = c.path("history_turns").asInt(0);
        if (historyTurns > 0) {
            cm.setMaxRounds(historyTurns);
            log.info("Using custom agent's history_turns: {}", cm.getMaxRounds());
        }
        if (!c.path("multi_turn_enabled").asBoolean(true)) {
            cm.setMaxRounds(0);
            log.info("Multi-turn disabled by custom agent, clearing history");
        }

        cm.setFaqPriorityEnabled(c.path("faq_priority_enabled").asBoolean(false));
        cm.setFaqDirectAnswerThreshold(c.path("faq_direct_answer_threshold").asDouble(0.0));
        cm.setFaqScoreBoost(c.path("faq_score_boost").asDouble(0.0));

        cm.setDataAnalysisEnabled(c.path("data_analysis_enabled").asBoolean(false));

        JsonNode intentPrompts = c.get("intent_prompts");
        if (intentPrompts != null && intentPrompts.isObject() && intentPrompts.size() > 0) {
            Map<String, String> overrides = new LinkedHashMap<>();
            intentPrompts.fields().forEachRemaining(e -> overrides.put(e.getKey(), e.getValue().asText("")));
            cm.setIntentPromptOverrides(overrides);
        }
    }

    /** ResolveCustomAgentPrompts（config/agent_prompts.go L10-33）。 */
    private record Prompts(String system, String context) {}

    private Prompts resolveCustomAgentPrompts(
            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode c) {
        if (c == null) {
            return new Prompts("", "");
        }
        String system = c.path("system_prompt").asText("");
        String context = c.path("context_template").asText("");
        boolean agentMode = isAgentMode(c);
        String systemId = c.path("system_prompt_id").asText("");
        if (system.isEmpty() && !systemId.isEmpty()) {
            String content = templateContentByIdAndFile(systemId,
                    agentMode ? "agent_system_prompt.yaml" : "system_prompt.yaml");
            if (content != null) {
                system = content;
            }
        }
        String contextId = c.path("context_template_id").asText("");
        if (context.isEmpty() && !contextId.isEmpty()) {
            String content = templateContentByIdAndFile(contextId, "context_template.yaml");
            if (content != null) {
                context = content;
            }
        }
        return new Prompts(system, context);
    }

    /**
     * 对照 Go {@code types.CustomAgent.IsAgentMode}（internal/types/custom_agent.go
     * L553-556：{@code Config.AgentMode == AgentModeSmartReasoning}）。
     *
     * <p>⚠️ 2026-09-23 修复：原实现误写成 {@code == "agent"}（Go 侧无此取值），
     * 导致所有真实 agent（前端/内置/IM 一律写 {@code smart-reasoning}）在
     * agent-chat 被误判进 RAG 快答分支——实弹 2×2 对拍证据见
     * known-issues/06-wave-5.md 尾部。</p>
     */
    public static boolean isAgentMode(ObjectNode c) {
        return "smart-reasoning".equals(c.path("agent_mode").asText(""));
    }

    private static String templateContentByIdAndFile(String id, String file) {
        try (java.io.InputStream in = SessionKnowledgeQaService.class.getClassLoader()
                .getResourceAsStream("agentm/prompt_templates/" + file)) {
            if (in == null) {
                return null;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            JsonNode root = JSON.valueToTree(raw);
            JsonNode list = root.get("templates");
            if (list == null || !list.isArray()) {
                return null;
            }
            for (JsonNode t : list) {
                if (id.equals(t.path("id").asText(""))) {
                    return t.path("content").asText("");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // fallback（Go L929-1196）
    // ==================================================================

    private void handleFallbackResponse(ChatManage chatManage) {
        if ("model".equals(chatManage.getFallbackStrategy())) {
            handleModelFallback(chatManage);
        } else {
            handleFixedFallback(chatManage);
        }
    }

    private void handleFixedFallback(ChatManage chatManage) {
        String fallbackContent = chatManage.getFallbackResponse();
        com.ragagent.llm.domain.ChatResponse response = new com.ragagent.llm.domain.ChatResponse();
        response.setContent(fallbackContent);
        chatManage.setChatResponse(response);
        emitFallbackAnswer(chatManage, fallbackContent);
    }

    private void handleModelFallback(ChatManage chatManage) {
        if (chatManage.getFallbackPrompt().isEmpty()) {
            log.warn("Fallback strategy is 'model' but FallbackPrompt is empty, falling back to fixed response");
            handleFixedFallback(chatManage);
            return;
        }
        String promptContent = renderFallbackPrompt(chatManage);

        EventBusInterface eventBus = chatManage.getEventBus();
        if (eventBus == null) {
            log.warn("EventBus not available for streaming fallback, falling back to fixed response");
            handleFixedFallback(chatManage);
            return;
        }

        LlmChatClient chatModel;
        try {
            chatModel = pipelineModelService.getChatModel(chatManage.getChatModelId());
        } catch (RuntimeException e) {
            log.error("Failed to get chat model for fallback: {}, falling back to fixed response", e.toString());
            handleFixedFallback(chatManage);
            return;
        }

        ChatOptions opt = new ChatOptions();
        opt.setTemperature(chatManage.getSummaryConfig().getTemperature());
        opt.setMaxCompletionTokens(chatManage.getSummaryConfig().getMaxCompletionTokens());
        opt.setThinking(Boolean.FALSE);

        var prepared = prepareFallbackMessages(chatManage, promptContent);
        java.util.concurrent.BlockingQueue<StreamResponse> responseQueue;
        try {
            responseQueue = chatModel.chatStream(prepared.messages(), opt);
        } catch (RuntimeException e) {
            log.error("Failed to start streaming fallback response: {}, falling back to fixed response", e.toString());
            handleFixedFallback(chatManage);
            return;
        }
        if (responseQueue == null) {
            log.error("Chat stream returned nil channel, falling back to fixed response");
            handleFixedFallback(chatManage);
            return;
        }
        Thread.ofVirtual().start(() -> consumeFallbackStream(chatManage, responseQueue, prepared.registry()));
    }

    private record FallbackPrepared(List<ChatMessage> messages, Registry registry) {}

    /** prepareFallbackMessages + buildFallbackMessages（Go L1004-1052）。 */
    private FallbackPrepared prepareFallbackMessages(ChatManage chatManage, String promptContent) {
        List<ChatMessage> messages = new ArrayList<>();
        if (!promptContent.trim().isEmpty()) {
            ChatMessage system = new ChatMessage();
            system.setRole("system");
            system.setContent(promptContent + "\n\n" + com.ragagent.agent.PromptInstructions.SOURCE_DATA_BOUNDARY_PROMPT
                    + "\n\n" + com.ragagent.agent.PromptInstructions.SOURCED_ANSWER_OUTPUT_PROMPT);
            messages.add(system);
        }
        com.ragagent.chatpipeline.PipelineCommon.appendHistoryMessages(messages, chatManage.getHistory());

        String query = chatManage.getQuery();
        String rq = chatManage.getRewriteQuery() == null ? "" : chatManage.getRewriteQuery().trim();
        if (!rq.isEmpty()) {
            query = rq;
        }
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(query);
        if (chatManage.isChatModelSupportsVision() && chatManage.getImages() != null) {
            userMsg.setImages(chatManage.getImages());
        }
        messages.add(userMsg);

        boolean citationsEnabled = chatManage.citationsEnabled();
        Registry registry = new Registry(citationsEnabled);
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            ChatMessage first = messages.get(0);
            first.setContent(trailTrim(first.getContent()) + registry.protocolPrompt());
        } else {
            ChatMessage system = new ChatMessage();
            system.setRole("system");
            system.setContent(registry.protocolPrompt().trim());
            messages.add(0, system);
        }
        return new FallbackPrepared(registry.encodeMessages(messages), registry);
    }

    private static String trailTrim(String s) {
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
    private String renderFallbackPrompt(ChatManage chatManage) {
        String query = chatManage.getQuery();
        String rq = chatManage.getRewriteQuery() == null ? "" : chatManage.getRewriteQuery().trim();
        if (!rq.isEmpty()) {
            query = rq;
        }
        String kbDocuments = buildKbDocumentListing(chatManage);
        String result = com.ragagent.agent.AgentPromptPlaceholders.renderPromptPlaceholders(chatManage.getFallbackPrompt(), Map.of(
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
    private void consumeFallbackStream(ChatManage chatManage,
            java.util.concurrent.BlockingQueue<StreamResponse> responseChan, Registry modelContext) {
        String fallbackId = com.ragagent.event.EventIds.generateEventID("fallback");
        EventBusInterface eventBus = chatManage.getEventBus();
        StringBuilder finalContent = new StringBuilder();
        boolean streamCompleted = false;
        var decoder = modelContext.streamDecoder();

        while (true) {
            StreamResponse response;
            try {
                response = responseChan.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (response == null) {
                continue; // Java 无 channel 关闭：done 收束约定由生产者保证
            }
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
    private void emitFallbackAnswer(ChatManage chatManage, String content) {
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

    private static long requireTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant ID not found in context");
        }
        return tid;
    }

    /** 当前语言（Go types.LanguageNameFromContext；dev 缺省 en-US）。 */
    private static String currentLanguage() {
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
