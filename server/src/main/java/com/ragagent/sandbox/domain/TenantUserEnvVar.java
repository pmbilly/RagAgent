package com.ragagent.sandbox.domain;

import java.time.OffsetDateTime;

/**
 * 对照 Go {@code types.TenantUserEnvVar}（internal/types/tenant_env_vars.go L127-160）：
 * 一个主体自己的环境变量。空 {@code skillId} = 整个沙箱配置共用（注入该配置上的
 * 每一次执行）；非空 = 只注入点名该 skill 的执行。存储形态相同，只是加载时机不同。
 *
 * <p>按主体（principal）而非 user id 键：IM 路径会把合成账号 {@code system-<tenantID>}
 * 塞进 user id，那会让一个工作区的所有 IM 用户共享一份凭据（Go 注释原文）。</p>
 *
 * <p>{@code value} 不出现在任何响应体（Go {@code json:"-"}; 本类不作响应体）。
 * 落库加密在读/写路径的 service 层显式做（对照 Go 的 BeforeSave/AfterFind 钩子，
 * §3 清单第 1 条的"service 层显式赋值"等效）。</p>
 */
public class TenantUserEnvVar {

    private String id = "";
    private long tenantId;
    private String principalType = "";
    private String principalId = "";
    private String sandboxConfigId = "";
    /** 空 = 配置级变量；非空 = 该 skill 声明的凭据。 */
    private String skillId = "";
    private String name = "";
    /** 存储态（密文或明文，随部署 AES key 而定）；service 层加解密。 */
    private String value = "";
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

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

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType == null ? "" : principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId == null ? "" : principalId;
    }

    public String getSandboxConfigId() {
        return sandboxConfigId;
    }

    public void setSandboxConfigId(String sandboxConfigId) {
        this.sandboxConfigId = sandboxConfigId == null ? "" : sandboxConfigId;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId == null ? "" : skillId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
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
}
