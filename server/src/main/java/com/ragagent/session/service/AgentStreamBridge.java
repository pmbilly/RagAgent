package com.ragagent.session.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.tools.ToolResultPersist;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.event.AgentCompleteData;
import com.ragagent.event.AgentFinalAnswerData;
import com.ragagent.event.AgentReflectionData;
import com.ragagent.event.AgentReferencesData;
import com.ragagent.event.AgentThoughtData;
import com.ragagent.event.AgentToolCallData;
import com.ragagent.event.AgentToolResultData;
import com.ragagent.event.ContextCompactedData;
import com.ragagent.event.ErrorData;
import com.ragagent.event.MCPOAuthRequiredData;
import com.ragagent.event.MCPOAuthResolvedData;
import com.ragagent.event.MemoryRecalledData;
import com.ragagent.event.SessionTitleData;
import com.ragagent.event.ToolApprovalRequiredData;
import com.ragagent.event.ToolApprovalResolvedData;
import com.ragagent.event.UserMessageInjectedData;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.UsedMemory;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * agent 事件订阅桥（对照 Go internal/handler/session/agent_stream_handler.go 全文）。
 *
 * <p>每个请求一条专属 EventBus（无 SessionID 过滤），事件按到达序 AppendEvent 进
 * StreamManager（不做累积——前端按 event id 累积）。</p>
 *
 * <h2>17 种事件订阅 + final_answer 分片重组（最高危，逐条对照 Go）</h2>
 * <ul>
 *   <li><b>superseded preamble 剔除</b>：一次非终局轮可能在它自己的 answer event id
 *       下流出一段前导（"让我搜一下…"），随后该轮决定调工具 → 这些段被标 superseded，
 *       不再进持久化的 Message.Content。tool_call 首次到达时统一标记。</li>
 *   <li><b>duration 记账</b>：thought/final_answer 用 evt.ID 记首 chunk 时间；
 *       tool_call/tool_result 用 ToolCallID。</li>
 *   <li><b>complete 事件</b>：usage 恒取（Go 的 typed-nil 语义在 4.6b 已定：
 *       Java 用 NullNode/instanceof 判别）；agent_steps 经 Sanitize 落库；
 *       finalAnswer 为空但有 FinalAnswer 时补发 fallback answer 事件对。</li>
 * </ul>
 *
 */
