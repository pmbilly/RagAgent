package com.ragagent.sandbox.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 对照 Go {@code internal/types/config_redaction.go} 的沙箱族（L267-453）+
 * {@code secret.go} 的 PreserveIfRedacted。Java 先例：
 * {@code com.ragagent.auth.domain.tenantconfig.TenantConfigRedaction}（语义同族）。
 *
 * <p>三个方向共用 {@link SandboxNetworkPolicy#cloneWithSecrets(UnaryOperator)}，
 * 保证"哪些字段算密钥"在打码（响应）、加密（落库）、解密（回读）之间不漂移。</p>
 */
public final class SandboxConfigRedaction {

    private SandboxConfigRedaction() {
    }

    /** 对照 secret.go PreserveIfRedacted：空串或 "***" → 保留旧值。 */
    public static String preserveIfRedacted(String incoming, String existing) {
        if (incoming == null || incoming.isEmpty() || SandboxConstants.REDACTED_SECRET_PLACEHOLDER.equals(incoming)) {
            return existing == null ? "" : existing;
        }
        return incoming;
    }

    /**
     * 对照 {@code SandboxConfigForResponse}：返回可安全序列化进 API 响应的副本。
     * maskSecrets=true 时每个携带密钥的字段替换为 "***"；未设置的密钥保持空串，
     * UI 由此区分"已配置"与"未配置"。入参 null → null。
     */
    public static TenantSandboxConfig sandboxConfigForResponse(TenantSandboxConfig cfg, boolean maskSecrets) {
        if (cfg == null) {
            return null;
        }
        TenantSandboxConfig out = cfg.shallowCopy();
        if (!maskSecrets) {
            return out;
        }
        if (out.getCube() != null) {
            CubeSandboxConfig cube = out.getCube().copy();
            if (!cube.getApiKey().isEmpty()) {
                cube.setApiKey(SandboxConstants.REDACTED_SECRET_PLACEHOLDER);
            }
            out.setCube(cube);
        }
        if (out.getE2b() != null) {
            E2BSandboxConfig e2b = out.getE2b().copy();
            if (!e2b.getApiKey().isEmpty()) {
                e2b.setApiKey(SandboxConstants.REDACTED_SECRET_PLACEHOLDER);
            }
            out.setE2b(e2b);
        }
        // EnvVars 值落库加密且可能携带凭据，所以按类整体打码而非按名
        if (out.getEnvVars() != null && !out.getEnvVars().isEmpty()) {
            Map<String, String> envVars = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : out.getEnvVars().entrySet()) {
                String value = e.getValue();
                if (value != null && !value.isEmpty()) {
                    value = SandboxConstants.REDACTED_SECRET_PLACEHOLDER;
                }
                envVars.put(e.getKey(), value);
            }
            out.setEnvVars(envVars);
        }
        // 注入的 header 携带凭据，与 EnvVars 值同样按类打码
        if (out.getNetwork() != null) {
            out.setNetwork(out.getNetwork().cloneWithSecrets(value ->
                    value == null || value.isEmpty() ? "" : SandboxConstants.REDACTED_SECRET_PLACEHOLDER));
        }
        return out;
    }

    /**
     * 对照 {@code MergeSandboxConfigForUpdate}：把 incoming 里的打码占位符对着当前存储的
     * 配置解析，让从未拿到真密钥的客户端提交表单其余部分时不会抹掉旧密钥。
     * 编辑器不拥有的指针（SkillImage、VolumeMount）从 existing 保留。
     */
    public static TenantSandboxConfig mergeSandboxConfigForUpdate(
            TenantSandboxConfig incoming, TenantSandboxConfig existing) {
        if (incoming == null) {
            return null;
        }
        TenantSandboxConfig out = incoming.shallowCopy();

        if (out.getCube() != null) {
            CubeSandboxConfig cube = out.getCube().copy();
            String prevApiKey = existing != null && existing.getCube() != null
                    ? existing.getCube().getApiKey() : "";
            cube.setApiKey(preserveIfRedacted(cube.getApiKey(), prevApiKey));
            out.setCube(cube);
        }
        if (out.getE2b() != null) {
            E2BSandboxConfig e2b = out.getE2b().copy();
            String prevApiKey = existing != null && existing.getE2b() != null
                    ? existing.getE2b().getApiKey() : "";
            e2b.setApiKey(preserveIfRedacted(e2b.getApiKey(), prevApiKey));
            out.setE2b(e2b);
        }
        // 只有 incoming 里出现的键存活：UI 删一行就必须真的删掉变量，
        // 而不是悄悄还原它
        if (out.getEnvVars() != null && !out.getEnvVars().isEmpty()) {
            Map<String, String> envVars = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : out.getEnvVars().entrySet()) {
                String prev = existing != null && existing.getEnvVars() != null
                        ? existing.getEnvVars().get(e.getKey()) : null;
                envVars.put(e.getKey(), preserveIfRedacted(e.getValue(), prev));
            }
            out.setEnvVars(envVars);
        }

        // Network 是编辑器拥有的字段，形状由 incoming 决定；只有凭据值回落到存储值。
        // 与 SkillImage 不同，省略它就是清除——管理员删光每条规则的决定必须生效。
        if (out.getNetwork() != null) {
            SandboxNetworkPolicy prev = existing != null ? existing.getNetwork() : null;
            out.setNetwork(mergeNetworkPolicyForUpdate(out.getNetwork(), prev));
        }

        // SkillImage 与 VolumeMount 由 install / volume 路径拥有，不属于沙箱设置表单。
        // 编辑器重建载荷时两者都没有——原样拷贝 incoming 会在每次运行时保存里抹掉活快照。
        // 从存储行读取（无存储行则清除）还意味着构造的 PUT 既栽不上也抹不掉指针。
        out.setSkillImage(null);
        out.setVolumeMount(null);
        if (existing != null) {
            if (existing.getSkillImage() != null) {
                out.setSkillImage(existing.getSkillImage().copy());
            }
            if (existing.getVolumeMount() != null) {
                out.setVolumeMount(existing.getVolumeMount().copy());
            }
            // 运行时表单省略 skill_rollout。空的 incoming 值不得重置已保存的 "new_session"
            // 选择；skills 面板在管理员切回时发送显式的 next_turn token。
            if ((out.getSkillRollout() == null || out.getSkillRollout().trim().isEmpty())) {
                out.setSkillRollout(existing.getSkillRollout());
            }
        }

        return out;
    }

    /**
     * 对照 {@code mergeNetworkPolicyForUpdate}：把 incoming 里的打码注入 header 密钥对着
     * existing 解析。规则按管理员看到的身份匹配：Cube 按 (规则名, header 名)、E2B 按
     * (host, header 名)。改名的规则因此丢掉存储的密钥——这是正确的，无从区分改名与替换。
     */
    static SandboxNetworkPolicy mergeNetworkPolicyForUpdate(
            SandboxNetworkPolicy incoming, SandboxNetworkPolicy existing) {
        if (incoming == null) {
            return null;
        }
        SandboxNetworkPolicy out = incoming.cloneWithSecrets(value -> value);
        // 入站恒要求凭据。接受线上字段让旧客户端仍能解码，然后丢弃，
        // 使它无法持久化或重新打开。
        out.setAllowPublicInbound(false);
        if (existing == null) {
            return out;
        }

        Map<String, String> storedCube = new LinkedHashMap<>();
        if (existing.getCubeRules() != null) {
            for (SandboxNetworkPolicy.CubeEgressRule rule : existing.getCubeRules()) {
                if (rule.getInject() == null) {
                    continue;
                }
                for (SandboxNetworkPolicy.CubeHeaderInject inject : rule.getInject()) {
                    storedCube.put(networkSecretKey(rule.getName(), inject.getHeader()),
                            inject.getSecret());
                }
            }
        }
        if (out.getCubeRules() != null) {
            for (int i = 0; i < out.getCubeRules().size(); i++) {
                SandboxNetworkPolicy.CubeEgressRule rule = out.getCubeRules().get(i);
                if (rule.getInject() == null) {
                    continue;
                }
                for (int j = 0; j < rule.getInject().size(); j++) {
                    SandboxNetworkPolicy.CubeHeaderInject inject = rule.getInject().get(j);
                    String prev = storedCube.get(networkSecretKey(rule.getName(), inject.getHeader()));
                    rule.getInject().get(j).setSecret(
                            preserveIfRedacted(inject.getSecret(), prev));
                }
            }
        }

        Map<String, String> storedE2B = new LinkedHashMap<>();
        if (existing.getE2bHostRules() != null) {
            for (SandboxNetworkPolicy.E2BHostRule rule : existing.getE2bHostRules()) {
                if (rule.getHeaders() == null) {
                    continue;
                }
                for (Map.Entry<String, String> e : rule.getHeaders().entrySet()) {
                    storedE2B.put(networkSecretKey(rule.getHost(), e.getKey()), e.getValue());
                }
            }
        }
        if (out.getE2bHostRules() != null) {
            for (int i = 0; i < out.getE2bHostRules().size(); i++) {
                SandboxNetworkPolicy.E2BHostRule rule = out.getE2bHostRules().get(i);
                if (rule.getHeaders() == null) {
                    continue;
                }
                for (Map.Entry<String, String> e : rule.getHeaders().entrySet()) {
                    String prev = storedE2B.get(networkSecretKey(rule.getHost(), e.getKey()));
                    rule.getHeaders().put(e.getKey(), preserveIfRedacted(e.getValue(), prev));
                }
            }
        }
        return out;
    }

    /**
     * 对照 {@code networkSecretKey}：merge 用的一条注入 header 的身份。父（规则名/host）
     * 与子（header 名）都 trim + 小写折叠，空白或 HTTP header 大小写变化不算改名。
     */
    static String networkSecretKey(String parent, String child) {
        String p = parent == null ? "" : parent.trim().toLowerCase();
        String c = child == null ? "" : child.trim().toLowerCase();
        return p + "\u0000" + c;
    }
}
