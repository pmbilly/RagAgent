package com.ragagent.im.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.im.runtime.AdapterInterfaces.FullOutputProgressSender;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.QaQueue;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.Session;
import com.ragagent.session.service.QaSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.im.service.ImService.InflightEntry;
import com.ragagent.im.service.ImService.QaAttach;
import com.ragagent.im.service.ImService.QaOutcome;
import com.ragagent.im.service.ImService.QaTask;

/**
 * IM QA 执行编排：队列 worker 的租户上下文绑定与在途登记、full-output / 流式 /
 * 非流式三条发送路径、同步 runQA（事件收集 + 消息落库）。
 * 对照 Go executeQARequest（service.go L1927-2013）与 runQA（L2924-3115）。
 */
final class ImQaRunner {

    private static final Logger log = LoggerFactory.getLogger(ImQaRunner.class);

    private final ImService service;

    ImQaRunner(ImService service) {
        this.service = service;
    }

    // ── QA 执行（executeQARequest，service.go L1927-2013） ────────────────

    void executeQARequest(QaTask task) {
        // 队列 worker 是独立虚拟线程：租户上下文必须显式携带（约定 §5，对照 Go
        // ctx 随 qaRequest.ctx 流转）。
        TenantContext.set(task.tenantId(), new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + task.tenantId()),
                "viewer", false, "system-" + task.tenantId(), false);
        QaQueue.QaRequest req = task.queueReq();
        InflightEntry entry = new InflightEntry(() -> {
            req.cancel();
            return true;
        });
        service.inflight.put(task.userKey, entry);
        try {
            // 排队期间被 /stop 的直接跳过。
            if (req.isCancelled()) {
                return;
            }
            IncomingMessage msg = task.msg;
            QaAttach attach = task.attach();
            List<com.ragagent.session.domain.MessageAttachment> attachments = List.of();
            List<String> imageUrls = List.of();
            boolean streamDisabled = "full".equals(attach.channel().getOutputMode());

            if (streamDisabled) {
                if (attach.adapter() instanceof FullOutputProgressSender progress
                        && progress.supportsFullOutputProgress()) {
                    try {
                        handleMessageFullOutput(msg, attach, progress);
                    } catch (Exception e) {
                        log.error("[IM] Full-output QA failed: {}", e.getMessage(), e);
                    }
                    return;
                }
            } else if (attach.adapter() instanceof StreamSender streamer) {
                try {
                    service.streamPipeline.handleMessageStream(msg, attach, streamer);
                } catch (Exception e) {
                    log.error("[IM] Stream QA failed: {}", e.getMessage(), e);
                }
                return;
            }

            // 非流式兜底：收集完整答案再发。
            QaOutcome outcome = runQA(attach);
            String answer = outcome.answer();
            if (outcome.error() != null) {
                log.error("[IM] QA failed: {}, sending fallback reply", outcome.error());
                answer = ImFormat.imQAFailureReply(outcome.error());
            }
            String display = service.formatIMOutboundAnswerOrFallback(answer);
            service.sendReplyQuiet(attach.adapter(), msg, new ReplyMessage(display, false, true));
            log.info("[IM] Reply sent: channel={} platform={} user={} answer_len={}",
                    attach.channelId(), msg.platform, msg.userId, answer.length());
        } finally {
            service.inflight.remove(task.userKey);
            TenantContext.clear();
        }
    }


    void handleMessageFullOutput(IncomingMessage msg, QaAttach attach,
            FullOutputProgressSender streamer) throws Exception {
        String streamId;
        try {
            streamId = streamer.startStream(msg);
        } catch (Exception e) {
            log.warn("[IM] StartStream failed for full output, falling back: {}", e.getMessage());
            runFallbackNonStream(attach);
            return;
        }
        QaOutcome outcome = runQA(attach);
        String answer = outcome.answer();
        if (outcome.error() != null) {
            log.error("[IM] Full-output QA failed: {}, sending fallback reply", outcome.error());
            answer = ImFormat.imQAFailureReply(outcome.error());
        }
        String finalContent = service.formatIMOutboundAnswerOrFallback(answer);
        Exception finalizeErr = null;
        try {
            streamer.finalizeStream(msg, streamId, finalContent);
        } catch (Exception e) {
            finalizeErr = e;
            log.warn("[IM] FinalizeStream failed for full output: {}", e.getMessage());
        }
        try {
            streamer.endStream(msg, streamId);
        } catch (Exception e) {
            log.warn("[IM] EndStream failed for full output: {}", e.getMessage());
        }
        if (finalizeErr != null) {
            service.sendReplyQuiet(attach.adapter(), msg, new ReplyMessage(finalContent, false, true));
        }
    }


    void runFallbackNonStream(QaAttach attach) {
        QaOutcome outcome = runQA(attach);
        String answer = outcome.answer();
        if (outcome.error() != null) {
            answer = ImFormat.imQAFailureReply(outcome.error());
        }
        String display = service.formatIMOutboundAnswerOrFallback(answer);
        service.sendReplyQuiet(attach.adapter(), attach.msg(), new ReplyMessage(display, false, true));
    }

    // ── runQA（service.go L2924-3115）：事件收集 + 消息落库 ────────────────

    QaOutcome runQA(QaAttach attach) {
        EventBus eventBus = new EventBus();
        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<Exception> qaErr = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);

        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.AgentFinalAnswerData data)) {
                return;
            }
            String content = data.getContent();
            if (content != null && !content.isEmpty()) {
                synchronized (answerBuilder) {
                    answerBuilder.append(content);
                }
            }
            if (data.isDone()) {
                done.countDown();
            }
        });
        eventBus.on(EventType.EVENT_ERROR, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.ErrorData data)) {
                return;
            }
            log.error("[IM] QA error: {}", data.getError());
            qaErr.set(new RuntimeException("QA pipeline error: " + data.getError()));
            done.countDown();
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_MCP_OAUTH_REQUIRED, evt -> {
            // OAuth 待授权：IM 无法处理会话内提示 → 汇总成文末提示（γ2 精简为日志备案）。
            log.info("[IM] MCP OAuth required: {}", evt.getData());
        });

        CustomAgentEntity agent = attach.agent();
        Session session = attach.session();
        String requestId = UUID.randomUUID().toString();

        Message userMsg = service.qaRequests.createUserMessage(session.getId(), attach.msg().content, requestId);
        Message assistantMsg = service.qaRequests.createAssistantMessage(session.getId(), requestId);

        eventBus.on(EventType.EVENT_AGENT_COMPLETE, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.AgentCompleteData data)) {
                return;
            }
            String finalAnswer = data.getFinalAnswer();
            if (finalAnswer != null && !finalAnswer.isEmpty()) {
                synchronized (answerBuilder) {
                    if (answerBuilder.isEmpty()) {
                        answerBuilder.append(finalAnswer);
                    }
                }
            }
            assistantMsg.setCompleted(true);
            complete.countDown();
        });

        // 租户上下文：IM 回调无 JWT——显式注入合成身份（Go withIMIdentity 的
        // "system-<tenantID>" 对应 TenantContext.IM_USER principal）。
        long imTenant = attach.channel().getTenantId();
        TenantContext.set(imTenant, new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + imTenant), "viewer",
                false, "system-" + imTenant, false);
        try {
            QaSupport.QaRequest qaReq = service.qaRequests.buildIMQARequest(session, attach.msg().content,
                    assistantMsg.getId(), userMsg.getId(), agent, attach.msg().quote);
            // 同步执行（Go 是 goroutine + select 等待；Java 侧 QA 服务内部为
            // 虚拟线程管线，事件经 eventBus 回流到上面的订阅）。
            Exception runErr;
            try {
                if (agent != null && service.qaRequests.isAgentMode(agent)) {
                    service.agentQaService.agentQA(qaReq, eventBus);
                } else {
                    service.knowledgeQaService.knowledgeQA(qaReq, eventBus);
                }
                runErr = null;
            } catch (Exception e) {
                runErr = e;
            }
            if (runErr != null) {
                qaErr.set(new RuntimeException("QA execution error: " + runErr.getMessage(), runErr));
                done.countDown();
                complete.countDown();
            } else {
                try {
                    // 等待最终帧（对照 select done / waitForIMAgentComplete）。
                    if (!done.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
                        qaErr.compareAndSet(null, new java.util.concurrent.TimeoutException("IM QA wait"));
                    }
                    if (service.qaRequests.isAgentMode(agent)) {
                        complete.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            TenantContext.clear();
        }

        String answer;
        synchronized (answerBuilder) {
            answer = answerBuilder.toString();
        }
        Exception err = qaErr.get();
        if (answer.isEmpty() && err != null) {
            return new QaOutcome("", err);
        }
        if (answer.isEmpty()) {
            answer = ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        assistantMsg.setContent(answer);
        assistantMsg.setCompleted(true);
        try {
            service.messageService.updateMessage(assistantMsg);
        } catch (Exception e) {
            log.warn("[IM] Failed to update assistant message: {}", e.getMessage());
        }
        return new QaOutcome(answer, null);
    }
}