public final class AgentStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamBridge.class);

    private final String sessionId;
    /** Tenant that owns this session; used when persisting skill artifacts. */
    private final long tenantId;
    private final String assistantMessageId;
    private final String requestId;
    /** Handler entry timestamp, used for TTFB logging */
    private final OffsetDateTime receivedAt;
    private boolean ttfbLogged;
    private final Message assistantMessage;
    private final StreamManager streamManager;
    private final EventBus eventBus;
    // ---- State tracking ----
    private final List<SearchResult> knowledgeRefs = new ArrayList<>();
    private String finalAnswer = "";
    /** Per-answer-event-ID accumulation, so superseded preambles can be dropped */
    private final List<AnswerSegment> answerSegments = new ArrayList<>();
    /** Track start time for duration calculation */
    private final Map<String, Long> eventStartTimes = new LinkedHashMap<>();
    private final Object mu = new Object();

    /** 对照 answerSegment（agent_stream_handler.go L54-58）。 */
    private static final class AnswerSegment {
        final String id;
        String content = "";
        boolean superseded;

        AnswerSegment(String id) {
            this.id = id;
        }
    }

    private AnswerSegment findAnswerSegment(String id) {
        for (AnswerSegment seg : answerSegments) {
            if (seg.id.equals(id)) {
                return seg;
            }
        }
        return null;
    }

    /** 对照 composeFinalAnswer：所有未 superseded 的段按到达序重组。 */
    private String composeFinalAnswer() {
        StringBuilder b = new StringBuilder();
        for (AnswerSegment seg : answerSegments) {
            if (!seg.superseded) {
                b.append(seg.content);
            }
        }
        return b.toString();
    }

    public AgentStreamBridge(
            String sessionId, String assistantMessageId, String requestId,
            long tenantId, OffsetDateTime receivedAt, Message assistantMessage,
            StreamManager streamManager, EventBus eventBus) {
        this.sessionId = sessionId;
        this.assistantMessageId = assistantMessageId;
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.receivedAt = receivedAt;
        this.assistantMessage = assistantMessage;
        this.streamManager = streamManager;
        this.eventBus = eventBus;
    }

    public Message getAssistantMessage() {
        return assistantMessage;
    }

    /**
     * 对照 emitArtifactsPending（agent_stream_handler.go L841-858）：告知活 UI
     * 沙箱有文件正在上传。count ≤ 0 直接跳过。
     */
    private void emitArtifactsPending(int count) {
        if (count <= 0) {
            return;
        }
        StreamEvent event = new StreamEvent();
        event.setId("artifacts-pending-" + System.currentTimeMillis());
        event.setType(ResponseType.ARTIFACTS_PENDING);
        event.setTimestamp(OffsetDateTime.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        event.setData(data);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, event);
        } catch (RuntimeException e) {
            log.warn("append artifacts_pending failed session={} message={}: {}",
                    sessionId, assistantMessageId, e.toString());
        }
    }

    /** 对照 Subscribe：17 种事件订阅序逐字对齐（订阅序即回调序，Go 按此序 On）。 */
    public void subscribe() {
        eventBus.on(EventType.EVENT_AGENT_THOUGHT, this::handleThought);
        eventBus.on(EventType.EVENT_AGENT_TOOL_CALL, this::handleToolCall);
        eventBus.on(EventType.EVENT_AGENT_TOOL_RESULT, this::handleToolResult);
        eventBus.on(EventType.EVENT_AGENT_REFERENCES, this::handleReferences);
        eventBus.on(EventType.EVENT_MEMORY_RECALLED, this::handleMemoryRecalled);
        eventBus.on(EventType.EVENT_CONTEXT_COMPACTED, this::handleContextCompacted);
        eventBus.on(EventType.EVENT_USER_MESSAGE_INJECTED, this::handleUserMessageInjected);
        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, this::handleFinalAnswer);
        eventBus.on(EventType.EVENT_AGENT_REFLECTION, this::handleReflection);
        eventBus.on(EventType.EVENT_ERROR, this::handleError);
        eventBus.on(EventType.EVENT_SESSION_TITLE, this::handleSessionTitle);
        eventBus.on(EventType.EVENT_AGENT_COMPLETE, this::handleComplete);
        eventBus.on(EventType.EVENT_TOOL_APPROVAL_REQUIRED, this::handleToolApprovalRequired);
        eventBus.on(EventType.EVENT_TOOL_APPROVAL_RESOLVED, this::handleToolApprovalResolved);
        eventBus.on(EventType.EVENT_MCP_OAUTH_REQUIRED, this::handleMCPOAuthRequired);
        eventBus.on(EventType.EVENT_MCP_OAUTH_RESOLVED, this::handleMCPOAuthResolved);
    }

    // ── handleThought（Go L134-179） ─────────────────────────────────────────

    private Object handleThought(Event evt) {
        if (!(evt.getData() instanceof AgentThoughtData data)) {
            return null;
        }
        Map<String, Object> metadata;
        synchronized (mu) {
            eventStartTimes.putIfAbsent(evt.getId(), System.currentTimeMillis());
            if (data.isDone()) {
                long startTime = eventStartTimes.getOrDefault(evt.getId(), System.currentTimeMillis());
                long duration = System.currentTimeMillis() - startTime;
                metadata = new LinkedHashMap<>();
                metadata.put("event_id", evt.getId());
                metadata.put("duration_ms", duration);
                metadata.put("completed_at", System.currentTimeMillis() / 1000L);
                eventStartTimes.remove(evt.getId());
            } else {
                metadata = new LinkedHashMap<>();
                metadata.put("event_id", evt.getId());
            }
        }
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.THINKING);
        se.setContent(orEmpty(data.getContent()));
        se.setDone(data.isDone());
        se.setTimestamp(OffsetDateTime.now());
        se.setData(metadata);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append thought event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleToolCall（Go L182-228，含 superseded preamble 剔除） ────────────

    private Object handleToolCall(Event evt) {
        if (!(evt.getData() instanceof AgentToolCallData data)) {
            return null;
        }
        synchronized (mu) {
            boolean first = !eventStartTimes.containsKey(data.getToolCallId());
            if (first) {
                eventStartTimes.put(data.getToolCallId(), System.currentTimeMillis());
                // Any answer text streamed before this tool call was a non-terminal round's
                // preamble, not the final answer. Drop those segments from the persisted
                // answer so the preamble never leaks into Message.Content.
                boolean supersededAny = false;
                for (AnswerSegment seg : answerSegments) {
                    if (!seg.superseded && !seg.content.isEmpty()) {
                        seg.superseded = true;
                        supersededAny = true;
                    }
                }
                if (supersededAny) {
                    finalAnswer = composeFinalAnswer();
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_name", data.getToolName());
        metadata.put("arguments", data.getArguments());
        metadata.put("tool_call_id", data.getToolCallId());

        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.TOOL_CALL);
        se.setContent("Calling tool: " + data.getToolName());
        se.setDone(false);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(metadata);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append tool call event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleToolResult（Go L231-291） ──────────────────────────────────────

    private Object handleToolResult(Event evt) {
        if (!(evt.getData() instanceof AgentToolResultData data)) {
            return null;
        }
        long durationMs;
        synchronized (mu) {
            Long startTime = eventStartTimes.get(data.getToolCallId());
            if (startTime != null) {
                durationMs = System.currentTimeMillis() - startTime;
                eventStartTimes.remove(data.getToolCallId());
            } else if (data.getDurationMs() > 0) {
                // Fallback to provided duration if start time not tracked
                durationMs = data.getDurationMs();
            } else {
                durationMs = 0;
            }
        }

        // Send SSE response (both success and failure)
        ResponseType responseType = ResponseType.TOOL_RESULT;
        Map<String, Object> resultData = data.getData();
        String content = ToolResultPersist.streamContentForToolResult(
                data.getToolName(), data.isSuccess(), data.getError(), resultData);
        if (!data.isSuccess()) {
            responseType = ResponseType.ERROR;
            if (content.isEmpty() && data.getError() != null && !data.getError().isEmpty()) {
                content = data.getError();
            }
        }

        // Build metadata including tool result data for rich frontend rendering
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_name", data.getToolName());
        metadata.put("success", data.isSuccess());
        metadata.put("error", data.getError());
        metadata.put("duration_ms", durationMs);
        metadata.put("tool_call_id", data.getToolCallId());

        ToolResult tr = new ToolResult();
        tr.setSuccess(data.isSuccess());
        tr.setOutput(data.getOutput());
        tr.setError(data.getError());
        tr.setData(resultData);
        Map<String, Object> clientData = ToolResultPersist.sanitizeToolResultForClient(data.getToolName(), tr);
        metadata.putAll(clientData);

        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(responseType);
        se.setContent(content);
        se.setDone(false);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(metadata);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append tool result event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── 审批 / OAuth（Go L305-388；#1173） ───────────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper GO_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static Map<String, Object> toolApprovalDataToMap(Object v) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = GO_JSON.convertValue(v,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return m == null ? new LinkedHashMap<>() : m;
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private Object handleToolApprovalRequired(Event evt) {
        if (!(evt.getData() instanceof ToolApprovalRequiredData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pending_id", data.getPendingId());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.TOOL_APPROVAL_REQUIRED);
        se.setContent("MCP tool requires human approval");
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(meta);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append tool approval required event failed: {}", e.toString());
        }
        return null;
    }

    private Object handleToolApprovalResolved(Event evt) {
        if (!(evt.getData() instanceof ToolApprovalResolvedData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pending_id", data.getPendingId());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.TOOL_APPROVAL_RESOLVED);
        se.setContent("MCP tool approval resolved");
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(meta);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append tool approval resolved event failed: {}", e.toString());
        }
        return null;
    }

    private Object handleMCPOAuthRequired(Event evt) {
        if (!(evt.getData() instanceof MCPOAuthRequiredData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pending_id", data.getPendingId());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.MCP_OAUTH_REQUIRED);
        se.setContent("MCP service requires OAuth authorization");
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(meta);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append mcp oauth required event failed: {}", e.toString());
        }
        return null;
    }

    private Object handleMCPOAuthResolved(Event evt) {
        if (!(evt.getData() instanceof MCPOAuthResolvedData data)) {
            return null;
        }
        Map<String, Object> meta = toolApprovalDataToMap(data);
        meta.put("pending_id", data.getPendingId());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.MCP_OAUTH_RESOLVED);
        se.setContent("MCP OAuth authorization resolved");
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(meta);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append mcp oauth resolved event failed: {}", e.toString());
        }
        return null;
    }

    // ── handleReferences（Go L391-434） ──────────────────────────────────────

    private Object handleReferences(Event evt) {
        if (!(evt.getData() instanceof AgentReferencesData data)) {
            return null;
        }
        synchronized (mu) {
            // Extract knowledge references（Java：List<SearchResult> 直 cast，Map 回退重建）
            if (data.getReferences() instanceof List<?> refs) {
                for (Object ref : refs) {
                    if (ref instanceof SearchResult sr) {
                        knowledgeRefs.add(sr);
                    } else if (ref instanceof Map<?, ?> refMap) {
                        knowledgeRefs.add(searchResultFromMap(refMap));
                    }
                }
            }
            assistantMessage.setKnowledgeReferences(knowledgeRefs);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("references", List.copyOf(knowledgeRefs));

        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.REFERENCES);
        se.setContent("");
        se.setDone(false);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(payload);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append references event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleMemoryRecalled（Go L439-463） ──────────────────────────────────

    private Object handleMemoryRecalled(Event evt) {
        if (!(evt.getData() instanceof MemoryRecalledData data)) {
            return null;
        }
        if (!(data.getMemories() instanceof List<?> used) || used.isEmpty()) {
            return null;
        }
        synchronized (mu) {
            List<UsedMemory> typed = new ArrayList<>();
            for (Object item : used) {
                if (item instanceof UsedMemory um) {
                    typed.add(um);
                }
            }
            assistantMessage.setUsedMemories(typed);
        }
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.MEMORY_RECALLED);
        se.setDone(false);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(Map.of("memories", data.getMemories()));
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append memory recalled event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleContextCompacted（Go L470-496） ────────────────────────────────

    private Object handleContextCompacted(Event evt) {
        if (!(evt.getData() instanceof ContextCompactedData data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", data.getReason());
        payload.put("round", data.getRound());
        payload.put("tokens_before", data.getTokensBefore());
        payload.put("tokens_after", data.getTokensAfter());
        payload.put("messages_before", data.getMessagesBefore());
        payload.put("messages_after", data.getMessagesAfter());
        payload.put("summary", data.getSummary());
        payload.put("degraded", data.isDegraded());
        payload.put("split_turn", data.isSplitTurn());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.CONTEXT_COMPACTED);
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(payload);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append context compacted event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleFinalAnswer（Go L499-572，event-id 分片重组） ───────────────────

    private Object handleFinalAnswer(Event evt) {
        if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
            return null;
        }
        Map<String, Object> metadata;
        synchronized (mu) {
            eventStartTimes.putIfAbsent(evt.getId(), System.currentTimeMillis());

            // One-shot TTFB log on the first answer chunk.
            if (!ttfbLogged && receivedAt != null) {
                ttfbLogged = true;
                long ttfb = System.currentTimeMillis() - receivedAt.toInstant().toEpochMilli();
                log.info("TTFB:first_answer_chunk request_id={}, session_id={}, ttfb_ms={}",
                        requestId, sessionId, ttfb);
            }

            // Accumulate per event ID so a later supersede can subtract this segment.
            if (data.getContent() != null && !data.getContent().isEmpty()) {
                AnswerSegment seg = findAnswerSegment(evt.getId());
                if (seg == null) {
                    seg = new AnswerSegment(evt.getId());
                    answerSegments.add(seg);
                }
                seg.content += data.getContent();
                finalAnswer = composeFinalAnswer();
            }
            if (data.isFallback()) {
                assistantMessage.setFallback(true);
            }

            if (data.isDone()) {
                long startTime = eventStartTimes.getOrDefault(evt.getId(), System.currentTimeMillis());
                long duration = System.currentTimeMillis() - startTime;
                metadata = new LinkedHashMap<>();
                metadata.put("event_id", evt.getId());
                metadata.put("duration_ms", duration);
                metadata.put("completed_at", System.currentTimeMillis() / 1000L);
                eventStartTimes.remove(evt.getId());
            } else {
                metadata = new LinkedHashMap<>();
                metadata.put("event_id", evt.getId());
            }
            if (data.isFallback()) {
                metadata.put("is_fallback", true);
            }
        }
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.ANSWER);
        se.setContent(orEmpty(data.getContent()));
        se.setDone(data.isDone());
        se.setTimestamp(OffsetDateTime.now());
        se.setData(metadata);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append answer event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleReflection（Go L575-593） ──────────────────────────────────────

    private Object handleReflection(Event evt) {
        if (!(evt.getData() instanceof AgentReflectionData data)) {
            return null;
        }
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.REFLECTION);
        se.setContent(orEmpty(data.getContent()));
        se.setDone(data.isDone());
        se.setTimestamp(OffsetDateTime.now());
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append reflection event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleError（Go L596-621） ───────────────────────────────────────────

    private Object handleError(Event evt) {
        if (!(evt.getData() instanceof ErrorData data)) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", data.getStage());
        metadata.put("error", data.getError());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.ERROR);
        se.setContent(orEmpty(data.getError()));
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(metadata);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append error event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleSessionTitle（Go L624-649） ────────────────────────────────────

    private Object handleSessionTitle(Event evt) {
        if (!(evt.getData() instanceof SessionTitleData data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", data.getSessionId());
        payload.put("title", data.getTitle());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.SESSION_TITLE);
        se.setContent(data.getTitle());
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(payload);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.warn("Append session title event to stream failed (stream may have ended): {}", e.toString());
        }
        return null;
    }

    // ── handleUserMessageInjected（Go L655-676） ─────────────────────────────

    private Object handleUserMessageInjected(Event evt) {
        if (!(evt.getData() instanceof UserMessageInjectedData data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("steer_id", data.getSteerId());
        payload.put("message_id", data.getMessageId());
        payload.put("content", data.getContent());
        payload.put("user_message_id", data.getUserMessageId());
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.USER_MESSAGE_INJECTED);
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(payload);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append user message injected event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── handleComplete（Go L679-836） ────────────────────────────────────────

    private Object handleComplete(Event evt) {
        if (!(evt.getData() instanceof AgentCompleteData data)) {
            return null;
        }
        synchronized (mu) {
            if (assistantMessageId.equals(data.getMessageId())) {
                assistantMessage.setCompleted(true);
                assistantMessage.setAgentDurationMs(data.getTotalDurationMs());

                // Update knowledge references if provided
                if (data.getKnowledgeRefs() != null && !data.getKnowledgeRefs().isEmpty()) {
                    List<SearchResult> refs = new ArrayList<>();
                    for (Object ref : data.getKnowledgeRefs()) {
                        if (ref instanceof SearchResult sr) {
                            refs.add(sr);
                        }
                    }
                    assistantMessage.setKnowledgeReferences(refs);
                }

                assistantMessage.setContent(assistantMessage.getContent() + orEmpty(data.getFinalAnswer()));

                // Update agent steps if provided
                if (data.getAgentSteps() instanceof List<?> rawSteps) {
                    List<AgentStep> steps = new ArrayList<>();
                    for (Object raw : rawSteps) {
                        if (raw instanceof AgentStep as) {
                            steps.add(as);
                        }
                    }
                    assistantMessage.setAgentSteps(ToolResultPersist.sanitizeAgentStepsForStorage(steps));
                }

                // Persist the turn's aggregated LLM usage（NullNode/空 = 无用量）
                if (data.getUsage() instanceof TokenUsage usage) {
                    assistantMessage.setUsage(usage);
                }
            }

            // Fallback: no answer events streamed but a final answer exists → emit answer pair.
            if (finalAnswer.isEmpty() && data.getFinalAnswer() != null && !data.getFinalAnswer().isEmpty()) {
                log.warn("No answer events were streamed, emitting fallback answer (len={}). "
                        + "This typically happens when: (1) model stopped naturally and content was sent as "
                        + "thought events, or (2) Ollama model returned tool calls non-incrementally. "
                        + "total_steps={}, total_duration_ms={}",
                        data.getFinalAnswer().length(), data.getTotalSteps(), data.getTotalDurationMs());
                String fallbackId = "answer-fallback-" + System.currentTimeMillis();
                StreamEvent first = new StreamEvent();
                first.setId(fallbackId);
                first.setType(ResponseType.ANSWER);
                first.setContent(data.getFinalAnswer());
                first.setDone(false);
                first.setTimestamp(OffsetDateTime.now());
                Map<String, Object> d1 = new LinkedHashMap<>();
                d1.put("event_id", fallbackId);
                d1.put("is_fallback", true);
                first.setData(d1);
                StreamEvent second = new StreamEvent();
                second.setId(fallbackId);
                second.setType(ResponseType.ANSWER);
                second.setContent("");
                second.setDone(true);
                second.setTimestamp(OffsetDateTime.now());
                second.setData(new LinkedHashMap<>(d1));
                try {
                    streamManager.appendEvent(sessionId, assistantMessageId, first);
                    streamManager.appendEvent(sessionId, assistantMessageId, second);
                } catch (RuntimeException e) {
                    log.error("Append fallback answer event failed: {}", e.toString());
                }
            }
        }

        // Completion event for the stream manager（SSE 据此收流）
        Map<String, Object> completeData = new LinkedHashMap<>();
        completeData.put("total_steps", data.getTotalSteps());
        completeData.put("total_duration_ms", data.getTotalDurationMs());
        completeData.put("final_content", assistantMessage.getContent());
        TokenUsage turnUsage = data.getUsage() instanceof TokenUsage u ? u : null;
        if (turnUsage != null) {
            completeData.put("usage", turnUsage);
        }
        StreamEvent se = new StreamEvent();
        se.setId(evt.getId());
        se.setType(ResponseType.COMPLETE);
        se.setContent("");
        se.setDone(true);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(completeData);
        se.setUsage(turnUsage);
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, se);
        } catch (RuntimeException e) {
            log.error("Append complete event to stream failed: {}", e.toString());
        }
        return null;
    }

    // ── searchResultFromMap（helpers.go L467-508 的等价实现，见 StreamResponseBuilder 同款） ──

    private static SearchResult searchResultFromMap(Map<?, ?> refMap) {
        SearchResult sr = new SearchResult();
        sr.setId(getString(refMap, "id"));
        sr.setContent(getString(refMap, "content"));
        sr.setKnowledgeId(getString(refMap, "knowledge_id"));
        sr.setChunkIndex((int) getFloat64(refMap, "chunk_index"));
        sr.setKnowledgeTitle(getString(refMap, "knowledge_title"));
        sr.setStartAt((int) getFloat64(refMap, "start_at"));
        sr.setEndAt((int) getFloat64(refMap, "end_at"));
        sr.setSeq((int) getFloat64(refMap, "seq"));
        sr.setScore(getFloat64(refMap, "score"));
        sr.setChunkType(getString(refMap, "chunk_type"));
        sr.setParentChunkId(getString(refMap, "parent_chunk_id"));
        sr.setImageInfo(getString(refMap, "image_info"));
        sr.setKnowledgeFilename(getString(refMap, "knowledge_filename"));
        sr.setKnowledgeSource(getString(refMap, "knowledge_source"));
        sr.setKnowledgeDescription(getString(refMap, "knowledge_description"));
        sr.setKnowledgeBaseId(getString(refMap, "knowledge_base_id"));
        if (refMap.get("metadata") instanceof Map<?, ?> meta) {
            Map<String, String> metadata = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : meta.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    metadata.put(key, value);
                }
            }
            sr.setMetadata(metadata);
        }
        return sr;
    }

    private static String getString(Map<?, ?> m, String key) {
        Object val = m.get(key);
        return val instanceof String s ? s : "";
    }

    private static double getFloat64(Map<?, ?> m, String key) {
        Object val = m.get(key);
        return val instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 供 executeQA 的「无 answer 事件但有最终答案」判定读取（Go 直接读字段）。 */
    public String composedFinalAnswer() {
        synchronized (mu) {
            return finalAnswer;
        }
    }

    /** 已订阅事件类型数（自检用，17 = Go 的订阅数）。 */
    public static Set<String> subscribedEventTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add(EventType.EVENT_AGENT_THOUGHT);
        types.add(EventType.EVENT_AGENT_TOOL_CALL);
        types.add(EventType.EVENT_AGENT_TOOL_RESULT);
        types.add(EventType.EVENT_AGENT_REFERENCES);
        types.add(EventType.EVENT_MEMORY_RECALLED);
        types.add(EventType.EVENT_CONTEXT_COMPACTED);
        types.add(EventType.EVENT_USER_MESSAGE_INJECTED);
        types.add(EventType.EVENT_AGENT_FINAL_ANSWER);
        types.add(EventType.EVENT_AGENT_REFLECTION);
        types.add(EventType.EVENT_ERROR);
        types.add(EventType.EVENT_SESSION_TITLE);
        types.add(EventType.EVENT_AGENT_COMPLETE);
        types.add(EventType.EVENT_TOOL_APPROVAL_REQUIRED);
        types.add(EventType.EVENT_TOOL_APPROVAL_RESOLVED);
        types.add(EventType.EVENT_MCP_OAUTH_REQUIRED);
        types.add(EventType.EVENT_MCP_OAUTH_RESOLVED);
        return types;
    }
}
