package com.ragagent.event;

/**
 * 中间件恢复出来的 panic（对照 Go {@code event.PanicError}，internal/event/middleware.go:71-78）。
 *
 * <p>Go 的 {@code WithRecovery} 把 handler panic 转成该错误返回，消息
 * {@code panic in event handler: %v}——%v 的是被恢复的<b>值</b>（如 {@code panic("boom")}
 * 的 "boom"）。Java 捕获到的是 Throwable，等价的"恢复值"取 {@link Throwable#getMessage()}
 * （为空时退回 toString），再被 Emit 收到时按 error 路径包成
 * {@code event handler failed for <type>: panic in event handler: ...}
 * （与 Go 的 %w 包装链同构）。</p>
 *
 * <p>{@link #getPanic()} 对照 Go 的 {@code Panic interface{}} 字段（原样承载被恢复的值）。</p>
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
