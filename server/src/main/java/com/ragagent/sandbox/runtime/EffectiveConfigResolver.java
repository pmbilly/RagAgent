package com.ragagent.sandbox.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.ragagent.sandbox.domain.CubeSandboxConfig;
import com.ragagent.sandbox.domain.DockerSandboxConfig;
import com.ragagent.sandbox.domain.E2BSandboxConfig;
import com.ragagent.sandbox.domain.SandboxNetworkPolicy;
import com.ragagent.sandbox.domain.TenantSandboxConfig;

/**
 * 对照 Go {@code internal/sandbox/tenant_config.go}（ResolveEffectiveConfig 及其辅助，
 * 全文）+ {@code runtime_defaults.go}（applyXxxRuntimeDefaults）。
 *
 * <p>ResolveEffectiveConfig 把一个存储配置变成构建租户沙箱管理器用的 {@link EffectiveConfig}，
 * 或在存储配置不安全（ErrUnsafeOutboundURL）/不完整（ErrSandboxConfigIncomplete）时报错。</p>
 *
 * <p>字段级继承被试过又删掉了（Go 注释：存储行变成沙箱实际所在位置的不完整画像，
 * 同时破坏身份比较、.env 编辑语义与 provider 与部署模式不一致的配置）。
 * 仍来自基线的刻意地窄：部署的脚本执行超时，它是运维护栏而非后端身份的一部分。</p>
 */
public final class EffectiveConfigResolver {

    private EffectiveConfigResolver() {
    }

