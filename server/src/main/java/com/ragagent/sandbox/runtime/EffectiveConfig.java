package com.ragagent.sandbox.runtime;

import java.util.List;
import java.util.Map;

/**
 * 对照 Go {@code sandbox.Config}（internal/sandbox/sandbox.go L244-374）：沙箱管理器的配置。
 *
 * <p>Go 的 {@code time.Duration} 字段（DefaultTimeout / 各 TTL / HTTP 超时）在本批只以
 * "秒"粒度赋值（overrideSeconds 的唯一写入形态），统一用 {@code longSeconds} 表达，
 * 0 = 未设（回落内建默认）。</p>
 *
 * <p>{@link #defaultConfig()} 对照 {@code DefaultConfig}：刻意不带任何 Cube/E2B 端点、
 * 凭据或模板——那些属于具名工作区配置。曾在这里预设它们，导致不完整的工作区配置
 * 会悄悄拨号 localhost。</p>
 */
public class EffectiveConfig {

    // ── 默认值常量（对照 sandbox.go L44-96 与 docker_engine.go L89-114） ────

    public static final long DEFAULT_TIMEOUT_SEC = 60;
    public static final long DEFAULT_MEMORY_LIMIT = 256 * 1024 * 1024; // 256MB
    public static final double DEFAULT_CPU_LIMIT = 1.0;
    /**
     * 对照 DefaultDockerImage：跟踪 main 而非 latest（镜像注释见 Go 原文）。
     */
    public static final String DEFAULT_DOCKER_IMAGE = "wechatopenai/weknora-sandbox:main";
    public static final long DEFAULT_CUBE_SANDBOX_TTL_SEC = 30 * 60;
    public static final long DEFAULT_CUBE_HTTP_TIMEOUT_SEC = 30;
    /** 对照 DefaultE2BSandboxTTL / DefaultE2BHTTPTimeout */
    public static final long DEFAULT_E2B_SANDBOX_TTL_SEC = 5 * 60;
    public static final long DEFAULT_E2B_HTTP_TIMEOUT_SEC = 30;
    /** 对照 DefaultDockerHost */
    public static final String DEFAULT_DOCKER_HOST = "unix:///var/run/docker.sock";
    public static final long DEFAULT_DOCKER_HTTP_TIMEOUT_SEC = 30;
    public static final long DEFAULT_DOCKER_IDLE_TTL_SEC = 30 * 60;
    public static final long DEFAULT_DOCKER_MEMORY_LIMIT = 2L * 1024 * 1024 * 1024;
    public static final double DEFAULT_DOCKER_CPU_LIMIT = 2.0;
    public static final long DEFAULT_DOCKER_PIDS_LIMIT = 512;
    /** 对照 DefaultTerminalIdleDisconnect（terminal.go）：0 值存储配置的回落。 */
    public static final long DEFAULT_TERMINAL_IDLE_DISCONNECT_SEC = 15 * 60;
    static final long MIN_TERMINAL_IDLE_DISCONNECT_SEC = 60;
    static final long MAX_TERMINAL_IDLE_DISCONNECT_SEC = 24 * 60 * 60;

    /** 对照 Config.Type */
    public String type = SandboxTypes.TYPE_DISABLED;

    /** 对照 Config.DefaultTimeout（秒） */
    public long defaultTimeoutSec = DEFAULT_TIMEOUT_SEC;

    /** 对照 Config.TerminalIdleDisconnect（秒；0 = 使用时按内建默认处理） */
    public long terminalIdleDisconnectSec;

    /** 对照 Config.AllowPrivateEndpoints */
    public boolean allowPrivateEndpoints;

    /** 对照 Config.DockerImage / DockerHost / DockerTLSCertPath */
    public String dockerImage = DEFAULT_DOCKER_IMAGE;
    public String dockerHost = "";
    public String dockerTlsCertPath = "";

