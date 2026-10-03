package com.ragagent.mcp.protocol;

import java.util.List;

/**
 * tools/call 结果。
 *
 * <p>{@code isError} 是 MCP 协议里"工具执行失败"的正常返回（不是传输错误）——
 * 内容里通常带错误说明，调用方需自行判断（协议层原样透传）。</p>
 */
public record CallToolResult(boolean isError, List<ContentItem> content) {

    public CallToolResult {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
