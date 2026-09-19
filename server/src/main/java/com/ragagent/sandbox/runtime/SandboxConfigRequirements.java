package com.ragagent.sandbox.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * 对照 Go {@code internal/sandbox/config_required.go}（全文）。
 *
 * <p>具名配置是自包含的。provider 客户端无法自行合成的每个值都必须出现在工作区配置里，
 * 本文件把这些值命名一次，保存与 resolve 两条路径都对照检查。</p>
 *
 * <p>两类值刻意<b>不</b>必填：自托管部署不用的凭据（单节点 Cube 常不认证 → cube api_key
 * 可选）；SDK 自行解析的值（go-e2b 在 api_url/sandbox_domain 为空时都有默认 → 不强求）。
 * 其余必填恰因缺失会晚而含糊地失败：缺 Cube proxy URL 或 sandbox domain 仍会创建沙箱，
 * 直到 envd 流量被路由时才坏——读起来像 provider 故障而非表单里的笔误。</p>
 */
public final class SandboxConfigRequirements {

    private SandboxConfigRequirements() {
    }

    /**
     * 对照 MissingRequiredFields：列出 cfg 对自己的 provider 缺失的字段，按 API 与设置
     * 表单用的 JSON 键命名，消息可以不经翻译直接透出。disabled 不含后端专属值；
     * docker 必须显式命名镜像。
     */
    public static List<String> missingRequiredFields(EffectiveConfig cfg) {
        List<String> missing = new ArrayList<>();
        if (cfg == null) {
            return missing;
        }
        switch (cfg.type) {
            case SandboxTypes.TYPE_CUBE -> {
                require(missing, "api_url", cfg.cubeApiUrl);
                require(missing, "proxy_url", cfg.cubeProxyUrl);
                require(missing, "sandbox_domain", cfg.cubeSandboxDomain);
                require(missing, "template_id", cfg.cubeTemplate);
            }
            case SandboxTypes.TYPE_E2B -> {
                require(missing, "api_key", cfg.e2bApiKey);
                require(missing, "template_id", cfg.e2bTemplate);
            }
            case SandboxTypes.TYPE_DOCKER -> require(missing, "image", cfg.dockerImage);
            default -> {
            }
        }
        return missing;
    }

    /**
     * 对照 RequireCompleteConfig：MissingRequiredFields 的错误形态。保存路径与 resolve
     * 路径共用，两者以相同措辞拒绝相同的配置。
     *
     * @throws SandboxConfigIncompleteException 消息形如
     *         {@code sandbox: config is missing required fields: <provider> backend requires a, b}
     */
    public static void requireCompleteConfig(EffectiveConfig cfg) {
        List<String> missing = missingRequiredFields(cfg);
        if (missing.isEmpty()) {
            return;
        }
        String provider = cfg == null ? "" : cfg.type;
        throw new SandboxConfigIncompleteException(
                "sandbox: config is missing required fields: " + provider
                        + " backend requires " + String.join(", ", missing));
    }

    private static void require(List<String> missing, String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            missing.add(field);
        }
    }
}
