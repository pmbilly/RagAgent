package com.ragagent.session.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.mapper.SessionRepository;
import com.ragagent.session.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话 HTTP 层（对照 Go {@code internal/handler/session/handler.go} 的
 * CreateSession / GetSession / GetSessionsByTenant / UpdateSession / DeleteSession /
 * BatchDeleteSessions / PinSession / UnpinSession，路由对照
 * {@code routes_chat.go} RegisterSessionRoutes L53-83）。
 *
 * <p>本波（G1）只落会话 CRUD + 置顶 8 条；消息 / steer / 附件 / 产物随后续分组补。</p>
 *
 * <h2>响应形态：gin.H = map = 键按字母序（§9 的 JSON 键序规则）</h2>
 * <ul>
 *   <li>创建/读取/更新：{@code {"data":…,"success":true}}（创建是 201）</li>
 *   <li>列表：{@code {"data":…,"page":N,"page_size":N,"success":true,"total":N}}</li>
 *   <li>删除：{@code {"message":"…","success":true}}</li>
 *   <li>置顶：{@code {"is_pinned":bool,"success":true}}</li>
 * </ul>
 *
 * <h2>错误门槛顺序（逐条对照 Go handler）</h2>
 * <ol>
 *   <li>路径参数 sanitize 后为空 → 400 {@code "invalid session id"}；</li>
 *   <li>请求体解析失败 → 400（Go 的 message 来自 gin 的 binding 错误，golden 已锁）；</li>
 *   <li>上下文无租户 → 401 {@code "Unauthorized"}；</li>
 *   <li>服务层 ErrSessionNotFound → 404 {@code "session not found"}（code 1003）；</li>
 *   <li>其余服务层错误 → 500 + 错误 message。</li>
 * </ol>
 *
 * <h2>SanitizeForLog 为什么参与查找</h2>
 * <p>Go handler 拿到路径参数后先 {@code secutils.SanitizeForLog} 再查库——
 * 这不只是日志卫生：批量删除会把 sanitize 后的 id 列表**当作真实入参**。
 * Java 侧用 {@link LogSanitizer} 逐字对照。</p>
 */
