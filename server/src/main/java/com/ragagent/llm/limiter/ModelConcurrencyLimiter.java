package com.ragagent.llm.limiter;

/**
 * 对照 Go limiter.ModelConcurrencyLimiter（limiter.go）：按 key（通常是 model ID）限制
 * 并发在途调用数的信号量。
 *
 * Go 侧签名 {@code Acquire(ctx, key, limit) (release func(), err error)} 的映射：
 * <ul>
 *   <li>ctx → Java 线程中断（等待中被中断即 fail open）；</li>
 *   <li>err → 本接口不返回错误：Go 两个实现的所有失败路径都是 {@code return noop, nil}
 *       （fail OPEN，限流器/Redis 故障绝不允许阻断模型流量），调用方只判断 release 是否可用。
 *       Java 侧约定 {@link #acquire} 永不返回 null，失败即返回 {@link Release#NOOP}。</li>
 * </ul>
 */
public interface ModelConcurrencyLimiter {

    /**
     * 阻塞直到 key 有可用槽位或等待被中断。任何后端错误（或等待被中断）都 fail open：
     * 返回 {@link Release#NOOP}，调用方无需持槽继续执行，绝不抛异常。
     *
     * @param key   限流键（通常是 model ID）；空串 fail open
     * @param limit 并发上限；<=0 fail open
     * @return 释放句柄，永不 null
     */
    Release acquire(String key, int limit);

    /**
     * 对照 Go governor 里可选的 {@code interface{ SetModelName(string, string) }} 类型断言：
     * Java 用 default 方法承载同一"可选能力"，实现不需要就沿用空实现。
     * 仅用于运行时观测（RuntimeStats 里展示模型名），不影响限流判断。
     */
    default void setModelName(String modelId, String name) {
    }
}
