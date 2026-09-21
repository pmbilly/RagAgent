package com.ragagent.auth.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.auth.service.ValidatedToken;
import com.ragagent.common.context.TenantContext;
import com.ragagent.config.TenantProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 对照 Go internal/middleware/ws_auth.go 的 AttachAuthenticatedUser（W5d 沙箱终端 WS 用）。
 *
 * <p>本 bean 承载「给已解析的用户安装与 Auth 中间件相同的认证会话」这条能力链
 * （空间解析 → 成员判定 → 角色装配），从 {@link AuthFilter} 抽出：{@code AuthFilter}
 * 不是 Spring bean（{@code WebConfig} 里 {@code new AuthFilter(...)} 塞进
 * {@code FilterRegistrationBean}），而 W5d 的 {@code SandboxTerminalController}
 * 需要注入这条能力——过滤器依赖网保持小（本类只依赖 user/tenant/member 服务与
 * TenantProperties，不碰 APIKeyAuthChannel）。{@code AuthFilter} 自身的通道 2
 * （Bearer JWT）同样委托本类，保证两条入口的认证装配逻辑只有一份。</p>
 */
@Component
public class WsAuthSupport {

    private static final Logger log = LoggerFactory.getLogger(WsAuthSupport.class);

    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberService memberService;
    private final TenantProperties tenantProperties;

    public WsAuthSupport(UserService userService,
                         TenantService tenantService,
                         TenantMemberService memberService,
                         TenantProperties tenantProperties) {
        this.userService = userService;
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.tenantProperties = tenantProperties;
    }

    /**
     * 对照 AttachAuthenticatedUser（middleware/ws_auth.go 全文，W5d 沙箱终端 WS 用）：
     * 给**已解析**的用户安装与 Auth 中间件相同的认证会话。浏览器 WS 握手带不了
     * Authorization 头——票据在 handler 内解析出 user 后，由本方法完成空间解析、
     * 成员判定与角色装配（tenant 取 jwtTenantID，除非调用方已带 X-Tenant-ID 头）。
     * 返回 false 表示响应已写入。
     */
    public boolean attachAuthenticatedUser(HttpServletRequest request, HttpServletResponse response,
                                           User user, long jwtTenantId) throws IOException {
        if (user == null) {
            writeUnauthorized(response, "Unauthorized: invalid or expired token");
            return false;
        }
        if (jwtTenantId != 0
                && (request.getHeader("X-Tenant-ID") == null
                        || request.getHeader("X-Tenant-ID").trim().isEmpty())) {
            // 对照 Go 的 c.Request.Header.Set（L43-45）：只改本请求可见的头
            request = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if ("X-Tenant-ID".equalsIgnoreCase(name)) {
                        return Long.toUnsignedString(jwtTenantId);
                    }
                    return super.getHeader(name);
                }

                @Override
                public java.util.Enumeration<String> getHeaders(String name) {
                    if ("X-Tenant-ID".equalsIgnoreCase(name)) {
                        return java.util.Collections.enumeration(
                                java.util.List.of(Long.toUnsignedString(jwtTenantId)));
                    }
                    return super.getHeaders(name);
                }
            };
        }
        return authenticateJwtUser(request, response, new ValidatedToken(user, jwtTenantId));
    }

    /**
     * 对照 authenticateJWTUser（auth.go L197-267）。
     * 返回 true 表示可继续链路；false 表示响应已写入。
     */
    boolean authenticateJwtUser(HttpServletRequest request, HttpServletResponse response,
                                ValidatedToken vt) throws IOException {
        User user = vt.user();
        long homeTenantId = user.getTenantId() == null ? 0 : user.getTenantId();

        TargetResolution target = resolveTargetTenant(request, response, vt, homeTenantId);
        if (target == null) {
            return false;
        }

        if (target.tenantId() == 0) {
            // 无可用空间：身份级路由放行 tenantless，其余 TENANT_REQUIRED
            if (AuthFilter.isTenantOptionalAPI(request.getRequestURI(), request.getMethod())) {
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
