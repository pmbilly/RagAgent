package com.ragagent.common.approval;

/**
 * 审批流程内用到的**取消面**（只保留“是否已取消 + 取消回调”语义）。
 *
 * <p>审批门用它做两件事：{@link Gate#requestAndWait} / {@link Gate#requestOAuthAndWait}
 * 的“请求被取消 → 返回取消决策”分支、{@link ToolPolicy#enabledTools}
 * 每个名字前的短路。不引入隐式的全局上下文，改为显式传入一个可被取消的信号。</p>
 *
 * <p><b>接线提示</b>：SSE 断连 / 用户点“停止生成”时把该信号置为已取消即可；
 * 无需取消的场景用 {@link #none()}。</p>
 *
 * <p>这里只有最小面，不做派生树；超时不用它表达，
 * gate 自己的 timeout / WaitTimeout 已经覆盖。</p>
 */
public interface Cancellation {

    /** 是否已被取消。 */
    boolean isCancelled();

    /**
     * 注册取消回调。若注册时**已经取消**，
     * 必须立即同步执行一次 action；否则在取消发生时执行一次。
     *
     * @return 注销句柄（close 后不再回调）；实现方必须保证多次触发只执行一次 action
     */
    AutoCloseable onCancel(Runnable action);

    /** 永不取消的空实现。 */
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
            // 永不取消：注册即丢弃
            return () -> {
            };
        }
    }
}
