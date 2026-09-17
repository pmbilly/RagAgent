package com.ragagent.agent.approval;

/**
 * Go {@code context.Context} 在本包内用到的**取消面**（只保留 Done/Canceled 语义）。
 *
 * <p>Go 的 gate 用 ctx 做三件事：{@code RequestAndWait} / {@code RequestOAuthAndWait} 的
 * “请求被取消 → 返回 ContextCanceled 决策”分支、{@code EnabledTools} 每个名字前的
 * {@code ctx.Err()} 短路、以及 {@code EventBus.Emit(ctx, ...)} 的透传。
 * Java 侧不引入 golang 风格的全局 context，改为显式传入一个可被取消的信号。</p>
 *
 * <p><b>接线提示</b>：SSE 断连 / 用户点“停止生成”时把该信号置为已取消即可；
 * 无需取消的场景用 {@link #none()}。</p>
 *
 * <p><b>与 Go 的差异</b>：Go 的 ctx 是值语义的树（WithCancel/WithTimeout 派生），
 * 这里只有一个“是否已取消 + 注册回调”的最小面；超时不用它表达，
 * gate 自己的 timeout / WaitTimeout 已经覆盖。</p>
 */
public interface Cancellation {

    /** 对照 Go {@code ctx.Err() != nil} */
    boolean isCancelled();

    /**
     * 注册取消回调（对照 Go {@code ctx.Done()} 分支）。若注册时**已经取消**，
     * 必须立即同步执行一次 action；否则在取消发生时执行一次。
     *
     * @return 注销句柄（close 后不再回调）；实现方必须保证多次触发只执行一次 action
     */
    AutoCloseable onCancel(Runnable action);

    /** 永不取消的空实现（对照 Go {@code context.Background()} 在被取消语义上的行为） */
    static Cancellation none() {
        return NoneCancellation.INSTANCE;
    }

    /** {@link #none()} 的单例实现 */
    final class NoneCancellation implements Cancellation {

        static final NoneCancellation INSTANCE = new NoneCancellation();

        private NoneCancellation() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public AutoCloseable onCancel(Runnable action) {
            // 对照 Go context.Background()：Done() 永不关闭，注册即丢弃
            return () -> {
            };
        }
    }
}
