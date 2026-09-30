package com.ragagent.wiki.service.ingest;

import java.util.concurrent.Callable;

import com.ragagent.common.context.TenantContext;

/**
 * 脱钩的收尾清理作用域。
 *
 * <p><b>要解决什么</b>：wiki 批次 worker 可能在<b>应用关闭中</b>、或父任务已被取消 /
 * 超时时执行收尾清理（删除已消费的 pending 行、释放认领、把失败 op 归档）。
 * 这些清理<b>必须照常完成</b>——否则行会永远留在队列里、认领要等 90 分钟才过期、
 * 文档会卡在 "finalizing"。但父上下文一取消，用它做的任何 DB 调用都会立刻失败。</p>
 *
 * <h2>两个动作</h2>
 * <table>
 *   <tr><th>动作</th><th>作用</th></tr>
 *   <tr><td>快照 {@link TenantContext} 的全部值，并<b>清除线程中断位</b></td>
 *       <td>保留上下文值，但切断取消传播（Java 的取消传播载体是中断，不是 ctx）</td></tr>
 *   <tr><td>不引入硬超时</td>
 *       <td>给清理一个 10 秒上限在语义上更稳，但 Java 侧没有可传递的截止时间载体，
 *           仓储调用也不接收截止时间；照抄一个"假装有超时"的看门狗只会制造
 *           无法兑现的承诺。</td></tr>
 * </table>
 *
 * <p><b>为什么在同一个线程上执行</b>：脱钩并不换线程，
 * 清理仍跑在原来的线程上（上下文值保留、取消被切断）。因此
 * <b>内联执行</b>，不做线程跳转——这同时避免了"跨虚拟线程传递 ThreadLocal 值"。</p>
 *
 * <p><b>用法</b>：</p>
 * <pre>{@code
 * try (WikiCleanupScope scope = WikiCleanupScope.open()) {
 *     scope.run(() -> pendingRepo.deleteByIds(ids));
 * }
 * }</pre>
 */
public final class WikiCleanupScope implements AutoCloseable {

    /** 进入作用域时的租户上下文快照 */
    private final Long tenantId;
    private final TenantContext.Principal principal;
    private final String role;
    private final String userId;
    private final String embedVisitorId;
    private final String requestId;
    private final boolean systemAdmin;
    private final boolean canAccessAllTenants;

    /** 进入作用域前的线程中断位（="父作用域已被取消"这个事实） */
    private final boolean wasInterrupted;

    private boolean closed;

    private WikiCleanupScope() {
        this.tenantId = TenantContext.currentTenantId();
        this.principal = TenantContext.currentPrincipal();
        this.role = TenantContext.currentRole();
        this.userId = TenantContext.currentUserId();
        this.embedVisitorId = TenantContext.currentEmbedVisitorId();
        this.requestId = TenantContext.currentRequestId();
        this.systemAdmin = TenantContext.isSystemAdmin();
        this.canAccessAllTenants = TenantContext.canAccessAllTenants();
        this.wasInterrupted = Thread.interrupted();
    }

    /**
     * 开一个脱钩的清理作用域。
     *
     * <p>副作用是<b>清除当前线程的中断位</b>（= 切断取消传播）。调用 {@link #close()}
     * 时会把它恢复，因此调用方无需自己记着这件事。</p>
     */
    public static WikiCleanupScope open() {
        return new WikiCleanupScope();
    }

    /**
     * 在脱钩路径上执行清理动作：租户值已保留、中断位已清。
     *
     * <p>{@code Throwable} 被原样透传，由调用方决定是记日志还是聚合。</p>
     */
    public void run(Runnable action) {
        applyTenantContext();
        try {
            action.run();
        } finally {
            clearInterrupt();
        }
    }

    /** 同 {@link #run}，但带返回值。 */
    public <T> T call(Callable<T> action) throws Exception {
        applyTenantContext();
        try {
            return action.call();
        } finally {
            clearInterrupt();
        }
    }

    /**
     * 恢复进入前的线程中断位：
     * 清理结束后，调用方仍应看到"父作用域已被取消"这一事实。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyTenantContext() {
        TenantContext.set(tenantId, principal, role, systemAdmin, userId, canAccessAllTenants);
        TenantContext.setEmbedVisitorId(embedVisitorId);
        TenantContext.setRequestId(requestId);
    }

    private static void clearInterrupt() {
        Thread.interrupted();
    }
}
