package com.ragagent.model.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * models 表实体（对照 Go types/model.go Model）。
 *
 * GORM 隐式行为清单（约定 §3）：
 * - 软删除 → 显式 isNull("deleted_at")（同 users/tenants 约定）
 * - 钩子 BeforeCreate：id 为空 → UUID（Java 侧由 ModelService.create 显式赋值，语义等价）
 * - 钩子 BeforeCreate(Tenant)：nil RetrieverEngines —— 与 Model 无关，不适用
 * - parameters jsonb：写前加密 api_key/app_secret、读后宽容解密 → {@link ModelParametersTypeHandler}
 * - is_builtin / managed_by 列由迁移 000001 添加
 * - Update（repo）= Select("*") 全列更新含零值：Java 用 updateById（primitive boolean/int
 *   恒写入，String 默认 "" 非 null）——字段默认零值与 Go 非指针语义一致
 * - GetByID/List 可见性：WHERE (tenant_id = ? OR is_builtin = true)（内建模型全租户可见）
 */
@TableName(value = "models", autoResultMap = true)
public class Model {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String name;
    private String displayName;
    private String type;
    private String source;
    private String description;
    @TableField(typeHandler = ModelParametersTypeHandler.class)
    private ModelParameters parameters;
    private boolean isDefault;
    private boolean isBuiltin;
    /** gorm default ''；yaml = 内置 YAML 托管 */
    private String managedBy;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v == null ? "" : v; }
    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }
    public ModelParameters getParameters() {
        if (parameters == null) {
            parameters = new ModelParameters();
        }
        return parameters;
    }
    public void setParameters(ModelParameters v) { parameters = v; }
    public boolean isIsDefault() { return isDefault; }
    public void setIsDefault(boolean v) { isDefault = v; }
    public boolean isIsBuiltin() { return isBuiltin; }
    public void setIsBuiltin(boolean v) { isBuiltin = v; }
    public String getManagedBy() { return managedBy; }
    public void setManagedBy(String v) { managedBy = v == null ? "" : v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v == null ? "" : v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
