package com.ragagent.sandbox.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 对照 Go {@code internal/sandbox/docker_enabled.go}（全文）。
 *
 * <p>Docker 沙箱后端的进程级开关。能保存 Docker 配置的工作区管理员可以在本进程能触达的
 * 任何 Engine API 上创建容器——通常是宿主的 docker.sock，等于宿主 root。因此默认关闭，
 * 直到 SystemAdmin 或部署者显式启用。</p>
 *
 * <p>三层解析（DB &gt; env &gt; false）：System Settings（sandbox.docker_enabled）推送的
 * 覆盖值优先于 env；本批只接 env 层 + 覆盖值存取口（对照 Go 的 atomic.Pointer，
 * SystemSettingService 的推送随系统设置/sandbox 收口时调 {@link #setDockerBackendEnabled}）。</p>
 */
public final class SandboxBackendPolicy {

    /** 对照 DockerBackendEnabledEnv：进程级兜底开关的环境变量键名。 */
    public static final String DOCKER_BACKEND_ENABLED_ENV = "WEKNORA_SANDBOX_DOCKER_ENABLED";

    /** 对照 DockerBackendEnabledSettingKey：system_settings 注册表键。 */
    public static final String DOCKER_BACKEND_ENABLED_SETTING_KEY = "sandbox.docker_enabled";

    /**
     * 对照 dockerBackendEnabledOverride：运行期可调源。null 表示"SystemSettingService
     * 尚未推送"，此时 {@link #dockerBackendEnabled()} 读 env。
     */
    private static final AtomicReference<Boolean> OVERRIDE = new AtomicReference<>(null);

    private SandboxBackendPolicy() {
    }

    /**
     * 对照 SetDockerBackendEnabled：记录解析后的三层值（DB &gt; env &gt; false）。
     * system_settings 的 preload/Update/Reset/pubsub reload 调用。
     */
    public static void setDockerBackendEnabled(boolean enabled) {
        OVERRIDE.set(enabled);
    }

    /**
     * 对照 ClearDockerBackendEnabledOverride：恢复仅 env 解析。否则某个测试构造的
     * SystemSettingService 会把预载推送泄漏给同包后续只 Setenv 的用例。
     */
    public static void clearDockerBackendEnabledOverride() {
        OVERRIDE.set(null);
    }

    /**
     * 对照 DockerBackendEnabled：本进程是否可运行 Docker 沙箱后端。
     * 空的或不可解析的 env 值一律 false。接受集合与 strconv.ParseBool 一致
     * （1/t/T/TRUE/true/True → true；0/f/F/FALSE/false/False → false；其余 false）。
     */
    public static boolean dockerBackendEnabled() {
        Boolean override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        String raw = System.getenv(DOCKER_BACKEND_ENABLED_ENV);
        if (raw == null) {
            return false;
        }
        switch (raw.trim()) {
            case "1", "t", "T", "true", "TRUE", "True":
                return true;
            default:
                return false;
        }
    }

    /**
     * 对照 EnsureDockerBackendAllowed：所有要代表工作区配置与 Docker daemon 通信的路径
     * 的总闸。
     *
     * @throws DockerBackendDisabledException 类型是 docker 且进程未启用时
     */
    public static void ensureDockerBackendAllowed(String type) {
        if (!SandboxTypes.TYPE_DOCKER.equals(type)) {
            return;
        }
        if (dockerBackendEnabled()) {
            return;
        }
        throw new DockerBackendDisabledException();
    }
}
