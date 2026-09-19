package com.ragagent.org.domain;

import java.time.OffsetDateTime;

/**
 * custom_agents 的只读投影（agents CRUD 面属另一批次，本批只读行用于共享响应）。
 * config 列以字符串承载 jsonb（H2 VARCHAR / PG 经 CAST 取文本）。
 */
public class AgentRow {
    private String id;
    private String name;
    private String description;
    private String avatar;
    private boolean isBuiltin;
    private Long tenantId;
    private String createdBy;
    private String config;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String v) { this.avatar = v; }
    public boolean isBuiltin() { return isBuiltin; }
    public void setBuiltin(boolean v) { this.isBuiltin = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public String getConfig() { return config; }
    public void setConfig(String v) { this.config = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