    /**
     * 对照 ResolveEffectiveConfig。overrideX 辅助虽然读作 "override"，但 provider 字段
     * 刚被清空：它们只赋非空值——恰是让省略的 TTL 落到内建默认所需的语义。
     */
    public static EffectiveConfig resolveEffectiveConfig(TenantSandboxConfig tenantCfg,
            EffectiveConfig global) {
        if (global == null) {
            throw new IllegalStateException("sandbox: global config is required");
        }
        EffectiveConfig effective = copyGlobal(global);
        if (tenantCfg == null) {
            effective.network = resolveNetworkPolicy(null);
            return effective;
        }
        // 保留基线的跨领域设置，丢弃一切 provider 作用域的：
        // 从此存储配置是端点、凭据、域名与模板的唯一来源
        clearProviderFields(effective);

        if (tenantCfg.getSandboxType() != null && !tenantCfg.getSandboxType().isEmpty()) {
            effective.type = SandboxTypes.parseSandboxType(tenantCfg.getSandboxType());
        }
        overrideSeconds(s -> effective.defaultTimeoutSec = s, tenantCfg.getDefaultTimeoutSec());
        // 终端 idle 是工作区策略而非部署默认：省略的值必须落到内建 15 分钟，
        // 绝不落到进程 Config 恰好携带的值
        effective.terminalIdleDisconnectSec = 0;
        overrideSeconds(s -> effective.terminalIdleDisconnectSec = s,
                tenantCfg.getTerminalIdleDisconnectSec());
        effective.terminalIdleDisconnectSec =
                EffectiveConfig.effectiveTerminalIdleDisconnect(effective.terminalIdleDisconnectSec);
        effective.allowPrivateEndpoints = tenantCfg.isAllowPrivateEndpoints();
        effective.network = resolveNetworkPolicy(tenantCfg.getNetwork());
        if (tenantCfg.getEnvVars() != null) {
            effective.envVars = cloneMetadata(tenantCfg.getEnvVars());
        }

        CubeSandboxConfig cube = tenantCfg.getCube();
        if (cube != null) {
            overrideUrl(s -> effective.cubeApiUrl = s, cube.getApiUrl(), effective.allowPrivateEndpoints);
            overrideUrl(s -> effective.cubeProxyUrl = s, cube.getProxyUrl(), effective.allowPrivateEndpoints);
            overrideString(s -> effective.cubeSandboxDomain = s, cube.getSandboxDomain());
            overrideString(s -> effective.cubeApiKey = s, cube.getApiKey());
            overrideString(s -> effective.cubeTemplate = s, cube.getTemplateId());
            overrideSeconds(s -> effective.cubeHttpTimeoutSec = s, cube.getHttpTimeoutSec());
            overrideSeconds(s -> effective.cubeSandboxTtlSec = s, cube.getCubeSandboxTtlSeconds());
            effective.cubeDnsServers = CubeDns.normalizeCubeDNSServers(cube.getDnsServers());
        }

        E2BSandboxConfig e2bCfg = tenantCfg.getE2b();
        if (e2bCfg != null) {
            overrideUrl(s -> effective.e2bApiUrl = s, e2bCfg.getApiUrl(), effective.allowPrivateEndpoints);
            overrideUrl(s -> effective.e2bProxyUrl = s, e2bCfg.getProxyUrl(), effective.allowPrivateEndpoints);
            overrideString(s -> effective.e2bSandboxDomain = s, e2bCfg.getSandboxDomain());
            overrideString(s -> effective.e2bApiKey = s, e2bCfg.getApiKey());
            overrideString(s -> effective.e2bTemplate = s, e2bCfg.getTemplateId());
            overrideSeconds(s -> effective.e2bHttpTimeoutSec = s, e2bCfg.getHttpTimeoutSec());
            overrideSeconds(s -> effective.e2bSandboxTtlSec = s, e2bCfg.getE2bSandboxTtlSeconds());
        }

        DockerSandboxConfig docker = tenantCfg.getDocker();
        if (docker != null) {
            overrideString(s -> effective.dockerImage = s, docker.getImage());
            DockerHostSupport.validateDockerNetworkMode(docker.getNetworkMode());
            overrideString(s -> effective.dockerHost = s, docker.getHost());
            overrideString(s -> effective.dockerTlsCertPath = s, docker.getTlsCertPath());
            overrideString(s -> effective.dockerNetworkMode = s, docker.getNetworkMode());
            overrideString(s -> effective.dockerRuntime = s, docker.getRuntime());
            if (docker.getCpuLimit() > 0) {
                effective.dockerCpuLimit = docker.getCpuLimit();
            }
            if (docker.getMemoryLimitMb() > 0) {
                effective.dockerMemoryBytes = (long) docker.getMemoryLimitMb() * 1024 * 1024;
            }
            if (docker.getPidsLimit() > 0) {
                effective.dockerPidsLimit = docker.getPidsLimit();
            }
            overrideSeconds(s -> effective.dockerIdleTtlSec = s, docker.getIdleTtlSeconds());
            overrideSeconds(s -> effective.dockerHttpTimeoutSec = s, docker.getHttpTimeoutSec());
        }

        switch (effective.type) {
            case SandboxTypes.TYPE_CUBE -> applyCubeRuntimeDefaults(effective);
            case SandboxTypes.TYPE_E2B -> applyE2BRuntimeDefaults(effective);
            case SandboxTypes.TYPE_DOCKER -> applyDockerRuntimeDefaults(effective);
            default -> {
            }
        }
        // skill 快照是 template ID（Cube/E2B）或镜像 tag（Docker），
        // 所以在这里覆盖那个字段就是会话侧的全部变化。
        // 下游一切继续读 CubeTemplate / E2BTemplate / DockerImage，无需知道 skills 的存在。
        switch (effective.type) {
            case SandboxTypes.TYPE_CUBE -> {
                String snapshot = SkillImageSupport.skillImageTemplateOverride(
                        tenantCfg.getSkillImage(), "cube", effective.cubeApiKey, effective.cubeApiUrl);
                if (!snapshot.isEmpty()) {
                    effective.cubeTemplate = snapshot;
                }
            }
            case SandboxTypes.TYPE_E2B -> {
                String snapshot = SkillImageSupport.skillImageTemplateOverride(
                        tenantCfg.getSkillImage(), "e2b", effective.e2bApiKey, effective.e2bApiUrl);
                if (!snapshot.isEmpty()) {
                    effective.e2bTemplate = snapshot;
                }
            }
            case SandboxTypes.TYPE_DOCKER -> {
                // 刻意从存储的 docker 块而非 effective.dockerHost 计算：
                // 空 host 由 applyDockerRuntimeDefaults 从环境解析，解析出的值绝不能进入指纹
                String snapshot = SkillImageSupport.dockerSkillImageOverride(tenantCfg);
                if (!snapshot.isEmpty()) {
                    effective.dockerImage = snapshot;
                }
            }
            default -> {
            }
        }
        // 刻意在 runtime defaults 之后：TTL 与 HTTP 超时有内建回落，端点与凭据没有
        SandboxConfigRequirements.requireCompleteConfig(effective);
        // daemon endpoint 按 RESOLVED host 判定，这正是它不能上移到其他 Docker 字段旁边
        // 的原因（applyDockerRuntimeDefaults 才会把空 host 从 DOCKER_HOST / docker context
        // 填上，那个值才是本配置真正会拨的）。它在 RequireCompleteConfig 之后运行，
        // 使缺镜像——管理员看得见修得了的字段——仍是第一个被报告的。
        if (SandboxTypes.TYPE_DOCKER.equals(effective.type)) {
            DockerHostSupport.validateDockerHost(effective.dockerHost, effective.allowPrivateEndpoints);
            DockerHostSupport.validateDockerRemoteTLS(effective.dockerHost, effective.dockerTlsCertPath);
            applyDockerNetworkPolicy(effective);
        }
        return effective;
    }

