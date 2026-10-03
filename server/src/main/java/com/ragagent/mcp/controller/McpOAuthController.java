package com.ragagent.mcp.controller;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.common.approval.ApprovalException;
import com.ragagent.common.approval.Decision;
import com.ragagent.common.approval.Gate;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.oauth.OAuthAuthorizationStatus;
import com.ragagent.mcp.oauth.OAuthCallbackException;
import com.ragagent.mcp.oauth.OAuthManager;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.service.McpServiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每用户维度的 MCP OAuth2 授权码流程 HTTP 层。
 *
 * <h3>端点清单（路由前缀 {@code /api/v1} 由主会话在 WebConfig 注册）</h3>
 * <ol>
 *   <li>{@code POST /mcp-services/{id}/oauth/authorize-url} —— 发起授权，返回浏览器应打开的
 *       授权地址与本次尝试 ID（RBAC：<b>Viewer+</b>，对照 {@code g.Viewer()}）；</li>
 *   <li>{@code GET /mcp-services/{id}/oauth/status} —— 查询授权状态；带
 *       {@code authorization_attempt} 时只认本次流程（RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code DELETE /mcp-services/{id}/oauth/token} —— 撤销当前用户的 token，返回 204
 *       （RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code GET /mcp-oauth/callback} —— <b>公开、无鉴权</b>（授权服务器回跳不带 WeKnora
 *       bearer；靠一次性 state 自证）。该路径已在
 *       {@code AuthFilter.NO_AUTH_API} 中放行，<b>不得</b>加 RBAC 规则；</li>
 *   <li>{@code POST /agent/mcp-oauth-resolutions/{pending_id}} —— 对话内 OAuth 授权完成后恢复
 *       被暂停的 Agent 工具调用（RBAC：<b>Viewer+</b>）；</li>
 *   <li>{@code POST /agent/mcp-oauth-resolutions/{pending_id}/cancel} —— 用户跳过授权，
 *       解除 Agent 阻塞（RBAC：<b>Viewer+</b>）。</li>
 * </ol>
 *
 * <p><b>主会话注册路由时的路径注意</b>：回调刻意注册在 {@code /mcp-services} 组<b>之外</b>
 * （避开静态段与 {@code /{id}} 动态段的路由歧义）。
 * Spring 的 {@code PathPattern} 对字面量段天然优先于变量段，但为避免任何歧义，
 * 请仍把回调挂在 {@code /api/v1/mcp-oauth/callback} 下。</p>
 *
 * <p>两个依赖刻意用 {@link ObjectProvider} 而非直接注入：{@code Gate} 与
 * {@code McpClientManager} 的 bean 都可能缺失（未接线时），使用处各自做了
 * 缺省判断。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class McpOAuthController {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthController.class);

    /** 前端地址缺失时的兜底。 */
    static final String FALLBACK_REDIRECT = "/";

    private final OAuthManager oauth;
    private final McpServiceService svc;
    private final ObjectProvider<McpClientManager> mcpManager;
    private final ObjectProvider<Gate> gate;

    public McpOAuthController(OAuthManager oauth, McpServiceService svc,
                              ObjectProvider<McpClientManager> mcpManager,
                              ObjectProvider<Gate> gate) {
        this.oauth = oauth;
        this.svc = svc;
        this.mcpManager = mcpManager;
        this.gate = gate;
    }

    // ── 1. 发起授权 ────────────────────────────────────────────────────

    /**
     * 发起授权的请求体：前端按 {@code redirectUri} / {@code frontendRedirect} 提交。
     */
    public record AuthorizeRequest(String redirectUri, String frontendRedirect) {
    }

    @PostMapping("/mcp-services/{id}/oauth/authorize-url")
    public ResponseEntity<Map<String, Object>> authorizeUrl(@PathVariable("id") String serviceId,
                                                            @RequestBody(required = false) AuthorizeRequest req) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        if (tenantId == 0 || !McpPrincipal.valid(principal)) {
            throw BizException.unauthorized("authentication required");
        }
        if (req == null) {
            // 空 body → 400，文案固定为 "EOF"（有契约测试钉死；直连路由同 handler 同文案）
            throw BizException.badRequest("EOF");
        }
        String redirectUri = trim(req.redirectUri());
        if (redirectUri.isEmpty()) {
            throw new BizException(AppError.validation("redirect_uri is required"));
        }
        String frontendRedirect = trim(req.frontendRedirect());
        if (frontendRedirect.isEmpty()) {
            frontendRedirect = FALLBACK_REDIRECT;
        }

        McpService service;
        try {
            service = svc.getMCPServiceByID(tenantId, serviceId);
        } catch (RuntimeException e) {
            service = null;
        }
        if (service == null) {
            throw BizException.notFound("MCP service not found");
        }
        if (service.getAuthConfig() == null || !service.getAuthConfig().isOAuth()) {
            throw new BizException(AppError.validation("MCP service is not configured to use OAuth"));
        }

        OAuthManager.StartResult result;
        try {
            result = oauth.startAuthorization(service, tenantId, principal, redirectUri, frontendRedirect);
        } catch (RuntimeException e) {
            log.error("failed to start MCP OAuth authorization, service_id={}",
                    LogSanitizer.sanitize(serviceId), e);
            throw BizException.internal("failed to start authorization: " + e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authorizationUrl", result.authorizationUrl());
        data.put("authorizationAttempt", result.attemptId());
        return ResponseEntity.ok(data);
    }

    // ── 2. 公开回调 ────────────────────────────────────────────────────

    /**
     * <b>公开路由</b>（无 bearer），靠一次性 state 自证。
     *
     * <p>结果经 URL fragment 回传前端：成功 {@code #mcp_oauth_result=success}，
     * 失败 {@code #mcp_oauth_error=<code>}。</p>
     */
    @GetMapping("/mcp-oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(value = "state", required = false) String stateRaw,
                                         @RequestParam(value = "code", required = false) String codeRaw,
                                         @RequestParam(value = "error", required = false) String errorRaw) {
        String state = trim(stateRaw);
        String code = trim(codeRaw);
        String providerError = trim(errorRaw);

        if (!providerError.isEmpty()) {
            return redirect(FALLBACK_REDIRECT + "#mcp_oauth_error=" + urlQueryEscape(providerError));
        }
        if (state.isEmpty() || code.isEmpty()) {
            return redirect(FALLBACK_REDIRECT + "#mcp_oauth_error="
                    + urlQueryEscape("missing_code_or_state"));
        }

        String frontendRedirect;
        String serviceId;
        try {
            OAuthManager.CompleteResult result = oauth.completeAuthorization(state, code);
            frontendRedirect = result.frontendRedirect();
            serviceId = result.serviceId();
        } catch (OAuthCallbackException e) {
            frontendRedirect = e.frontendRedirect();
            serviceId = e.serviceId();
            if (frontendRedirect.isEmpty()) {
                frontendRedirect = FALLBACK_REDIRECT;
            }
            // 失败路径不回收连接（旧的传输可能仍然是好的）
            log.error("MCP OAuth callback failed: {} (service_id={})", e.getMessage(),
                    LogSanitizer.sanitize(serviceId));
            return redirect(frontendRedirect + "#mcp_oauth_error=" + urlQueryEscape("authorization_failed"));
        }
        if (frontendRedirect.isEmpty()) {
            frontendRedirect = FALLBACK_REDIRECT;
        }
        // 旧传输可能是在已被判定失效的 OAuth 客户端注册上建的；按刚落库的新 token/客户端重建
        closeClient(serviceId);
        return redirect(frontendRedirect + "#mcp_oauth_result=success");
    }

    // ── 3. 授权状态 ────────────────────────────────────────────────────

    /**
     * 不带 {@code authorization_attempt} 时返回 token 生命周期状态；
     * 带的时候<b>只</b>回答"这一次尝试是否完成"（历史 token 不算数）。
     */
    @GetMapping("/mcp-services/{id}/oauth/status")
    public ResponseEntity<?> status(
            @PathVariable("id") String serviceId,
            @RequestParam(value = "authorization_attempt", required = false) String attemptIdRaw) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        if (tenantId == 0 || !McpPrincipal.valid(principal)) {
            throw BizException.unauthorized("authentication required");
        }

        String attemptId = trim(attemptIdRaw);
        if (!attemptId.isEmpty()) {
            boolean authorized;
            try {
                authorized = oauth.isAuthorizationAttemptComplete(tenantId, principal, serviceId, attemptId);
            } catch (RuntimeException e) {
                throw BizException.internal("failed to query authorization status: " + e.getMessage());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authorized", authorized);
            data.put("state", authorized ? "authorized" : "pending");
            return ResponseEntity.ok(data);
        }

        OAuthAuthorizationStatus status;
        try {
            status = oauth.authorizationStatus(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to query authorization status: " + e.getMessage());
        }
        return ResponseEntity.ok(status);
    }

    // ── 4. 撤销 ────────────────────────────────────────────────────────

    /** 撤销授权：204 + 回收缓存连接，让下一次调用重新走授权。 */
    @DeleteMapping("/mcp-services/{id}/oauth/token")
    public ResponseEntity<Void> revoke(@PathVariable("id") String serviceId) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        if (tenantId == 0 || !McpPrincipal.valid(principal)) {
            throw BizException.unauthorized("authentication required");
        }
        try {
            oauth.revoke(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to revoke authorization: " + e.getMessage());
        }
        closeClient(serviceId);
        return ResponseEntity.noContent().build();
    }

    // ── 5. 会话内 OAuth 挂起 / 取消 ─────────────────────────────────────

    /** 会话内 OAuth 处理的请求体（键名 {@code serviceId}）。 */
    public record ResolveRequest(String serviceId, String decision) {
    }

    /**
     * 前端在弹窗授权完成后调用；后端<b>先确认 token
     * 真的存在</b>再放行，免得过早/失败的授权把工具调用放回火坑再失败一次。
     */
    @PostMapping("/agent/mcp-oauth-resolutions/{pending_id}")
    public ResponseEntity<Void> resolveMcpOAuth(
            @PathVariable("pending_id") String pendingId,
            @RequestBody(required = false) ResolveRequest body) {
        long tenantId = tenantIdOrZero();
        TenantContext.Principal principal = McpPrincipal.oauthPrincipalFromContext();
        String gateUserId = gateUserId();
        if (tenantId == 0 || !McpPrincipal.valid(principal) || gateUserId.isEmpty()) {
            throw BizException.unauthorized("authentication required");
        }
        Gate approvalGate = gate.getIfAvailable();
        if (approvalGate == null) {
            throw BizException.internal("OAuth gate is not configured");
        }
        if (body == null) {
            // 空 body → 400 "EOF"（同 authorize-url）
            throw BizException.badRequest("EOF");
        }
        String serviceId = trim(body.serviceId());
        if (serviceId.isEmpty()) {
            throw new BizException(AppError.validation("service_id is required"));
        }

        String decision = trim(body.decision()).toLowerCase(java.util.Locale.ROOT);
        if (decision.isEmpty()) {
            decision = "authorize";
        }

        switch (decision) {
            case "cancel", "reject", "skip" -> {
                resolveGate(approvalGate, tenantId, gateUserId, pendingId, Decision.deny("user canceled"));
                return ResponseEntity.noContent().build();
            }
            case "authorize" -> {
                // 继续往下走
            }
            default -> throw BizException.badRequest("decision must be authorize or cancel");
        }

        // 只有用户真的持有 token 才恢复：否则重试只会再撞一次 authorization-required
        boolean authorized;
        try {
            authorized = oauth.isAuthorized(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to verify authorization: " + e.getMessage());
        }
        if (!authorized) {
            throw BizException.conflict("authorization not completed yet for this MCP service");
        }

        resolveGate(approvalGate, tenantId, gateUserId, pendingId, Decision.allow());
        return ResponseEntity.noContent().build();
    }

    /**
     * 用户主动跳过授权，以"拒绝"解除 Agent 阻塞。
     */
    @PostMapping("/agent/mcp-oauth-resolutions/{pending_id}/cancel")
    public ResponseEntity<Void> cancelMcpOAuth(@PathVariable("pending_id") String pendingId) {
        long tenantId = tenantIdOrZero();
        String gateUserId = gateUserId();
        if (tenantId == 0 || gateUserId.isEmpty()) {
            throw BizException.unauthorized("authentication required");
        }
        Gate approvalGate = gate.getIfAvailable();
        if (approvalGate == null) {
            throw BizException.internal("OAuth gate is not configured");
        }
        resolveGate(approvalGate, tenantId, gateUserId, pendingId, Decision.deny("user canceled"));
        return ResponseEntity.noContent().build();
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    /**
     * token 用 principal 走 oauthPrincipalFromContext（embed 访客会被细分），
     * 而 gate 的 userID 用<b>原始</b> principal 的 StorageID（会话所有者）。
     */
    private static String gateUserId() {
        TenantContext.Principal raw = McpPrincipal.normalize(McpPrincipal.fromContext());
        if (McpPrincipal.valid(raw)) {
            return McpPrincipal.storageId(raw);
        }
        return "";
    }

    private static long tenantIdOrZero() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    /**
     * {@code gate.Resolve} 的错误映射。
     *
     * <p>四类哨兵 → 404 / 400 各一，其余（INTERNAL）→ 500（文案取异常消息）。</p>
     */
    private static void resolveGate(Gate approvalGate, long tenantId, String gateUserId,
                                    String pendingId, Decision decision) {
        try {
            approvalGate.resolve(tenantId, gateUserId, pendingId, decision);
        } catch (ApprovalException e) {
            switch (e.kind()) {
                case PENDING_NOT_FOUND ->
                        throw BizException.notFound("pending authorization not found or already completed");
                case ALREADY_RESOLVED -> throw BizException.badRequest(
                        "pending authorization already resolved (timeout / cancel raced your action)");
                case TENANT_MISMATCH -> throw BizException.badRequest("workspace mismatch");
                case USER_MISMATCH -> throw BizException.badRequest(
                        "user mismatch: only the session owner may resolve this prompt");
                default -> {
                    log.error("failed to resolve MCP OAuth pending, pending_id={}",
                            LogSanitizer.sanitize(pendingId), e);
                    throw BizException.internal(String.valueOf(e.getMessage()));
                }
            }
        }
    }

    /** manager 已接线且 serviceId 非空时，回收该服务的缓存连接。 */
    private void closeClient(String serviceId) {
        if (serviceId == null || serviceId.isEmpty()) {
            return;
        }
        McpClientManager manager = mcpManager.getIfAvailable();
        if (manager == null) {
            return;
        }
        try {
            manager.closeClient(serviceId);
        } catch (RuntimeException e) {
            // 回收失败不影响本次响应
            log.warn("failed to close MCP client after OAuth change, service_id={}",
                    LogSanitizer.sanitize(serviceId), e);
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    /**
     * fragment 值的轻量转义：只替换 7 个字符，<b>不是</b>完整的 percent-encoding——
     * 顺序敏感，必须先把 {@code %} 换成 {@code %25} 再处理其余，否则会二次编码。
     */
    static String urlQueryEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("%", "%25")
                .replace(" ", "%20")
                .replace("#", "%23")
                .replace("&", "%26")
                .replace("+", "%2B")
                .replace("=", "%3D")
                .replace("?", "%3F");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
