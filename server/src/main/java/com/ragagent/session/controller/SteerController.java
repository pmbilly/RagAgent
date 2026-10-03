package com.ragagent.session.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.session.domain.MentionedItem;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.SessionService;
import com.ragagent.stream.LiveRun;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行中轮次的中途消息（steer）HTTP 层。
 *
 * steer 事件落在 StreamManager 的独立子列表上，永不出现在用户可见的 SSE 流里；
 * 本层只覆盖 HTTP 面（排队/列表/删除/提升）——引擎侧轮询与 follow-up
 * 交接在 SteerRunCoordinator / SteerSinkBridge。
 *
 * 关键契约：delivery 缺省 after（注入是显式 opt-in）；队列深度按未消费条数计
 * （max 10）；query 上限 10000 rune；live run 指向的消息已完成时清理并视为无 run；
 * consumed 标记在事件 data 上（跨副本一致）；503=活 turn 查询失败（可重试），
 * 409=活轮已切换/steer_id 被占用。
 */
@RestController
public class SteerController {

    private static final Logger log = LoggerFactory.getLogger(SteerController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final String DELIVERY_INJECT = "inject";
    static final String DELIVERY_AFTER = "after";
    static final String DATA_CONSUMED = "consumed";
    static final int MAX_STEER_QUEUE_DEPTH = 10;
    static final int MAX_STEER_QUERY_LENGTH = 10000;

    private final SessionService sessionService;
    private final MessageService messageService;
    private final StreamManager streamManager;

    public SteerController(SessionService sessionService, MessageService messageService,
                           StreamManager streamManager) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.streamManager = streamManager;
    }

