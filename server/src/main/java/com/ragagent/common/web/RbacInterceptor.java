package com.ragagent.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.config.TenantProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 对照 Go middleware/rbac.go 的 RequireRole / RequireRoleOrSystemAdmin。
 *
 * 判定顺序（与 Go 严格一致）：
 *  1. 角色达标（TenantRole level 比较）→ 放行
 *  2. 跨空间超管（EnableCrossTenantAccess && CanAccessAllTenants）→ 放行
 *  3. EnableRBAC=false（滚动窗口）→ 记日志放行
 *  4. 否则 403 {"error":"Forbidden: insufficient workspace role"}
 *
 * 阶段说明：Go 对 API-key 主体在 RequireRole 短路（由 APIKeyGate 全权判定），
 * Java 阶段 1 未实现 API key 主体 → 无此分支；RequireSystemAdmin /
 * RequireOwnershipOrRole 随对应模块（system admin / KB / agent）翻译。
 * 审计落库（AuditService.LogDenied）未翻译，仅记日志。
 */
public class RbacInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RbacInterceptor.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 一条 RBAC 规则：方法 + Ant 路径 + 角色下限 + 是否允许系统管理员绕过 */
    public record Rule(String method, String pattern, TenantRole minRole, boolean orSystemAdmin) {}

    private final List<Rule> rules = new ArrayList<>();
    private final TenantProperties tenantProperties;

    public RbacInterceptor(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    public RbacInterceptor addRule(String method, String pattern, TenantRole minRole, boolean orSystemAdmin) {
        rules.add(new Rule(method, pattern, minRole, orSystemAdmin));
        return this;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Rule rule = match(request.getMethod(), request.getRequestURI());
        if (rule == null) {
            // 未声明路由：对照 Go 该组默认无守卫时不拦截（API-key default-deny 属 APIKeyGate，未翻译）
            return true;
        }
        if (check(rule)) {
            return true;
        }
        log.warn("[rbac] role insufficient: user={} have={} need={} path={}",
                TenantContext.currentUserId(), TenantContext.currentRole(),
                rule.minRole().value(), request.getRequestURI());
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"Forbidden: insufficient workspace role\"}");
        return false;
    }

    private Rule match(String method, String uri) {
        for (Rule rule : rules) {
            if (rule.method().equals(method) && MATCHER.match(rule.pattern(), uri)) {
                return rule;
            }
        }
        return null;
    }

    /** 对照 RequireRole / RequireRoleOrSystemAdmin 判定链 */
    private boolean check(Rule rule) {
        if (rule.orSystemAdmin() && TenantContext.isSystemAdmin()) {
            return true;
        }
        TenantRole role = TenantRole.fromString(TenantContext.currentRole());
        if (role.hasPermission(rule.minRole())) {
            return true;
        }
        if (tenantProperties.enableCrossTenantAccess() && TenantContext.canAccessAllTenants()) {
            return true;
        }
        if (!tenantProperties.isRbacEnforced()) {
            log.warn("[rbac] role insufficient (logged but not enforced): user={} have={} need={}",
                    TenantContext.currentUserId(), role.value(), rule.minRole().value());
            return true;
        }
        return false;
    }
}
