package com.ragagent.sandbox.domain;

import java.util.ArrayList;

/**
 * 对照 Go {@code types.SkillEnvVars}（internal/types/tenant_env_vars.go L49）：
 * 一份 skill 的整份声明列表，作为一列 JSON 存储（{@code tenant_skills.envs}）。
 *
 * <p>列的读写在 {@link SkillEnvVarsTypeHandler}（对照 Go 的 driver.Valuer/sql.Scanner
 * 钩子模式，因为加密字段活在 JSON 文档内部）。本类只是带 {@code Get(name)} 的列表：
 * Go 的 {@code (v SkillEnvVars) Get} 供 UpdateSkillAdmin 按 name 查声明。</p>
 */
public class SkillEnvVars extends ArrayList<SkillEnvVar> {

    /** 对照 {@code Get}：返回 name 的声明；未声明时 null。 */
    public SkillEnvVar get(String name) {
        for (SkillEnvVar e : this) {
            if (e.getName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** 对照 Go 的 {@code (v SkillEnvVars) Get} 的 ok 形态。 */
    public boolean declares(String name) {
        return get(name) != null;
    }
}
