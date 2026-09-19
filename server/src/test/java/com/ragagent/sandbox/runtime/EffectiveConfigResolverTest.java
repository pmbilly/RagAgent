package com.ragagent.sandbox.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.ragagent.sandbox.domain.CubeSandboxConfig;
import com.ragagent.sandbox.domain.DockerSandboxConfig;
import com.ragagent.sandbox.domain.E2BSandboxConfig;
import com.ragagent.sandbox.domain.SandboxNetworkPolicy;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 波 3 子批 1 校验层单测：ResolveEffectiveConfig 缺失字段文案逐字、URL 守卫各分支、
 * Docker 后端开关、Cube DNS 归一化、网络策略 resolve 反转。全部纯内存，无网络
 * （字面 IP / unix socket，主机名只碰 localhost）。
 */
class EffectiveConfigResolverTest {

    @AfterEach
    void resetDockerOverride() {
        // 覆盖值是进程级静态（对照 Go 包级 atomic），用完必须还原
        SandboxBackendPolicy.clearDockerBackendEnabledOverride();
    }

    // ── ParseSandboxType / IsNamedSandboxBackendType ────────────────────

    @Test
    void parseSandboxTypeAcceptsKnownValues() {
        assertEquals("cube", SandboxTypes.parseSandboxType("cube"));
        assertEquals("e2b", SandboxTypes.parseSandboxType("e2b"));
        assertEquals("docker", SandboxTypes.parseSandboxType("docker"));
        assertEquals("disabled", SandboxTypes.parseSandboxType("disabled"));
        UnsupportedSandboxTypeException e = assertThrows(UnsupportedSandboxTypeException.class,
                () -> SandboxTypes.parseSandboxType("kubernetes"));
        assertEquals("sandbox: unsupported sandbox type \"kubernetes\"", e.getMessage());
    }

    @Test
    void namedBackendTypeCheck() {
        assertTrue(SandboxTypes.isNamedSandboxBackendType("cube"));
        assertTrue(SandboxTypes.isNamedSandboxBackendType("e2b"));
        assertTrue(SandboxTypes.isNamedSandboxBackendType("docker"));
        assertFalse(SandboxTypes.isNamedSandboxBackendType("disabled"));
        assertFalse(SandboxTypes.isNamedSandboxBackendType(""));
        assertFalse(SandboxTypes.isNamedSandboxBackendType("CUBE"));
    }

    // ── Docker 后端开关（默认 disabled） ─────────────────────────────────

    @Test
    void dockerBackendDisabledByDefaultAndOverridable() {
        // 默认（env 未设）：docker 被拒，其余放行
        assertThrows(DockerBackendDisabledException.class,
                () -> SandboxBackendPolicy.ensureDockerBackendAllowed("docker"));
        SandboxBackendPolicy.ensureDockerBackendAllowed("cube");

        // 三层解析的顶层覆盖（对照 SetDockerBackendEnabled；env 层无注入缝）
        SandboxBackendPolicy.setDockerBackendEnabled(true);
        SandboxBackendPolicy.ensureDockerBackendAllowed("docker");
        SandboxBackendPolicy.clearDockerBackendEnabledOverride();
        assertThrows(DockerBackendDisabledException.class,
                () -> SandboxBackendPolicy.ensureDockerBackendAllowed("docker"));

        SandboxBackendPolicy.setDockerBackendEnabled(false);
        assertThrows(DockerBackendDisabledException.class,
                () -> SandboxBackendPolicy.ensureDockerBackendAllowed("docker"));
        assertEquals("sandbox: docker backend is disabled; enable it in System Settings "
                        + "or set WEKNORA_SANDBOX_DOCKER_ENABLED=true",
                assertThrows(DockerBackendDisabledException.class,
                        () -> SandboxBackendPolicy.ensureDockerBackendAllowed("docker")).getMessage());
    }

    // ── RequireCompleteConfig：缺失字段文案逐字（Go config_required.go） ───

