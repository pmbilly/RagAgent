package com.ragagent.mcp.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次**完整且显式同步**的目录快照（对照 Go types.MCPMetadata，
 * internal/types/mcp_metadata.go:28-40）。
 *
 * <p>OAuth 快照按授权 principal 归属，**绝不可**变成租户级共享。</p>
 *
 * GORM 隐式行为清单（约定 §3）：
 * <ul>
 *   <li>复合主键 (tenant_id, service_id, principal)：MyBatis-Plus 不支持复合主键的
 *       BaseMapper 方法，故 {@code McpMetadataMapper} **不继承 BaseMapper**，
 *       全部语句显式书写（对照 GORM 的 clause.OnConflict 需要手写等价语句）</li>
 *   <li>无 DeletedAt → 无软删除</li>
 *   <li>tools 列：{@code serializer:json;type:jsonb;not null} → {@link McpToolListTypeHandler}</li>
 *   <li>Stale 是 {@code gorm:"-"} 的派生字段，**不落库**</li>
 *   <li>synced_at 由调用方赋值（Go 无 autoCreateTime/autoUpdateTime）</li>
 * </ul>
 */
public class McpMetadata {

    private Long tenantId;
    private String serviceId;
    /** 空串 = 非 OAuth（租户级）快照；非空 = principal.StorageID() */
    private String principal = "";
    private String configFingerprint;
    private List<McpTool> tools = new ArrayList<>();
    private String instructions = "";
    private String serverName = "";
    private String serverVersion = "";
    private String serverDescription = "";
    private OffsetDateTime syncedAt;
    /** 派生字段：快照指纹与当前服务配置指纹不一致（不落库） */
    private boolean stale;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { serviceId = v == null ? "" : v; }
    public String getPrincipal() { return principal; }
    public void setPrincipal(String v) { principal = v == null ? "" : v; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String v) { configFingerprint = v == null ? "" : v; }
    public List<McpTool> getTools() { return tools; }
    public void setTools(List<McpTool> v) { tools = v == null ? new ArrayList<>() : v; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String v) { instructions = v == null ? "" : v; }
    public String getServerName() { return serverName; }
    public void setServerName(String v) { serverName = v == null ? "" : v; }
    public String getServerVersion() { return serverVersion; }
    public void setServerVersion(String v) { serverVersion = v == null ? "" : v; }
    public String getServerDescription() { return serverDescription; }
    public void setServerDescription(String v) { serverDescription = v == null ? "" : v; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime v) { syncedAt = v; }
    public boolean isStale() { return stale; }
    public void setStale(boolean v) { stale = v; }
}
