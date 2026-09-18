package com.ragagent.session.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.MessageSuggestionSet;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.service.MessageSuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 追问建议 HTTP 层（对照 Go {@code internal/handler/message_suggestion.go}，
 * 路由对照 routes_chat.go L89-91 的 3 条）。
 *
 * <h2>响应形态</h2>
 * <ul>
 *   <li>Ensure：{@code {"data":set,"success":true}}，集合进入 generating 态时是 <b>202</b>；</li>
 *   <li>Get：同上恒 200；</li>
 *   <li>RecordEvent：<b>204 无响应体</b>。</li>
 * </ul>
 *
 * <h2>writeError 的子串映射（逐条对照 Go L135-155，顺序有语义）</h2>
 * <ul>
 *   <li>gorm.ErrRecordNotFound（含消息不存在）→ 404 "suggestions not found"；</li>
 *   <li>会话不可见 → 404 "session not found"（独立分支）；</li>
 *   <li>"completed assistant" / "invalid suggestion event" / "requires question_id" /
 *       "does not belong" / "not allowed" → 400 + 原文；</li>
 *   <li>其余 → 500 固定文案 "message suggestion operation failed"（不透传原文）。</li>
 * </ul>
 */
@RestController
public class MessageSuggestionController {

    private static final Logger log = LoggerFactory.getLogger(MessageSuggestionController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MessageSuggestionService suggestionService;

    public MessageSuggestionController(MessageSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    /**
     * 对照 Go {@code Ensure}（L48-71）。⚠️ 请求体<b>只在非空时解析</b>——空 body 合法
     * （regenerate=false），但畸形 JSON → 400 固定文案 "invalid request body"
     * （不是解析器原文，与其他端点不同）。
     */
    @PostMapping("/api/v1/sessions/{session_id}/messages/{message_id}/suggestions")
    public ResponseEntity<Map<String, Object>> ensure(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId,
            @RequestBody(required = false) String rawBody) {
        boolean regenerate = false;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                EnsureRequest request = MAPPER.readValue(rawBody, EnsureRequest.class);
                regenerate = request != null && Boolean.TRUE.equals(request.regenerate());
            } catch (Exception e) {
                throw new BizException(AppError.badRequest("invalid request body"));
            }
        }
        MessageSuggestionSet set;
        try {
            set = suggestionService.ensureFollowUps(
                    LogSanitizer.sanitize(sessionId), LogSanitizer.sanitize(messageId), regenerate);
        } catch (RuntimeException e) {
            throw writeError(e);
        }
        HttpStatus status = HttpStatus.OK;
        if (set != null && MessageSuggestionSet.STATUS_GENERATING.equals(set.getStatus())) {
            status = HttpStatus.ACCEPTED;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", set);
        body.put("success", true);
        return ResponseEntity.status(status).body(body);
    }

    /** 对照 Go {@code EnsureMessageSuggestionsRequest}。 */
    private record EnsureRequest(@JsonProperty("regenerate") Boolean regenerate) {
    }

    /**
     * 对照 Go {@code Get}（L83-94）。Go 的 GET 同时注册在 :session_id 与 :id 两个通配下
     * （router 两种写法并存），Spring 用双 pattern 表达同一件事。
     */
    @GetMapping({"/api/v1/sessions/{session_id}/messages/{message_id}/suggestions",
            "/api/v1/sessions/{id}/messages/{message_id}/suggestions"})
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable(value = "session_id", required = false) String sessionId,
            @PathVariable(value = "id", required = false) String idFallback,
            @PathVariable("message_id") String messageId) {
        String sid = sessionId == null || sessionId.isEmpty()
                ? LogSanitizer.sanitize(idFallback) : LogSanitizer.sanitize(sessionId);
        MessageSuggestionSet set;
        try {
            set = suggestionService.getFollowUps(sid, LogSanitizer.sanitize(messageId));
        } catch (RuntimeException e) {
            throw writeError(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", set);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 Go {@code RecordEvent}（L115-133）：成功是 <b>204 无响应体</b>；
     * 解析失败（含空 body、required 字段缺失）→ 400 固定文案 "invalid request body"。
     */
    @PostMapping("/api/v1/sessions/{session_id}/suggestion-events")
    public ResponseEntity<Void> recordEvent(
            @PathVariable("session_id") String sessionId,
            @RequestBody(required = false) String rawBody) {
        EventRequest request = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                request = MAPPER.readValue(rawBody, EventRequest.class);
            } catch (Exception e) {
                throw new BizException(AppError.badRequest("invalid request body"));
            }
        }
        if (request == null || isBlank(request.suggestionSetId())
                || isBlank(request.eventType())) {
            // binding:"required" 的字段缺失同样落 "invalid request body"
            throw new BizException(AppError.badRequest("invalid request body"));
        }
        try {
            suggestionService.recordEvent(LogSanitizer.sanitize(sessionId),
                    request.suggestionSetId().trim(),
                    request.questionId() == null ? "" : request.questionId().trim(),
                    request.eventType().trim());
        } catch (RuntimeException e) {
            throw writeError(e);
        }
        return ResponseEntity.noContent().build();
    }

    /** 对照 Go {@code SuggestionEventRequest}。 */
    private record EventRequest(
            @JsonProperty("suggestion_set_id") String suggestionSetId,
            @JsonProperty("question_id") String questionId,
            @JsonProperty("event_type") String eventType) {
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    /**
     * 对照 Go {@code writeError}（L135-155）的子串分派。顺序有语义：
     * gorm.NotFound 在前（消息不存在也落 "suggestions not found"），
     * 会话 404 独立分支，业务 400 靠子串匹配，其余 500 固定文案。
     */
    private static BizException writeError(RuntimeException e) {
        if (e instanceof MessageSuggestionRepository.SuggestionSetNotFoundException
                || e instanceof MessageNotFoundException) {
            // Go：errors.Is(err, gorm.ErrRecordNotFound) → 404 "suggestions not found"
            return BizException.notFound("suggestions not found");
        }
        if (e instanceof SessionNotFoundException) {
            // 独立分支：ErrSessionNotFound 不包装 gorm 错误，需自己的 404
            return BizException.notFound("session not found");
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("completed assistant")
                || message.contains("invalid suggestion event")
                || message.contains("requires question_id")
                || message.contains("does not belong")
                || message.contains("not allowed")) {
            return BizException.badRequest(message);
        }
        log.error("message suggestion operation failed", e);
        return BizException.internal("message suggestion operation failed");
    }
}
