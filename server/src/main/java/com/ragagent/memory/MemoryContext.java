package com.ragagent.memory;

/**
 * "本请求禁用了记忆"的上下文标记。
 *
 * <p>agent 的开关是**按请求**而不是按 scope 的，所以它走上下文而不是数据库：
 * 同一个人在两个 agent 之间，一个会话有记忆、另一个没有。</p>
 *
 * <h2>为什么用 ThreadLocal 而不是参数</h2>
 * <p>与 {@code TenantContext} / {@code APIKeyScopeContext} / {@code StorageUrlContext}
 * 保持同一形状（约定 §5）。</p>
 *
 * <h2>⚠️ 生命周期</h2>
 * <p>ThreadLocal 的值不会随作用域自动失效——设置方必须在请求结束的 finally 里
 * {@link #clear()}，漏清会污染同线程的下一个请求（与 {@code StorageUrlContext} 同一条坑）。</p>
 *
 * <h2>语义：缺席即允许</h2>
 * <p>没有标记表示允许，所以每一个既有调用点都照常工作，只有**显式**退出才关掉记忆。
 * {@code enabled == null} 时继承工作区设置，
 * 于是"没表态"与"表态为 true"在上下文里是同一种形态。</p>
 */
public final class MemoryContext {

    private static final ThreadLocal<Boolean> DISABLED = new ThreadLocal<>();

    private MemoryContext() {}

    /** 把当前请求标记为"不许读记忆"。 */
    public static void markDisabled() {
        DISABLED.set(Boolean.TRUE);
    }

    /**
     * 当前请求是否允许记忆。
     *
     * <p>没有标记 → 允许。</p>
     */
    public static boolean allowedForAgent() {
        return !Boolean.TRUE.equals(DISABLED.get());
    }

    /**
     * 把 agent 的记忆开关写进上下文。
     *
     * @param enabled {@code null} 表示该 agent 没表态，**继承工作区设置**（即什么都不做）；
     *                显式 {@code false} 才关掉。
     */
    public static void applyAgentMemoryPreference(Boolean enabled) {
        if (enabled != null && !enabled) {
            markDisabled();
        }
    }

    /** 请求结束必须调用：ThreadLocal 的值不会随作用域自动失效。 */
    public static void clear() {
        DISABLED.remove();
    }
}
