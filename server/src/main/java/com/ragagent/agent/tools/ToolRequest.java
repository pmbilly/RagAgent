package com.ragagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具执行的入参（对照 Go 把 {@code context.Context} 传进 {@code Tool.Execute(ctx, args)}）。
 *
 * <p>Go 的 ctx 里挂了两样东西，Java 拆成显式字段：</p>
 * <ol>
 *   <li><b>调用元数据</b>：{@code ToolExecFromContext(ctx)} → {@link #execMeta}（可为 null，
 *       直连执行时没有）。</li>
 *   <li><b>输出预算</b>：{@code WithOutputBudget(ctx, maxChars)} → {@link #budget}。
 *       {@link #outputBudget()} 对照 Go 的 {@code OutputBudget(ctx)}——≤0 回落
 *       {@link ToolOutput#DEFAULT_MAX_TOOL_OUTPUT}。</li>
 * </ol>
 *
 * <p>取消探测独立于预算：{@link #cancellation} 对照 {@code ctx.Err()}。</p>
 */
public record ToolRequest(JsonNode args, ToolExecContext execMeta, ToolCancellation cancellation, int budget) {

    /** 无元数据、无取消、无预算的直连请求（测试/独立执行用）。 */
    public static ToolRequest of(JsonNode args) {
        return new ToolRequest(args, null, ToolCancellation.LIVE, 0);
    }

    public ToolRequest {
        if (cancellation == null) {
            cancellation = ToolCancellation.LIVE;
        }
    }

    /** 对照 Go {@code OutputBudget(ctx)}：≤0 回落 DefaultMaxToolOutput。 */
    public int outputBudget() {
        return budget > 0 ? budget : ToolOutput.DEFAULT_MAX_TOOL_OUTPUT;
    }

    /** 对照 {@code ctx.Err() != nil}。 */
    public boolean isCancelled() {
        return cancellation.cancellationError() != null;
    }

    /** 带 execMeta 时的 sessionId 快捷读取（无元数据返回 ""）。 */
    public String sessionId() {
        return execMeta != null ? execMeta.sessionId() : "";
    }

    /** 带 execMeta 时的 toolCallId 快捷读取（无元数据返回 ""）。 */
    public String toolCallId() {
        return execMeta != null ? execMeta.toolCallId() : "";
    }
}
