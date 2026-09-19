package com.ragagent.sandbox.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.crypto.CryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 波 3 子批 1 领域层单测：打码 / 合并 / 加解密往返 / 网络策略校验文案逐字断言。
 * 对照 Go 的 types 层行为（config_redaction.go / sandbox_network_policy.go / tenant.go 的
 * Value/Scan）。测试不连真实网络（Mockito 伪造 JDBC，不落库）。
 */
class SandboxConfigDomainTest {

    // ── 打码（SandboxConfigForResponse） ────────────────────────────────

    @Test
    void maskSecretsReplacesSetSecretsOnly() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiKey("live-key");
        cube.setApiUrl("http://127.0.0.1:33000");
        cfg.setCube(cube);
        E2BSandboxConfig e2b = new E2BSandboxConfig();
        e2b.setApiKey("");  // 未配置保持空串——UI 由此区分已配置/未配置
        cfg.setE2b(e2b);
        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("EMPTY", "");
        envVars.put("TOKEN", "t");
        cfg.setEnvVars(envVars);
        SandboxNetworkPolicy policy = new SandboxNetworkPolicy();
        SandboxNetworkPolicy.CubeEgressRule rule = new SandboxNetworkPolicy.CubeEgressRule();
        rule.setName("r");
        SandboxNetworkPolicy.CubeHeaderInject inject = new SandboxNetworkPolicy.CubeHeaderInject();
        inject.setHeader("X-T");
        inject.setSecret("s");
        rule.setInject(List.of(inject));
        policy.setCubeRules(List.of(rule));
        cfg.setNetwork(policy);

        TenantSandboxConfig masked = SandboxConfigRedaction.sandboxConfigForResponse(cfg, true);
        assertEquals(SandboxConstants.REDACTED_SECRET_PLACEHOLDER, masked.getCube().getApiKey());
        assertEquals("", masked.getE2b().getApiKey());
        assertEquals("", masked.getEnvVars().get("EMPTY"));
        assertEquals(SandboxConstants.REDACTED_SECRET_PLACEHOLDER, masked.getEnvVars().get("TOKEN"));
        assertEquals(SandboxConstants.REDACTED_SECRET_PLACEHOLDER,
                masked.getNetwork().getCubeRules().get(0).getInject().get(0).getSecret());
        // 原 cfg 不被修改（Go：接收者从不被改动）
        assertEquals("live-key", cfg.getCube().getApiKey());
        assertEquals("t", cfg.getEnvVars().get("TOKEN"));

