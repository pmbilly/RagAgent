package com.ragagent.llm.limiter;

/**
 * 对照 Go limiter 的 release func（Acquire 返回的释放闭包）。
 *
 * Go 调用方写 {@code defer release()}；Java 侧实现 {@link AutoCloseable}，
 * 用 try-with-resources 复刻同一语义：
 *
 * <pre>
 * try (Release r = governor.gate(modelId)) {
 *     // 持槽调用上游
 * }
 * </pre>
 *
 * 契约（与 Go 完全一致）：
 * <ul>
 *   <li>fail-open / passthrough 路径返回的是 {@link #NOOP}——调用它没有任何副作用，永远安全；</li>
 *   <li>正常路径的 release 幂等（对照 Go 的 sync.Once），重复调用不会多释放一个槽位。</li>
 * </ul>
 */
@FunctionalInterface
public interface Release extends AutoCloseable {

    /** 对照 Go 的 noop()：fail-open / passthrough 路径的空实现 */
    Release NOOP = () -> {
    };

    /** 释放槽位。必须幂等；失败路径下为空操作。 */
    @Override
    void close();
}
