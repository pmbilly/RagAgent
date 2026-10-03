package com.ragagent.llm.limiter;

/**
 * acquire 返回的释放句柄。
 *
 * 实现 {@link AutoCloseable}，配合 try-with-resources 使用：
 *
 * <pre>
 * try (Release r = governor.gate(modelId)) {
 *     // 持槽调用上游
 * }
 * </pre>
 *
 * 契约：
 * <ul>
 *   <li>fail-open / passthrough 路径返回的是 {@link #NOOP}——调用它没有任何副作用，永远安全；</li>
 *   <li>正常路径的 release 幂等，重复调用不会多释放一个槽位。</li>
 * </ul>
 */
@FunctionalInterface
public interface Release extends AutoCloseable {

    /** fail-open / passthrough 路径的空实现 */
    Release NOOP = () -> {
    };

    /** 释放槽位。必须幂等；失败路径下为空操作。 */
    @Override
    void close();
}
