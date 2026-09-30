package com.ragagent.session.controller;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import com.ragagent.event.payload.ErrorData;
import com.ragagent.event.payload.AgentFinalAnswerData;
import com.ragagent.event.payload.AgentCompleteData;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageImage;
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
 * {@code KnowledgeQaController} 的**执行/落库簇**（§14.9c 刀 6b）：一整轮 QA 的编排（模式分派、
 * follow-up 交接与回滚、租户作用域）、轮次消息落库（用户消息构造/回滚）、并发守卫。
 *
 * <p>共享项处理（§11.26 判据）：{@code turnFinalizer}/{@code sseOrchestrator}/{@code attachmentResolver}
 * 均为已建协作者，直接持有转发；{@code appendQuickAnswerReasoning} 是 static → 留控制器按类名引用。</p>
 */
final class QaTurnExecutor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaTurnExecutor.class);

    private final MessageService messageService;
    private final StreamManager streamManager;
    private final SessionKnowledgeQaService knowledgeQaService;
    private final SessionAgentQaService agentQaService;
    private final com.ragagent.session.service.SteerRunCoordinator steerCoordinator;
    private final QaSseOrchestrator sseOrchestrator;
    private final QaTurnFinalizer turnFinalizer;
    private final QaAttachmentResolver attachmentResolver;
    private final com.ragagent.auth.service.TenantService tenantService;

    QaTurnExecutor(MessageService messageService, StreamManager streamManager, SessionKnowledgeQaService knowledgeQaService, SessionAgentQaService agentQaService, com.ragagent.session.service.SteerRunCoordinator steerCoordinator, QaSseOrchestrator sseOrchestrator, QaTurnFinalizer turnFinalizer, QaAttachmentResolver attachmentResolver, com.ragagent.auth.service.TenantService tenantService) {
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.knowledgeQaService = knowledgeQaService;
        this.agentQaService = agentQaService;
        this.steerCoordinator = steerCoordinator;
        this.sseOrchestrator = sseOrchestrator;
        this.turnFinalizer = turnFinalizer;
        this.attachmentResolver = attachmentResolver;
        this.tenantService = tenantService;
    }

    void executeQA(QaRequestContext reqCtx, QaMode mode, boolean generateTitle,
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
                    KnowledgeQaController.appendQuickAnswerReasoning(streamCtx.assistantMessage, data.getContent());
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
                        && tenantService.getTenantById(reqCtx.effectiveTenantId) != null
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
                attachmentResolver.resolveTemporaryAttachments(streamCtx, reqCtx);
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
}
