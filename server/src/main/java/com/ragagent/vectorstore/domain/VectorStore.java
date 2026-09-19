package com.ragagent.vectorstore.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对照 Go {@code types.VectorStore}（表 vector_stores，迁移 000032）。
 * connection_config / index_config 两 jsonb 列均有 PG DEFAULT（'{}'）——
 * 实体恒持非 null 对象（Go 的 Value() 也恒序列化结构体），不依赖列默认。
 */
@TableName(value = "vector_stores", autoResultMap = true)
public class VectorStore {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String name;
    private String engineType;
    @TableField(typeHandler = ConnectionConfigTypeHandler.class)
    private ConnectionConfig connectionConfig;
    @TableField(typeHandler = IndexConfigTypeHandler.class)
    private IndexConfig indexConfig;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String v) { engineType = v; }
    public ConnectionConfig getConnectionConfig() { return connectionConfig; }
    public void setConnectionConfig(ConnectionConfig v) { connectionConfig = v; }
    public IndexConfig getIndexConfig() { return indexConfig; }
    public void setIndexConfig(IndexConfig v) { indexConfig = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }

    /** 对照 VectorStore.Validate（name/engine/tenant 必填 + 引擎白名单） */
    public void validate() {
        if (name == null || name.isEmpty()) {
            throw validation("name is required");
        }
        if (!VectorStoreEngines.isValidEngineType(engineType)) {
            throw validation("unsupported engine type: " + engineType);
        }
        if (tenantId == null || tenantId == 0) {
            throw validation("tenant_id is required");
        }
    }

    static com.ragagent.common.error.BizException validation(String message) {
        return new com.ragagent.common.error.BizException(
                com.ragagent.common.error.AppError.validation(message));
    }
}
