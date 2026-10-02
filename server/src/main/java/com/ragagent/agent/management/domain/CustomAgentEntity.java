package com.ragagent.agent.management.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.agent.management.mapper.JsonbRawStringTypeHandler;

/**
 * custom_agents 行实体。
 *
 * <p>持久化行为清单：</p>
 * <ul>
 *   <li>无生命周期钩子：UUID/时间戳由 service 显式赋值。</li>
 *   <li>软删除：显式 {@code deleted_at IS NULL} 查询条件（不用 @TableLogic）。</li>
 *   <li>排序：列表查询显式 {@code created_at DESC}。</li>
 *   <li>业务键为 (id, tenant_id)；本表 id 物理主键。</li>
 * </ul>
 *
 * <p>config 列是 jsonb：Java 侧读成原始文本（响应层按固定声明序重排键——
 * PG 规范化键序直接序列化会字节 DIFF，见 org.dto.OrgResponses.agentConfigMap），
 * 写路径用 {@link JsonbRawStringTypeHandler}（setObject(Types.OTHER)）。</p>
 */
@TableName(value = "custom_agents", autoResultMap = true)
public class CustomAgentEntity {

    @TableId
    private String id;
    private String name;
    private String description;
    private String avatar;
    private boolean isBuiltin;
    private Long tenantId;
    private String createdBy;
    @TableField(typeHandler = JsonbRawStringTypeHandler.class)
    private String config;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    /** 瞬态字段：list 接口批量回填，不落库。 */
    @TableField(exist = false)
    private String creatorName;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public boolean isBuiltin() { return isBuiltin; }
    public void setBuiltin(boolean isBuiltin) { this.isBuiltin = isBuiltin; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }

    @Override
    public String toString() {
        return "CustomAgentEntity{" + id + ", tenant=" + tenantId + ", name=" + name + "}";
    }
}
