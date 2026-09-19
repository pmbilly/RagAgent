package com.ragagent.sandbox.domain;

import java.time.OffsetDateTime;

/**
 * 对照 Go {@code types.TenantSkillCatalogEntity}（internal/types/tenant_skill.go
 * L143-160）：一个工作区级 skill 定义。它不属于某个沙箱：装到各配置镜像上的安装是
 * 指回这里的 {@link TenantSkillEntity} 行。
 */
public class TenantSkillCatalogEntity {

    private String id = "";
    private long tenantId;
    private String name = "";
    private String version = "";
    private String description = "";
    private String instructions = "";
    /** 本定义唯一一份 zip 的定位符。安装行不持副本：沙箱卸载不得删除这个对象。 */
    private String bundleRef = "";
    private String bundleSha256 = "";
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "" : version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions == null ? "" : instructions;
    }

    public String getBundleRef() {
        return bundleRef;
    }

    public void setBundleRef(String bundleRef) {
        this.bundleRef = bundleRef == null ? "" : bundleRef;
    }

    public String getBundleSha256() {
        return bundleSha256;
    }

    public void setBundleSha256(String bundleSha256) {
        this.bundleSha256 = bundleSha256 == null ? "" : bundleSha256;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
