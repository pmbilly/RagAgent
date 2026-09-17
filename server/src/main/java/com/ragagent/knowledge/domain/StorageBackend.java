package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * storage_backends（迁移 000068）：具体对象存储实例。
 * 阶段 3 仅消费解析逻辑（id/provider），配置 jsonb 原样透传。
 */
@TableName(value = "storage_backends", autoResultMap = true)
public class StorageBackend {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String name;
    private String provider;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode config;
    private String source;
    private String status;
    private boolean legacyAlias;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public JsonNode getConfig() { return config; }
    public void setConfig(JsonNode v) { config = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public boolean isLegacyAlias() { return legacyAlias; }
    public void setLegacyAlias(boolean v) { legacyAlias = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
