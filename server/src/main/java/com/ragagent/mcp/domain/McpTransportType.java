package com.ragagent.mcp.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MCP 传输类型（对照 Go types.MCPTransportType）。
 *
 * 注意 STDIO 的语义：结构体与 `stdio_config` 列、DTO 字段都保留，但**传输层硬禁用**
 * （client.go:229-231 与 manager.go:97-100 双重拒绝），理由是命令注入。
 * Java 侧必须保持这个"字段存在但传输被拒"的行为。
 */
public enum McpTransportType {

    /** Server-Sent Events */
    SSE("sse"),
    /** HTTP Streamable */
    HTTP_STREAMABLE("http-streamable"),
    /** 标准输入输出——保留类型，但传输被硬禁用 */
    STDIO("stdio");

    private final String value;

    McpTransportType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static McpTransportType fromValue(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (McpTransportType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
