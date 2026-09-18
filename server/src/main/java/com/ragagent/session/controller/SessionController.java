package com.ragagent.session.controller;

import java.time.OffsetDateTime;
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
import com.ragagent.session.domain.Message;
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
    private final com.ragagent.session.service.MessageService messageService;
    private final com.ragagent.stream.StreamManager streamManager;

    public SessionController(SessionService sessionService,
                             com.ragagent.session.service.MessageService messageService,
                             com.ragagent.stream.StreamManager streamManager) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.streamManager = streamManager;
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

    // ══════════════════════════ 清空消息 ══════════════════════════

    /**
     * 对照 Go {@code ClearSessionMessages}（handler.go L407-435，路由 L59）：
     * 会话本身保留，消息全软删（含建议与聊天历史知识清理——在 MessageService 里）。
     * 会话不可见 → 404 "session not found"。
     */
    @DeleteMapping("/api/v1/sessions/{id}/messages")
    public ResponseEntity<Map<String, Object>> clearSessionMessages(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        try {
            messageService.clearSessionMessages(sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return messageBody("Session messages cleared successfully");
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

    // ══════════════════════════ 产物（波 1 G6） ══════════════════════════

    /**
     * 对照 Go {@code ListSessionArtifacts}（artifact_download.go L49-93）：
     * 会话全部 assistant 消息的产物元数据，**不含存储 URL**——客户端不能绕过
     * download 端点直接读 provider:// 路径。
     */
    @GetMapping("/api/v1/sessions/{id}/artifacts")
    public ResponseEntity<Map<String, Object>> listSessionArtifacts(@PathVariable("id") String id) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        // 归属校验走 GetSession（读可见性，与 Go 相同）
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        List<com.ragagent.session.domain.MessageArtifact> artifacts;
        try {
            artifacts = messageService.getSessionArtifacts(sessionId);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", artifactListItems(artifacts));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 Go {@code ListMessageArtifacts}（L103-144）。 */
    @GetMapping("/api/v1/sessions/{id}/messages/{message_id}/artifacts")
    public ResponseEntity<Map<String, Object>> listMessageArtifacts(
            @PathVariable("id") String id,
            @PathVariable("message_id") String messageId) {
        String sessionId = LogSanitizer.sanitize(id);
        String mid = LogSanitizer.sanitize(messageId);
        if (sessionId.isEmpty() || mid.isEmpty()) {
            throw new BizException(AppError.badRequest("session_id and message_id are required"));
        }
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        Message message;
        try {
            message = messageService.getMessage(sessionId, mid);
        } catch (com.ragagent.session.domain.MessageNotFoundException e) {
            // Go：err != nil || msg == nil → 404 "message not found"（固定文案）
            throw BizException.notFound("message not found");
        } catch (RuntimeException e) {
            throw BizException.notFound("message not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", artifactListItems(
                message.getArtifacts() == null ? List.of() : message.getArtifacts()));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 Go {@code DownloadMessageArtifact}（L152-258）。
     *
     * <p>确定性分支逐条对照（400/404 文案固定）；实际取文件依赖 access 层的
     * 资源目录解析（未翻译）——Go 在 catalog 查不到资源时同样回
     * 404 "artifact not accessible"，Java 恒落该分支（provider 级文件服务未翻译）。</p>
     */
    @GetMapping("/api/v1/sessions/{id}/messages/{message_id}/artifacts/{index}/download")
    public ResponseEntity<Map<String, Object>> downloadMessageArtifact(
            @PathVariable("id") String id,
            @PathVariable("message_id") String messageId,
            @PathVariable("index") String indexParam) {
        String sessionId = LogSanitizer.sanitize(id);
        String mid = LogSanitizer.sanitize(messageId);
        if (sessionId.isEmpty() || mid.isEmpty() || indexParam == null || indexParam.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "session_id, message_id and index are required"));
        }
        final int index;
        try {
            index = Integer.parseInt(indexParam);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest("invalid artifact index"));
        }
        if (index < 0) {
            throw new BizException(AppError.badRequest("invalid artifact index"));
        }
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        Message message;
        try {
            message = messageService.getMessage(sessionId, mid);
        } catch (RuntimeException e) {
            throw BizException.notFound("message not found");
        }
        List<com.ragagent.session.domain.MessageArtifact> artifacts =
                message.getArtifacts() == null ? List.of() : message.getArtifacts();
        if (index >= artifacts.size()) {
            throw BizException.notFound("artifact index out of range");
        }
        com.ragagent.session.domain.MessageArtifact artifact = artifacts.get(index);
        if (artifact.getUrl() == null || artifact.getUrl().isEmpty()) {
            throw BizException.notFound("artifact storage path missing");
        }
        // access.ResolveMessageArtifact（资源目录/共享授权）未翻译——与 Go 的
        // catalog 查不到资源同一出口：404 "artifact not accessible"。
        // （Go 的 fileService==nil → 500 分支生产装配不可达；Java 侧同理不设。）
        throw BizException.notFound("artifact not accessible");
    }

    /** 对照 Go {@code artifactListItem}（L265-279）：声明序 + handle omitempty。 */
    private static List<Map<String, Object>> artifactListItems(
            List<com.ragagent.session.domain.MessageArtifact> artifacts) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < artifacts.size(); i++) {
            com.ragagent.session.domain.MessageArtifact a = artifacts.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            String handle = artifactHandle(a.getUrl());
            if (handle != null && !handle.isEmpty()) {
                item.put("handle", handle);
            }
            item.put("file_name", a.getFileName());
            item.put("file_type", a.getFileType());
            item.put("file_size", a.getFileSize());
            item.put("source_path", a.getSourcePath());
            item.put("mod_time", a.getModTime());
            item.put("created_at", a.getCreatedAt());
            items.add(item);
        }
        return items;
    }

    /**
     * 对照 Go {@code artifactHandle}（L352-357）：URL 是 {@code resource://<handle>}
     * （恰好 22 个合法字符）时返回规范化的 {@code resource://<handle>}，否则空串
     * （空串被 omitempty 省略——响应里没有 handle 键）。
     */
    private static String artifactHandle(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("resource://")) {
            return "";
        }
        String handle = trimmed.substring("resource://".length());
        if (handle.length() != 22) {
            return "";
        }
        for (int i = 0; i < handle.length(); i++) {
            char c = handle.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return "";
            }
        }
        return "resource://" + handle;
    }

    // ══════════════════════════ 生成标题（波 1 G6） ══════════════════════════

    /**
     * 对照 Go {@code GenerateTitle}（title.go L25-76）。写会话行，用严格 owner 范围
     * （GetOwnedSession——管理员可读不可改）。响应 {"data":title,"success":true}。
     */
    @PostMapping("/api/v1/sessions/{session_id}/generate_title")
    public ResponseEntity<Map<String, Object>> generateTitle(
            @PathVariable("session_id") String sessionId,
            @RequestBody(required = false) String rawBody) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        GenerateTitleRequest request = bindBody(rawBody, GenerateTitleRequest.class);
        // binding:"required"：validator 对切片是**非 nil 即通过**（golden 实测
        // {"messages":[]} 通过 binding 走到了模型查找），只有字段缺失才 400
        if (request == null || request.messages() == null) {
            throw new BizException(AppError.badRequest(
                    "Key: 'GenerateTitleRequest.Messages' Error:Field validation for 'Messages' "
                            + "failed on the 'required' tag"));
        }
        Session session;
        try {
            session = sessionService.getOwnedSession(sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        String title;
        try {
            title = sessionService.generateTitle(session, request.messages(), "");
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", title);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 Go {@code GenerateTitleRequest}（types.go L18-20）。 */
    private record GenerateTitleRequest(
            @JsonProperty("messages") List<Message> messages) {
    }

    // ══════════════════════════ 停止生成（波 1 G6） ══════════════════════════

    /**
     * 对照 Go {@code StopSession}（stream.go L219-324）。
     *
     * <p>⚠️ 错误形态与组内其他端点不同：Go 直接 {@code c.JSON(code, gin.H{"error": "..."})}
     * ——纯字符串信封（**不是** AppError 信封），状态码有 400/401/403/404 五种。
     * 停止事件经 StreamManager 落存储（跨语言键空间），事件 type 是
     * {@code types.ResponseType(event.EventStop)} 的字符串强转 "stop"。</p>
     */
    @PostMapping("/api/v1/sessions/{session_id}/stop")
    public ResponseEntity<Map<String, Object>> stopSession(
            @PathVariable("session_id") String sessionId,
            @RequestBody(required = false) String rawBody) {
        String sid = LogSanitizer.sanitize(sessionId);
        if (sid == null || sid.isEmpty()) {
            return errorBody(400, "Session ID is required");
        }
        StopSessionRequest request = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                request = MAPPER.readValue(rawBody, StopSessionRequest.class);
            } catch (Exception e) {
                return errorBody(400, "message_id is required");
            }
        }
        if (request == null || isBlankStr(request.messageId())) {
            return errorBody(400, "message_id is required");
        }
        String assistantMessageId = LogSanitizer.sanitize(request.messageId());

        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            return errorBody(401, "Unauthorized");
        }

        // 消息可见性走 GetMessage（读路径），会话走严格 owner 范围（写路径）
        Message message;
        try {
            message = messageService.getMessage(sid, assistantMessageId);
        } catch (RuntimeException e) {
            return errorBody(404, "Message not found");
        }
        if (message.getSessionId() == null || !message.getSessionId().equals(sid)) {
            return errorBody(403, "Message does not belong to this session");
        }
        Session session;
        try {
            session = sessionService.getOwnedSession(sid);
        } catch (RuntimeException e) {
            return errorBody(404, "Session not found");
        }
        if (session.getTenantId() == null || session.getTenantId().longValue() != tenantId) {
            // ⚠️ Long 比较必须拆箱（陷阱 §5 第 6 条：租户 10002 超出缓存区间，
            // 引用比较恒不等 → 误判 "Access denied"，G6 契约测试抓回）
            return errorBody(403, "Access denied");
        }
        if (message.isCompleted()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Message already completed");
            body.put("success", true);
            return ResponseEntity.ok(body);
        }

        com.ragagent.stream.StreamEvent stopEvent = new com.ragagent.stream.StreamEvent(
                "stop-" + System.nanoTime(), com.ragagent.llm.domain.ResponseType.STOP,
                "", true);
        stopEvent.setTimestamp(OffsetDateTime.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", sid);
        data.put("message_id", assistantMessageId);
        data.put("reason", "user_requested");
        stopEvent.setData(data);
        try {
            streamManager.appendEvent(sid, assistantMessageId, stopEvent);
        } catch (RuntimeException e) {
            return errorBody(500, "Failed to write stop event");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Generation stopped");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 Go {@code StopSessionRequest}（stream.go 内定义，message_id 必填）。 */
    private record StopSessionRequest(@JsonProperty("message_id") String messageId) {
    }

    /** Go 的 {@code c.JSON(code, gin.H{"error": "..."})}：纯字符串错误信封。 */
    private static ResponseEntity<Map<String, Object>> errorBody(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    private static boolean isBlankStr(String v) {
        return v == null || v.trim().isEmpty();
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
