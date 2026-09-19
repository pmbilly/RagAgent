package com.ragagent.sandbox.service;

import com.ragagent.sandbox.runtime.ConfigSandboxClient;
import com.ragagent.sandbox.runtime.DockerBackendDisabledException;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.RemoteConfigSandboxClient;
import com.ragagent.sandbox.runtime.RemoteError;
import com.ragagent.sandbox.runtime.RemoteErrorKind;
import com.ragagent.sandbox.runtime.SandboxBackendPolicy;
import com.ragagent.sandbox.runtime.SandboxTypes;
import org.springframework.stereotype.Component;

/**
 * provider 客户端工厂（对照 Go {@code newClient} 字段的默认值
 * {@code sandbox.NewRemoteClientForCheck}，tenant_resolver.go L239-261）。
 *
 * <p>波 3 子批 2 起为真实实现：按配置类型构建 {@link RemoteConfigSandboxClient}
 * （cube/e2b 控制面探测 + 盘点；docker 先过后端开关）。客户端方法在控制面
 * 不可达时抛 {@link RemoteError}（分类见 {@link RemoteErrorKind}）——Update/
 * Delete/Inventory/QueryTemplates/sandbox-check 各自的失败分支由此驱动，
 * 与 Go 的失败分支同形。</p>
 */
@Component
public class RemoteSandboxClientFactory implements SandboxClientFactory {

    @Override
    public ConfigSandboxClient create(EffectiveConfig effective) {
        if (effective == null) {
            throw new IllegalArgumentException("sandbox: config is required");
        }
        try {
            return switch (effective.type) {
                case SandboxTypes.TYPE_CUBE,
                     SandboxTypes.TYPE_E2B,
                     SandboxTypes.TYPE_DOCKER ->
                        new RemoteConfigSandboxClient(buildProvider(effective));
                default -> throw RemoteError.of(effective.type, "build",
                        RemoteErrorKind.INTERNAL,
                        "sandbox: provider \"" + effective.type + "\" cannot be probed");
            };
        } catch (DockerBackendDisabledException e) {
            // NewRemoteClientForCheck 的 docker 分支直接透传开关错误
            // （ErrDockerBackendDisabled 原文，sandbox-check 的 client_build 检查展示它）
            throw e;
        } catch (SandboxClientBuildException e) {
            throw e;
        } catch (RuntimeException e) {
            // 构造期传输异常（如 unix socket 探测）按各 provider 的归一化路径收口
            if (e instanceof RemoteError re) {
                throw re;
            }
            throw RemoteError.of(effective.type, "build", RemoteErrorKind.INTERNAL,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private com.ragagent.sandbox.runtime.RemoteProviderClient buildProvider(EffectiveConfig cfg) {
        return switch (cfg.type) {
            case SandboxTypes.TYPE_CUBE -> com.ragagent.sandbox.runtime.RemoteProviderClient.cube(cfg);
            case SandboxTypes.TYPE_E2B -> com.ragagent.sandbox.runtime.RemoteProviderClient.e2b(cfg);
            case SandboxTypes.TYPE_DOCKER -> com.ragagent.sandbox.runtime.RemoteProviderClient.docker(cfg);
            default -> throw RemoteError.of(cfg.type, "build", RemoteErrorKind.INTERNAL,
                    "sandbox: provider \"" + cfg.type + "\" cannot be probed");
        };
    }

    /** 构建失败的显式信号（不与 RemoteError 混淆时的兜底类型）。 */
    public static class SandboxClientBuildException extends RuntimeException {
        public SandboxClientBuildException(String message) {
            super(message);
        }
    }
}