    @Test
    void cubeConfigMissingFieldsMessage() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("cube");
        cfg.setCube(new CubeSandboxConfig());
        SandboxConfigIncompleteException e = assertThrows(SandboxConfigIncompleteException.class,
                () -> EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig()));
        assertEquals("sandbox: config is missing required fields: "
                + "cube backend requires api_url, proxy_url, sandbox_domain, template_id",
                e.getMessage());
    }

    @Test
    void e2bConfigMissingFieldsMessage() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("e2b");
        cfg.setE2b(new E2BSandboxConfig());
        // e2b 的 api_url / sandbox_domain 由 SDK 自行解析 → 不在必填清单
        SandboxConfigIncompleteException e = assertThrows(SandboxConfigIncompleteException.class,
                () -> EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig()));
        assertEquals("sandbox: config is missing required fields: "
                + "e2b backend requires api_key, template_id",
                e.getMessage());
    }

    @Test
    void dockerConfigMissingImageMessage() {
        SandboxBackendPolicy.setDockerBackendEnabled(true);
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("docker");
        cfg.setDocker(new DockerSandboxConfig());
        cfg.getDocker().setHost("unix:///var/run/docker.sock");
        SandboxConfigIncompleteException e = assertThrows(SandboxConfigIncompleteException.class,
                () -> EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig()));
        assertEquals("sandbox: config is missing required fields: docker backend requires image",
                e.getMessage());
    }

    // ── resolve 成功路径 + 运行时默认 ───────────────────────────────────

    @Test
    void cubeResolveAppliesRuntimeDefaultsAndDns() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("cube");
        cfg.setAllowPrivateEndpoints(true);
        cfg.setDefaultTimeoutSec(120);
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiUrl("http://127.0.0.1:33000");
        cube.setProxyUrl("http://127.0.0.1:80");
        cube.setSandboxDomain("cube.app");
        cube.setTemplateId("tpl-1");
        cfg.setCube(cube);

        EffectiveConfig effective =
                EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig());
        assertEquals("cube", effective.type);
        assertEquals(120, effective.defaultTimeoutSec);
        // 运行时默认（TTL/HTTP 超时未设 → 内建）
        assertEquals(EffectiveConfig.DEFAULT_CUBE_SANDBOX_TTL_SEC, effective.cubeSandboxTtlSec);
        assertEquals(EffectiveConfig.DEFAULT_CUBE_HTTP_TIMEOUT_SEC, effective.cubeHttpTimeoutSec);
        // 部署基线的 provider 字段被清除后未继承（DefaultConfig 本来就为空，此处验形状）
        assertEquals("http://127.0.0.1:33000", effective.cubeApiUrl);
        // 网络默认：出网放开、入站关闭
        assertEquals(Boolean.TRUE, effective.network.allowInternetAccess);
        assertEquals(Boolean.FALSE, effective.network.allowPublicTraffic);
        // 终端 idle：未设 → 内建 15 分钟（对照 EffectiveTerminalIdleDisconnect）
        assertEquals(EffectiveConfig.DEFAULT_TERMINAL_IDLE_DISCONNECT_SEC,
                effective.terminalIdleDisconnectSec);

        // DNS 归一化：trim/去重/规范序
        cube.setDnsServers(List.of(" 8.8.8.8 ", "8.8.4.4", "8.8.8.8"));
        effective = EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig());
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), effective.cubeDnsServers);

        // 非法 DNS 文案
        cube.setDnsServers(List.of("dns.example.com"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig()));
        assertEquals("sandbox: invalid cube DNS server \"dns.example.com\" (need an IP address)",
                e.getMessage());
    }

    @Test
    void dockerResolveWithNoneNetworkClosesEgress() {
        SandboxBackendPolicy.setDockerBackendEnabled(true);
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("docker");
        cfg.setAllowPrivateEndpoints(true);
        DockerSandboxConfig docker = new DockerSandboxConfig();
        docker.setImage("wechatopenai/weknora-sandbox:main");
        docker.setHost("unix:///var/run/docker.sock");
        docker.setNetworkMode("none");
        cfg.setDocker(docker);

        EffectiveConfig effective =
                EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig());
        assertEquals("docker", effective.type);
        assertEquals(Boolean.FALSE, effective.network.allowInternetAccess,
                "network_mode=none 映射到 resolve 后的出网开关");
        assertEquals(EffectiveConfig.DEFAULT_DOCKER_CPU_LIMIT, effective.dockerCpuLimit, 1e-9);
        assertEquals(EffectiveConfig.DEFAULT_DOCKER_MEMORY_LIMIT, effective.dockerMemoryBytes);

        // 非法 network_mode 文案
        docker.setNetworkMode("host");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig()));
        assertEquals("sandbox: docker network mode \"host\" is not allowed; use \"bridge\" or \"none\"",
                e.getMessage());
    }

    @Test
    void dockerTcpHostRequiresTlsCertDir() {
        SandboxBackendPolicy.setDockerBackendEnabled(true);
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("docker");
        cfg.setAllowPrivateEndpoints(true);
        DockerSandboxConfig docker = new DockerSandboxConfig();
        docker.setImage("img");
        docker.setHost("tcp://10.1.2.3:2376");
        cfg.setDocker(docker);
        // 无 TLS 目录 → 文案逐字
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig()));
        assertEquals("sandbox: remote docker host \"tcp://10.1.2.3:2376\" requires a TLS certificate directory",
                e.getMessage());

        docker.setTlsCertPath("/etc/docker/certs");
        cfg.setDocker(docker);
        EffectiveConfig effective =
                EffectiveConfigResolver.resolveEffectiveConfig(cfg, EffectiveConfig.defaultConfig());
        assertEquals("tcp://10.1.2.3:2376", effective.dockerHost);
    }

    // ── URL 守卫（ErrUnsafeOutboundURL 各分支逐字） ──────────────────────

    @Test
    void outboundUrlGuardBranches() {
        // 空 URL
        UnsafeOutboundURLException e = assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("   ",
                        new OutboundUrlGuard.OutboundURLPolicy(false)));
        assertEquals("sandbox: unsafe outbound URL: empty URL", e.getMessage());

        // scheme
        e = assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("ftp://example.com",
                        new OutboundUrlGuard.OutboundURLPolicy(false)));
        assertEquals("sandbox: unsafe outbound URL: scheme \"ftp\" is not allowed", e.getMessage());

        // host 缺失（Go 的 url.Parse 能解析空 authority；Java URI 抛错 → 回退同文案）
        e = assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("http://",
                        new OutboundUrlGuard.OutboundURLPolicy(false)));
        assertEquals("sandbox: unsafe outbound URL: missing host", e.getMessage());

        // mDNS
        e = assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("http://box.local/x",
                        new OutboundUrlGuard.OutboundURLPolicy(true)));
        assertEquals("sandbox: unsafe outbound URL: host \"box.local\" is mDNS-local", e.getMessage());

        // localhost：默认拒绝 + opt-in 放行（保留 link-local 拒绝）
        assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("http://localhost:33000",
                        new OutboundUrlGuard.OutboundURLPolicy(false)));
        OutboundUrlGuard.validateOutboundURLWithPolicy("http://localhost:33000",
                new OutboundUrlGuard.OutboundURLPolicy(true));

        // 私有地址：opt-in 放行、默认拒绝（文案逐字）
        e = assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("http://127.0.0.1:33000",
                        new OutboundUrlGuard.OutboundURLPolicy(false)));
        assertEquals("sandbox: unsafe outbound URL: address 127.0.0.1 is private; "
                + "enable private endpoints for this workspace config", e.getMessage());
        OutboundUrlGuard.validateOutboundURLWithPolicy("http://10.0.0.5/x",
                new OutboundUrlGuard.OutboundURLPolicy(true));

        // link-local / 云元数据：opt-in 下也拒绝
        e = assertThrows(UnsafeOutboundURLException.class,
                () -> OutboundUrlGuard.validateOutboundURLWithPolicy("http://169.254.169.254/latest",
                        new OutboundUrlGuard.OutboundURLPolicy(true)));
        assertEquals("sandbox: unsafe outbound URL: "
                + "address 169.254.169.254 is never routable to a sandbox (link-local address)",
                e.getMessage());

        // 文档段（TEST-NET）：拒绝它保护不了任何东西，作为免 DNS 的公网替身放行
        OutboundUrlGuard.validateOutboundURLWithPolicy("http://192.0.2.1/x",
                new OutboundUrlGuard.OutboundURLPolicy(false));

        // 保留差异说明：前导零八进制串（"010.0.0.1"）Go ParseIP 拒绝后按主机名走 DNS，
        // 结果随环境 DNS 而变（本机 fake-ip 会解析成功）——非稳定契约，不在此断言。
    }

    // ── resolveNetworkPolicy：反转与 deny-all 物化 ──────────────────────

    @Test
    void networkPolicyInversionsAndMaterialization() {
        // nil 存储策略 → 出网放开、入站关闭；AllowOut/DenyOut 保持 nil（Go 的 nil 切片）
        RemoteNetworkPolicy def = EffectiveConfigResolver.resolveNetworkPolicy(null);
        assertEquals(Boolean.TRUE, def.allowInternetAccess);
        assertEquals(Boolean.FALSE, def.allowPublicTraffic);
        assertNull(def.allowOut);
        assertNull(def.denyOut);

        // 非 nil 存储策略：AllowOut 走 append(nil,...) → 恒非 nil（空也是空切片）；
        // DenyOut 走 CanonicalizeDenyOut(nil) → 保持 nil（Go 的不对称，照抄）
        SandboxNetworkPolicy emptyStored = new SandboxNetworkPolicy();
        RemoteNetworkPolicy empty = EffectiveConfigResolver.resolveNetworkPolicy(emptyStored);
        assertTrue(empty.allowOut != null && empty.allowOut.isEmpty());
        assertNull(empty.denyOut);

        // DenyEgressByDefault 物化为真实 0.0.0.0/0 条目
        SandboxNetworkPolicy stored = new SandboxNetworkPolicy();
        stored.setDenyEgressByDefault(true);
        stored.setAllowOut(List.of("*.example.com"));
        RemoteNetworkPolicy p = EffectiveConfigResolver.resolveNetworkPolicy(stored);
        assertEquals(Boolean.FALSE, p.allowInternetAccess);
        assertEquals(List.of("0.0.0.0/0"), p.denyOut);
        assertTrue(p.deniesEgressByDefault());

        // 非规范 /0 折叠；已有 deny-all 不重复追加
        stored.setDenyOut(List.of("1.2.3.4/0"));
        p = EffectiveConfigResolver.resolveNetworkPolicy(stored);
        assertEquals(List.of("0.0.0.0/0"), p.denyOut);

        // Cube deny → allow 字段反转（Deny=true → Allow=false；反转只在这里发生一次）
        SandboxNetworkPolicy.CubeEgressRule rule = new SandboxNetworkPolicy.CubeEgressRule();
        rule.setName("r");
        rule.setDeny(true);
        rule.setMethods(List.of("GET"));
        stored.setDenyEgressByDefault(false);
        stored.setCubeRules(List.of(rule));
        p = EffectiveConfigResolver.resolveNetworkPolicy(stored);
        assertFalse(p.cubeRules.get(0).allow, "存储的拒绝规则 → 远程 Allow=false");
        assertEquals("r", p.cubeRules.get(0).name);
        assertEquals(List.of("GET"), p.cubeRules.get(0).methods);

        // 非拒绝规则 → Allow=true
        rule.setDeny(false);
        p = EffectiveConfigResolver.resolveNetworkPolicy(stored);
        assertTrue(p.cubeRules.get(0).allow);
    }

    // ── 身份投影 ────────────────────────────────────────────────────────

    @Test
    void identityComparisonPerProvider() {
        TenantSandboxConfig cubeOld = new TenantSandboxConfig();
        cubeOld.setSandboxType("cube");
        CubeSandboxConfig oldCube = new CubeSandboxConfig();
        oldCube.setApiKey("k1");
        oldCube.setTemplateId("will-not-count");
        cubeOld.setCube(oldCube);

        TenantSandboxConfig cubeNew = new TenantSandboxConfig();
        cubeNew.setSandboxType("cube");
        CubeSandboxConfig newCube = new CubeSandboxConfig();
        newCube.setApiKey("k1");
        newCube.setTemplateId("changed-template-does-not-count");
        cubeNew.setCube(newCube);
        assertTrue(SandboxIdentity.identityOf(cubeOld).equals(SandboxIdentity.identityOf(cubeNew)),
                "API key 不变、模板变化不算身份变更（身份不含模板）");

        newCube.setApiKey("k2");
        assertTrue(!SandboxIdentity.identityOf(cubeOld).equals(SandboxIdentity.identityOf(cubeNew)),
                "API key 变更 = 身份变更");

        // provider 切换 = 身份变更；nil → 恒等（什么都不算搁浅）
        cubeNew.setSandboxType("e2b");
        assertTrue(!SandboxIdentity.identityOf(cubeOld).equals(SandboxIdentity.identityOf(cubeNew)));
        assertEquals(SandboxIdentity.identityOf(null), SandboxIdentity.identityOf(null));

        // Docker：daemon endpoint 是身份；镜像不是
        TenantSandboxConfig dockerA = new TenantSandboxConfig();
        dockerA.setSandboxType("docker");
        DockerSandboxConfig da = new DockerSandboxConfig();
        da.setHost("unix:///a.sock");
        da.setImage("img-1");
        dockerA.setDocker(da);
        TenantSandboxConfig dockerB = new TenantSandboxConfig();
        dockerB.setSandboxType("docker");
        DockerSandboxConfig db = new DockerSandboxConfig();
        db.setHost("unix:///a.sock");
        db.setImage("img-2");
        dockerB.setDocker(db);
        assertEquals(SandboxIdentity.identityOf(dockerA), SandboxIdentity.identityOf(dockerB),
                "改镜像只影响之后创建的沙箱，不是身份");
        db.setHost("unix:///b.sock");
        assertTrue(!SandboxIdentity.identityOf(dockerA).equals(SandboxIdentity.identityOf(dockerB)),
                "改 daemon endpoint 搁浅所有沙箱");
    }

    // ── Docker host 校验 ────────────────────────────────────────────────

    @Test
    void dockerHostValidation() {
        DockerHostSupport.validateDockerHost("", false);
        DockerHostSupport.validateDockerHost("unix:///var/run/docker.sock", false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DockerHostSupport.validateDockerHost("var/run/docker.sock", false));
        assertEquals("sandbox: docker host \"var/run/docker.sock\" must include a scheme (unix:// or tcp://)",
                e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> DockerHostSupport.validateDockerHost("unix://relative/path", false));
        assertEquals("sandbox: docker unix socket path \"relative/path\" must be absolute", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> DockerHostSupport.validateDockerHost("npipe:///x", false));
        assertEquals("sandbox: unsupported docker host scheme \"npipe\"", e.getMessage());
    }

    // ── 指纹 ────────────────────────────────────────────────────────────

    @Test
    void skillImageFingerprintIsSha256OfJoinedInputs() {
        // sha256("cube\nkey1\nhttp://a") 的已知值（shasum 实录）
        assertEquals("7d0b36ab48970e531fbc45cec2d00f75cf0017cc036cb72c9887745b380657a9",
                SkillImageSupport.skillImageFingerprint(" cube ", " key1 ", "http://a "));
        assertEquals("7d0b36ab48970e531fbc45cec2d00f75cf0017cc036cb72c9887745b380657a9",
                SkillImageSupport.skillImageFingerprint("cube", "key1", "http://a"),
                "trim 后等价");

        // skillImageTemplateOverride：指纹不匹配 → 保留基础模板
        com.ragagent.sandbox.domain.SkillImageConfig image =
                new com.ragagent.sandbox.domain.SkillImageConfig();
        image.setSnapshotId("snap");
        image.setOwnerFingerprint(SkillImageSupport.skillImageFingerprint("cube", "key1", "http://a"));
        assertEquals("snap", SkillImageSupport.skillImageTemplateOverride(
                image, "cube", "key1", "http://a"));
        assertEquals("", SkillImageSupport.skillImageTemplateOverride(
                image, "cube", "key2", "http://a"));
        assertEquals("", SkillImageSupport.skillImageTemplateOverride(null, "cube", "key1", "http://a"));
        image.setOwnerFingerprint("");
        assertEquals("", SkillImageSupport.skillImageTemplateOverride(
                image, "cube", "key1", "http://a"), "无指纹 → 保留基础模板");
    }
}
