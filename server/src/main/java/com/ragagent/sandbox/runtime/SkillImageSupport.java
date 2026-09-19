package com.ragagent.sandbox.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.ragagent.sandbox.domain.DockerSandboxConfig;
import com.ragagent.sandbox.domain.SkillImageConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfig;

/**
 * 对照 Go {@code internal/sandbox/skill_image.go} 本批需要的子集
 * （SkillImageFingerprint / dockerSkillOwnerIdentity / DockerSkillImageOverride /
 * skillImageTemplateOverride）。快照 ledger 本身属子批 2。
 */
public final class SkillImageSupport {

    /**
     * 对照 dockerLocalDaemonIdentity：本地 daemon 的身份占位。显式配置的 host 仍是身份
     * 一部分（只有管理员编辑配置才会变）。
     */
    public static final String DOCKER_LOCAL_DAEMON_IDENTITY = "local-daemon";

    private SkillImageSupport() {
    }

    /**
     * 对照 SkillImageFingerprint：识别 skill 快照所在的 provider 账户。快照跨账户不可见，
     * 凭据变化时存储的快照对我们悄悄不复存在——这个指纹用于发现并回落，
     * 而不是拿着死镜像 ID 启动会话。sha256(join("\n", provider, apiKey, apiURL)) 的 hex。
     */
    public static String skillImageFingerprint(String provider, String apiKey, String apiURL) {
        String joined = trim(provider) + "\n" + trim(apiKey) + "\n" + trim(apiURL);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    /**
     * 对照 dockerSkillOwnerIdentity：读<b>存储的</b>配置而非 resolve 后的，
     * 使所有调用方从相同输入计算指纹。
     */
    static String[] dockerSkillOwnerIdentity(DockerSandboxConfig docker) {
        if (docker == null) {
            return new String[] {"", ""};
        }
        String host = docker.getHost() == null ? "" : docker.getHost().trim();
        if (host.isEmpty()) {
            host = DOCKER_LOCAL_DAEMON_IDENTITY;
        }
        return new String[] {host, docker.getTlsCertPath() == null ? "" : docker.getTlsCertPath().trim()};
    }

    /**
     * 对照 DockerSkillImageOverride：docker 后端应改启的已提交 skill 镜像，
     * 或 ""（保留基础镜像）。
     */
    public static String dockerSkillImageOverride(TenantSandboxConfig tenantCfg) {
        if (tenantCfg == null || tenantCfg.getDocker() == null) {
            return "";
        }
        String[] owner = dockerSkillOwnerIdentity(tenantCfg.getDocker());
        return skillImageTemplateOverride(tenantCfg.getSkillImage(), "docker", owner[1], owner[0]);
    }

    /**
     * 对照 skillImageTemplateOverride：应替换基础模板的快照 ID；
     * 必须保留基础模板时返回 ""（快照缺失、无指纹、或指纹不匹配）。
     */
    public static String skillImageTemplateOverride(
            SkillImageConfig image, String provider, String apiKey, String apiURL) {
        if (image == null || image.getSnapshotId() == null || image.getSnapshotId().trim().isEmpty()) {
            return "";
        }
        if (image.getOwnerFingerprint() == null || image.getOwnerFingerprint().isEmpty()) {
            return "";
        }
        if (!image.getOwnerFingerprint().equals(skillImageFingerprint(provider, apiKey, apiURL))) {
            return "";
        }
        return image.getSnapshotId().trim();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
