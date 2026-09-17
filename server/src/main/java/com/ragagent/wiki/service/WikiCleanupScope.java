package com.ragagent.wiki.service;

import java.util.concurrent.Callable;

import com.ragagent.common.context.TenantContext;

/**
 * 脱钩的收尾清理作用域（对照 Go {@code wikiIngestCleanupContext}，
 * wiki_ingest.go L680-685）。
 *
 * <pre>{@code
 * func wikiIngestCleanupContext(ctx context.Context) (context.Context, context.CancelFunc) {
 *     if ctx == nil { ctx = context.Background() }
 *     return context.WithTimeout(context.WithoutCancel(ctx), wikiIngestCleanupTimeout)
 * }
 * }</pre>
 *
 * <p><b>要解决什么</b>：wiki 批次 worker 可能在<b>应用关闭中</b>、或父任务已被取消 /
 * 超时时执行收尾清理（删除已消费的 pending 行、释放认领、把失败 op 归档）。
 * 这些清理<b>必须照常完成</b>——否则行会永远留在队列里、认领要等 90 分钟才过期、
 * 文档会卡在 "finalizing"。但父 ctx 一取消，用它做的任何 DB 调用都会立刻失败。</p>
 *
 * <h2>Go 的两个动作 → Java 的两个动作</h2>
 * <table>
 *   <tr><th>Go</th><th>作用</th><th>Java 等价</th></tr>
 *   <tr><td>{@code context.WithoutCancel(ctx)}</td>
 *       <td>保留 ctx 里的值，但切断取消传播</td>
 *       <td>快照 {@link TenantContext} 的全部值，并<b>清除线程中断位</b>
 *           （Java 的取消传播载体是中断，不是 ctx）</td></tr>
 *   <tr><td>{@code context.WithTimeout(..., 10s)}</td>
 *       <td>给清理一个 10 秒上限，避免它自己挂死</td>
 *       <td>不引入硬超时：Java 侧没有 ctx 可传，仓储调用也不接收截止时间；
 *           照抄一个"假装有超时"的看门狗只会制造无法兑现的承诺。
 *           <b>差异已登记</b>（见报告"需决策的点"）。</td></tr>
 * </table>
 *
 * <p><b>为什么在同一个线程上执行</b>：Go 的 {@code WithoutCancel} 并不换 goroutine，
 * 清理仍跑在原来的 goroutine 上（只是 ctx 的值保留、取消被切断）。Java 侧因此
 * <b>内联执行</b>，不做线程跳转——这同时避免了"跨虚拟线程传递 ThreadLocal 值"
 * （约定文档 §5 明确禁止共享 ThreadLocal）。</p>
 *
 * <p><b>用法</b>：</p>
 * <pre>{@code
 * try (WikiCleanupScope scope = WikiCleanupScope.open()) {
 *     scope.run(() -> pendingRepo.deleteByIds(ids));
 * }
 * }</pre>
 */
public final class WikiCleanupScope implements AutoCloseable {

    /** 对照 Go 的 {@code types.TenantIDContextKey} 快照 */
    private final Long tenantId;
    private final TenantContext.Principal principal;
    private final String role;
    private final String userId;
    private final String embedVisitorId;
    private final String requestId;
    private final boolean systemAdmin;
    private final boolean canAccessAllTenants;

    /** 进入作用域前的线程中断位（Go 里对应"父 ctx 已被取消"这个事实） */
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
     * 对照 Go {@code wikiIngestCleanupContext(ctx)}：开一个脱钩的清理作用域。
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
     * <p>{@code Throwable} 被原样透传（Go 里清理的错误由调用方决定是记日志还是
     * 聚合成 settle error）。</p>
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
     * 恢复进入前的线程中断位。对照 Go 里 {@code cancel()} 之后的 ctx 语义：
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
