package com.ragagent.session.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.domain.SessionPage;
import com.ragagent.session.dto.ArtifactView;
import com.ragagent.session.dto.BatchDeleteSessionsRequest;
import com.ragagent.session.dto.CreateSessionRequest;
import com.ragagent.session.dto.GenerateTitleRequest;
import com.ragagent.session.dto.GenerateTitleResponse;
import com.ragagent.session.dto.SessionListResponse;
import com.ragagent.session.dto.SessionPinResponse;
import com.ragagent.session.dto.StopSessionRequest;
import com.ragagent.session.dto.UpdateSessionRequest;
import com.ragagent.session.service.SessionService;
import jakarta.validation.Valid;
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
 * <h2>响应形态（§14.9l S1b 换锚后，§2.1）</h2>
 * <ul>
 *   <li>创建 → <b>201</b> + 裸 {@link Session}；读取/更新 → 200 + 裸 {@link Session}；</li>
 *   <li>列表 → {@code {items,page,pageSize,total}}（{@link SessionListResponse}）；</li>
 *   <li>删除 / 批量删除 / 清空消息 → <b>204</b>（§1.13；旧 {@code {"message":…,"success":true}} 退役，
 *       前端不再解析服务端文案）；</li>
 *   <li>置顶 → 200 + {@code {"pinned":bool}}（{@link SessionPinResponse}）；</li>
 *   <li>产物列表 → 裸数组 {@code [ArtifactView]}；生成标题 → {@code {"title":"…"}}；</li>
 *   <li>停止生成 → 成功 <b>204</b>（§1.17：操作类响应不带服务端文案）。</li>
 * </ul>
 *
 * <h2>错误门槛顺序（逐条对照 Go handler）</h2>
 * <ol>
 *   <li>路径参数 sanitize 后为空 → 400 {@code "invalid session id"}；</li>
 *   <li>请求体绑定失败 → 400，文案由全局处理器给（空体/字面量 null → {@code 请求体不能为空}；
 *       畸形 JSON → {@code 请求体格式不正确}；缺 {@code messages} → {@code messages: 不能为空}）；</li>
 *   <li>上下文无租户 → 401 {@code "Unauthorized"}；</li>
 *   <li>服务层 ErrSessionNotFound → 404 {@code "session not found"}（code 1003）；</li>
 *   <li>其余服务层错误 → 500 + 错误 message。</li>
 * </ol>
 * <p>⚠️ {@code stop} 端点的错误仍是**纯字符串信封** {@code {"error":"…"}}
 * （Go 原文 {@code c.JSON(code, gin.H{"error": …})}）——错误形态统一是独立的一批（§14.9 第 ④ 项），
 * 本批不混轴。</p>
 *
 * <h2>SanitizeForLog 为什么参与查找</h2>
 * <p>Go handler 拿到路径参数后先 {@code secutils.SanitizeForLog} 再查库——
 * 这不只是日志卫生：批量删除会把 sanitize 后的 id 列表**当作真实入参**。
 * Java 侧用 {@link LogSanitizer} 逐字对照。</p>
 */
