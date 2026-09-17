package com.ragagent.llm.limiter;

/**
 * 对照 Go types.WithBackgroundTask / IsBackgroundTask（internal/types/context_helpers.go）。
 *
 * Go 把"该调用来自 asynq 后台 worker（文档解析/摘要/问题生成/图谱/多模态富化）"这一标记
 * 放进 context.Value；Java 无 context 对象，用 ThreadLocal 承载同义标记。
 * 并发闸门只节流后台 LLM 流量，交互式（HTTP 请求）路径一律放行 —— 见
 * {@link ConcurrencyGovernor#gateNamedN}。
 *
 * 虚拟线程安全（约定 §5）：ThreadLocal 在虚拟线程内独立，但**不会**跨线程传递——
 * 后台 worker 若再派生子任务，必须在子线程内显式 mark()，禁止把标记当继承属性用
 * （对照 Go 侧 detached ctx 需要 CloneContext 才能保留该标记）。
 *
 * 用法：
 * <pre>
 * try (var ignored = BackgroundTaskContext.mark()) {
 *     // 后台任务体：其间发起的模型调用会被并发闸门节流
 * }
 * </pre>
 */
public final class BackgroundTaskContext {

    private static final ThreadLocal<Boolean> BACKGROUND = new ThreadLocal<>();

    private BackgroundTaskContext() {
    }

    /** 对照 Go IsBackgroundTask：未标记（含交互式 HTTP 路径）返回 false */
    public static boolean isBackgroundTask() {
        return Boolean.TRUE.equals(BACKGROUND.get());
    }

    /**
     * 对照 Go WithBackgroundTask：标记当前线程为后台任务作用域。
     * 返回的 Scope 关闭时恢复进入前的值（支持嵌套），配合 try-with-resources 使用。
     */
    public static Scope mark() {
        Boolean previous = BACKGROUND.get();
        BACKGROUND.set(Boolean.TRUE);
        return () -> {
            if (previous == null) {
                BACKGROUND.remove();
            } else {
                BACKGROUND.set(previous);
            }
        };
    }

    /** 显式清除标记（对照 Go 丢弃带标记的 ctx） */
    public static void clear() {
        BACKGROUND.remove();
    }

    /** mark() 返回的作用域句柄；关闭即恢复进入前的标记 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