    /**
     * 对照 clearProviderFields：移除部署基线携带的一切 provider 作用域值，
     * 使具名配置不能悄悄继承（注释见 Go 原文）。
     */
    static void clearProviderFields(EffectiveConfig cfg) {
        cfg.dockerImage = "";
        cfg.dockerHost = "";
        cfg.dockerTlsCertPath = "";
        cfg.dockerNetworkMode = "";
        cfg.dockerRuntime = "";
        cfg.dockerCpuLimit = 0;
        cfg.dockerMemoryBytes = 0;
        cfg.dockerPidsLimit = 0;
        cfg.dockerIdleTtlSec = 0;
        cfg.dockerHttpTimeoutSec = 0;
        cfg.cubeApiUrl = "";
        cfg.cubeProxyUrl = "";
        cfg.cubeSandboxDomain = "";
        cfg.cubeApiKey = "";
        cfg.cubeTemplate = "";
        cfg.cubeSandboxTtlSec = 0;
        cfg.cubeHttpTimeoutSec = 0;
        cfg.cubeDnsServers = null;

        cfg.e2bApiUrl = "";
        cfg.e2bProxyUrl = "";
        cfg.e2bSandboxDomain = "";
        cfg.e2bApiKey = "";
        cfg.e2bTemplate = "";
        cfg.e2bSandboxTtlSec = 0;
        cfg.e2bHttpTimeoutSec = 0;
        cfg.network = new RemoteNetworkPolicy();
    }

    // ── runtime_defaults.go ─────────────────────────────────────────────

    static void applyCubeRuntimeDefaults(EffectiveConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (cfg.cubeSandboxTtlSec <= 0) {
            cfg.cubeSandboxTtlSec = EffectiveConfig.DEFAULT_CUBE_SANDBOX_TTL_SEC;
        }
        if (cfg.cubeHttpTimeoutSec <= 0) {
            cfg.cubeHttpTimeoutSec = EffectiveConfig.DEFAULT_CUBE_HTTP_TIMEOUT_SEC;
        }
    }

    static void applyDockerRuntimeDefaults(EffectiveConfig cfg) {
        if (cfg == null) {
            return;
        }
        // 镜像刻意不在这里给默认：它是本后端的模板，没命名镜像的配置
        // 必须被报告为不完整，而不是悄悄指到本 release 恰好带的镜像
        if (cfg.dockerHost == null || cfg.dockerHost.isEmpty()) {
            cfg.dockerHost = DockerHostSupport.detectLocalDockerHost();
        }
        if (cfg.dockerCpuLimit <= 0) {
            cfg.dockerCpuLimit = EffectiveConfig.DEFAULT_DOCKER_CPU_LIMIT;
        }
        if (cfg.dockerMemoryBytes <= 0) {
            cfg.dockerMemoryBytes = EffectiveConfig.DEFAULT_DOCKER_MEMORY_LIMIT;
        }
        if (cfg.dockerPidsLimit <= 0) {
            cfg.dockerPidsLimit = EffectiveConfig.DEFAULT_DOCKER_PIDS_LIMIT;
        }
        if (cfg.dockerIdleTtlSec <= 0) {
            cfg.dockerIdleTtlSec = EffectiveConfig.DEFAULT_DOCKER_IDLE_TTL_SEC;
        }
        if (cfg.dockerHttpTimeoutSec <= 0) {
            cfg.dockerHttpTimeoutSec = EffectiveConfig.DEFAULT_DOCKER_HTTP_TIMEOUT_SEC;
        }
    }

    static void applyE2BRuntimeDefaults(EffectiveConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (cfg.e2bSandboxTtlSec <= 0) {
            cfg.e2bSandboxTtlSec = EffectiveConfig.DEFAULT_E2B_SANDBOX_TTL_SEC;
        }
        if (cfg.e2bHttpTimeoutSec <= 0) {
            cfg.e2bHttpTimeoutSec = EffectiveConfig.DEFAULT_E2B_HTTP_TIMEOUT_SEC;
        }
    }