    /** 对照 DockerCPULimit / DockerMemoryBytes / DockerPidsLimit */
    public double dockerCpuLimit;
    public long dockerMemoryBytes;
    public long dockerPidsLimit;

    /** 对照 DockerNetworkMode（"bridge" / "none"） */
    public String dockerNetworkMode = "";
    /** 对照 DockerRuntime */
    public String dockerRuntime = "";
    public long dockerIdleTtlSec;
    public long dockerHttpTimeoutSec;

    /** 对照 MaxMemory / MaxCPU */
    public long maxMemory = DEFAULT_MEMORY_LIMIT;
    public double maxCpu = DEFAULT_CPU_LIMIT;

    /** 对照 EnvVars */
    public Map<String, String> envVars;

    /** 对照 Network：默认配置与 ResolveEffectiveConfig 完全指定它 */
    public RemoteNetworkPolicy network;

    // ── Cube ────────────────────────────────────────────────────────────

    public String cubeApiUrl = "";
    public String cubeProxyUrl = "";
    public String cubeSandboxDomain = "";
    public String cubeApiKey = "";
    public String cubeTemplate = "";
    public long cubeSandboxTtlSec;
    public long cubeHttpTimeoutSec;
    public List<String> cubeDnsServers;

    // ── E2B ─────────────────────────────────────────────────────────────

    public String e2bApiUrl = "";
    public String e2bProxyUrl = "";
    public String e2bSandboxDomain = "";
    public String e2bApiKey = "";
    public String e2bTemplate = "";
    public long e2bSandboxTtlSec;
    public long e2bHttpTimeoutSec;

    /** 对照 DefaultConfig：跨领域设置取内建默认，provider 作用域字段全空。 */
    public static EffectiveConfig defaultConfig() {
        EffectiveConfig cfg = new EffectiveConfig();
        cfg.type = SandboxTypes.TYPE_DISABLED;
        cfg.defaultTimeoutSec = DEFAULT_TIMEOUT_SEC;
        cfg.dockerImage = DEFAULT_DOCKER_IMAGE;
        cfg.maxMemory = DEFAULT_MEMORY_LIMIT;
        cfg.maxCpu = DEFAULT_CPU_LIMIT;
        cfg.cubeSandboxTtlSec = DEFAULT_CUBE_SANDBOX_TTL_SEC;
        cfg.cubeHttpTimeoutSec = DEFAULT_CUBE_HTTP_TIMEOUT_SEC;
        cfg.network = EffectiveConfigResolver.resolveNetworkPolicy(null);
        return cfg;
    }

    /**
     * 对照 EffectiveTemplateID：给定 provider 将使用的模板。Docker 的 image 就是
     * MicroVM 后端的 template ID：沙箱从中启动的预烘焙文件系统。
     */
    public String effectiveTemplateId() {
        switch (type) {
            case SandboxTypes.TYPE_CUBE:
                return cubeTemplate;
            case SandboxTypes.TYPE_E2B:
                return e2bTemplate;
            case SandboxTypes.TYPE_DOCKER:
                return dockerImage;
            default:
                return "";
        }
    }

    /**
     * 对照 EffectiveTerminalIdleDisconnect（terminal.go）：把存储的工作区值钳到终端桥
     * 会遵循的区间。0（未设）用内建默认，既有配置无需迁移即获得 idle 断开。
     */
    public static long effectiveTerminalIdleDisconnect(long seconds) {
        if (seconds <= 0) {
            return DEFAULT_TERMINAL_IDLE_DISCONNECT_SEC;
        }
        if (seconds < MIN_TERMINAL_IDLE_DISCONNECT_SEC) {
            return MIN_TERMINAL_IDLE_DISCONNECT_SEC;
        }
        if (seconds > MAX_TERMINAL_IDLE_DISCONNECT_SEC) {
            return MAX_TERMINAL_IDLE_DISCONNECT_SEC;
        }
        return seconds;
    }
}
