package com.ragagent.mcp.protocol;

import java.util.List;

/**
 * tools/call 结果（对照 Go internal/mcp/types.go 的 {@code CallToolResult}）。
 *
 * <p>{@code isError} 是 MCP 协议里"工具执行失败"的正常返回（不是传输错误）——
 * 内容里通常带错误说明，调用方需自行判断（Go 侧同样原样透传）。</p>
 */
public record CallToolResult(boolean isError, List<ContentItem> content) {

    public CallToolResult {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
