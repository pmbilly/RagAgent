package com.ragagent.mcp.domain;

import java.time.OffsetDateTime;

/**
 * 快照的**列表卡片视图**：只带计数，不带工具 payload（对照 Go types.MCPMetadataSummary，
 * internal/types/mcp_metadata.go:42-50）。
 *
 * 这是只读投影，不对应任何表；由 {@code McpMetadataMapper} 的汇总查询直接填充，
 * tool_count 由 SQL 侧的 jsonb_array_length 计算（**不把 tools 本体取出**）。
 */
public class McpMetadataSummary {

    private String serviceId;
    private String principal;
    private String configFingerprint;
    private int toolCount;
    private OffsetDateTime syncedAt;
    private String serverName;

    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { serviceId = v; }
    public String getPrincipal() { return principal; }
    public void setPrincipal(String v) { principal = v == null ? "" : v; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String v) { configFingerprint = v; }
    public int getToolCount() { return toolCount; }
    public void setToolCount(int v) { toolCount = v; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime v) { syncedAt = v; }
    public String getServerName() { return serverName; }
    public void setServerName(String v) { serverName = v; }
}
