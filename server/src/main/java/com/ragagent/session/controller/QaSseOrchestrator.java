package com.ragagent.session.controller;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.AgentToolCallData;
import com.ragagent.event.payload.AgentToolResultData;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.session.domain.Message;
import com.ragagent.session.service.AgentStreamBridge;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.QaSupport.SseStreamContext;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.SteerSinkBridge;
import com.ragagent.session.sse.SseContract;
import com.ragagent.session.sse.StreamEventEmitter;
import com.ragagent.storage.support.StreamRewriter;
import com.ragagent.stream.StreamBatch;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code KnowledgeQaController} 的**SSE 编排簇**（§14.9c 刀 5）：建立 SSE 流上下文、写
 * {@code agent_query} 请求帧、看门狗线程（流结束/断连即收尾）、把 agent 事件转发进 SSE，
 * 以及 quick answer 时间线的录制（步进/工具调用）。
 *
 * <p>共享行为处理（§11.26 判据）：{@code runWithTenant} / {@code completeAssistantMessage} 随刀 7
 * 已进 {@link QaTurnFinalizer}，本类**持有它**转发（不回调控制器）；{@code ensureQuickAnswerStep}
 * 是 static 且执行簇也在用 → 留控制器，按类名引用。</p>
 */
final class QaSseOrchestrator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaSseOrchestrator.class);

    private final StreamManager streamManager;
    private final StreamEventEmitter emitter;
    private final SessionService sessionService;
    private final MessageService messageService;
    private final com.ragagent.session.sse.SseFrameWriter sseFrameWriter;
    private final QaTurnFinalizer turnFinalizer;

    QaSseOrchestrator(StreamManager streamManager, StreamEventEmitter emitter, SessionService sessionService, MessageService messageService, com.ragagent.session.sse.SseFrameWriter sseFrameWriter, QaTurnFinalizer turnFinalizer) {
        this.streamManager = streamManager;
        this.emitter = emitter;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.sseFrameWriter = sseFrameWriter;
        this.turnFinalizer = turnFinalizer;
    }

    SseStreamContext setupSSEStream(QaRequestContext reqCtx, boolean generateTitle, QaMode mode) {
        SseStreamContext streamCtx = new SseStreamContext();
        streamCtx.assistantMessage = reqCtx.assistantMessage;
        streamCtx.tenantSnapshot = com.ragagent.event.TenantContextSnapshot.capture();

        EventBus eventBus = new EventBus();
        streamCtx.eventBus = eventBus;

        // Mid-run steering：仅 agent 模式有引擎排空点（Go L713-731）
        if (mode == QaMode.AGENT && reqCtx.agentConfig != null) {
            SteerSinkBridge sink = new SteerSinkBridge(reqCtx.sessionId, reqCtx.requestId,
                    messageService, streamManager,
                    com.ragagent.event.TenantContextSnapshot.capture());
            streamCtx.steerSink = sink;
            reqCtx.steerSink = sink;
            try {
                streamManager.setLiveRun(reqCtx.sessionId, reqCtx.assistantMessage.getId(), reqCtx.requestId);
            } catch (RuntimeException e) {
                log.error("SetLiveRun failed for session {}: {}", reqCtx.sessionId, e.toString());
                streamCtx.liveRunFailed = true;
                streamCtx.liveRunErr = e.getMessage();
                streamCtx.liveRunExists = e instanceof com.ragagent.stream.LiveRunExistsException;
                return streamCtx;
            }
        }

        // agent_query 事件写流（writeAgentQueryEvent，helpers.go L418-440）
        writeAgentQueryEvent(reqCtx);

        // stop 事件处理器（setupStopEventHandler，helpers.go L318-338）
        long sessionTenantId = reqCtx.session.getTenantId();
        eventBus.on(EventType.EVENT_STOP, evt -> {
            log.info("Received stop event, cancelling async operations for session: {}", reqCtx.sessionId);
            streamCtx.cancelled = true;
            // 停止时保住已流出内容；用 session 租户落库
            turnFinalizer.runWithTenant(sessionTenantId, () -> turnFinalizer.completeAssistantMessage(
                    streamCtx.assistantMessage, "", "", sessionTenantId));
        });

        // 独立 stop watcher（helpers.go L364-415；自终止：complete/终态错误）
        startStopWatcher(reqCtx.sessionId, reqCtx.assistantMessage.getId(), eventBus);

        // AgentStreamBridge 订阅（17 种事件）
        AgentStreamBridge bridge = new AgentStreamBridge(reqCtx.sessionId, reqCtx.assistantMessage.getId(),
                reqCtx.requestId, sessionTenantId, OffsetDateTime.now(), reqCtx.assistantMessage,
                streamManager, eventBus);
        bridge.subscribe();

        // title 生成（GenerateTitleAsync：session title 为空时；2026-09-23 走查批接线）
        if (generateTitle && (reqCtx.session.getTitle() == null || reqCtx.session.getTitle().isEmpty())) {
            String modelId = "";
            if (reqCtx.agentConfig != null) {
                modelId = reqCtx.agentConfig.path("modelId").asText("");
            }
            log.info("Session has no title, starting async title generation, session ID: {}, model: {}",
                    reqCtx.sessionId, modelId);
            sessionService.generateTitleAsync(reqCtx.session, reqCtx.query, modelId, eventBus);
        }
        return streamCtx;
    }
    private void writeAgentQueryEvent(QaRequestContext reqCtx) {
        String assistantMessageId = reqCtx.assistantMessage == null ? "" : reqCtx.assistantMessage.getId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", reqCtx.sessionId);
        data.put("assistant_message_id", assistantMessageId);
        if (!reqCtx.userMessageID.isEmpty()) {
            data.put("user_message_id", reqCtx.userMessageID);
        }
        if (reqCtx.userCreatedAt != null) {
            data.put("user_created_at", reqCtx.userCreatedAt.toInstant().toString());
        }
        if (reqCtx.assistantMessage != null && reqCtx.assistantMessage.getCreatedAt() != null) {
            data.put("assistant_created_at", reqCtx.assistantMessage.getCreatedAt().toInstant().toString());
        }
        StreamEvent evt = new StreamEvent();
        evt.setId("query-" + System.nanoTime());
        evt.setType(ResponseType.AGENT_QUERY);
        evt.setContent("");
        evt.setDone(true);
        evt.setTimestamp(OffsetDateTime.now());
        evt.setData(data);
        try {
            streamManager.appendEvent(reqCtx.sessionId, assistantMessageId, evt);
        } catch (RuntimeException e) {
            log.error("write agent query event failed session={} message={}: {}",
                    reqCtx.sessionId, assistantMessageId, e.toString());
        }
    }
    /** startStopWatcher（helpers.go L364-415）。 */
    private void startStopWatcher(String sessionId, String assistantMessageId, EventBus eventBus) {
        Thread.ofVirtual().start(() -> {
            int offset = 0;
            long deadline = System.currentTimeMillis() + 2 * 60 * 60 * 1000L; // stopWatcherMaxDuration
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                StreamBatch batch;
                try {
                    batch = streamManager.getEvents(sessionId, assistantMessageId, offset);
                } catch (RuntimeException e) {
                    continue; // 瞬态读错误，下一 tick 重试
                }
                offset = batch.nextOffset();
                for (StreamEvent evt : batch.events()) {
                    if (evt.getType() == ResponseType.STOP) {
                        log.info("Stop watcher detected stop event, cancelling generation for session={}, message={}",
                                sessionId, assistantMessageId);
                        Event stopEvt = new Event();
                        stopEvt.setType(EventType.EVENT_STOP);
                        stopEvt.setSessionId(sessionId);
                        com.ragagent.event.payload.StopData data = new com.ragagent.event.payload.StopData();
                        data.setSessionId(sessionId);
                        data.setMessageId(assistantMessageId);
                        data.setReason("user_requested");
                        stopEvt.setData(data);
                        eventBus.emit(stopEvt);
                        return;
                    }
                    if (evt.getType() == ResponseType.COMPLETE) {
                        return;
                    }
                    if (evt.getType() == ResponseType.ERROR && evt.isDone()) {
                        return;
                    }
                }
            }
        });
    }
    void handleAgentEventsForSSE(HttpServletResponse response, String sessionId,
            String assistantMessageId, String requestId, SseStreamContext streamCtx,
            boolean waitForTitle, StreamRewriter resourceRewriter) throws IOException {
        SseContract.setSSEHeaders(response);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        StreamEventEmitter.ClientState client = clientGone::get;

        int lastOffset = 0;
        log.info("Starting pull-based SSE streaming for session={}, message={}", sessionId, assistantMessageId);

        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            StreamBatch batch;
            try {
                batch = streamManager.getEvents(sessionId, assistantMessageId, lastOffset);
            } catch (RuntimeException e) {
                log.warn("Failed to get events from stream: {}", e.toString());
                continue;
            }
            boolean streamCompleted = false;
            boolean titleReceived = false;
            for (StreamEvent evt : batch.events()) {
                if (evt.getType() == ResponseType.STOP) {
                    log.info("Detected stop event, session={}", sessionId);
                    if (streamCtx.eventBus != null) {
                        Event stopEvt = new Event();
                        stopEvt.setType(EventType.EVENT_STOP);
                        stopEvt.setSessionId(sessionId);
                        com.ragagent.event.payload.StopData data = new com.ragagent.event.payload.StopData();
                        data.setSessionId(sessionId);
                        data.setMessageId(assistantMessageId);
                        data.setReason("user_requested");
                        stopEvt.setData(data);
                        streamCtx.eventBus.emit(stopEvt);
                    }
                    try {
                        emitter.flushHeldStreamContent(response, requestId, resourceRewriter, client);
                    } catch (IOException ignored) {
                        // 客户端已断开
                    }
                    // 对照 Go c.SSEvent("message", &StreamResponse{ResponseType: "stop", ...})
                    com.ragagent.llm.domain.StreamResponse stopResp = new com.ragagent.llm.domain.StreamResponse();
                    stopResp.setId(requestId);
                    stopResp.setResponseType(ResponseType.STOP);
                    stopResp.setContent("Generation stopped by user");
                    stopResp.setDone(true);
                    sseFrameWriter.write(response, stopResp);
                    return;
                }
                if (evt.getType() == ResponseType.COMPLETE) {
                    streamCompleted = true;
                }
                if (evt.getType() == ResponseType.SESSION_TITLE) {
                    titleReceived = true;
                }
                try {
                    emitter.emitStreamEvent(response, evt, requestId, resourceRewriter, client);
                } catch (IOException e) {
                    clientGone.set(true);
                    log.info("Connection closed during event sending, stopping");
                    return;
                }
            }
            lastOffset = batch.nextOffset();
            if (streamCompleted) {
                if (waitForTitle && !titleReceived) {
                    log.info("Stream completed, waiting for title event");
                    long titleDeadline = System.currentTimeMillis() + 3000;
                    titleWait:
                    while (System.currentTimeMillis() < titleDeadline) {
                        StreamBatch titleBatch;
                        try {
                            titleBatch = streamManager.getEvents(sessionId, assistantMessageId, lastOffset);
                        } catch (RuntimeException e) {
                            break titleWait;
                        }
                        if (!titleBatch.events().isEmpty()) {
                            for (StreamEvent evt : titleBatch.events()) {
                                try {
                                    emitter.emitStreamEvent(response, evt, requestId, resourceRewriter, client);
                                } catch (IOException e) {
                                    clientGone.set(true);
                                    return;
                                }
                                if (evt.getType() == ResponseType.SESSION_TITLE) {
                                    break titleWait;
                                }
                            }
                            lastOffset = titleBatch.nextOffset();
                        }
                    }
                }
                SseContract.sendCompletionEvent(response, requestId);
                return;
            }
        }
    }
    private static final Set<String> QUICK_ANSWER_TIMELINE_TOOLS = Set.of(
            "query_understand", "knowledge_search", "attachment_parsing", "image_analysis");
    void registerQuickAnswerTimelineRecorder(EventBus bus, Message msg) {
        if (bus == null || msg == null) {
            return;
        }
        Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
        Map<String, Long> startedAt = new LinkedHashMap<>();
        bus.on(EventType.EVENT_AGENT_TOOL_CALL, evt -> {
            if (evt.getData() instanceof AgentToolCallData data
                    && QUICK_ANSWER_TIMELINE_TOOLS.contains(data.getToolName())
                    && !data.getToolCallId().isEmpty()) {
                synchronized (msg) {
                    pending.put(data.getToolCallId(), data.getArguments());
                    startedAt.put(data.getToolCallId(), System.currentTimeMillis());
                }
            }
        });
        bus.on(EventType.EVENT_AGENT_TOOL_RESULT, evt -> {
            if (evt.getData() instanceof AgentToolResultData data
                    && QUICK_ANSWER_TIMELINE_TOOLS.contains(data.getToolName())
                    && !data.getToolCallId().isEmpty()) {
                synchronized (msg) {
                    Map<String, Object> args = pending.remove(data.getToolCallId());
                    Long started = startedAt.remove(data.getToolCallId());
                    long duration = data.getDurationMs();
                    if (duration == 0 && started != null) {
                        duration = System.currentTimeMillis() - started;
                    }
                    com.ragagent.agent.domain.ToolCall call = new com.ragagent.agent.domain.ToolCall();
                    call.setId("pipeline:" + data.getToolCallId());
                    call.setName(data.getToolName());
                    call.setArgs(args);
                    com.ragagent.common.llm.ToolResult result = new com.ragagent.common.llm.ToolResult();
                    result.setSuccess(data.isSuccess());
                    result.setOutput(data.getOutput());
                    result.setError(data.getError());
                    result.setData(data.getData());
                    call.setResult(result);
                    call.setDuration(duration);
                    appendQuickAnswerToolCall(msg, call);
                }
            }
        });
    }
    private static void appendQuickAnswerToolCall(Message msg, com.ragagent.agent.domain.ToolCall call) {
        AgentStep step = KnowledgeQaController.ensureQuickAnswerStep(msg);
        if (step.getToolCalls() != null) {
            for (int i = 0; i < step.getToolCalls().size(); i++) {
                if (step.getToolCalls().get(i).getId().equals(call.getId())) {
                    step.getToolCalls().set(i, call);
                    return;
                }
            }
        }
        if (step.getToolCalls() == null) {
            step.setToolCalls(new ArrayList<>());
        }
        step.getToolCalls().add(call);
    }
}