@RestController
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

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

    /** 对照 Go {@code CreateSession}（L123-178）。换锚后：<b>201</b> + 裸 {@link Session}。 */
    @PostMapping("/api/v1/sessions")
    public ResponseEntity<Session> createSession(@RequestBody @Valid CreateSessionRequest request) {
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

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ══════════════════════════ 读取 ══════════════════════════

    /** 对照 Go {@code GetSession}（L192-225）。 */
    @GetMapping("/api/v1/sessions/{id}")
    public ResponseEntity<Session> getSession(@PathVariable("id") String id) {
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
        return ResponseEntity.ok(session);
    }

    /**
     * 对照 Go {@code GetSessionsByTenant}（L243-277）。
     *
     * <p>查询参数换锚后是 camelCase（{@code page}/{@code pageSize}/{@code keyword}/{@code source}/
     * {@code agentId}，§1.16）；分页门槛的文案是标准中文（见 {@link #bindPagination}），
     * 不再是 go-playground 的 tag 文案。响应是 {@link SessionListResponse}（§2.1）。</p>
     */
    @GetMapping("/api/v1/sessions")
    public SessionListResponse getSessionsByTenant(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "pageSize", required = false) String pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "agentId", required = false) String agentId) {
        int p = bindPagination(page, "page", false);
        int size = bindPagination(pageSize, "pageSize", true);

        SessionPage result;
        try {
            result = sessionService.listSessions(
                    SessionListQuery.of(keyword, source, agentId, p, size));
        } catch (RuntimeException e) {
            throw toInternal(e);
        }

        return new SessionListResponse(result.items(), result.page(), result.pageSize(),
                result.total());
    }

    /**
     * 分页参数的门槛（换锚后文案是标准中文，不再是 Go validator 的 tag 文案）：
     * <ul>
     *   <li>参数缺席/为空 → 0（服务层再归一化）；<b>显式 {@code 0} 同样跳过</b>
     *       （历史行为：{@code page=0} → 200 且归一化）；</li>
     *   <li>非整数 → 400 {@code 分页参数不合法 / page: 必须是整数}；</li>
     *   <li>负数 → {@code page: 必须为正整数}；{@code withMax} 且 &gt;1000 → {@code pageSize: 超出上限}。</li>
     * </ul>
     */
    private static int bindPagination(String raw, String field, boolean withMax) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        final long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw paginationError(field + ": 必须是整数");
        }
        if (value == 0) {
            return 0;
        }
        if (value < 1) {
            throw paginationError(field + ": 必须为正整数");
        }
        if (withMax && value > 1000) {
            throw paginationError(field + ": 超出上限");
        }
        return (int) value;
    }

    private static BizException paginationError(String details) {
        return new BizException(
                AppError.badRequest("分页参数不合法").withDetails(details));
    }

    // ══════════════════════════ 更新 ══════════════════════════

    /**
     * 对照 Go {@code UpdateSession}（L292-348）：更新成功后**重新加载**再返回
     * （拿完整的落库时间戳），这是响应与请求体不同源的原因。
     */
    @PutMapping("/api/v1/sessions/{id}")
    public ResponseEntity<Session> updateSession(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateSessionRequest request) {
        String sessionId = LogSanitizer.sanitize(id);
        if (sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }

        Session session = new Session();
        session.setTitle(request.title() == null ? "" : request.title());
        session.setDescription(request.description() == null ? "" : request.description());
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

        return ResponseEntity.ok(updated);
    }



    // ══════════════════════════ 删除 ══════════════════════════

    /** 对照 Go {@code DeleteSession}（L362-392）。换锚后：同步删除 → <b>204</b>（§1.13）。 */
    @DeleteMapping("/api/v1/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable("id") String id) {
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
        return ResponseEntity.noContent().build();
    }

    /**
     * 对照 Go {@code BatchDeleteSessions}（L455-514）：{@code deleteAll=true} 走全量删除；
     * 否则要求非空 ids，逐个 sanitize 后丢弃空项。换锚后：<b>204</b>（同步删除）。
     */
    @DeleteMapping("/api/v1/sessions/batch")
    public ResponseEntity<Void> batchDeleteSessions(
            @RequestBody @Valid BatchDeleteSessionsRequest req) {
        if (Boolean.TRUE.equals(req.deleteAll())) {
            try {
                sessionService.deleteAllSessions();
            } catch (RuntimeException e) {
                throw toInternal(e);
            }
            return ResponseEntity.noContent().build();
        }

        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BizException(AppError.badRequest("ids are required when deleteAll is false"));
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
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 清空消息 ══════════════════════════

    /**
     * 对照 Go {@code ClearSessionMessages}（handler.go L407-435，路由 L59）：
     * 会话本身保留，消息全软删（含建议与聊天历史知识清理——在 MessageService 里）。
     * 会话不可见 → 404 "session not found"。
     */
    @DeleteMapping("/api/v1/sessions/{id}/messages")
    public ResponseEntity<Void> clearSessionMessages(@PathVariable("id") String id) {
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
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 置顶 ══════════════════════════

    /** 对照 Go {@code PinSession}（L527-529）。换锚后：200 + {@code {"pinned":true}}。 */
    @PostMapping("/api/v1/sessions/{sessionId}/pin")
    public SessionPinResponse pinSession(@PathVariable("sessionId") String sessionId) {
        return setSessionPinned(sessionId, true);
    }

    /** 对照 Go {@code UnpinSession}（L542-544）。 */
    @DeleteMapping("/api/v1/sessions/{id}/pin")
    public SessionPinResponse unpinSession(@PathVariable("id") String id) {
        return setSessionPinned(id, false);
    }

    /**
     * 对照 Go {@code setSessionPinned}（L546-582）：服务层报错 → 500（不是 404）；
     * 0 行受影响（不存在/不可见）→ 404 {@code "session not found"}。
     */
    private SessionPinResponse setSessionPinned(String rawId, boolean pinned) {
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
        return new SessionPinResponse(pinned);
    }

    // ══════════════════════════ 产物（波 1 G6） ══════════════════════════

    /**
     * 对照 Go {@code ListSessionArtifacts}（artifact_download.go L49-93）：
     * 会话全部 assistant 消息的产物元数据，**不含存储 URL**——客户端不能绕过
     * download 端点直接读 provider:// 路径。
     */
    @GetMapping("/api/v1/sessions/{id}/artifacts")
    public List<ArtifactView> listSessionArtifacts(@PathVariable("id") String id) {
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
        return artifactListItems(artifacts);
    }

    /** 对照 Go {@code ListMessageArtifacts}（L103-144）。换锚后：裸数组。 */
    @GetMapping("/api/v1/sessions/{id}/messages/{messageId}/artifacts")
    public List<ArtifactView> listMessageArtifacts(
            @PathVariable("id") String id,
            @PathVariable("messageId") String messageId) {
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
        return artifactListItems(
                message.getArtifacts() == null ? List.of() : message.getArtifacts());
    }

    /**
     * 对照 Go {@code DownloadMessageArtifact}（L152-258）。
     *
     * <p>确定性分支逐条对照（400/404 文案固定）；实际取文件依赖 access 层的
     * 资源目录解析（未翻译）——Go 在 catalog 查不到资源时同样回
     * 404 "artifact not accessible"，Java 恒落该分支（provider 级文件服务未翻译）。</p>
     */
    @GetMapping("/api/v1/sessions/{id}/messages/{messageId}/artifacts/{index}/download")
    public ResponseEntity<Void> downloadMessageArtifact(
            @PathVariable("id") String id,
            @PathVariable("messageId") String messageId,
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

    /** 对照 Go {@code artifactListItem}（L265-279）：{@code index} 是列表下标、其余字段照抄。 */
    private static List<ArtifactView> artifactListItems(
            List<com.ragagent.session.domain.MessageArtifact> artifacts) {
        List<ArtifactView> items = new ArrayList<>(artifacts.size());
        for (int i = 0; i < artifacts.size(); i++) {
            items.add(ArtifactView.of(i, artifacts.get(i)));
        }
        return items;
    }



    // ══════════════════════════ 生成标题（波 1 G6） ══════════════════════════

    /**
     * 对照 Go {@code GenerateTitle}（title.go L25-76）。写会话行，用严格 owner 范围
     * （GetOwnedSession——管理员可读不可改）。响应 {"data":title,"success":true}。
     */
    @PostMapping("/api/v1/sessions/{sessionId}/generate_title")
    public GenerateTitleResponse generateTitle(
            @PathVariable("sessionId") String sessionId,
            @RequestBody @Valid GenerateTitleRequest request) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid session id"));
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
        return new GenerateTitleResponse(title);
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
    @PostMapping("/api/v1/sessions/{sessionId}/stop")
    public ResponseEntity<?> stopSession(
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) StopSessionRequest request) {
        String sid = LogSanitizer.sanitize(sessionId);
        if (sid == null || sid.isEmpty()) {
            return errorBody(400, "Session ID is required");
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
            // 已经结束的消息是幂等的成功（换锚后不再回 message 文案，§1.17）
            return ResponseEntity.noContent().build();
        }

        com.ragagent.stream.StreamEvent stopEvent = new com.ragagent.stream.StreamEvent(
                "stop-" + System.nanoTime(), com.ragagent.common.llm.ResponseType.STOP,
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
        return ResponseEntity.noContent().build();
    }

    /**
     * Go 的 {@code c.JSON(code, gin.H{"error": "..."})}：纯字符串错误信封。
     *
     * <p>⚠️ 只有 {@code stop} 端点用它（Go 原文如此）；其余端点的错误都是 AppError 信封。
     * 错误形态统一是独立的一批（§14.9 第 ④ 项），本批不混轴——所以这里连成功响应
     * 也只能是 {@link ResponseEntity}{@code <?>}。</p>
     */
    private static ResponseEntity<Map<String, Object>> errorBody(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    private static boolean isBlankStr(String v) {
        return v == null || v.trim().isEmpty();
    }
}
