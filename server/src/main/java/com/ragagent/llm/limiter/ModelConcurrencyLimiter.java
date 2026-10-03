package com.ragagent.llm.limiter;

/**
 * 按 key（通常是 model ID）限制并发在途调用数的信号量。
 *
 * 契约：
 * <ul>
 *   <li>取消 → 线程中断（等待中被中断即 fail open）；</li>
 *   <li>本接口不返回错误：所有失败路径都返回 {@link Release#NOOP}
 *       （fail OPEN，限流器故障绝不允许阻断模型流量），调用方只判断 release 是否可用。
 *       {@link #acquire} 永不返回 null，失败即返回 {@link Release#NOOP}。</li>
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
     * 可选能力：仅用于运行时观测（RuntimeStats 里展示模型名），不影响限流判断。
     * 实现不需要就沿用空实现。
     */
    default void setModelName(String modelId, String name) {
    }
}
