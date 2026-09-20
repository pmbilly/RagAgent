package com.ragagent.auth.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.TokenValidationException;
import com.ragagent.auth.service.UserService;
import com.ragagent.auth.service.ValidatedToken;
import com.ragagent.common.context.TenantContext;
import com.ragagent.config.TenantProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 对照 Go internal/middleware/auth.go 的 Auth() 三通道认证链（阶段 1 全量翻译）。
 *
 * 通道顺序（与 Go 严格一致）：
 *  1. OPTIONS 预检 / noAuthAPI 白名单 → 直接放行
 *  2. Bearer JWT → UserService.validateToken；成功走 authenticateJWTUser
 *     （空间解析 → TENANT_REQUIRED/角色解析）；失败不立即拒绝，继续通道 3
 *  3. X-API-Key → 阶段 1 未实现 TenantAPIKeyService → 固定
 *     401 {"error":"Unauthorized: API key service is not configured"}（对照 Go apiKeyService==nil 分支）
 *  全部未命中 → 401（bearerPresented 决定消息区分"未登录"与"登录态过期"）
 *
 * 覆盖 /*：Go 的 Auth 挂在 engine 全局，未匹配路径同样 401（golden 已锁定）。
 * 未匹配 controller 的放行请求由 DispatcherServlet 返回 404——与 Go 不同，记录在约定 §8
 * （Go 有对应 handler；Java 端点随模块翻译逐步补齐）。
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    /** 对照 noAuthAPI（auth.go L36-63）：精确路径 → 允许方法 */
    private static final Map<String, Set<String>> NO_AUTH_API = Map.ofEntries(
            Map.entry("/health", Set.of("GET")),
            Map.entry("/api/v1/auth/register", Set.of("POST")),
            Map.entry("/api/v1/auth/login", Set.of("POST")),
            Map.entry("/api/v1/auth/auto-setup", Set.of("POST")),
            Map.entry("/api/v1/auth/invitations/lookup", Set.of("POST")),
            Map.entry("/api/v1/auth/register-by-invite", Set.of("POST")),
            Map.entry("/api/v1/auth/config", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/config", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/url", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/start", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/callback", Set.of("GET")),
            Map.entry("/api/v1/mcp-oauth/callback", Set.of("GET")),
            // 波 3 browserskill：/api/v1/local-browser 三条在 Go router.go L184-186
            // 注册于 Auth 中间件**之前**（引擎级路由）——扩展靠 WS 子协议票据、
            // authorize 靠一次性 Bearer 票据、internal 靠 HMAC 签名各自鉴权，
            // Java 侧经本白名单获得等价的"无 Auth"语义（X-API-Key 同样被忽略）。
            Map.entry("/api/v1/local-browser/extension", Set.of("GET")),
            Map.entry("/api/v1/local-browser/extension/authorize", Set.of("POST")),
            Map.entry("/api/v1/local-browser/internal", Set.of("POST")),
            Map.entry("/api/v1/auth/refresh", Set.of("POST")),
            Map.entry("/api/v1/files/presigned", Set.of("GET", "HEAD")));

    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberService memberService;
    private final TenantProperties tenantProperties;
    private final com.ragagent.apikey.filter.APIKeyAuthChannel apiKeyAuthChannel;

    public AuthFilter(UserService userService,
                      TenantService tenantService,
                      TenantMemberService memberService,
                      TenantProperties tenantProperties,
                      com.ragagent.apikey.filter.APIKeyAuthChannel apiKeyAuthChannel) {
        this.userService = userService;
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.tenantProperties = tenantProperties;
        this.apiKeyAuthChannel = apiKeyAuthChannel;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 通道 0：OPTIONS 预检
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 1：白名单
        if (isNoAuthAPI(request.getRequestURI(), request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 1.5：embed 公开面（对照 Go：embed 路由组注册在 engine 上、不进全局 Auth 组，
        // 由 middleware.EmbedAuth 自行鉴权——Java 侧由 com.ragagent.embed 的
        // EmbedAuthFilter 承担，本过滤器整体让路）。
        if (request.getRequestURI().startsWith("/api/v1/embed/")) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 2：Bearer JWT
        boolean bearerPresented = false;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            bearerPresented = true;
            String token = authHeader.substring("Bearer ".length());
            try {
                ValidatedToken vt = userService.validateToken(token);
                if (authenticateJwtUser(request, response, vt)) {
                    chain.doFilter(request, response);
                }
                return;
            } catch (TokenValidationException e) {
                // 对照 Go：bearer 校验失败继续尝试 API key，不立即拒绝
                log.warn("[auth] bearer token rejected: {}", e.getMessage());
            }
        }

        // 通道 3：X-API-Key（对照 Go middleware/auth.go 的 apiKeyService 分支）。
        // 鉴权通过时由 channel 自行写入 principal/scope 上下文，不再往下走其它通道。
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            if (apiKeyAuthChannel.authenticate(request, response)) {
                chain.doFilter(request, response);
            }
            return;
        }

        // 全部未命中
        writeUnauthorized(response, bearerPresented
                ? "Unauthorized: invalid or expired token"
                : "Unauthorized: missing authentication");
    }

    /** 对照 isNoAuthAPI：全路径精确匹配 + 方法包含 */
    static boolean isNoAuthAPI(String path, String method) {
        Set<String> methods = NO_AUTH_API.get(path);
        return methods != null && methods.contains(method);
    }

    /** 对照 isTenantOptionalAPI（auth.go L84-105）：tenantless 放行清单 */
    static boolean isTenantOptionalAPI(String path, String method) {
        if ("/api/v1/auth/me".equals(path)
                && ("GET".equals(method) || "PUT".equals(method))) {
            return true;
        }
        if ("/api/v1/auth/me/preferences".equals(path) && "PUT".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/logout".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/change-password".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/validate".equals(path) && "GET".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/switch-tenant".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/v1/tenants".equals(path) && "POST".equals(method)) {
            return true;
        }
        return path != null && path.startsWith("/api/v1/me/invitations");
    }

    /**
     * 对照 authenticateJWTUser（auth.go L197-267）。
     * 返回 true 表示可继续链路；false 表示响应已写入。
     */
    private boolean authenticateJwtUser(HttpServletRequest request, HttpServletResponse response,
                                        ValidatedToken vt) throws IOException {
        User user = vt.user();
        long homeTenantId = user.getTenantId() == null ? 0 : user.getTenantId();

        TargetResolution target = resolveTargetTenant(request, response, vt, homeTenantId);
        if (target == null) {
            return false;
        }

        if (target.tenantId() == 0) {
            // 无可用空间：身份级路由放行 tenantless，其余 TENANT_REQUIRED
            if (isTenantOptionalAPI(request.getRequestURI(), request.getMethod())) {
                TenantContext.set(null, TenantContext.webUserPrincipal(user.getId()), null, user.isIsSystemAdmin(), user.getId(), user.isCanAccessAllTenants());
                return true;
            }
            response.setStatus(409);
            // Go 侧为 gin.H map，键按字母序：code < error
            writeJson(response, "{\"code\":\"TENANT_REQUIRED\",\"error\":\"Workspace required\"}");
            return false;
        }

        Tenant tenant = target.tenant();
        if (tenant == null) {
            tenant = tenantService.getTenantById(target.tenantId());
            if (tenant == null) {
                log.warn("[auth] tenant lookup failed: tenant={} user={}", target.tenantId(), user.getId());
                writeUnauthorized(response, "Unauthorized: invalid workspace");
                return false;
            }
        }

        TenantRole role = resolveTenantRole(user, target.tenantId(), target.crossTenantSwitch());
        if (role == null) {
            // 强制 RBAC 且无任何成员关系 → 403（fail-open 已在 resolveTenantRole 内部处理）
            log.warn("User {} has no active membership in tenant {}", user.getId(), target.tenantId());
            writePlainError(response, 403, "Forbidden: not a member of the target workspace");
            return false;
        }

        log.info("[auth] resolved role={} for user={} in tenant={} (jwt_tenant={}, header={}, cross_switch={})",
                role.value(), user.getId(), target.tenantId(), vt.tenantId(),
                request.getHeader("X-Tenant-ID"), target.crossTenantSwitch());
        TenantContext.set(target.tenantId(), TenantContext.webUserPrincipal(user.getId()), role.value(),
                user.isIsSystemAdmin(), user.getId(), user.isCanAccessAllTenants());
        return true;
    }

    /**
     * 对照 resolveTargetTenant（auth.go L284-338）。
     * 优先级：X-Tenant-ID 头 → JWT tenant claim（fallback user.TenantID）→ 首个 active membership。
     * 返回 null 表示响应已写入（畸形头/无权限/目标不存在）。
     */
    private TargetResolution resolveTargetTenant(HttpServletRequest request, HttpServletResponse response,
                                                 ValidatedToken vt, long homeTenantId) throws IOException {
        long targetTenantId = vt.tenantId();
        if (targetTenantId == 0) {
            targetTenantId = homeTenantId;
        }

        String tenantHeader = request.getHeader("X-Tenant-ID");
        if (tenantHeader != null && !tenantHeader.isEmpty()) {
            long parsedTenantId;
            try {
                parsedTenantId = Long.parseLong(tenantHeader);
            } catch (NumberFormatException e) {
                parsedTenantId = 0;
            }
            // 对照 strconv.ParseUint：负数/0/溢出均视为畸形
            if (parsedTenantId <= 0) {
                log.warn("Invalid X-Tenant-ID header from user={}: \"{}\"", vt.user().getId(), tenantHeader);
                writePlainError(response, 400, "Invalid X-Tenant-ID header");
                return null;
            }
            if (!isTenantAccessible(vt.user(), parsedTenantId)) {
                log.warn("User {} attempted to access tenant {} without permission",
                        vt.user().getId(), parsedTenantId);
                writePlainError(response, 403, "Forbidden: insufficient permissions to access target workspace");
                return null;
            }
            Tenant targetTenant = tenantService.getTenantById(parsedTenantId);
            if (targetTenant == null) {
                log.warn("Error getting target tenant by ID: tenantID={}", parsedTenantId);
                writePlainError(response, 400, "Invalid target workspace ID");
                return null;
            }
            log.info("User {} switching to tenant {}", vt.user().getId(), parsedTenantId);
            return new TargetResolution(parsedTenantId, targetTenant, parsedTenantId != homeTenantId);
        }

        if (targetTenantId == 0) {
            targetTenantId = resolveFirstMembershipTarget(vt.user());
        }
        return new TargetResolution(targetTenantId, null, targetTenantId != homeTenantId);
    }

    /** 对照 IsTenantAccessible（access.go L77-101）：home / 跨空间超管 / active membership */
    private boolean isTenantAccessible(User user, long targetTenantId) {
        if (user == null || targetTenantId == 0) {
            return false;
        }
        long home = user.getTenantId() == null ? 0 : user.getTenantId();
        if (home == targetTenantId) {
            return true;
        }
        if (tenantProperties.enableCrossTenantAccess() && user.isCanAccessAllTenants()) {
            return true;
        }
        TenantMember m = memberService.getMembership(user.getId(), targetTenantId);
        return m != null && TenantMemberService.STATUS_ACTIVE.equals(m.getStatus());
    }

    /** 对照 resolveFirstMembershipTarget（auth.go L345-369） */
    private long resolveFirstMembershipTarget(User user) {
        if (user == null) {
            return 0;
        }
        List<TenantMember> members = memberService.listByUser(user.getId());
        for (TenantMember member : members) {
            if (member == null
                    || member.getTenantId() == null || member.getTenantId() == 0
                    || !TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
                continue;
            }
            if (tenantService.getTenantById(member.getTenantId()) != null) {
                return member.getTenantId();
            }
        }
        return 0;
    }

    /**
     * 对照 resolveTenantRole（auth.go L750-827）。返回 null 表示拒绝（调用方 403）：
     *  1. active membership → 该行 role
     *  2. 跨空间超管（crossTenantSwitch && CanAccessAllTenants）→ 临时 Admin（不落库）
     *  3. 孤儿空间自愈：home tenant 且无任何 active 成员 → 自动晋升 Owner 并落库
     *  4. EnableRBAC（默认 true）→ null；否则 fail-open Admin
     */
    private TenantRole resolveTenantRole(User user, long targetTenantId, boolean crossTenantSwitch) {
        TenantMember member = memberService.getMembership(user.getId(), targetTenantId);
        if (member != null && TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
            return TenantRole.fromString(member.getRole());
        }

        if (crossTenantSwitch && user.isCanAccessAllTenants()) {
            log.info("[auth] resolveTenantRole step2 (cross-tenant superuser) -> Admin: user={} tenant={}",
                    user.getId(), targetTenantId);
            return TenantRole.ADMIN;
        }

        long home = user.getTenantId() == null ? 0 : user.getTenantId();
        boolean isHomeTenant = !crossTenantSwitch && targetTenantId == home;
        if (isHomeTenant && !memberService.hasAnyActiveMembers(targetTenantId)) {
            try {
                memberService.addMember(user.getId(), targetTenantId, TenantRole.OWNER.value(), null);
                log.info("[audit] Auto-promoted user {} to Owner of orphan tenant {} (home_tenant=true)",
                        user.getId(), targetTenantId);
                return TenantRole.OWNER;
            } catch (RuntimeException e) {
                log.warn("Failed to auto-promote user {} in tenant {}: {}", user.getId(), targetTenantId, e.toString());
            }
        }

        if (tenantProperties.isRbacEnforced()) {
            log.warn("[auth] resolveTenantRole step4 fail-closed (EnableRBAC=true): user={} tenant={}",
                    user.getId(), targetTenantId);
            return null;
        }
        log.warn("[auth] resolveTenantRole step4 fail-open (EnableRBAC=false) -> Admin: user={} tenant={}",
                user.getId(), targetTenantId);
        return TenantRole.ADMIN;
    }

    private record TargetResolution(long tenantId, Tenant tenant, boolean crossTenantSwitch) {}

    // ── 响应写入（契约逐字符锁定，golden 测试比对） ──────────────────────────

    /** 对照 gin JSON 401：{"error":"Unauthorized: ..."} */
    static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        writePlainError(response, 401, message);
    }

    static void writePlainError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        writeJson(response, "{\"error\":\"" + message + "\"}");
    }

    static void writeJson(HttpServletResponse response, String body) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
