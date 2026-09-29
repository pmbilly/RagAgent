package com.ragagent.agent.tools;

/**
 * 工具取消探测。
 *
 * <p>约定：返回 <b>null 表示未取消</b>，非 null 是要写进 {@code ToolResult.error}
 * 的原文（registry 原样照抄，不加工）。</p>
 */
@FunctionalInterface
public interface ToolCancellation {

    /** 未取消（cancellationError 恒为 null）。 */
    ToolCancellation LIVE = () -> null;

    /** 已取消的错误原文。 */
    String CONTEXT_CANCELED = "context canceled";

    /** 已超时的错误原文。 */
    String CONTEXT_DEADLINE_EXCEEDED = "context deadline exceeded";

    /**
     * @return 未取消时返回 null；已取消时返回错误原文。
     */
    String cancellationError();
}
