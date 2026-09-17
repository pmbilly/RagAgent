package com.ragagent.audit.service;

import com.ragagent.common.web.RbacInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 把 {@link AuditLogService} 接进 RBAC 拒绝分支（对照 Go
 * {@code middleware/audit_provider.go} 的 {@code AuditServiceProvider} +
 * {@code middleware/rbac.go} L99-106 的 {@code AuditServiceFromContext}）。
 *
 * <p><b>为什么需要这个注册器</b>：{@code RbacInterceptor} 由
 * {@code config/WebConfig} 直接 {@code new} 出来（不是 Spring bean），拿不到依赖注入；
 * Go 那边则是把 service 塞进 gin 的请求上下文（{@code c.Set(auditServiceContextKey, svc)}）。
 * 两种手法都是"进程级装配"，Java 侧的等价物就是
 * {@link RbacInterceptor#setDeniedAuditor} 的静态注册点。</p>
 *
 * <p>这一条是约定 §9「阶段 1 已知差异」第 8 条的正式回补：
 * 此前 RBAC 拒绝只记日志、不落库。</p>
 *
 * <p>关停时把钩子复位成空操作（多 Spring 上下文并存的测试环境里，
 * 陈旧的钩子会指向已关闭的上下文）。</p>
 */
@Component
public class RbacDeniedAuditorRegistrar implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RbacDeniedAuditorRegistrar.class);

    private final AuditLogService auditLogService;

    public RbacDeniedAuditorRegistrar(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void afterPropertiesSet() {
        RbacInterceptor.setDeniedAuditor((tenantId, actorUserId, actorRole, requiredRole,
                                          requestPath, requestMethod, rawPath) -> {
            // 对照 Go LogDenied 的 dedupPath 回落：路由模板为空时才用原始路径
            // （Spring 侧 rule.pattern() 恒非空，这一层是防御性等价）。
            String dedupPath = (requestPath == null || requestPath.isEmpty()) ? rawPath : requestPath;
            auditLogService.logDenied(tenantId, actorUserId, actorRole, requiredRole,
                    dedupPath, requestMethod, rawPath);
        });
        log.info("rbac denied-audit hook installed (AuditService.LogDenied)");
    }

    @Override
    public void destroy() {
        RbacInterceptor.setDeniedAuditor(null);
    }
}
