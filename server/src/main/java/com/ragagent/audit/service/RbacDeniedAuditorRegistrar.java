package com.ragagent.audit.service;

import com.ragagent.common.web.RbacInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 把 {@link AuditLogService} 接进 RBAC 拒绝分支。
 *
 * <p><b>为什么需要这个注册器</b>：{@code RbacInterceptor} 由
 * {@code config/WebConfig} 直接 {@code new} 出来（不是 Spring bean），拿不到依赖注入；
 * 所以这里提供进程级装配点 {@link RbacInterceptor#setDeniedAuditor}。</p>
 *
 * <p>背景：此前 RBAC 拒绝只记日志、不落库，本注册器补上落库一环。</p>
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
            // 路由模板为空时才用原始路径做去重键
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
