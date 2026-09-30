package com.ragagent.session.controller;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.event.Event;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.AgentThoughtData;
import com.ragagent.event.payload.AgentToolCallData;
import com.ragagent.event.payload.AgentToolResultData;
import com.ragagent.event.payload.ErrorData;
import com.ragagent.event.payload.AgentFinalAnswerData;
import com.ragagent.event.payload.AgentCompleteData;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.dto.QaRequests.SearchKnowledgeRequest;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.MessageSuggestionService;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.QaSupport.SseStreamContext;
import com.ragagent.session.service.SessionAgentQaService;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.TemporaryDocumentService;
import com.ragagent.session.sse.StreamEventEmitter;
import com.ragagent.stream.StreamManager;

import jakarta.servlet.http.HttpServletResponse;


/**
 * chat 三入口（对照 Go internal/handler/session/qa.go 全文 + stream.go 的
 * handleAgentEventsForSSE + quick_answer_timeline.go + helpers.go 的 handler 侧）。
 *
 * <h2>三个端点</h2>
 * <ul>
 *   <li>POST /api/v1/knowledge-chat/{session_id} — KnowledgeQA（RAG/纯聊天管线）</li>
 *   <li>POST /api/v1/agent-chat/{session_id} — AgentQA（agent 引擎）</li>
 *   <li>POST /api/v1/knowledge-search — SearchKnowledge（无 LLM 总结检索）</li>
 * </ul>
 *
 * <h2>关键时序（Go 逐条对照，SSE 时序最高危）</h2>
 * <ol>
 *   <li>agent 模式先 rejectIfOtherAgentRunLive（409）→ Emit(agent.query)；</li>
 *   <li>persistTurnMessages 先建 user/assistant 行；</li>
 *   <li>setupSSEStream：SetLiveRun 在 SSE 头<b>之前</b>（409/503 必须还能改状态码）；
 *       agent_query 事件写流；stop 处理器 + 独立 stop watcher + AgentStreamBridge 订阅；</li>
 *   <li>异步执行 QA 服务（虚拟线程），主线程 handleAgentEventsForSSE 100ms 轮询
 *       StreamManager 推帧，complete 后补 completion 事件；</li>
 *   <li>agent 模式 defer：completeAssistantMessage → follow-up 交接 → ClearLiveRun。</li>
 * </ol>
 *
 * <h2>已备案差异</h2>
 * <ul>
 *   <li>共享 agent 解析（W5α2 已收口）：resolveAgent 共享优先、source==0 才回落 own；
 *       GetSharedAgentForTenant 的 ApplyBuiltinAgentLocalization 只覆盖
 *       name/description/avatar（QA 消费 config/tenant，不进字节契约），随 agentm 装配层
 *       统一补齐；access.WithSharedAgent 的 KB grant 机制（检索授权收窄）随检索面
 *       专项收口——Java 检索租户已取 agentRow.tenantId（等价执行范围）。</li>
 *   <li>图片上传/附件的存储写入与 VLM 分析：saveImageAttachments 的对象存储写入
 *       在 dev（本地盘）与 Go 行为一致；VLM 分析 emit-only 形态保留。</li>
 *   <li>临时附件的 ResolveForPrompt 内容选择（等待/跳过的事件形态保留，内容解析
 *       seam 随附件管线收口）。</li>
 * </ul>
 */
