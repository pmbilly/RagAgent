package com.ragagent.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.tenant.TenantProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 路由级 RBAC 拦截器（角色下限 / 系统管理员守卫 / 跨空间守卫）。
 *
 * 判定顺序：
 *  1. 角色达标（TenantRole level 比较）→ 放行
 *  2. 跨空间超管（EnableCrossTenantAccess && CanAccessAllTenants）→ 放行
 *  3. EnableRBAC=false（滚动窗口）→ 记日志放行
 *  4. 否则 403 {"error":"Forbidden: insufficient workspace role"}
 *
 * API-key 主体在此短路放行（能力维度由 APIKeyGate 全权判定），
 * 见下方的 {@code APIKeyScopeContext.present()} 分支；系统管理员与所有权守卫
 * 随对应模块（system admin / KB / agent）落在各自入口。
 *
 * <p><b>拒绝审计</b>：拒绝分支会调用
 * {@link DeniedAuditor}，把拒绝落成一条 durable 的 {@code rbac.access_denied}
 * 审计行。服务内部有 1 分钟
 * 滑动窗口去重，所以被打的端点不会以线速刷表。</p>
 */
public class RbacInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RbacInterceptor.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * 一条 RBAC 规则：方法 + Ant 路径 + 角色下限 + 是否允许系统管理员绕过 + 是否**仅限**系统管理员
     * + 是否跨空间守卫。
     *
     * <p>后三者是不同语义，别混：{@code orSystemAdmin} 是"租户角色达标**或**系统管理员"（放行条件），
     * {@code sysAdminOnly} 是"必须是系统管理员"（限定条件）。
     * 用前者表达后者会把租户 Owner 也放进来。
     * （注记：{@code crossTenant} 随空间分享裁撤已无规则置位，判定分支已移除。）</p>
     */
    public record Rule(String method, String pattern, TenantRole minRole, boolean orSystemAdmin,
                       boolean sysAdminOnly, boolean crossTenant) {}

    /**
     * 拒绝审计回调。
     *
     * <p>{@code requestPath} 传<b>路由模板</b>——这是 Spring 侧对 gin {@code c.FullPath()}
     * 的等价物（用 {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}，拿不到时才回落到
     * RBAC 规则的 Ant 模式），目的见 AuditLogService 的注释（防止遍历 URL 里的 UUID
     * 绕过去重窗口）。{@code rawPath} 是原始 URI，只在与模板不同时才会被服务写进
     * Details 的 {@code raw_path}。</p>
     *
     * <p>⚠️ 路由模板用 Spring 的 {@code {id}} 记法
     * （{@code /api/v1/tenants/{id}/audit-log}）。
     * 去重键仍然稳定（同一路由恒得同一串）。</p>
     */
    @FunctionalInterface
    public interface DeniedAuditor {
        void logDenied(long tenantId, String actorUserId, String actorRole,
                       String requiredRole, String requestPath, String requestMethod, String rawPath);
    }

    /** 未注册审计实现时的空操作。 */
    private static final DeniedAuditor NOOP_AUDITOR =
            (tenantId, actorUserId, actorRole, requiredRole, requestPath, requestMethod, rawPath) -> {};

    /**
     * 进程级注册点。本拦截器由 {@code WebConfig} 直接 {@code new} 出来（不是 Spring bean），
     * 拿不到依赖注入，所以审计实现由 {@code com.ragagent.audit.service.RbacDeniedAuditorRegistrar}
     * 在启动时注册进来。
     */
    private static volatile DeniedAuditor deniedAuditor = NOOP_AUDITOR;

    public static void setDeniedAuditor(DeniedAuditor auditor) {
        deniedAuditor = auditor == null ? NOOP_AUDITOR : auditor;
    }

    public static DeniedAuditor deniedAuditor() {
        return deniedAuditor;
    }

    private final List<Rule> rules = new ArrayList<>();
    private final TenantProperties tenantProperties;

    public RbacInterceptor(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    public RbacInterceptor addRule(String method, String pattern, TenantRole minRole, boolean orSystemAdmin) {
        rules.add(new Rule(method, pattern, minRole, orSystemAdmin, false, false));
        return this;
    }

    /**
     * 仅系统管理员可访问。
     * 用于 {@code /api/v1/system/**} 这类平台级端点——租户角色再高也不放行。
     */
    public RbacInterceptor addSystemAdminRule(String method, String pattern) {
        rules.add(new Rule(method, pattern, TenantRole.ADMIN, false, true, false));
        return this;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // URL 里的租户 id 必须等于活动租户。
        // 自动对所有 /api/v1/tenants/{id}/** 生效（漏配规则的代价是越权读取别人的审计/密钥列表）。
        // 必须先于 API-Key 短路执行（对 Key 主体同样生效）——
        // 否则持有 A 空间 Key 的调用方可把 URL 里的 id 换成 B 空间去增删对方成员/邀请。
        if (!checkPathTenantMatch(request, response)) {
            return false;
        }
        // API-Key 主体直接放行——能力维度由 APIKeyGate 全权判定，
        // 否则 full-access Key 会被这里的角色下限拦住
        if (com.ragagent.auth.apikey.domain.APIKeyScopeContext.present()) {
            return true;
        }

        Rule rule = match(request.getMethod(), request.getRequestURI());
        if (rule == null) {
            // 未声明路由：该组默认无守卫，不拦截（API-key default-deny 属 APIKeyGate）
            return true;
        }
        if (check(rule)) {
            return true;
        }
        log.warn("[rbac] role insufficient: user={} have={} need={} path={}",
                TenantContext.currentUserId(), TenantContext.currentRole(),
                rule.minRole().value(), request.getRequestURI());
        // 拒绝时写一条 durable 审计行。
        // 非持久化的告警行（上一行 log.warn）依然每次拒绝都打——去重只压制落库。
        // 审计失败绝不能把一次 403 变成 500。
        try {
            Long tenantId = TenantContext.currentTenantId();
            deniedAuditor.logDenied(
                    tenantId == null ? 0L : tenantId,
                    TenantContext.currentUserId(),
                    TenantContext.currentRole(),
                    rule.sysAdminOnly() ? "system_admin" : rule.minRole().value(),
                    routeTemplate(request, rule),
                    request.getMethod(),
                    request.getRequestURI());
        } catch (RuntimeException e) {
            log.warn("[rbac] denied-audit write failed (ignored): path={} err={}",
                    request.getRequestURI(), e.getMessage());
        }
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 两种守卫的文案不同，golden 钉住：
        // RequireRole → "Forbidden: insufficient workspace role"；
        // RequireSystemAdmin → "Forbidden: system administrator required"。
        response.getWriter().write(rule.sysAdminOnly()
                ? "{\"error\":\"Forbidden: system administrator required\"}"
                : "{\"error\":\"Forbidden: insufficient workspace role\"}");
        return false;
    }

    /**
     * 取当前请求的路由模板（对照 gin 的 {@code c.FullPath()}）。
     *
     * <p>拦截器 preHandle 跑在 handler mapping 之后，所以
     * {@code BEST_MATCHING_PATTERN_ATTRIBUTE} 已经就位；理论上拿不到时（未映射路径）
     * 回落到本拦截器命中的 Ant 规则模式——无论如何都<b>不能</b>退回原始 URI，
     * 那会让 URL 里的 UUID 变成新的去重键。</p>
     */
    private static String routeTemplate(HttpServletRequest request, Rule rule) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isEmpty()) {
            return s;
        }
        return rule.pattern();
    }

    private Rule match(String method, String uri) {
        for (Rule rule : rules) {
            if (rule.method().equals(method) && MATCHER.match(rule.pattern(), uri)) {
                return rule;
            }
        }
        return null;
    }

    /** 对照 RequireRole / RequireRoleOrSystemAdmin / RequireSystemAdmin 判定链 */
    private boolean check(Rule rule) {
        // API-key 主体由 APIKeyGate 全权判定（能力 + KB 白名单 + default-deny），
        // 角色阶梯不适用于机器主体——角色下限规则短路放行；
        // sysAdminOnly 规则只放行平台 Key、拒绝租户 Key。
        com.ragagent.auth.apikey.domain.TenantAPIKeyScope apiKeyScope =
                com.ragagent.auth.apikey.domain.APIKeyScopeContext.current();
        if (apiKeyScope != null) {
            if (rule.sysAdminOnly()) {
                if (apiKeyScope.isPlatform()) {
                    return true;
                }
                log.warn("[rbac] system admin required: API-key principal denied path-rule={}",
                        rule.pattern());
                return false;
            }
            return true;
        }
        if (rule.sysAdminOnly()) {
            // 仅系统管理员：租户角色不参与判定
            if (TenantContext.isSystemAdmin()) {
                return true;
            }
            if (!tenantProperties.isRbacEnforced()) {
                log.warn("[rbac] system-admin required (logged but not enforced): user={} path-rule={}",
                        TenantContext.currentUserId(), rule.pattern());
                return true;
            }
            return false;
        }
        if (rule.orSystemAdmin() && TenantContext.isSystemAdmin()) {
            return true;
        }
        TenantRole role = TenantRole.fromString(TenantContext.currentRole());
        if (role.hasPermission(rule.minRole())) {
            return true;
        }
        if (!tenantProperties.isRbacEnforced()) {
            log.warn("[rbac] role insufficient (logged but not enforced): user={} have={} need={}",
                    TenantContext.currentUserId(), role.value(), rule.minRole().value());
            return true;
        }
        return false;
    }

    /**
     * 对 {@code /api/v1/tenants/{id}/**} 强制 URL 里的租户 == 调用方活动租户。
     *
     * <p><b>门控范围</b>：仅当最佳匹配模板的租户段是 {@code {...}} 占位符
     * （形如 {@code /api/v1/tenants/{id}/...}）才执行；静态段路由
     * （{@code /tenants/all}、{@code /tenants/search}、{@code /tenants/kv/{key}}）不查——
     * 若对所有首段强制数字解析，会把 "all"/"kv" 误判成 400。</p>
     *
     * <p>没有这层时，租户 A 的 Owner 可以把 URL 里的 id 换成租户 B 去读对方的
     * 审计日志 / API Key 列表——角色下限（Owner）照样满足。</p>
     *
     * <p>错误形态：空 → 400、非正整数 → 400、上下文无租户 → 401（fail closed）、
     * 不匹配 → 403；跨租户超管放行。</p>
     */
    private boolean checkPathTenantMatch(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/v1/tenants/")) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String p) || !p.startsWith("/api/v1/tenants/{")) {
            // 静态段路由（/all、/search、/kv/{key}）或拿不到模板：不在占位符组，不查
            return true;
        }
        String rest = uri.substring("/api/v1/tenants/".length());
        int slash = rest.indexOf('/');
        String raw = (slash < 0 ? rest : rest.substring(0, slash)).trim();

        if (raw.isEmpty()) {
            throw new BizException(AppError.validation("workspace id is required"));
        }
        long pathTenantId;
        try {
            pathTenantId = Long.parseLong(raw);
            if (pathTenantId <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("workspace id must be a positive integer"));
        }

        Long ctxTenantId = TenantContext.currentTenantId();
        if (ctxTenantId == null || ctxTenantId == 0) {
            // Auth 中间件本应已设置；没设置时**失败关闭**，而不是把"无上下文"当成匹配
            log.warn("[rbac] path-tenant-match: no tenant in ctx, path={}", uri);
            throw new BizException(AppError.unauthorized("workspace context missing"));
        }
        if (pathTenantId == ctxTenantId) {
            return true;
        }
        // 平台 Key 等价跨租户超管，放行
        com.ragagent.auth.apikey.domain.TenantAPIKeyScope keyScope =
                com.ragagent.auth.apikey.domain.APIKeyScopeContext.current();
        if (keyScope != null && keyScope.isPlatform()) {
            return true;
        }
        log.warn("[rbac] path-tenant-match rejected: user={} ctx_tenant={} path_tenant={} path={}",
                TenantContext.currentUserId(), ctxTenantId, pathTenantId, uri);
        throw new BizException(AppError.forbidden(
                "Access denied: URL workspace does not match the active workspace"));
    }
}
