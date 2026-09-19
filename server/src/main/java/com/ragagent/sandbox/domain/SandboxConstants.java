package com.ragagent.sandbox.domain;

/**
 * 波 3 sandbox 子批 1 的跨文件常量（对照 Go 三处来源，逐条注明）：
 *
 * <ul>
 *   <li>{@code internal/types/tenant.go} L815-822：SkillRollout 两个 token；</li>
 *   <li>{@code internal/types/tenant_sandbox_config_entity.go}：SandboxConfigIDGlobalDefault、
 *       SandboxWorkspacePolicyConfigName、SandboxCordonLease；</li>
 *   <li>{@code internal/types/secret.go}：RedactedSecretPlaceholder（"***"）。</li>
 * </ul>
 */
public final class SandboxConstants {

    /** 对照 types.RedactedSecretPlaceholder：API 响应里已配置但被扣下的密钥的固定值。 */
    public static final String REDACTED_SECRET_PLACEHOLDER = "***";

    /** 对照 types.SkillRolloutNextTurn（默认）：skill 镜像变更后，活会话在下一个聊天轮重建沙箱。 */
    public static final String SKILL_ROLLOUT_NEXT_TURN = "next_turn";

    /** 对照 types.SkillRolloutNewSession：活沙箱留在旧镜像上，只有之后新开的会话用新快照。 */
    public static final String SKILL_ROLLOUT_NEW_SESSION = "new_session";

    /**
     * 对照 types.SandboxConfigIDGlobalDefault：为旧版本（部署级沙箱配置）创建的会话保留的
     * 哨兵值。用哨兵而非空串，使 sessions.sandbox_config_id 的 NULL 明确表示"该会话没有活沙箱"。
     */
    public static final String SANDBOX_CONFIG_ID_GLOBAL_DEFAULT = "-";

    /**
     * 对照 types.SandboxWorkspacePolicyConfigName：工作区级"停用部署默认 agent 的脚本执行"
     * 开关占用的保留行名。对管理列表隐藏，仅经 workspace-policy API 更新。
     */
    public static final String SANDBOX_WORKSPACE_POLICY_CONFIG_NAME = "__workspace_scripts_policy__";

    /**
     * 对照 types.SandboxCordonLease：cordon 的租约时长。身份变更持有它的时长是两次
     * provider API 调用；更旧的 cordon 是崩溃 handler 的遗留物，不得卡死该配置。
     */
    public static final java.time.Duration SANDBOX_CORDON_LEASE = java.time.Duration.ofMinutes(2);

    private SandboxConstants() {
    }
}
