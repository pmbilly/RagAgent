package com.ragagent.sandbox.domain;

import java.time.OffsetDateTime;

/**
 * 对照 Go {@code types.TenantSkillEntity}（internal/types/tenant_skill.go L48-103）：
 * 安装到一份沙箱配置上的一个 skill。
 *
 * <p>刻意没有 entry_script / interpreter / smoke_command 列：一个 skill 可能跨语言带
 * 多个可执行文件，没有哪个是单值；解释器在执行时按镜像内路径约定推导（Go 注释原文）。</p>
 *
 * <p>所有列经显式 SQL 读写（{@code mapper.TenantSkillMapper}），所以这里只是纯
 * POJO；{@code envs} 列的加密往返见 {@link SkillEnvVarsTypeHandler}。
 * {@code enabled} 用原始 boolean（Go 非指针零值语义，§7.5 第 5 条）。</p>
 */
public class TenantSkillEntity {

    /** 行主键；不是镜像内的目录名（目录名是 SKILL.md 的 name）。 */
    private String id = "";
    private long tenantId;
    private String sandboxConfigId = "";
    /** 指向本安装创建自的租户级 skill 定义；早于 catalog 迁移的行此值为空。 */
    private String catalogId = "";
    /** SKILL.md frontmatter 的 name，也是 /opt/weknora/tenant/skills 下的目录名。同名重装保持不变。 */
    private String name = "";
    private String version = "";
    private String description = "";
    /** SKILL.md 正文（level 2 disclosure）。 */
    private String instructions = "";
    /** 旧制遗留定位符（曾按安装行存 zip）。新安装留空：catalog 行持有归档。 */
    private String bundleRef = "";
    private String bundleSha256 = "";
    /** 只控制对 agent 的可见性；文件无论怎样都留在镜像里。 */
    private boolean enabled;
    /** 本 skill 安装产生的快照，留作审计与链路排查。 */
    private String installedSnapshotId = "";
    /** 定位最近一次安装的 installer agent 转写；重安装会覆写。 */
    private String installSessionId = "";
    private String installMessageId = "";
    /** installer agent 声明的环境变量，可各带一个工作区级管理值。 */
    private SkillEnvVars envs;
    private String status = "";
    private String error = "";
    /** 驱动 install 与 remove 共用的卡死 run reaper。 */
    private OffsetDateTime installingSince;
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

    public String getSandboxConfigId() {
        return sandboxConfigId;
    }

    public void setSandboxConfigId(String sandboxConfigId) {
        this.sandboxConfigId = sandboxConfigId == null ? "" : sandboxConfigId;
    }

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId == null ? "" : catalogId;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInstalledSnapshotId() {
        return installedSnapshotId;
    }

    public void setInstalledSnapshotId(String installedSnapshotId) {
        this.installedSnapshotId = installedSnapshotId == null ? "" : installedSnapshotId;
    }

    public String getInstallSessionId() {
        return installSessionId;
    }

    public void setInstallSessionId(String installSessionId) {
        this.installSessionId = installSessionId == null ? "" : installSessionId;
    }

    public String getInstallMessageId() {
        return installMessageId;
    }

    public void setInstallMessageId(String installMessageId) {
        this.installMessageId = installMessageId == null ? "" : installMessageId;
    }

    public SkillEnvVars getEnvs() {
        return envs;
    }

    public void setEnvs(SkillEnvVars envs) {
        this.envs = envs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error == null ? "" : error;
    }

    public OffsetDateTime getInstallingSince() {
        return installingSince;
    }

    public void setInstallingSince(OffsetDateTime installingSince) {
        this.installingSince = installingSince;
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