    @PostMapping("/api/v1/sessions/{session_id}/steer")
    public ResponseEntity<Map<String, Object>> steerMessage(
            @PathVariable("session_id") String sessionId,
            @RequestBody(required = false) String rawBody) {
        String sid = LogSanitizer.sanitize(sessionId);
        if (sid.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        SteerMessageRequest req = bindBody(rawBody);
        // ⚠️ 顺序有语义：required 校验先于 trim——空串/缺失落绑定校验原文，
        // 只有纯空白才走到 handler 里的 "query must not be empty"
        if (req.query() == null || req.query().isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'SteerMessageRequest.Query' Error:Field validation for 'Query' "
                            + "failed on the 'required' tag"));
        }
        String query = req.query().trim();
        if (query.isEmpty()) {
            throw new BizException(AppError.badRequest("query must not be empty"));
        }
        if (query.codePointCount(0, query.length()) > MAX_STEER_QUERY_LENGTH) {
            throw new BizException(AppError.badRequest("query too long"));
        }
        String delivery = parseSteerDelivery(req.delivery());

        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }

        String assistantId = resolveLiveAgentRunOr503(sid);
        String expected = req.expectedAssistantMessageId() == null
                ? "" : req.expectedAssistantMessageId();
        String steerIdIn = req.steerId() == null ? "" : req.steerId();
        if (!steerIdIn.isEmpty() && !expected.isEmpty()) {
            List<StreamEvent> previous =
                    steerEventsOr503(sid, expected, "Failed to look up previous delivery");
            for (StreamEvent delivered : previous) {
                if (delivered.getId().equals(steerIdIn) && isConsumed(delivered)) {
                    if (!delivered.getContent().equals(query)) {
                        throw conflict("steer_id already belongs to another message");
                    }
                    return ok(ordered("status", "already_injected", "steerId", steerIdIn));
                }
            }
        }
        if (!expected.isEmpty() && assistantId != null && !assistantId.isEmpty()
                && !expected.equals(assistantId)) {
            throw conflict("The active turn changed; retry the message");
        }
        if (assistantId == null || assistantId.isEmpty()) {
            return ok(ordered("status", "new_run"));
        }

        List<StreamEvent> existing = steerEventsOr500(sid, assistantId, "Failed to check steer queue");

        String steerId = steerIdIn;
        if (steerId.isEmpty()) {
            steerId = UUID.randomUUID().toString();
        } else if (!isValidUuid(steerId)) {
            throw new BizException(AppError.badRequest("invalid steer_id"));
        }
        for (StreamEvent existingEvent : existing) {
            if (existingEvent.getId().equals(steerId)) {
                if (!existingEvent.getContent().equals(query)) {
                    throw conflict("steer_id already belongs to another message");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("assistantMessageId", assistantId);
                body.put("delivery", deliveryOf(existingEvent));
                body.put("status", "queued");
                body.put("steerId", steerId);
                return ResponseEntity.ok(body);
            }
        }
        int pending = selectBacklog(existing, null).size();
        if (pending >= MAX_STEER_QUEUE_DEPTH) {
            throw new BizException(AppError.badRequest(
                    "too many queued messages for the running turn"));
        }

        StreamEvent evt = steerEvent(steerId, query, req.mentionedItems(), req.channel());
        evt.getData().put("delivery", delivery);
        try {
            streamManager.appendSteerEvents(sid, assistantId, List.of(evt));
        } catch (RuntimeException e) {
            log.error("steer append failed: session={}", sid, e);
            throw BizException.internal("Failed to queue message");
        }

        RebindResult rebind = rebindIfLiveRunMoved(sid, assistantId, evt);
        if ("new_run".equals(rebind.status())) {
            return ok(ordered("status", "new_run"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistantMessageId", rebind.queuedOn());
        body.put("delivery", delivery);
        body.put("status", "queued");
        body.put("steerId", steerId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v1/sessions/{session_id}/steer/{steer_id}/inject")
    public ResponseEntity<Map<String, Object>> promoteSteerMessage(
            @PathVariable("session_id") String sessionId,
            @PathVariable("steer_id") String steerId) {
        String sid = LogSanitizer.sanitize(sessionId);
        if (sid.isEmpty() || steerId == null || steerId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        String assistantId = resolveLiveAgentRunOr503(sid);
        if (assistantId == null || assistantId.isEmpty()) {
            return ok(ordered("status", "new_run"));
        }
        List<StreamEvent> events = steerEventsOr500(sid, assistantId, "Failed to update queued message");
        for (StreamEvent evt : events) {
            if (evt.getId().equals(steerId) && isConsumed(evt)) {
                return ok(ordered("status", "already_injected", "steerId", steerId));
            }
        }
        boolean updated;
        try {
            updated = streamManager.updateSteerEventData(sid, assistantId, steerId,
                    Map.of("delivery", DELIVERY_INJECT));
        } catch (RuntimeException e) {
            log.error("steer update failed: session={} steer={}", sid, steerId, e);
            throw BizException.internal("Failed to update queued message");
        }
        if (!updated) {
            throw BizException.notFound("Queued message not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistantMessageId", assistantId);
        body.put("delivery", DELIVERY_INJECT);
        body.put("status", "queued");
        body.put("steerId", steerId);
        return ResponseEntity.ok(body);
    }

    @GetMapping({"/api/v1/sessions/{id}/steer", "/api/v1/sessions/{session_id}/steer"})
    public ResponseEntity<Map<String, Object>> listSteerMessages(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "session_id", required = false) String sessionIdFallback) {
        String sid = LogSanitizer.sanitize(id != null && !id.isEmpty() ? id : sessionIdFallback);
        if (sid.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        String assistantId = resolveLiveAgentRunOr503(sid);
        if (assistantId == null || assistantId.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", List.of());
            return ResponseEntity.ok(body);
        }
        List<StreamEvent> events = steerEventsOr500(sid, assistantId, "Failed to load queued messages");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assistantMessageId", assistantId);
        body.put("items", pendingQueueItems(events, null));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping({"/api/v1/sessions/{id}/steer/{steer_id}",
            "/api/v1/sessions/{session_id}/steer/{steer_id}"})
    public ResponseEntity<Map<String, Object>> deleteSteerMessage(
            @PathVariable(value = "id", required = false) String id,
            @PathVariable(value = "session_id", required = false) String sessionIdFallback,
            @PathVariable("steer_id") String steerId) {
        String sid = LogSanitizer.sanitize(id != null && !id.isEmpty() ? id : sessionIdFallback);
        if (sid.isEmpty() || steerId == null || steerId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            sessionService.getOwnedSession(sid);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound("Session not found");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        String assistantId = resolveLiveAgentRunOr503(sid);
        if (assistantId == null || assistantId.isEmpty()) {
            return ok(ordered("status", "gone"));
        }
        List<StreamEvent> events = steerEventsOr500(sid, assistantId, "Failed to delete queued message");
        for (StreamEvent evt : events) {
            if (evt.getId().equals(steerId) && isConsumed(evt)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("removed", false);
                body.put("status", "already_injected");
                body.put("steerId", steerId);
                return ResponseEntity.ok(body);
            }
        }
        boolean removed;
        try {
            removed = streamManager.deleteSteerEvent(sid, assistantId, steerId);
        } catch (RuntimeException e) {
            log.error("steer delete failed: session={} steer={}", sid, steerId, e);
            throw BizException.internal("Failed to delete queued message");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", removed);
        body.put("status", "deleted");
        body.put("steerId", steerId);
        return ResponseEntity.ok(body);
    }

    // ── 内部辅助 ──────────────────────────────

    /** 活跃 run 指向的消息已完成时清理并视为无 run。 */
    private String liveAgentRun(String sessionId) {
        LiveRun live = streamManager.getLiveRun(sessionId);
        String assistantId = live == null ? "" : live.assistantMessageId();
        if (assistantId == null || assistantId.isEmpty()) {
            return "";
        }
        Message msg = messageService.getMessage(sessionId, assistantId);
        if (msg == null || msg.isCompleted()) {
            try {
                streamManager.clearLiveRun(sessionId, assistantId);
            } catch (RuntimeException e) {
                log.warn("stale live run cleanup failed for session {}: {}", sessionId, e.toString());
            }
            return "";
        }
        return assistantId;
    }

    /** 查询失败 → 503（可重试）。 */
    private String resolveLiveAgentRunOr503(String sessionId) {
        try {
            return liveAgentRun(sessionId);
        } catch (RuntimeException e) {
            log.error("live run lookup failed: session={}", sessionId, e);
            throw BizException.serviceUnavailable("Failed to look up running turn");
        }
    }

    private List<StreamEvent> steerEventsOr503(String sessionId, String messageId, String message) {
        try {
            return streamManager.getSteerEvents(sessionId, messageId, 0).events();
        } catch (RuntimeException e) {
            log.error("steer read failed: session={}", sessionId, e);
            throw BizException.serviceUnavailable(message);
        }
    }

    private List<StreamEvent> steerEventsOr500(String sessionId, String messageId, String message) {
        try {
            return streamManager.getSteerEvents(sessionId, messageId, 0).events();
        } catch (RuntimeException e) {
            log.error("steer read failed: session={}", sessionId, e);
            throw BizException.internal(message);
        }
    }

    /** 追加后活 run 已切换 → 把事件搬到新 run 上。 */
    private RebindResult rebindIfLiveRunMoved(String sessionId, String appendedOn, StreamEvent evt) {
        String current;
        try {
            current = liveAgentRun(sessionId);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to queue message");
        }
        if (current.equals(appendedOn)) {
            return new RebindResult(appendedOn, "queued");
        }
        if (current.isEmpty()) {
            // 留在已结束的 run 上——删掉会在客户端无法中断 SSE 时丢用户文本
            return new RebindResult("", "new_run");
        }
        streamManager.appendSteerEvents(sessionId, current, List.of(evt));
        streamManager.deleteSteerEvent(sessionId, appendedOn, evt.getId());
        return new RebindResult(current, "queued");
    }

    private record RebindResult(String queuedOn, String status) {
    }

    /** 构造 steer 事件：data 键与注入事件对齐。 */
    static StreamEvent steerEvent(String id, String query, List<MentionedItem> mentionedItems,
                                  String channel) {
        StreamEvent evt = new StreamEvent(id,
                com.ragagent.common.llm.ResponseType.STEER, query, true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("steer_id", id);
        data.put("channel", channel == null ? "" : channel);
        data.put("delivery", DELIVERY_INJECT);
        data.put("mentioned_items", mentionedItemsToRaw(mentionedItems));
        evt.setData(data);
        return evt;
    }

    /** 提及项 → 事件 data 的原始 map 形态。 */
    static List<Object> mentionedItemsToRaw(List<MentionedItem> items) {
        List<Object> out = new ArrayList<>();
        if (items != null) {
            for (MentionedItem item : items) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("id", item.getId());
                raw.put("name", item.getName());
                raw.put("type", item.getType());
                raw.put("kb_type", item.getKbType());
                raw.put("kb_id", item.getKbId());
                raw.put("kb_name", item.getKbName());
                raw.put("service_id", item.getServiceId());
                raw.put("skill_name", item.getSkillName());
                out.add(raw);
            }
        }
        return out;
    }

    /** 解析 delivery：缺省 after。 */
    static String parseSteerDelivery(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (s.isEmpty() || s.equals(DELIVERY_AFTER)) {
            return DELIVERY_AFTER;
        }
        if (s.equals(DELIVERY_INJECT)) {
            return DELIVERY_INJECT;
        }
        throw new BizException(AppError.badRequest(
                "invalid delivery \"" + (raw == null ? "" : raw) + "\" (want inject or after)"));
    }

    /** 读事件的 delivery：非 inject 一律按 after。 */
    static String deliveryOf(StreamEvent evt) {
        Object d = evt.getData() == null ? null : evt.getData().get("delivery");
        return DELIVERY_AFTER.equals(d) ? DELIVERY_AFTER : DELIVERY_INJECT;
    }

    /** 事件是否已消费（data.consumed）。 */
    static boolean isConsumed(StreamEvent evt) {
        Object consumed = evt.getData() == null ? null : evt.getData().get(DATA_CONSUMED);
        return Boolean.TRUE.equals(consumed);
    }

    /** 选未消费的 backlog 事件（可排除已注入 id）。 */
    static List<StreamEvent> selectBacklog(List<StreamEvent> events, Set<String> injectedIds) {
        List<StreamEvent> out = new ArrayList<>();
        for (StreamEvent evt : events) {
            if (isConsumed(evt)) {
                continue;
            }
            if (injectedIds != null && injectedIds.contains(evt.getId())) {
                continue;
            }
            out.add(evt);
        }
        return out;
    }

    /** 队列项视图：事件 data 是冻结的线协议（下划线），响应体按契约输出 camelCase。 */
    static List<Map<String, Object>> pendingQueueItems(List<StreamEvent> events,
            Set<String> injectedIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StreamEvent evt : selectBacklog(events, injectedIds)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("steerId", evt.getId());
            item.put("content", evt.getContent());
            item.put("delivery", deliveryOf(evt));
            Object mentions = evt.getData() == null ? null : evt.getData().get("mentioned_items");
            if (mentions != null) {
                item.put("mentionedItems", MentionedItem.fromRawList(mentions));
            }
            out.add(item);
        }
        return out;
    }

    /** steer_id 必须是合法 UUID。 */
    static boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private SteerMessageRequest bindBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("No content to map due to end-of-input"));
        }
        try {
            return MAPPER.readValue(rawBody, SteerMessageRequest.class);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    e.getMessage()));
        }
    }

    /** 请求体键名＝Java 字段名（camelCase）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SteerMessageRequest(
            String expectedAssistantMessageId,
            String steerId,
            String query,
            List<MentionedItem> mentionedItems,
            String channel,
            String delivery) {
    }

    private static BizException conflict(String message) {
        return new BizException(AppError.conflict(message));
    }

    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        return BizException.internal(e.getMessage());
    }

    /** 直接返回已拼好的载荷（原信封的 success 标记已按契约去除）。 */
    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> ordered(String k1, Object v1) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(k1, v1);
        return body;
    }

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (k1 != null) {
            body.put(k1, v1);
        }
        if (k2 != null) {
            body.put(k2, v2);
        }
        return body;
    }
}
