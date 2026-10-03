package com.ragagent.mcp.protocol;

/**
 * MCP 服务端自述信息。
 *
 * <p>{@code name}/{@code version} 恒有；{@code title}/{@code description} 是可选文档字段
 * （omitempty 语义），可能为 null。</p>
 */
public record ServerInfo(String name, String version, String title, String description) {

    public static ServerInfo empty() {
        return new ServerInfo("", "", null, null);
    }
}
