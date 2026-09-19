package com.ragagent.sandbox.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 对照 Go {@code types.TenantSandboxConfigEntity}（internal/types/tenant_sandbox_config_entity.go）。
 *
 * <p>一个工作区持有的一个具名沙箱后端配置。凭据载荷在 {@code config}（jsonb）里，
 * 复用 {@link TenantSandboxConfigTypeHandler} 的加密 Value/Scan 语义。</p>
 *
 * <h3>§3 GORM 隐式行为清单（本实体）</h3>
 * <ul>
 *   <li><b>钩子</b>：无 BeforeCreate/AfterFind 钩子；created_at/updated_at 由
 *       GORM Create 自动填（Java：mapper insert 显式写 now 并回写内存对象——
 *       §9 波 2 基础设施第 3 条），Update 由 GORM 写 updated_at 但<b>不回写内存</b>
 *       （Java：mapper 显式 SET updated_at=now，实体字段保持读时值，响应与 Go 一致）；</li>
 *   <li><b>关联预加载</b>：无关联；</li>
 *   <li><b>软删除</b>：gorm.DeletedAt → <b>不用 @TableLogic</b>，显式
 *       {@code deleted_at IS NULL} 条件（§9 既有约定）；</li>
 *   <li><b>默认排序</b>：ListByTenant 带 {@code created_at ASC}（仓储原文）；</li>
 *   <li><b>唯一索引</b>：迁移 000082 有 {@code uq_tenant_sandbox_configs_tenant_name
 *       (tenant_id, name) WHERE deleted_at IS NULL}；H2 测试 DDL 不建该部分索引，
 *       唯一性以迁移为准（§3 第 5 条）；</li>
 *   <li><b>自动时间戳</b>：同钩子条目——收口在 mapper 的显式 SQL。</li>
 * </ul>
 *
 * <p>id 由 service 层显式生成 UUID（对照 Go 的 {@code uuid.New().String()}；
 * IdType.INPUT，不用 ASSIGN_UUID——那是 32 位无连字符 hex，§9 波 1 G5）。</p>
 */
@TableName(value = "tenant_sandbox_configs", autoResultMap = true)
public class TenantSandboxConfigEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    /**
     * 从 Config 里提升出来，使列表与清理决策不必解密并反序列化载荷
     * （Go 注释原文）。响应仍以 merged 后的 Config 为准（writeConfig/Create 显式赋）。
     */
    @TableField("sandbox_type")
    private String sandboxType;

    /** jsonb 载荷（端点、加密 API key、env vars、卷挂载）；AES 加解在 TypeHandler */
    @TableField(value = "config", typeHandler = TenantSandboxConfigTypeHandler.class)
    private TenantSandboxConfig config;

    /** 身份字段变更期间持有的短租约；见 {@link #isCordoned(OffsetDateTime, java.time.Duration)} */
    @TableField("cordoned_at")
    private OffsetDateTime cordonedAt;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    @TableField("deleted_at")
    @JsonIgnore
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getSandboxType() { return sandboxType; }
    public void setSandboxType(String v) { sandboxType = v == null ? "" : v; }
    public TenantSandboxConfig getConfig() { return config; }
    public void setConfig(TenantSandboxConfig v) { config = v; }
    public OffsetDateTime getCordonedAt() { return cordonedAt; }
    public void setCordonedAt(OffsetDateTime v) { cordonedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }

    /** 对照 {@code IsSandboxWorkspacePolicyRow}：是否内部策略行。 */
    public static boolean isSandboxWorkspacePolicyRow(TenantSandboxConfigEntity e) {
        return e != null && SandboxConstants.SANDBOX_WORKSPACE_POLICY_CONFIG_NAME.equals(e.getName());
    }

    /**
     * 对照 {@code IsCordoned}：沙箱解析此刻是否必须拒绝该配置。
     * cordon 是租约，不是永久锁。
     */
    public boolean isCordoned(OffsetDateTime now, java.time.Duration lease) {
        if (cordonedAt == null) {
            return false;
        }
        return java.time.Duration.between(cordonedAt.toInstant(), now.toInstant())
                .compareTo(lease) < 0;
    }
}
