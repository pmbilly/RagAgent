package com.ragagent.agent.tools;

/**
 * 取消状态的 {@code ctx.Err()} 等价物（对照 Go 的 {@code context.Context}）。
 *
 * <p>Go 的工具执行全程携带 ctx，取消探测点是 {@code ctx.Err() != nil}——返回 nil 表示
 * 存活，否则返回错误文案（{@code "context canceled"} / {@code "context deadline exceeded"}）。
 * Java 没有 context，把这条语义收进一个函数：返回 <b>null 表示未取消</b>，非 null 是
 * 要写进 {@code ToolResult.error} 的原文（registry 原样照抄，不加工）。</p>
 *
 * <p>波 4.6 引擎会用它包装未来/超时；本批只提供 {@link #LIVE} 与 registry 的检查点。</p>
 */
@FunctionalInterface
public interface ToolCancellation {

    /** 未取消。对照 Go 的 {@code ctx.Err() == nil}。 */
    ToolCancellation LIVE = () -> null;

    /** 对照 Go 的 {@code context.Canceled.Error()}。 */
    String CONTEXT_CANCELED = "context canceled";

    /** 对照 Go 的 {@code context.DeadlineExceeded.Error()}。 */
    String CONTEXT_DEADLINE_EXCEEDED = "context deadline exceeded";

    /**
     * @return 未取消时返回 null；已取消时返回错误原文（同 Go {@code ctx.Err().Error()}）。
     */
    String cancellationError();
}
