package com.ragagent.event;

import com.ragagent.common.context.TenantContext;

/**
 * TenantContext 的显式值快照——EventBus 异步派发（虚拟线程）跨线程传值的载体。
 *
 * <p>跨虚拟线程必须<b>显式传递值，禁止共享 ThreadLocal</b>：异步 handler 看到的
 * 是发射时刻的请求上下文——在发射线程 {@link #capture()}，在虚拟线程
 * {@link #replay()}（finally 里 {@link TenantContext#clear()}）。</p>
 */
public record TenantContextSnapshot(
        Long tenantId,
        TenantContext.Principal principal,
        String role,
        boolean systemAdmin,
        String userId,
        boolean canAccessAllTenants,
        String embedVisitorId,
        String requestId) {

    /** 在发射线程调用：抓取当前线程的 TenantContext 值（纯取值，不持有 ThreadLocal）。 */
    public static TenantContextSnapshot capture() {
        return new TenantContextSnapshot(
                TenantContext.currentTenantId(),
                TenantContext.currentPrincipal(),
                TenantContext.currentRole(),
                TenantContext.isSystemAdmin(),
                TenantContext.currentUserId(),
                TenantContext.canAccessAllTenants(),
                TenantContext.currentEmbedVisitorId(),
                TenantContext.currentRequestId());
    }

    /** 在工作线程调用：把快照值写入该线程的 TenantContext（线程私有，无共享）。 */
    public void replay() {
        TenantContext.set(tenantId, principal, role, systemAdmin, userId, canAccessAllTenants);
        TenantContext.setEmbedVisitorId(embedVisitorId);
        TenantContext.setRequestId(requestId);
    }

    /**
     * 只换执行租户（仓库/模型解析范围），身份
     * （principal/role/userId）原样保留——授权面永远看调用方，不看执行租户。
     */
    public TenantContextSnapshot withTenantId(long executionTenantId) {
        return new TenantContextSnapshot(executionTenantId, principal, role, systemAdmin,
                userId, canAccessAllTenants, embedVisitorId, requestId);
    }
}
