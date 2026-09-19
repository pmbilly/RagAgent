package com.ragagent.sandbox.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 对照 Go {@code types.SkillEnvVar}（internal/types/tenant_env_vars.go L24-34）：
 * skill 声明的一个环境变量，可选携带管理员提供的全工作区值。
 *
 * <p>除 {@code value} 外的一切都以明文存列，让 UI 在 SYSTEM_AES_KEY 不可用时仍能渲染
 * 声明（Go 注释原文）。{@code value} 是 AES-GCM 加密落库的密钥：Go 用
 * {@code json:"-"} 使它<b>按构造</b>不可序列化——Java 侧对应 {@code @JsonIgnore}，
 * 列的往返走 {@link SkillEnvVarsTypeHandler} 的行形态，任何响应体都不可能因漏剥
 * DTO 而带出它。</p>
 *
 * <p>可变类（对照 Go 值语义的切片元素）：{@code UpdateSkillAdmin} 会就地改写
 * {@code value} 后整列回写。</p>
 */
public final class SkillEnvVar {

    private String name = "";
    private String description = "";
    private boolean required;
    private String value = "";

    public SkillEnvVar() {
    }

    public SkillEnvVar(String name, String description, boolean required, String value) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.required = required;
        this.value = value == null ? "" : value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    /** 密钥值；{@code @JsonIgnore} 对照 Go 的 {@code json:"-"}（恒不进任何响应体）。 */
    @JsonIgnore
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
    }

    /** 列写入前的浅拷贝（接收者不被修改——Go Value() 注释原文）。 */
    public SkillEnvVar copy() {
        return new SkillEnvVar(name, description, required, value);
    }
}
