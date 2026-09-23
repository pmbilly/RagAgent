package com.ragagent.sandbox.service;

import com.ragagent.agent.tools.SkillEnvironment;
import com.ragagent.common.context.TenantContext;
import com.ragagent.sandbox.domain.SkillEnvVar;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.domain.TenantUserEnvVar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 agent 运行的 skill 凭据解析器（对照 Go user_env_resolver.go 全文）。
 *
 * <p>按固定顺序分层叠加、后者覆盖前者：管理员的工作区级技能值 → 调用者自己的
 * 配置级变量 → 调用者对该技能的值。Mine 压过 workspace 的；Mine 之间技能专属
 * 压过配置级的。必填变量两层皆空 → missing（agent 循环转述成可行动的一句话）。</p>
 *
 * <p>tenantID/configID 是<b>行集的来源作用域</b>而非上下文恰持有的值——查找绝不
 * 解析到别的工作区或别的配置（Go 注释原文）。调用者身份是上下文里的 Principal：
 * 用 user id 会给同一工作区的每个 IM 调用者同一个合成账户、同一套值。</p>
 */
public final class UserEnvResolver implements SkillEnvironment.SkillEnvResolver {

    /** 按模型称呼技能的名字索引行。 */
    private final Map<String, TenantSkillEntity> byName;
    private final UserEnvService userEnvs;
    private final long tenantId;
    private final String configId;

    public UserEnvResolver(List<TenantSkillEntity> rows, UserEnvService userEnvs,
            long tenantId, String configId) {
        this.byName = new LinkedHashMap<>();
        if (rows != null) {
            for (TenantSkillEntity row : rows) {
                if (row != null && row.getName() != null && !row.getName().isEmpty()) {
                    byName.put(row.getName(), row);
                }
            }
        }
        this.userEnvs = userEnvs;
        this.tenantId = tenantId;
        this.configId = configId == null ? "" : configId;
    }

    @Override
    public SkillEnvironment.SkillEnvResolution resolveEnv(String skillName) throws Exception {
        // 身份只来自上下文里的 Principal
        TenantContext.Principal principal = TenantContext.currentPrincipal();
        boolean canReadMine = principal != null && userEnvs != null;

        Map<String, String> env = new LinkedHashMap<>();
        TenantSkillEntity row = skillName == null ? null : byName.get(skillName);
        if (row != null && row.getEnvs() != null) {
            for (SkillEnvVar declared : row.getEnvs()) {
                // 空的管理员值意味着未设置；注入它会让"未配置的变量"与"设为空串"
                // 无法区分（Go 注释原文）
                if (declared != null && declared.getValue() != null && !declared.getValue().isEmpty()) {
                    env.put(declared.getName(), declared.getValue());
                }
            }
        }

        if (canReadMine) {
            overlayMine(principal, "", env);
            if (row != null) {
                overlayMine(principal, row.getId(), env);
            }
        }

        List<String> missing = new ArrayList<>();
        if (row != null && row.getEnvs() != null) {
            for (SkillEnvVar declared : row.getEnvs()) {
                if (declared != null && declared.isRequired()
                        && (env.get(declared.getName()) == null || env.get(declared.getName()).isEmpty())) {
                    missing.add(declared.getName());
                }
            }
        }
        return new SkillEnvironment.SkillEnvResolution(env, missing);
    }

    /**
     * 对照 overlayMine：把调用者对一个作用域的自有值写上 env。失败绝不降级成
     * 管理员值——用别人的 key 跑技能比跑不了更糟（Go 注释原文）。
     */
    private void overlayMine(TenantContext.Principal principal, String skillId,
            Map<String, String> env) throws Exception {
        List<TenantUserEnvVar> owned = userEnvs.listDecryptedForResolver(
                tenantId, principal, configId, skillId);
        if (owned == null) {
            return;
        }
        for (TenantUserEnvVar value : owned) {
            if (value == null || value.getValue() == null || value.getValue().isEmpty()) {
                continue;
            }
            env.put(value.getName(), value.getValue());
        }
    }
}