@RestController
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /**
     * 请求体解析器：<b>必须</b>忽略未知字段（对照 Go {@code encoding/json} 的默认语义；
     * UpdateSession 直接把请求体绑到 {@code types.Session} 上，多带一个键就整条 400
     * 是这里最不该发生的事）。挂 JavaTimeModule 是因为绑定的 Session 实体带
     * OffsetDateTime 字段（pinned_at 等）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 对照 Go 的 {@code NewInternalServerError(err.Error())}：**已带形态的业务错误**
     * （BizException，比如渠道筛选的 {@code "error code: 1002, error message: …"}）
     * 必须原样透传——二次包装会把前缀叠两层（真实踩过，golden 抓到）。
     */
    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        return BizException.internal(e.getMessage());
    }

    // ══════════════════════════ 创建 ══════════════════════════

    /** 对照 Go {@code CreateSession}（L123-178）。201 + {"data":…,"success":true}。 */
    @PostMapping("/api/v1/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestBody(required = false) String rawBody) {
        CreateSessionRequest request = parseCreateBody(rawBody);

        // 对照 Go：body 解析门槛**先于**租户门槛
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }

        Session created = new Session();
        created.setTenantId(tenantId);
        created.setTitle(request.title() == null ? "" : request.title());
        created.setDescription(Session.sanitizeClientSessionDescription(
                request.description() == null ? "" : request.description(), ""));
        // API-Key 调用方按外部身份隔离；否则落到普通 user id / 空（历史行语义）
        String ownerId = SessionOwnerIds.currentSessionOwnerId();
        if (ownerId != null && !ownerId.isEmpty()) {
            created.setUserId(ownerId);
        }

        Session saved;
        try {
            saved = sessionService.createSession(created);
        } catch (RuntimeException e) {
            // Go：NewInternalServerError(err.Error())
            throw toInternal(e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", saved);
        body.put("success", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 对照 Go {@code CreateSessionRequest}（handler/session/types.go L10-15）。 */
    private record CreateSessionRequest(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description) {
    }

    /**
     * 对照 Go {@code ShouldBindJSON}：空 body → "EOF"；解析失败 → 400 +
     * Go 风格解析器消息（{@link GoJsonBindError}）。
     * body 为 {@code null} 字面量时 Go 零值绑定不报错——按空请求处理。
     */
    private CreateSessionRequest parseCreateBody(String rawBody) {
        CreateSessionRequest request = bindBody(rawBody, CreateSessionRequest.class);
        return request == null ? new CreateSessionRequest("", "") : request;
    }

    // ══════════════════════════ 读取 ══════════════════════════

    /** 对照 Go {@code GetSession}（L192-225）。 */
    @GetMapping("/api/v1/sessions/{id}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        Session session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", session);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 Go {@code GetSessionsByTenant}（L243-277）。
     *
     * <p>分页参数手工绑定（对照 gin 的 {@code ShouldBindQuery} + validator）：
     * 非整数 → strconv 错误文案；负数 / 超界 → validator 文案。文案逐字对照
     * go-playground 的输出，golden 已锁（见契约测试）。</p>
     */
    @GetMapping("/api/v1/sessions")
    public ResponseEntity<Map<String, Object>> getSessionsByTenant(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "page_size", required = false) String pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "agent_id", required = false) String agentId) {
        int p = bindPagination(page, "Page", false);
        int size = bindPagination(pageSize, "PageSize", true);

        SessionRepository.PagedItems result;
        try {
            result = sessionService.listSessions(
                    SessionListQuery.of(keyword, source, agentId, p, size));
        } catch (RuntimeException e) {
            throw toInternal(e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result.items());
        body.put("page", result.page());
        body.put("page_size", result.pageSize());
        body.put("success", true);
        body.put("total", result.total());
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 gin form 绑定 + validator（{@code binding:"omitempty,min=1[,max=1000]"}）：
     * <ul>
     *   <li>参数缺席/为空 → 0（omitempty 跳过校验，服务层再归一化）；
     *       <b>显式 {@code 0} 同样被 omitempty 跳过</b>（golden 实测 page=0 → 200 且归一化）；
     *   </li>
     *   <li>非整数 → {@code strconv.ParseInt: parsing "<raw>": invalid syntax}；</li>
     *   <li>负数 → min tag 文案；{@code withMax} 且 &gt;1000 → max tag 文案。</li>
     * </ul>
     */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest(
                    "strconv.ParseInt: parsing \"" + raw + "\": invalid syntax"));
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw new BizException(AppError.badRequest(
                    "Key: 'Pagination." + field + "' Error:Field validation for '"
                            + field + "' failed on the 'min' tag"));
        }
        if (withMax && value > 1000) {
            throw new BizException(AppError.badRequest(
                    "Key: 'Pagination." + field + "' Error:Field validation for '"
                            + field + "' failed on the 'max' tag"));
        }
        return (int) value;
    }

    // ══════════════════════════ 更新 ══════════════════════════

    /**
     * 对照 Go {@code UpdateSession}（L292-348）：更新成功后**重新加载**再返回
     * （拿完整的落库时间戳），这是响应与请求体不同源的原因。
     */
    @PutMapping("/api/v1/sessions/{id}")
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }

        Session session = parseSessionBody(rawBody);
        session.setId(sessionId);
        session.setTenantId(tenantId);

        try {
            sessionService.updateSession(session);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }

        Session updated;
        try {
            updated = sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", updated);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 Go {@code c.ShouldBindJSON(&session)}：body 直接绑到 Session 实体。
     * 只有 title/description 会被仓储真正写入（repo.Update 的 map 白名单），
     * 其余字段绑进来也不落库——语义与 Go 完全一致。
     * body 为 {@code null} 字面量时 Go 零值绑定不报错——按空实体处理。
     */
    private Session parseSessionBody(String rawBody) {
        Session session = bindBody(rawBody, Session.class);
        return session == null ? new Session() : session;
    }

    /**
     * 三个端点共用的请求体绑定：空 body → 400 "EOF"；解析失败 → 400 +
     * Go 风格解析器消息（{@link GoJsonBindError}，golden 锁定该文案）。
     */
    private <T> T bindBody(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }

    // ══════════════════════════ 删除 ══════════════════════════

    /** 对照 Go {@code DeleteSession}（L362-392）。 */
    @DeleteMapping("/api/v1/sessions/{id}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            sessionService.deleteSession(sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        // TODO(波 3 browserskill): Go 在此调 browserSkill.Forget(scope, [id])。
        return messageBody("Session deleted successfully");
    }

    /**
     * 对照 Go {@code BatchDeleteSessions}（L455-514）：{@code delete_all=true} 走全量删除；
     * 否则要求非空 ids，逐个 sanitize 后丢弃空项。
     */
    @DeleteMapping("/api/v1/sessions/batch")
    public ResponseEntity<Map<String, Object>> batchDeleteSessions(
            @RequestBody(required = false) String rawBody) {
        BatchDeleteRequest req = parseBatchBody(rawBody);

        if (Boolean.TRUE.equals(req.deleteAll())) {
            try {
                sessionService.deleteAllSessions();
            } catch (RuntimeException e) {
                throw toInternal(e);
            }
            // TODO(波 3 browserskill): Go 在此调 browserSkill.ForgetAll(scope)。
            return messageBody("All sessions deleted successfully");
        }

        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BizException(AppError.badRequest("ids are required when delete_all is false"));
        }
        List<String> sanitizedIds = new ArrayList<>();
        for (String raw : req.ids()) {
            String sanitized = LogSanitizer.sanitize(raw);
            if (!sanitized.isEmpty()) {
                sanitizedIds.add(sanitized);
            }
        }
        if (sanitizedIds.isEmpty()) {
            throw new BizException(AppError.badRequest("no valid session IDs provided"));
        }

        try {
            sessionService.batchDeleteSessions(sanitizedIds);
        } catch (SessionNotFoundException e) {
            log.warn("No visible sessions found for batch delete");
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        // TODO(波 3 browserskill): Go 在此调 browserSkill.Forget(scope, sanitizedIDs)。
        return messageBody("Sessions deleted successfully");
    }

    /** 对照 Go {@code batchDeleteRequest}（L438-441）。 */
    private record BatchDeleteRequest(
            @JsonProperty("ids") List<String> ids,
            @JsonProperty("delete_all") Boolean deleteAll) {
    }

    /** 对照 Go：解析失败一律 400 "invalid request"（不是原始解析器消息）。 */
    private BatchDeleteRequest parseBatchBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("invalid request"));
        }
        try {
            return MAPPER.readValue(rawBody, BatchDeleteRequest.class);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("invalid request"));
        }
    }

    // ══════════════════════════ 置顶 ══════════════════════════

    /** 对照 Go {@code PinSession}（L527-529）。 */
    @PostMapping("/api/v1/sessions/{sessionId}/pin")
    public ResponseEntity<Map<String, Object>> pinSession(@PathVariable("sessionId") String sessionId) {
        return setSessionPinned(sessionId, true);
    }

    /** 对照 Go {@code UnpinSession}（L542-544）。 */
    @DeleteMapping("/api/v1/sessions/{id}/pin")
    public ResponseEntity<Map<String, Object>> unpinSession(@PathVariable("id") String id) {
        return setSessionPinned(id, false);
    }

    /**
     * 对照 Go {@code setSessionPinned}（L546-582）：服务层报错 → 500（不是 404）；
     * 0 行受影响（不存在/不可见）→ 404 {@code "session not found"}。
     */
    private ResponseEntity<Map<String, Object>> setSessionPinned(String rawId, boolean pinned) {
        String id = LogSanitizer.sanitize(rawId);
        if (id.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        long rows;
        try {
            rows = sessionService.setSessionPinned(id, pinned);
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        if (rows == 0) {
            throw BizException.notFound("session not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("is_pinned", pinned);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════════════════ 公共 ══════════════════════════

    /** 删除类成功响应：{"message":…,"success":true}（map 字母序：message &lt; success）。 */
    private static ResponseEntity<Map<String, Object>> messageBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }
}