        TenantSandboxConfig raw = SandboxConfigRedaction.sandboxConfigForResponse(cfg, false);
        assertEquals("live-key", raw.getCube().getApiKey());
    }

    @Test
    void maskNullConfigStaysNull() {
        assertNull(SandboxConfigRedaction.sandboxConfigForResponse(null, true));
        assertNull(SandboxConfigRedaction.mergeSandboxConfigForUpdate(null, null));
    }

    // ── 合并（MergeSandboxConfigForUpdate） ─────────────────────────────

    @Test
    void mergePreservesRedactedSecrets() {
        TenantSandboxConfig existing = new TenantSandboxConfig();
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiKey("stored");
        existing.setCube(cube);
        existing.setEnvVars(Map.of("A", "old-a", "B", "old-b"));

        TenantSandboxConfig incoming = new TenantSandboxConfig();
        CubeSandboxConfig inCube = new CubeSandboxConfig();
        inCube.setApiKey(SandboxConstants.REDACTED_SECRET_PLACEHOLDER);
        incoming.setCube(inCube);
        // 删除一行必须真的删除：incoming 没有的键不存活
        incoming.setEnvVars(new LinkedHashMap<>(Map.of("A", SandboxConstants.REDACTED_SECRET_PLACEHOLDER,
                "B", "new-b")));

        TenantSandboxConfig merged = SandboxConfigRedaction.mergeSandboxConfigForUpdate(incoming, existing);
        assertEquals("stored", merged.getCube().getApiKey());
        assertEquals("old-a", merged.getEnvVars().get("A"));
        assertEquals("new-b", merged.getEnvVars().get("B"));
        assertFalse(merged.getEnvVars().containsKey("C"));
    }

    @Test
    void mergeKeepsSkillImageVolumeMountAndRollout() {
        TenantSandboxConfig existing = new TenantSandboxConfig();
        SkillImageConfig image = new SkillImageConfig();
        image.setSnapshotId("snap");
        existing.setSkillImage(image);
        VolumeMountConfig mount = new VolumeMountConfig();
        mount.setVolumeId("vol-1");
        existing.setVolumeMount(mount);
        existing.setSkillRollout(SandboxConstants.SKILL_ROLLOUT_NEW_SESSION);

        // 编辑器重建载荷时两字段都没有；skill_rollout 省略（运行时表单）
        TenantSandboxConfig incoming = new TenantSandboxConfig();
        incoming.setSkillRollout("");

        TenantSandboxConfig merged = SandboxConfigRedaction.mergeSandboxConfigForUpdate(incoming, existing);
        assertEquals("snap", merged.getSkillImage().getSnapshotId());
        assertEquals("vol-1", merged.getVolumeMount().getVolumeId());
        assertEquals(SandboxConstants.SKILL_ROLLOUT_NEW_SESSION, merged.getSkillRollout());

        // 无存储行：快照被清、rollout 保持空
        TenantSandboxConfig fresh = SandboxConfigRedaction.mergeSandboxConfigForUpdate(
                new TenantSandboxConfig(), null);
        assertNull(fresh.getSkillImage());
        assertNull(fresh.getVolumeMount());
        assertEquals("", fresh.getSkillRollout());
    }

    @Test
    void mergeNetworkPolicyMatchesByIdentityAndClearsInbound() {
        TenantSandboxConfig existing = new TenantSandboxConfig();
        SandboxNetworkPolicy oldPolicy = new SandboxNetworkPolicy();
        oldPolicy.setAllowPublicInbound(true);
        SandboxNetworkPolicy.CubeEgressRule oldRule = new SandboxNetworkPolicy.CubeEgressRule();
        oldRule.setName(" Vendor API ");  // trim+折叠后与 incoming 同身份
        SandboxNetworkPolicy.CubeHeaderInject oldInject = new SandboxNetworkPolicy.CubeHeaderInject();
        oldInject.setHeader("x-token");
        oldInject.setSecret("stored-secret");
        oldRule.setInject(List.of(oldInject));
        oldPolicy.setCubeRules(List.of(oldRule));
        existing.setNetwork(oldPolicy);

        TenantSandboxConfig incoming = new TenantSandboxConfig();
        SandboxNetworkPolicy newPolicy = new SandboxNetworkPolicy();
        newPolicy.setAllowPublicInbound(true); // 必须在保存边界被清除
        SandboxNetworkPolicy.CubeEgressRule newRule = new SandboxNetworkPolicy.CubeEgressRule();
        newRule.setName("vendor api");
        SandboxNetworkPolicy.CubeHeaderInject newInject = new SandboxNetworkPolicy.CubeHeaderInject();
        newInject.setHeader("X-Token");
        newInject.setSecret(SandboxConstants.REDACTED_SECRET_PLACEHOLDER);
        newRule.setInject(List.of(newInject));
        newPolicy.setCubeRules(List.of(newRule));
        incoming.setNetwork(newPolicy);

        TenantSandboxConfig merged = SandboxConfigRedaction.mergeSandboxConfigForUpdate(incoming, existing);
        SandboxNetworkPolicy net = merged.getNetwork();
        assertFalse(net.isAllowPublicInbound(), "allow_public_inbound 必须在保存时清除");
        assertEquals("stored-secret", net.getCubeRules().get(0).getInject().get(0).getSecret());

        // 无存储行：占位符无法解析 → 保持占位符（由校验/加密层处理）
        TenantSandboxConfig orphan = SandboxConfigRedaction.mergeSandboxConfigForUpdate(incoming, null);
        assertEquals(SandboxConstants.REDACTED_SECRET_PLACEHOLDER,
                orphan.getNetwork().getCubeRules().get(0).getInject().get(0).getSecret());
    }

    // ── 网络策略校验（文案逐字） ────────────────────────────────────────

    @Test
    void validateRejectsDockerRuleLists() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("docker");
        SandboxNetworkPolicy p = new SandboxNetworkPolicy();
        p.setAllowOut(List.of("10.0.0.0/8"));
        cfg.setNetwork(p);
        assertEquals("docker 后端只能整体开关出网，无法按 IP、域名或 HTTP 规则放行；"
                        + "请清空放行/拒绝列表，或改用 cube / e2b 后端",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));
    }

    @Test
    void validateRequiresDenyAllForDomainAllows() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("cube");
        SandboxNetworkPolicy p = new SandboxNetworkPolicy();
        p.setAllowOut(List.of("*.example.com"));
        cfg.setNetwork(p);
        assertEquals("allow_out 里有域名时必须同时兜底拒绝其余流量："
                + "启用「默认拒绝」，或在 deny_out 中加入 0.0.0.0/0。"
                + "否则未经 DNS 学习的目的 IP 仍会默认放行，白名单形同虚设",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));

        // deny-all 兜底后通过
        p.setDenyEgressByDefault(true);
        assertNull(SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));
    }

    @Test
    void validateRejectsDomainDenyAndDuplicates() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("e2b");
        SandboxNetworkPolicy p = new SandboxNetworkPolicy();
        p.setDenyOut(List.of("example.com"));
        cfg.setNetwork(p);
        assertEquals("deny_out 不支持域名（\"example.com\"）：拒绝判定只按目的 IP 匹配。"
                        + "如需只放行少数域名，请启用「默认拒绝」并把它们写进 allow_out",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));

        p.setDenyOut(List.of("10.0.0.1", "10.0.0.1/32"));
        assertEquals("deny_out 中 \"10.0.0.1\" 与 \"10.0.0.1/32\" 是同一个目标，请只保留一条",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));

        p.setDenyOut(List.of("1.2.3.4"));
        p.setAllowOut(List.of("*.a.com", "*.a.com"));
        assertEquals("allow_out 中 \"*.a.com\" 与 \"*.a.com\" 是同一个目标，请只保留一条",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));
    }

    @Test
    void validateCubeRuleMessages() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("cube");
        SandboxNetworkPolicy p = new SandboxNetworkPolicy();
        cfg.setNetwork(p);

        SandboxNetworkPolicy.CubeEgressRule noName = new SandboxNetworkPolicy.CubeEgressRule();
        p.setCubeRules(List.of(noName));
        assertEquals("每条 Cube HTTP 规则都需要 name，用于审计与模板合并",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));

        SandboxNetworkPolicy.CubeEgressRule noTarget = new SandboxNetworkPolicy.CubeEgressRule();
        noTarget.setName("r");
        noTarget.setMethods(List.of("GET"));
        noTarget.setPath("/x");
        p.setCubeRules(List.of(noTarget));
        assertEquals("cube HTTP 规则 \"r\" 必须填 host 或 sni："
                        + "网络层只从这两个字段提取放行目标，只写 method / path 的规则永远到不了 CubeEgress",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));

        SandboxNetworkPolicy.CubeEgressRule ipSni = new SandboxNetworkPolicy.CubeEgressRule();
        ipSni.setName("r");
        ipSni.setSni("1.2.3.4");
        p.setCubeRules(List.of(ipSni));
        assertEquals("cube HTTP 规则 \"r\" 的 sni 只能是域名，不能是 IP",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));
    }

    @Test
    void validateE2BHostRuleRequiresAllowOutEntry() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("e2b");
        SandboxNetworkPolicy p = new SandboxNetworkPolicy();
        p.setDenyEgressByDefault(true);
        SandboxNetworkPolicy.E2BHostRule rule = new SandboxNetworkPolicy.E2BHostRule();
        rule.setHost("api.example.com");
        rule.setHeaders(Map.of("Authorization", "Bearer x"));
        p.setE2bHostRules(List.of(rule));
        cfg.setNetwork(p);
        assertEquals("e2b host 规则的 host \"api.example.com\" 必须同时出现在 allow_out 中："
                        + "规则本身只做 header 注入，不授权出网",
                SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));

        p.setAllowOut(List.of("api.example.com"));
        assertNull(SandboxNetworkPolicy.validateSandboxNetworkPolicy(cfg));
    }

    // ── DenyOutCoversAllIPv4 / CanonicalizeDenyOut ──────────────────────

    @Test
    void denyOutCanonicalization() {
        assertTrue(SandboxNetworkPolicy.denyOutCoversAllIPv4(List.of("0.0.0.0/0")));
        assertTrue(SandboxNetworkPolicy.denyOutCoversAllIPv4(List.of("1.2.3.4/0")));
        assertFalse(SandboxNetworkPolicy.denyOutCoversAllIPv4(List.of("10.0.0.0/8")));
        assertFalse(SandboxNetworkPolicy.denyOutCoversAllIPv4(null));

        // 规范化折叠：任何 /0 → 0.0.0.0/0；其余原样；null 保持 null
        assertEquals(List.of("0.0.0.0/0", "10.0.0.0/8"),
                SandboxNetworkPolicy.canonicalizeDenyOut(List.of("1.2.3.4/0", "10.0.0.0/8")));
        assertNull(SandboxNetworkPolicy.canonicalizeDenyOut(null));
    }

    // ── TypeHandler：字段级 AES-GCM 加解密（Value/Scan 语义） ─────────────

    /** 固定 32 字节 key 的 CryptoService 替身（env 无法在进程内注入）。 */
    private static class FixedKeyCrypto extends CryptoService {
        private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        @Override
        public byte[] getAESKey() {
            return KEY;
        }
    }

    @Test
    void typeHandlerEncryptsOnWriteAndDecryptsOnRead() throws SQLException {
        TenantSandboxConfigTypeHandler handler = new TenantSandboxConfigTypeHandler(new FixedKeyCrypto());

        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("cube");
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiKey("plain-key");
        cfg.setCube(cube);
        cfg.setEnvVars(new LinkedHashMap<>(Map.of("TOKEN", "plain-env")));
        SandboxNetworkPolicy policy = new SandboxNetworkPolicy();
        SandboxNetworkPolicy.CubeEgressRule rule = new SandboxNetworkPolicy.CubeEgressRule();
        rule.setName("r");
        SandboxNetworkPolicy.CubeHeaderInject inject = new SandboxNetworkPolicy.CubeHeaderInject();
        inject.setHeader("X-T");
        inject.setSecret("plain-header");
        rule.setInject(List.of(inject));
        policy.setCubeRules(List.of(rule));
        cfg.setNetwork(policy);

        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, cfg, null);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ps).setObject(Mockito.eq(1), json.capture(), Mockito.eq(java.sql.Types.OTHER));
        // 落库 JSON 里不出现明文；出现 enc:v1: 前缀
        String stored = json.getValue();
        assertFalse(stored.contains("plain-key"));
        assertFalse(stored.contains("plain-env"));
        assertFalse(stored.contains("plain-header"));
        assertTrue(stored.contains(CryptoService.ENC_PREFIX));

        // 读回：同一 key 下解密还原
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("config")).thenReturn(stored);
        TenantSandboxConfig back = handler.getNullableResult(rs, "config");
        assertEquals("plain-key", back.getCube().getApiKey());
        assertEquals("plain-env", back.getEnvVars().get("TOKEN"));
        assertEquals("plain-header", back.getNetwork().getCubeRules().get(0).getInject().get(0).getSecret());
    }

    @Test
    void typeHandlerLenientDecryptWithoutKeyOrWithGarbage() throws SQLException {
        // 无 key（对照 SYSTEM_AES_KEY 缺失/轮换）：宽容解密 → 置空，不抛
        TenantSandboxConfigTypeHandler noKeyHandler =
                new TenantSandboxConfigTypeHandler(new CryptoService());
        String stored = "{\"sandbox_type\":\"cube\",\"cube\":{\"api_key\":\"" + CryptoService.ENC_PREFIX
                + "AAAA\",\"api_url\":\"http://127.0.0.1:33000\"},"
                + "\"env_vars\":{\"T\":\"" + CryptoService.ENC_PREFIX + "BBBB\"}}";
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("config")).thenReturn(stored);
        TenantSandboxConfig back = noKeyHandler.getNullableResult(rs, "config");
        assertEquals("", back.getCube().getApiKey(), "解不开的密钥置空（宽容，行仍可用）");
        assertEquals("http://127.0.0.1:33000", back.getCube().getApiUrl(), "非密钥字段不受影响");
        assertEquals("", back.getEnvVars().get("T"));

        // 有 key 但密文损坏：同样置空
        TenantSandboxConfigTypeHandler keyed = new TenantSandboxConfigTypeHandler(new FixedKeyCrypto());
        TenantSandboxConfig back2 = keyed.getNullableResult(rs, "config");
        assertEquals("", back2.getCube().getApiKey());

        // NULL 列 → null
        ResultSet rsNull = Mockito.mock(ResultSet.class);
        Mockito.when(rsNull.getString("config")).thenReturn(null);
        assertNull(noKeyHandler.getNullableResult(rsNull, "config"));
    }

    @Test
    void typeHandlerWritesPlaintextWhenNoKey() throws SQLException {
        // 无 key 时 Value 分支保留明文（Go 的 key == nil → return plain）；
        // 保存路径由 service 的 SYSTEM_AES_KEY 检查拒绝，这里是存储钩子自身的语义
        TenantSandboxConfigTypeHandler handler = new TenantSandboxConfigTypeHandler(new CryptoService());
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiKey("visible");
        cfg.setCube(cube);
        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, cfg, null);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ps).setObject(Mockito.eq(1), json.capture(), Mockito.eq(java.sql.Types.OTHER));
        assertTrue(json.getValue().contains("visible"));
    }

    // ── 杂项语义 ────────────────────────────────────────────────────────

    @Test
    void skillRolloutDefaultsTowardRebuilding() {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        assertTrue(cfg.rebuildsExistingOnSkillChange(), "空值默认重建");
        cfg.setSkillRollout(SandboxConstants.SKILL_ROLLOUT_NEW_SESSION);
        assertFalse(cfg.rebuildsExistingOnSkillChange());
        cfg.setSkillRollout("corrupted-value");
        assertTrue(cfg.rebuildsExistingOnSkillChange(), "未知值向重建失败");
        assertTrue(new TenantSandboxConfig().rebuildsExistingOnSkillChange());
    }

    @Test
    void entityCordonLeaseSemantics() {
        TenantSandboxConfigEntity e = new TenantSandboxConfigEntity();
        assertFalse(e.isCordoned(OffsetDateTime.now(), SandboxConstants.SANDBOX_CORDON_LEASE));
        e.setCordonedAt(OffsetDateTime.now().minusSeconds(60));
        assertTrue(e.isCordoned(OffsetDateTime.now(), SandboxConstants.SANDBOX_CORDON_LEASE),
                "租约 2 分钟内视为已 cordon");
        e.setCordonedAt(OffsetDateTime.now().minusSeconds(121));
        assertFalse(e.isCordoned(OffsetDateTime.now(), SandboxConstants.SANDBOX_CORDON_LEASE),
                "超过租约的 cordon 是崩溃 handler 的遗留物，不再生效");
        assertTrue(TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(null) == false);
        e.setName(SandboxConstants.SANDBOX_WORKSPACE_POLICY_CONFIG_NAME);
        assertTrue(TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(e));
    }
}