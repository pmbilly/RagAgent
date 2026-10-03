package com.ragagent.event;

/**
 * 中间件恢复出来的 panic。
 *
 * <p>{@code withRecovery()} 把 handler panic 转成本异常，消息为
 * {@code panic in event handler: <恢复值>}——Throwable 取 {@link Throwable#getMessage()}
 * （为空时退回 toString）。再被 Emit 收到时按处理失败路径包成
 * {@code event handler failed for <type>: panic in event handler: ...}。</p>
 *
 * <p>{@link #getPanic()} 原样承载被恢复的值。</p>
 */
public class PanicError extends RuntimeException {

    private final transient Object panic;

    public PanicError(Object panic) {
        super(messageOf(panic));
        this.panic = panic;
    }

    private static String messageOf(Object panic) {
        if (panic instanceof Throwable t) {
            return "panic in event handler: "
                    + (t.getMessage() != null ? t.getMessage() : t.toString());
        }
        return "panic in event handler: " + panic;
    }

    public Object getPanic() {
        return panic;
    }
}