    // ── override 辅助（Java 无指针出参 → setter lambda 表达 Go 的 *dst 赋值） ──

    static void overrideString(java.util.function.Consumer<String> dst, String value) {
        if (value != null && !value.isEmpty()) {
            dst.accept(value);
        }
    }

    /** 对照 overrideURL：租户自供 URL 必须先过 SSRF 守卫才进 effective config。 */
    static void overrideUrl(java.util.function.Consumer<String> dst, String value, boolean allowPrivate) {
        if (value == null || value.isEmpty()) {
            return;
        }
        OutboundUrlGuard.validateOutboundURLWithPolicy(value,
                new OutboundUrlGuard.OutboundURLPolicy(allowPrivate));
        dst.accept(value);
    }

    static void overrideSeconds(java.util.function.LongConsumer dst, int seconds) {
        if (seconds > 0) {
            dst.accept(seconds);
        }
    }

    private static EffectiveConfig copyGlobal(EffectiveConfig global) {
        EffectiveConfig c = new EffectiveConfig();
        c.type = global.type;
        c.defaultTimeoutSec = global.defaultTimeoutSec;
        c.terminalIdleDisconnectSec = global.terminalIdleDisconnectSec;
        c.allowPrivateEndpoints = global.allowPrivateEndpoints;
        c.dockerImage = global.dockerImage;
        c.dockerHost = global.dockerHost;
        c.dockerTlsCertPath = global.dockerTlsCertPath;
        c.dockerCpuLimit = global.dockerCpuLimit;
        c.dockerMemoryBytes = global.dockerMemoryBytes;
        c.dockerPidsLimit = global.dockerPidsLimit;
        c.dockerNetworkMode = global.dockerNetworkMode;
        c.dockerRuntime = global.dockerRuntime;
        c.dockerIdleTtlSec = global.dockerIdleTtlSec;
        c.dockerHttpTimeoutSec = global.dockerHttpTimeoutSec;
        c.maxMemory = global.maxMemory;
        c.maxCpu = global.maxCpu;
        c.envVars = global.envVars;
        c.network = global.network;
        c.cubeApiUrl = global.cubeApiUrl;
        c.cubeProxyUrl = global.cubeProxyUrl;
        c.cubeSandboxDomain = global.cubeSandboxDomain;
        c.cubeApiKey = global.cubeApiKey;
        c.cubeTemplate = global.cubeTemplate;
        c.cubeSandboxTtlSec = global.cubeSandboxTtlSec;
        c.cubeHttpTimeoutSec = global.cubeHttpTimeoutSec;
        c.cubeDnsServers = global.cubeDnsServers;
        c.e2bApiUrl = global.e2bApiUrl;
        c.e2bProxyUrl = global.e2bProxyUrl;
        c.e2bSandboxDomain = global.e2bSandboxDomain;
        c.e2bApiKey = global.e2bApiKey;
        c.e2bTemplate = global.e2bTemplate;
        c.e2bSandboxTtlSec = global.e2bSandboxTtlSec;
        c.e2bHttpTimeoutSec = global.e2bHttpTimeoutSec;
        return c;
    }