@RestController
public class KnowledgeQaController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaController.class);

    private final SessionService sessionService;

    /** SSE 编排簇（§14.9c 刀 5）。 */
    private final QaSseOrchestrator sseOrchestrator;

    /** 收尾簇（§14.9c 刀 7）。 */
    private final QaTurnFinalizer turnFinalizer;

    /** 请求解析主体（§14.9c 刀 4b）。 */
    private final QaRequestParser qaRequestParser;
    private final MessageService messageService;
    private final StreamManager streamManager;
    private final SessionKnowledgeQaService knowledgeQaService;
    private final SessionAgentQaService agentQaService;
    private final MessageSuggestionService suggestionService;
    private final TemporaryDocumentService temporaryDocuments;
    private final com.ragagent.session.service.SteerRunCoordinator steerCoordinator;
    private final StreamEventEmitter emitter;
    private final com.ragagent.session.sse.SseFrameWriter sseFrameWriter;
    private final com.ragagent.storage.support.FileService fileService;
    private final com.ragagent.storage.support.StorageBackendResolver storageBackendResolver;
    private final com.ragagent.memory.service.MemoryExtractionService memoryExtraction;

    public KnowledgeQaController(SessionService sessionService,
            MessageService messageService,
            StreamManager streamManager,
            SessionKnowledgeQaService knowledgeQaService,
            SessionAgentQaService agentQaService,
            com.ragagent.session.service.MessageSuggestionService suggestionService,
            TemporaryDocumentService temporaryDocuments,
            com.ragagent.session.service.SteerRunCoordinator steerCoordinator,
            StreamEventEmitter emitter,
            com.ragagent.session.sse.SseFrameWriter sseFrameWriter,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.storage.support.FileService> fileService,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.storage.support.StorageBackendResolver> storageBackendResolver,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.memory.service.MemoryExtractionService> memoryExtraction) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.knowledgeQaService = knowledgeQaService;
        this.agentQaService = agentQaService;
        this.suggestionService = suggestionService;
        this.temporaryDocuments = temporaryDocuments;
        this.steerCoordinator = steerCoordinator;
        this.emitter = emitter;
        this.sseFrameWriter = sseFrameWriter;
        // 两个端口按 ObjectProvider 取（A3-3 起 StorageBackendResolver 有生产实现）；
        // 缺 bean 时 Rewriter 按 Go 的 nil 分支降级（handle 模式同形）
        this.fileService = fileService.getIfAvailable();
        this.storageBackendResolver = storageBackendResolver.getIfAvailable();
        this.memoryExtraction = memoryExtraction.getIfAvailable();
            this.qaRequestParser = new QaRequestParser(sessionService, temporaryDocuments, this.fileService, this.storageBackendResolver);
        this.turnFinalizer = new QaTurnFinalizer(this.sessionService, this.messageService, this.suggestionService, this.temporaryDocuments, this.memoryExtraction);
        this.sseOrchestrator = new QaSseOrchestrator(this.streamManager, this.emitter, this.sessionService, this.messageService, this.sseFrameWriter, this.turnFinalizer);
}

    // ── 端点（qa.go L790-964） ───────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-chat/{session_id}")
    public void knowledgeQA(@PathVariable("session_id") String rawSessionId,
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = com.ragagent.storage.support.Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {
        CreateKnowledgeQARequest request = QaRequestBinder.bindQaRequest(rawBody);
        ParsedRequest parsed = qaRequestParser.parseQARequest(rawSessionId, request, resourceUrls, "KnowledgeQA", agentResolverField, currentTenant());
        executeQA(parsed.reqCtx(), QaMode.NORMAL, !request.disableTitle, response);
    }

    @PostMapping("/api/v1/agent-chat/{session_id}")
    public void agentQA(@PathVariable("session_id") String rawSessionId,
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = com.ragagent.storage.support.Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {
        CreateKnowledgeQARequest request = QaRequestBinder.bindQaRequest(rawBody);
        ParsedRequest parsed = qaRequestParser.parseQARequest(rawSessionId, request, resourceUrls, "AgentQA", agentResolverField, currentTenant());
        QaRequestContext reqCtx = parsed.reqCtx();

        // agent 模式判定：customAgent.agent_mode > request.agent_enabled（Go L929-936）
        boolean agentModeEnabled = request.agentEnabled;
        if (reqCtx.agentConfig != null) {
            agentModeEnabled = SessionKnowledgeQaService.isAgentMode(reqCtx.agentConfig);
        }

        if (agentModeEnabled && reqCtx.agentConfig == null) {
            throw BizException.badRequest("agent_id is required when agent mode is enabled");
        }

        if (agentModeEnabled) {
            executeQA(reqCtx, QaMode.AGENT, true, response);
        } else {
            executeQA(reqCtx, QaMode.NORMAL, !request.disableTitle, response);
        }
    }

    @PostMapping("/api/v1/knowledge-search")
    public List<SearchResult> searchKnowledge(@RequestBody(required = false) String rawBody) {
        SearchKnowledgeRequest request = QaRequestBinder.bindSearchRequest(rawBody);
        if (request.query.isEmpty()) {
            // Go 的手动分支被 binding:required 拦截（不可达），保留对应物
            throw BizException.badRequest("Query content cannot be empty");
        }

        // Merge single knowledge_base_id into knowledge_base_ids（Go L819-832）
        List<String> knowledgeBaseIds = new ArrayList<>(request.knowledgeBaseIds());
        if (!request.knowledgeBaseId.isEmpty() && !knowledgeBaseIds.contains(request.knowledgeBaseId)) {
            knowledgeBaseIds.add(request.knowledgeBaseId);
        }

        List<QaSupport.TagScope> mentionScopes = QaSupport.tagScopesFromMentionedItems(request.mentionedItems());
        List<String> requestTagIds = QaSupport.dedupRequestStrings(request.tagIds());
        String tagError = QaSupport.validateUnscopedTagIds(
                QaSupport.orphanTagIdsForScope(requestTagIds, mentionScopes), knowledgeBaseIds);
        if (tagError != null) {
            throw BizException.badRequest(tagError);
        }
        List<QaSupport.TagScope> tagScopes = QaSupport.mergeTagScopesFromRequestIds(
                mentionScopes, requestTagIds, knowledgeBaseIds);

        if (knowledgeBaseIds.isEmpty() && request.knowledgeIds().isEmpty() && tagScopes.isEmpty()) {
            throw BizException.badRequest(
                    "At least one knowledge_base_id, knowledge_base_ids, knowledge_ids, or scoped tag must be provided");
        }
        com.ragagent.auth.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeTargets(
                knowledgeBaseIds, request.knowledgeIds());

        List<SearchResult> searchResults = knowledgeQaService.searchKnowledge(
                knowledgeBaseIds, request.knowledgeIds(), tagScopes, request.query);

        // 裸列表（无 {success,data} 信封）：检索结果直出。
        // 引用形式（resource_urls）在检索面不带存储引用——Go 走 CopyReferences；
        // handle 模式为透传（public 模式的直链生成经 provider 级文件服务，A3-3 起已接线）。
        return searchResults;
    }

    // ── ShouldBindJSON 对应物（Go binding:required 文案逐字对齐） ─────────────


    // ── parseQARequest（qa.go L126-429） ─────────────────────────────────────

    record ParsedRequest(QaRequestContext reqCtx, CreateKnowledgeQARequest request) {}


    /** 共享/自有 agent 解析（Go qa.go 的 resolveAgent；与附件上传入口共用同一组件）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private AgentResolver agentResolverField;
    @org.springframework.beans.factory.annotation.Autowired
    private com.ragagent.auth.service.TenantService tenantServiceField;

    /**
     * 读者租户实体（A3-3 接线）——供 Rewriter 解析"引用不带 provider scheme 时的租户默认
     * provider"。此前恒传 null，等价于 Go 在 ctx 无租户时的降级（引用一律保留成 handle）；
     * 现在按 TenantContext 的 id 取实体，与 {@code SystemController} /
     * {@code HybridSearchService} 同一写法。
     */
    private com.ragagent.auth.domain.Tenant currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        try {
            return tid == null || tid <= 0 || tenantServiceField == null
                    ? null : tenantServiceField.getTenantById(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<String> stringListOf(com.fasterxml.jackson.databind.JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }


    // ── executeQA（qa.go L1069-1324） ────────────────────────────────────────

    private void executeQA(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
            HttpServletResponse response) throws IOException {
        executeQA(reqCtx, mode, generateTitle, response, null);
    }

    /**
     * @param asyncDone skipSSE 调用方（steer follow-up）的完成信号：异步 runner 收尾后
     *                  complete；HTTP 调用方传 null。对照 Go 的 asyncDone channel。
     */
    private void executeQA(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
            HttpServletResponse response, CompletableFuture<Void> asyncDone) throws IOException {
        String sessionId = reqCtx.sessionId;

        // 输入条状态（纯 UI memo）异步写：新虚拟线程没有 ThreadLocal——纪律 #1
        // 要求显式捕获-重放。旧实现直接 start，租户读到 0、owner 为空，
        // updateSessionLastRequestState 按 (tenant, owner) 过滤后静默 0 行。
        final com.ragagent.event.TenantContextSnapshot memoTenant =
                com.ragagent.event.TenantContextSnapshot.capture();
        Thread.ofVirtual().start(() -> {
            memoTenant.replay();
            // 派生线程同样按会话属主租户查（对照 Go 的 ctx 传播）
            com.ragagent.session.service.SessionLookupScope.mark();
            try {
                turnFinalizer.persistLastRequestState(reqCtx, mode);
            } finally {
                TenantContext.clear();
                com.ragagent.session.service.SessionLookupScope.clear();
            }
        });

        if (mode == QaMode.AGENT) {
            try {
                rejectIfOtherAgentRunLive(reqCtx);
            } catch (BizException e) {
                throw e;
            }
        }

        // agent 模式的 query 帧由 setupSSEStream 内的 writeAgentQueryEvent 直写流
        // （helpers.go L418-440）。这里不再向空 EventBus 发事件：该实例无任何订阅者，
        // 纯 no-op（Go 的 Emit(agent.query) 有 middleware 面才需要）。

        boolean createdUser = reqCtx.userMessageID.isEmpty();
        boolean createdAssistant = reqCtx.assistantMessage == null || reqCtx.assistantMessage.getId().isEmpty();

        try {
            persistTurnMessages(reqCtx);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }

        // steer carry-over：本 run 公布为 live 之前接上（Go L1131-1138）
        if (reqCtx.steerCarryOver != null && !reqCtx.steerCarryOver.isEmpty() && reqCtx.assistantMessage != null) {
            try {
                streamManager.appendSteerEvents(sessionId, reqCtx.assistantMessage.getId(), reqCtx.steerCarryOver);
            } catch (RuntimeException e) {
                log.warn("steer carry-over append failed for session {}: {}", sessionId, e.toString());
            }
        }

        // SSE 装配
        SseStreamContext streamCtx = sseOrchestrator.setupSSEStream(reqCtx, generateTitle, mode);
        if (streamCtx.liveRunFailed) {
            rollbackTurnMessages(reqCtx, createdUser, createdAssistant);
            if (streamCtx.liveRunExists) {
                throw BizException.conflict("another turn is already running in this session");
            }
            throw BizException.serviceUnavailable("Failed to publish running turn");
        }

        // 快答路径：timeline 记录器 + reasoning 累积 + 完成事件（Go L1159-1206）
        if (mode == QaMode.NORMAL) {
            sseOrchestrator.registerQuickAnswerTimelineRecorder(streamCtx.eventBus, streamCtx.assistantMessage);
            streamCtx.eventBus.on(EventType.EVENT_AGENT_THOUGHT, evt -> {
                if (evt.getData() instanceof AgentThoughtData data && !data.getContent().isEmpty()) {
                    appendQuickAnswerReasoning(streamCtx.assistantMessage, data.getContent());
                }
            });
            final boolean[] completionHandled = {false};
            // 事件在桥接虚拟线程触发，TenantContext 是 ThreadLocal——注册时捕获
            // session 租户，触发时 replay（与 L976 stop 处理器同款纪律 #1）。
            final long normalSessionTenantId = reqCtx.session.getTenantId();
            streamCtx.eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
                if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
                    return;
                }
                Message am = streamCtx.assistantMessage;
                synchronized (am) {
                    am.setContent(am.getContent() + QaSupport.orEmpty(data.getContent()));
                    if (data.isFallback()) {
                        am.setFallback(true);
                    }
                    if (data.isDone()) {
                        if (completionHandled[0]) {
                            return;
                        }
                        completionHandled[0] = true;
                        log.info("Knowledge QA service completed for session: {}", sessionId);
                        turnFinalizer.runWithTenant(normalSessionTenantId, () -> {
                            turnFinalizer.completeAssistantMessage(streamCtx.assistantMessage, reqCtx.query,
                                    reqCtx.userMessageID, normalSessionTenantId);
                            Event done = new Event();
                            done.setType(EventType.EVENT_AGENT_COMPLETE);
                            done.setSessionId(sessionId);
                            AgentCompleteData cd = new AgentCompleteData();
                            cd.setFinalAnswer(am.getContent());
                            done.setData(cd);
                            streamCtx.eventBus.emit(done);
                        });
                    }
                }
            });
        }

        // 异步执行（虚拟线程，Go L1209-1313 的 goroutine）。
        // 纪律 #1：TenantContext 是 ThreadLocal，跨虚拟线程必须显式 capture/replay。
        // 对照 setupSSEStream（Go qa.go L661-675）：共享 agent → 异步段以源租户为
        // 执行租户（模型/KB/MCP 解析范围；身份不变）；租户不存在则不切换（Go 同款守卫）。
        final com.ragagent.event.TenantContextSnapshot requestTenant =
                reqCtx.effectiveTenantId != 0
                        && tenantServiceField.getTenantById(reqCtx.effectiveTenantId) != null
                ? com.ragagent.event.TenantContextSnapshot.capture()
                        .withTenantId(reqCtx.effectiveTenantId)
                : com.ragagent.event.TenantContextSnapshot.capture();
        if (reqCtx.effectiveTenantId == requestTenant.tenantId() && reqCtx.effectiveTenantId != 0) {
            log.info("Using effective tenant {} for shared agent (model/KB/MCP)",
                    reqCtx.effectiveTenantId);
        }
        Thread.Builder.OfVirtual runner = Thread.ofVirtual();
        runner.start(() -> {
            try {
                requestTenant.replay();
                // 对照 Go L213（ctx 沿整条 QA 流传播）：本线程上的会话/消息查询按会话属主
                // 租户范围——共享 agent 场景下当前主体不是属主，带 user 范围会查不到。
                // 线程收尾处清理（见下方 finally 的 TenantContext.clear() 旁）。
                com.ragagent.session.service.SessionLookupScope.mark();
                resolveTemporaryAttachments(streamCtx, reqCtx);
                QaSupport.QaRequest qaReq = reqCtx.buildQaRequest();
                // 用户停止 → 引擎取消（Go 的 ctx 取消贯穿 think/act/审批等待三条路）：
                // 探针读 streamCtx.cancelled（stop 处理器置位），语义 null=未取消。
                qaReq.cancellationProbe = () -> streamCtx.cancelled ? "context canceled" : null;
                if (mode == QaMode.NORMAL) {
                    knowledgeQaService.knowledgeQA(qaReq, streamCtx.eventBus);
                } else {
                    agentQaService.agentQA(qaReq, streamCtx.eventBus);
                }
            } catch (RuntimeException serviceErr) {
                log.error("QA service failed for session {}: {}", sessionId, serviceErr.toString());
                Event errEvt = new Event();
                errEvt.setType(EventType.EVENT_ERROR);
                errEvt.setSessionId(sessionId);
                ErrorData errData = new ErrorData();
                // Go 的 serviceErr.Error() **带** AppError 前缀（W5γ5.12 线上 A/B 实测，两种模式都实测过）：
                // Go 管道返回的是 PluginError.Err 内层错误，而那个内层错误就是 AppError 本身，
                // 其 Error() = "error code: N, error message: M"——**不是**裸 message。
                // 旧实现在这里剥到 appError().message()，理由（"否则会带出 BizException 前缀"）把
                // Java 的包装类名与 Go 的 AppError 文案混为一谈了：只要不吐 Java 异常类名即可，
                // 前缀本身是契约（同 §known-issues/09 第三节）。
                errData.setError(com.ragagent.common.error.BizException.wireText(serviceErr));
                errData.setStage(mode == QaMode.NORMAL ? "knowledge_qa_execution" : "agent_execution");
                errData.setSessionId(sessionId);
                errEvt.setData(errData);
                try {
                    streamCtx.eventBus.emit(errEvt);
                } catch (RuntimeException ignore) {
                    // 流已终止
                }
            } finally {
                if (mode == QaMode.AGENT) {
                    Message am = streamCtx.assistantMessage;
                    // agent 收尾（Go L1224-1270）：steer 交接 + 完成 + ClearLiveRun
                    //
                    // 顺序纪律：收尾必须在**本线程上下文仍完整**时进行，clear 放到最后。
                    // 旧实现先 clear 再 runWithTenant，prev 已空、只剩 tenantId——
                    // completeAssistantMessage 的异步索引/follow-up 快照因此丢了
                    // principal/userId，owner 推导成 ""，embed（owner=embed_session:…）
                    // 与平台（owner=<userId>）会话双双 SessionNotFound。
                    // 对照 Go：defer 里的 ctx 值仍然完整，不存在这个顺序陷阱。
                    Long sessionTenant = reqCtx.session.getTenantId();
                    turnFinalizer.runWithTenant(sessionTenant, () -> {
                        if (streamCtx.cancelled) {
                            Set<String> injected = streamCtx.steerSink != null
                                    ? streamCtx.steerSink.injectedIds() : Set.of();
                            steerCoordinator.discardSteerBacklog(sessionId, am.getId(), injected);
                            turnFinalizer.completeAssistantMessage(am, reqCtx.query, reqCtx.userMessageID, sessionTenant);
                        } else {
                            boolean kicked = steerCoordinator.kickNextRunFromSteerBacklog(
                                    reqCtx, streamCtx, this::runFollowUp);
                            turnFinalizer.completeAssistantMessage(am, reqCtx.query, reqCtx.userMessageID, sessionTenant);
                            if (!kicked) {
                                steerCoordinator.kickNextRunFromSteerBacklog(reqCtx, streamCtx, this::runFollowUp);
                            }
                        }
                        try {
                            streamManager.clearLiveRun(sessionId, am.getId());
                        } catch (RuntimeException e) {
                            log.warn("live run cleanup failed for session {}: {}", sessionId, e.toString());
                        }
                        log.info("Agent QA service completed for session: {}", sessionId);
                    });
                }
                // 收尾（含身份相关的库写）完成后再清线程上下文
                TenantContext.clear();
                com.ragagent.session.service.SessionLookupScope.clear();
                if (asyncDone != null) {
                    asyncDone.complete(null);
                }
            }
        });

        // 主线程阻塞推 SSE（skipSSE 的 follow-up 无 HTTP 响应体：不推流，
        // 由 executeQaInternal 的 asyncDone.join() 等异步段收尾，对照 Go L1315-1318）
        if (response == null) {
            return;
        }
        boolean shouldWaitForTitle = generateTitle && reqCtx.session.getTitle() != null
                && reqCtx.session.getTitle().isEmpty();
        sseOrchestrator.handleAgentEventsForSSE(response, sessionId, reqCtx.assistantMessage.getId(), reqCtx.requestId,
                streamCtx, shouldWaitForTitle, reqCtx.resourceRewriter);
    }

    private void runFollowUp(QaRequestContext followUp) {
        // follow-up 是 skipSSE 的服务端自启轮（Go 的 go executeQA(followUp, agent, false)）
        Thread.ofVirtual().start(() -> {
            try {
                executeQaInternal(followUp, QaMode.AGENT, false, null);
            } catch (Throwable e) {
                // follow-up 失败必须自救：claimNextSteerFollowUp 已落 user/assistant 两行并
                // 抢占 live-run，异常路径若不清理会把这个会话的 agent 模式持续 409 锁死。
                log.error("steer follow-up run failed: {}", e.toString(), e);
                recoverFailedFollowUp(followUp);
            }
        });
    }

    /** follow-up 启动失败的兜底：收尾半成品 assistant 行 + 清 live-run（尽力而为）。 */
    private void recoverFailedFollowUp(QaRequestContext followUp) {
        try {
            String amId = followUp.assistantMessage == null ? "" : followUp.assistantMessage.getId();
            long sessionTenant = followUp.session == null ? 0 : followUp.session.getTenantId();
            if (followUp.assistantMessage != null && !amId.isEmpty() && sessionTenant != 0) {
                turnFinalizer.runWithTenant(sessionTenant, () -> turnFinalizer.completeAssistantMessage(
                        followUp.assistantMessage, followUp.query, followUp.userMessageID, sessionTenant));
            }
        } catch (RuntimeException e) {
            log.warn("follow-up completion recovery failed: {}", e.toString());
        }
        try {
            String amId = followUp.assistantMessage == null ? "" : followUp.assistantMessage.getId();
            if (!amId.isEmpty()) {
                streamManager.clearLiveRun(followUp.sessionId, amId);
            }
        } catch (RuntimeException e) {
            log.warn("follow-up live-run cleanup failed for session {}: {}",
                    followUp.sessionId, e.toString());
        }
    }

    private void executeQaInternal(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
            HttpServletResponse response) throws IOException {
        if (response == null) {
            // skipSSE 路径：等待异步段完成后返回（Go L1315-1318 的 <-asyncDone）
            CompletableFuture<Void> asyncDone = new CompletableFuture<>();
            try {
                executeQA(reqCtx, mode, generateTitle, null, asyncDone);
            } catch (RuntimeException | IOException e) {
                // runner 未起就失败：放行等待者，异常交给 runFollowUp 的 recover 路径
                asyncDone.complete(null);
                throw e;
            }
            asyncDone.join();
            return;
        }
        executeQA(reqCtx, mode, generateTitle, response, null);
    }

    // 曾有的 errorEventText（剥 BizException 取 appError().message()）已删除：
    // 其前提被线上 A/B 推翻（W5γ5.12），现统一走 BizException.wireText，理由见上面的调用点注释。


    // ── persistTurnMessages / rollback（qa.go L978-1047） ────────────────────

    private void persistTurnMessages(QaRequestContext reqCtx) {
        if (reqCtx.userMessageID.isEmpty()) {
            List<MessageAttachment> userMessageAttachments = new ArrayList<>(reqCtx.attachments);
            userMessageAttachments.addAll(reqCtx.attachmentMetas);
            Message userMsg = messageService.createMessage(buildUserMessage(reqCtx, userMessageAttachments));
            reqCtx.userMessageID = userMsg.getId();
            reqCtx.userCreatedAt = userMsg.getCreatedAt();
        }
        if (reqCtx.assistantMessage == null) {
            Message am = new Message();
            am.setSessionId(reqCtx.sessionId);
            am.setRole("assistant");
            am.setCompleted(false);
            am.setRequestId(reqCtx.requestId);
            am.setCreatedAt(OffsetDateTime.now());
            reqCtx.assistantMessage = am;
        }
        if (reqCtx.assistantMessage.getId().isEmpty()) {
            reqCtx.assistantMessage.setCreatedAt(OffsetDateTime.now());
            Message created = messageService.createMessage(reqCtx.assistantMessage);
            reqCtx.assistantMessage = created;
        }
    }

    private Message buildUserMessage(QaRequestContext reqCtx, List<MessageAttachment> attachments) {
        Message m = new Message();
        m.setSessionId(reqCtx.sessionId);
        m.setRole("user");
        m.setContent(reqCtx.query);
        m.setRequestId(reqCtx.requestId);
        m.setCreatedAt(OffsetDateTime.now());
        m.setCompleted(true);
        m.setMentionedItems(reqCtx.mentionedItems);
        List<MessageImage> images = new ArrayList<>();
        for (QaSupport.QaRequestsImage img : reqCtx.images) {
            MessageImage mi = new MessageImage();
            mi.setUrl(img.url);
            mi.setCaption(img.caption);
            images.add(mi);
        }
        m.setImages(images);
        m.setAttachments(attachments);
        m.setChannel(reqCtx.channel);
        if (reqCtx.suggestionAttribution != null) {
            com.ragagent.session.domain.MessageExecutionContext ctx =
                    new com.ragagent.session.domain.MessageExecutionContext();
            ctx.setSuggestionAttribution(reqCtx.suggestionAttribution);
            m.setExecutionContext(ctx);
        }
        return m;
    }

    private void rollbackTurnMessages(QaRequestContext reqCtx, boolean user, boolean assistant) {
        String sessionId = reqCtx.sessionId;
        if (user && !reqCtx.userMessageID.isEmpty()) {
            try {
                messageService.deleteMessage(sessionId, reqCtx.userMessageID);
                reqCtx.userMessageID = "";
            } catch (RuntimeException e) {
                log.warn("turn rollback failed for user message {}: {}", reqCtx.userMessageID, e.toString());
            }
        }
        if (assistant && reqCtx.assistantMessage != null && !reqCtx.assistantMessage.getId().isEmpty()) {
            try {
                messageService.deleteMessage(sessionId, reqCtx.assistantMessage.getId());
                reqCtx.assistantMessage.setId("");
            } catch (RuntimeException e) {
                log.warn("turn rollback failed for assistant message {}: {}",
                        reqCtx.assistantMessage.getId(), e.toString());
            }
        }
    }

    private void rejectIfOtherAgentRunLive(QaRequestContext reqCtx) {
        var live = streamManager.getLiveRun(reqCtx.sessionId);
        String liveId = live == null ? "" : live.assistantMessageId();
        if (liveId.isEmpty()) {
            return;
        }
        String self = reqCtx.assistantMessage == null ? "" : reqCtx.assistantMessage.getId();
        if (liveId.equals(self)) {
            return;
        }
        throw BizException.conflict("another turn is already running in this session");
    }

    // ── setupSSEStream（qa.go L659-775） ─────────────────────────────────────


    // ── handleAgentEventsForSSE（stream.go L330-474） ─────────────────────────


    // ── quick answer timeline（quick_answer_timeline.go 全文） ────────────────


    static AgentStep ensureQuickAnswerStep(Message msg) {
        if (msg.getAgentSteps() == null || msg.getAgentSteps().isEmpty()) {
            AgentStep step = new AgentStep();
            step.setIteration(0);
            step.setTimestamp(OffsetDateTime.now());
            step.setToolCalls(new ArrayList<>());
            List<AgentStep> steps = new ArrayList<>();
            steps.add(step);
            msg.setAgentSteps(steps);
        }
        return msg.getAgentSteps().get(0);
    }


    private static void appendQuickAnswerReasoning(Message msg, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        AgentStep step = ensureQuickAnswerStep(msg);
        step.setReasoningContent(QaSupport.orEmpty(step.getReasoningContent()) + content);
    }

    // ── 附件 / 完成 / 状态（qa.go L1417-1768） ───────────────────────────────

    /** resolveTemporaryAttachments（qa.go L1417-1512）：等待 → ResolveForPrompt → 注入。 */
    private void resolveTemporaryAttachments(SseStreamContext streamCtx, QaRequestContext reqCtx) {
        if (reqCtx.attachmentIDs.isEmpty()) {
            return;
        }
        long tenantId = reqCtx.session.getTenantId();
        String sessionId = reqCtx.sessionId;
        long start = System.currentTimeMillis();
        String toolCallId = "";
        if (turnFinalizer.hasPendingAttachments(tenantId, sessionId, reqCtx.attachmentIDs)) {
            toolCallId = UUID.randomUUID().toString();
            Event evt = new Event();
            evt.setType(EventType.EVENT_AGENT_TOOL_CALL);
            evt.setSessionId(sessionId);
                        AgentToolCallData callData = new AgentToolCallData();
            callData.setToolCallId(toolCallId);
            callData.setToolName("attachment_parsing");
            callData.setArguments(new LinkedHashMap<>());
            callData.setIteration(0);
            evt.setData(callData);
            streamCtx.eventBus.emit(evt);
            waitForAttachments(tenantId, sessionId, reqCtx.attachmentIDs, 60_000);
        }
        List<String> readyIds = new ArrayList<>();
        int skipped = 0;
        for (String id : reqCtx.attachmentIDs) {
            TemporaryDocument doc;
            try {
                doc = temporaryDocuments.get(tenantId, sessionId, id);
            } catch (RuntimeException e) {
                doc = null;
            }
            if (doc != null && "ready".equals(doc.getStatus())) {
                readyIds.add(id);
            } else {
                skipped++;
            }
        }
        TemporaryDocumentService.PromptResult resolved = null;
        RuntimeException resolveErr = null;
        if (!readyIds.isEmpty()) {
            try {
                resolved = temporaryDocuments.resolveForPrompt(
                        tenantId, sessionId, readyIds, reqCtx.query);
            } catch (RuntimeException e) {
                resolveErr = e;
            }
        }

        if (!toolCallId.isEmpty()) {
            String output = "已解析 " + readyIds.size() + " 个附件";
            if (skipped > 0) {
                output += "，" + skipped + " 个未完成已跳过";
            }
            boolean success = resolveErr == null;
            if (resolveErr != null) {
                output = "附件解析失败: " + resolveErr.getMessage();
            }
            Event evt = new Event();
            evt.setType(EventType.EVENT_AGENT_TOOL_RESULT);
            evt.setSessionId(sessionId);
            AgentToolResultData data = new AgentToolResultData();
            data.setToolCallId(toolCallId);
            data.setToolName("attachment_parsing");
            data.setOutput(output);
            data.setSuccess(success);
            data.setDurationMs(System.currentTimeMillis() - start);
            data.setIteration(0);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("display_type", "attachment_parsing");
            d.put("parsed_count", readyIds.size());
            d.put("skipped_count", skipped);
            data.setData(d);
            evt.setData(data);
            streamCtx.eventBus.emit(evt);
        }
        if (resolveErr != null || resolved == null) {
            if (resolveErr != null) {
                log.warn("temporary attachment resolution failed for session {}: {}",
                        sessionId, resolveErr.getMessage());
            }
            return;
        }
        List<MessageAttachment> attachments = resolved.attachments();
        // 对照 Go：handler 侧再按 supported_file_types 过滤一层（不支持的附件不进提示词）
        if (reqCtx.agentConfig != null && !attachments.isEmpty()) {
            List<String> supported = stringListOf(reqCtx.agentConfig.get("supported_file_types"));
            if (!supported.isEmpty()) {
                attachments.removeIf(att -> {
                    String ext = att.getFileType() == null
                            ? "" : att.getFileType().toLowerCase();
                    if (ext.startsWith(".")) {
                        ext = ext.substring(1);
                    }
                    return !supported.contains(ext);
                });
            }
        }
        reqCtx.attachments.addAll(attachments);
        persistResolvedAttachmentContent(reqCtx, attachments);
        // 图片进 vision：ImageURLs 挂到本回合的 images（与内联 base64 图片同一条下游，
        // 经 extractImageURLsAndOCRText 读 url）。Go 同样以 ImageUploadEnabled 为闸。
        if (reqCtx.agentConfig != null
                && reqCtx.agentConfig.path("image_upload_enabled").asBoolean(false)) {
            for (String imageUrl : resolved.imageUrls()) {
                QaSupport.QaRequestsImage image = new QaSupport.QaRequestsImage();
                image.url = imageUrl;
                reqCtx.images.add(image);
            }
        }
    }

    /**
     * 对照 Go {@code persistResolvedAttachmentContent}（qa.go L1516-1560）：把解析出的
     * 附件内容回写到已存的 user 消息（attachments 列）——消息创建时只带元数据，
     * 内容在 SSE 起流后才选出；不回写的话多轮历史重建时附件是空的。
     *
     * <p>失败只 WARN：丢这次写入只降级后续上下文，不能让本回合失败。</p>
     */
    private void persistResolvedAttachmentContent(QaRequestContext reqCtx,
            List<MessageAttachment> resolved) {
        if (reqCtx.userMessageID.isEmpty() || resolved.isEmpty()) {
            return;
        }
        Message msg;
        try {
            msg = messageService.getMessage(reqCtx.sessionId, reqCtx.userMessageID);
        } catch (RuntimeException e) {
            log.warn("persist attachment content: load user message {} failed: {}",
                    reqCtx.userMessageID, e.getMessage());
            return;
        }
        if (msg == null) {
            log.warn("persist attachment content: load user message {} failed: not found",
                    reqCtx.userMessageID);
            return;
        }
        Map<String, MessageAttachment> byId = new LinkedHashMap<>();
        for (MessageAttachment att : resolved) {
            if (att.getId() != null && !att.getId().isEmpty()) {
                byId.put(att.getId(), att);
            }
        }
        boolean changed = false;
        List<MessageAttachment> stored = msg.getAttachments();
        if (stored != null) {
            for (int i = 0; i < stored.size(); i++) {
                MessageAttachment existing = stored.get(i);
                if (existing == null || existing.getId() == null || existing.getId().isEmpty()) {
                    continue;
                }
                MessageAttachment enriched = byId.get(existing.getId());
                if (enriched != null) {
                    stored.set(i, enriched);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        try {
            messageService.updateMessage(msg);
        } catch (RuntimeException e) {
            log.warn("persist attachment content: update user message {} failed: {}",
                    reqCtx.userMessageID, e.getMessage());
        }
    }


    private void waitForAttachments(long tenantId, String sessionId, List<String> ids, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (turnFinalizer.hasPendingAttachments(tenantId, sessionId, ids) && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    /** 对照 secutils.GetMaxFileSize 的 dev 缺省（100MB 上传闸门同形）。 */
    static long maxFileBytes() {
        return 100L * 1024 * 1024;
    }

    static final class Base64Support {
        static byte[] decode(String data) {
            String payload = data == null ? "" : data;
            int comma = payload.indexOf(',');
            if (payload.startsWith("data:") && comma > 0) {
                payload = payload.substring(comma + 1);
            }
            return java.util.Base64.getDecoder().decode(payload);
        }
    }
}
