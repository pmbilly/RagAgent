package com.ragagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具执行的入参。
 *
 * <p>携带两样东西：</p>
 * <ol>
 *   <li><b>调用元数据</b>：{@link #execMeta}（可为 null，直连执行时没有）。</li>
 *   <li><b>输出预算</b>：{@link #budget}。{@link #outputBudget()} ≤0 时回落
 *       {@link ToolOutput#DEFAULT_MAX_TOOL_OUTPUT}。</li>
 * </ol>
 *
 * <p>取消探测独立于预算：{@link #cancellation}。</p>
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

    /** 输出预算：≤0 回落 DefaultMaxToolOutput。 */
    public int outputBudget() {
        return budget > 0 ? budget : ToolOutput.DEFAULT_MAX_TOOL_OUTPUT;
    }

    /** 是否已取消。 */
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