    /**
     * 对照 resolveNetworkPolicy：把存储的管理面策略变成 provider 面的。
     * 这是所有反转发生的唯一位置：
     *
     * <ul>
     *   <li>DenyEgressByDefault → AllowInternetAccess=false</li>
     *   <li>CubeEgressRule.Deny → RemoteCubeEgressRule.Allow</li>
     * </ul>
     *
     * <p>入站恒关闭（AllowPublicTraffic=false）。存储的 AllowPublicInbound 已在持久化边界
     * （mergeNetworkPolicyForUpdate）丢弃；这里再忽略一次，使未经该 merge 到达本函数的
     * 调用方也无法打开沙箱 URL。</p>
     *
     * <p>nil 存储策略不是"未设"：它 resolve 成 WeKnora 的默认——出网放开、入站关闭，
     * 每个下游消费者都看到一份完整指定的策略。</p>
     */
    public static RemoteNetworkPolicy resolveNetworkPolicy(SandboxNetworkPolicy stored) {
        boolean allowEgress = true;
        boolean inboundPublic = false;
        if (stored != null) {
            allowEgress = !stored.isDenyEgressByDefault();
        }
        RemoteNetworkPolicy policy = new RemoteNetworkPolicy();
        policy.allowInternetAccess = allowEgress;
        policy.allowPublicTraffic = inboundPublic;
        if (stored == null) {
            return policy;
        }
        policy.allowOut = stored.getAllowOut() == null
                ? new ArrayList<>()
                : new ArrayList<>(stored.getAllowOut());
        // 先规范化：任何 IPv4 /0（含 1.2.3.4/0）折叠到 0.0.0.0/0——E2B 以 ALL_TRAFFIC
        // 字符串匹配的写法。留非规范 /0 在线上会先过校验再在 create 时失败，
        // 或接受了域名 allow-list 却没真正拒绝其余流量
        policy.denyOut = SandboxNetworkPolicy.canonicalizeDenyOut(stored.getDenyOut());
        // DenyEgressByDefault 的文档定义就是"安装一条 0.0.0.0/0 deny-all"，
        // 所以物化成真实的 deny 条目而不是留作顶层开关的隐含义（E2B 独立校验两者并拒绝
        // allowOut 带域名而 denyOut 不带 ALL_TRAFFIC 的 create——注释见 Go 原文）。
        if (stored.isDenyEgressByDefault()
                && !SandboxNetworkPolicy.denyOutCoversAllIPv4(policy.denyOut)) {
            List<String> denyOut = policy.denyOut == null ? new ArrayList<>() : policy.denyOut;
            denyOut.add(SandboxNetworkPolicy.DENY_ALL_IPV4);
            policy.denyOut = denyOut;
        }

        if (stored.getCubeRules() != null) {
            for (SandboxNetworkPolicy.CubeEgressRule rule : stored.getCubeRules()) {
                RemoteNetworkPolicy.RemoteCubeEgressRule converted =
                        new RemoteNetworkPolicy.RemoteCubeEgressRule();
                converted.name = rule.getName();
                converted.scheme = rule.getScheme();
                converted.sni = rule.getSni();
                converted.host = rule.getHost();
                converted.methods = rule.getMethods() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(rule.getMethods());
                converted.path = rule.getPath();
                converted.allow = !rule.isDeny();
                converted.audit = rule.getAudit();
                if (rule.getInject() != null) {
                    converted.inject = new ArrayList<>();
                    for (SandboxNetworkPolicy.CubeHeaderInject inject : rule.getInject()) {
                        RemoteNetworkPolicy.RemoteHeaderInject remote =
                                new RemoteNetworkPolicy.RemoteHeaderInject();
                        remote.header = inject.getHeader();
                        remote.secret = inject.getSecret();
                        remote.format = inject.getFormat();
                        converted.inject.add(remote);
                    }
                }
                if (policy.cubeRules == null) {
                    policy.cubeRules = new ArrayList<>();
                }
                policy.cubeRules.add(converted);
            }
        }
        if (stored.getE2bHostRules() != null) {
            for (SandboxNetworkPolicy.E2BHostRule rule : stored.getE2bHostRules()) {
                RemoteNetworkPolicy.RemoteE2BHostRule converted =
                        new RemoteNetworkPolicy.RemoteE2BHostRule();
                converted.host = rule.getHost();
                if (rule.getHeaders() != null && !rule.getHeaders().isEmpty()) {
                    converted.headers = rule.getHeaders().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                    (a, b) -> a, java.util.LinkedHashMap::new));
                }
                if (policy.e2bHostRules == null) {
                    policy.e2bHostRules = new ArrayList<>();
                }
                policy.e2bHostRules.add(converted);
            }
        }
        return policy;
    }

    /**
     * 对照 applyDockerNetworkPolicy：把 docker.network_mode=none 映射到 resolve 后的
     * 出网开关，使 DeniesEgressByDefault（与深度连通性检查）与适配器实际创建的网络一致。
     * 存储的 SandboxNetworkPolicy 在 Docker 配置上保持为空；这是 Docker 表单的总开关
     * 在协议栈其余部分已经在读的同一字段上的表述。
     */
    static void applyDockerNetworkPolicy(EffectiveConfig cfg) {
        if (cfg == null) {
            return;
        }
        String mode = cfg.dockerNetworkMode == null ? "" : cfg.dockerNetworkMode.trim();
        if (!"none".equalsIgnoreCase(mode)) {
            return;
        }
        if (cfg.network == null) {
            cfg.network = new RemoteNetworkPolicy();
        }
        cfg.network.allowInternetAccess = false;
    }

    /** 对照 remote_client.go 的 cloneMetadata：可变浅拷贝。 */
    static Map<String, String> cloneMetadata(Map<String, String> source) {
        return source.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
