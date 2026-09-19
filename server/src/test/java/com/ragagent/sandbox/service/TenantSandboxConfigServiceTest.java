package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.BizException;
import com.ragagent.sandbox.domain.CubeSandboxConfig;
import com.ragagent.sandbox.domain.SandboxConfigRedaction;
import com.ragagent.sandbox.domain.SandboxConstants;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import com.ragagent.sandbox.runtime.DockerBackendDisabledException;
import com.ragagent.sandbox.runtime.SandboxConfigIncompleteException;
import com.ragagent.sandbox.runtime.UnsafeOutboundURLException;
import com.ragagent.sandbox.runtime.UnsupportedSandboxTypeException;
import com.ragagent.sandbox.service.SandboxClientFactory;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.NamedSandboxBackendUnsupportedException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxConfigNameRequiredException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxInventoryUnverifiableException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxesStillLiveException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SkillSnapshotBlocksTemplateChangeException;
import com.ragagent.sandbox.service.TenantSandboxConfigService.CreateSandboxConfigInput;
import com.ragagent.sandbox.service.TenantSandboxConfigService.SandboxTemplateQueryInput;
import com.ragagent.sandbox.service.TenantSandboxConfigService.UpdateSandboxConfigInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 波 3 子批 1 service H2 单测（对照 Go service 层语义；只连 H2，无真实网络——
 * provider 接缝恒抛"未接线"，恰好覆盖 Go 的失败分支形态）。
 *
 * <p>种子模式复用 {@code AuthRegisterContractTest}（租户 10002 + owner）。
 * {@code TestConfiguration} 注入恒 null-key 的 {@link CryptoService} @Primary 替身，
 * 使 "SYSTEM_AES_KEY 未配置" 的拒绝分支不随运行环境 env 波动（带 key 的加密往返
 * 在 {@code SandboxConfigDomainTest} 用固定 key 替身覆盖）。</p>
 */
@SpringBootTest
class TenantSandboxConfigServiceTest {

    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";

    @Autowired
    private TenantSandboxConfigService service;
    @Autowired
    private TenantSandboxConfigMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper memberMapper;

    @TestConfiguration
    static class NullKeyCryptoConfig {
        @Bean
        @Primary
        CryptoService nullKeyCrypto() {
            // getAESKey 恒 null：对应 "SYSTEM_AES_KEY 未配置" 的确定性环境
            return new CryptoService() {
                @Override
                public byte[] getAESKey() {
                    return null;
                }
            };
        }
    }

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(OWNER);
        user.setUsername("phase1test");
        user.setEmail("java-phase1@weknora.test");
        user.setPasswordHash("$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK");
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(OWNER);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, java.time.ZoneOffset.UTC));
        memberMapper.insert(member);
    }

    // ── 造数辅助 ────────────────────────────────────────────────────────

    private TenantSandboxConfig cubeConfig(String apiKey, String templateId) {
        TenantSandboxConfig cfg = new TenantSandboxConfig();
        cfg.setSandboxType("cube");
        cfg.setAllowPrivateEndpoints(true);
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiUrl("http://127.0.0.1:33000");
        cube.setProxyUrl("http://127.0.0.1:80");
        cube.setSandboxDomain("cube.app");
        cube.setApiKey(apiKey);
        cube.setTemplateId(templateId);
        cfg.setCube(cube);
        return cfg;
    }

    private TenantSandboxConfigEntity createCube(String name, String apiKey) {
        return service.create(TENANT, new CreateSandboxConfigInput(
                name, "desc", cubeConfig(apiKey, "tpl-1")));
    }

    // ── Create ──────────────────────────────────────────────────────────

    @Test
    void createPersistsMergedConfigAndPromotesType() {
        // null-key 环境：带密钥的 create 被 sanitize 拒绝（见下一条），这里用无密钥载荷
        TenantSandboxConfigEntity e = createCube("big-mem", null);
        assertNotNull(e.getId());
        assertEquals("cube", e.getSandboxType());
        assertEquals("big-mem", e.getName());
        assertEquals("tpl-1", e.getConfig().getCube().getTemplateId());
        // GORM Create 的时间回写语义（Java 侧显式赋值）
        assertNotNull(e.getCreatedAt());
        assertNotNull(e.getUpdatedAt());

        TenantSandboxConfigEntity fromDb = mapper.getByID(TENANT, e.getId());
        assertEquals(e.getId(), fromDb.getId());
        assertEquals("tpl-1", fromDb.getConfig().getCube().getTemplateId());
    }

    @Test
    void createValidationChainOrder() {
        // 名字缺失（在 sanitize 之后判定——Go L485-495 的顺序）
        assertThrows(SandboxConfigNameRequiredException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("   ", "", cubeConfig(null, "t"))));
        // backend type 缺失
        BizException e = assertThrows(BizException.class, () -> service.create(TENANT,
                new CreateSandboxConfigInput("n", "", null)));
        assertEquals("sandbox backend type is required", e.appError().message());
        // 未知类型：Create 先走 validateNamedSandboxBackend → 命名后端不支持
        // （ParseSandboxType 的哨兵在 sanitize 里，对具名后端走不到）
        TenantSandboxConfig bad = new TenantSandboxConfig();
        bad.setSandboxType("kubernetes");
        assertThrows(NamedSandboxBackendUnsupportedException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("n", "", bad)));
        // docker 默认被拒
        TenantSandboxConfig docker = new TenantSandboxConfig();
        docker.setSandboxType("docker");
        docker.setDocker(new com.ragagent.sandbox.domain.DockerSandboxConfig());
        assertThrows(DockerBackendDisabledException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("n", "", docker)));
        // 不完整配置（文案含 provider 与字段清单）
        TenantSandboxConfig incomplete = new TenantSandboxConfig();
        incomplete.setSandboxType("e2b");
        incomplete.setE2b(new com.ragagent.sandbox.domain.E2BSandboxConfig());
        SandboxConfigIncompleteException i = assertThrows(SandboxConfigIncompleteException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("n", "", incomplete)));
        assertEquals("sandbox: config is missing required fields: e2b backend requires api_key, template_id",
                i.getMessage());
        // 不安全 URL（字面 link-local；opt-in 也不放行）
        TenantSandboxConfig unsafe = cubeConfig(null, "t");
        unsafe.getCube().setApiUrl("http://169.254.169.254/latest");
        UnsafeOutboundURLException u = assertThrows(UnsafeOutboundURLException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("n", "", unsafe)));
        assertEquals("sandbox: unsafe outbound URL: "
                + "address 169.254.169.254 is never routable to a sandbox (link-local address)",
                u.getMessage());
        // 非法 skill_rollout
        TenantSandboxConfig rollout = cubeConfig(null, "t");
        rollout.setSkillRollout("whenever");
        BizException r = assertThrows(BizException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("n", "", rollout)));
        assertEquals("invalid skill_rollout", r.appError().message());
        // AES key 缺失 + 密钥载荷 → 固定文案
        TenantSandboxConfig withSecret = cubeConfig("k", "t");
        BizException a = assertThrows(BizException.class,
                () -> service.create(TENANT, new CreateSandboxConfigInput("n", "", withSecret)));
        assertEquals("SYSTEM_AES_KEY is not configured; refusing to store sandbox credentials in plaintext",
                a.appError().message());
    }

    // ── List / Get / 策略行隐藏 ─────────────────────────────────────────

    @Test
    void listHidesWorkspacePolicyRowAndGetHidesItToo() {
        createCube("a", null);
        createCube("b", null);
        service.setWorkspaceScriptsDisabled(TENANT, true);

        List<TenantSandboxConfigEntity> visible = service.list(TENANT);
        assertEquals(2, visible.size());
        assertTrue(service.workspaceScriptsDisabled(TENANT));

        // 策略行在库里（隐藏行：disabled 类型）
        Integer policyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_sandbox_configs WHERE name = ?",
                Integer.class, SandboxConstants.SANDBOX_WORKSPACE_POLICY_CONFIG_NAME);
        assertEquals(1, policyRows);

        // Get 按名隐藏策略行
        TenantSandboxConfigEntity policyRow = null;
        for (TenantSandboxConfigEntity row : mapper.listByTenant(TENANT)) {
            if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(row)) {
                policyRow = row;
                assertNull(service.get(TENANT, row.getId()), "策略行对 Get 隐藏");
                assertThrows(BizException.class, () -> service.delete(TENANT, row.getId(), false));
                assertThrows(BizException.class, () -> service.update(TENANT, row.getId(),
                        new UpdateSandboxConfigInput("x", "", cubeConfig(null, "t"))));
            }
        }
        assertNotNull(policyRow, "种子后应存在策略行");
        // 重复 disable 是幂等的（已存在 → 直接返回）
        service.setWorkspaceScriptsDisabled(TENANT, true);
        assertEquals(1, policyRows);

        // 重新启用 → 软删策略行
        service.setWorkspaceScriptsDisabled(TENANT, false);
        assertFalse(service.workspaceScriptsDisabled(TENANT));
        Integer deletedPolicy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_sandbox_configs WHERE name = ? AND deleted_at IS NOT NULL",
                Integer.class, SandboxConstants.SANDBOX_WORKSPACE_POLICY_CONFIG_NAME);
        assertEquals(1, deletedPolicy);
        // 再 enable 一次也是幂等
        service.setWorkspaceScriptsDisabled(TENANT, false);
        assertFalse(service.workspaceScriptsDisabled(TENANT));
    }

    @Test
    void getUnknownAndPolicyGuards() {
        assertNull(service.get(TENANT, "no-such-id"));
        TenantSandboxConfigEntity e = createCube("x", null);
        // Update 未知名 → null（handler 404）
        assertNull(service.update(TENANT, "no-such-id",
                new UpdateSandboxConfigInput("y", "", cubeConfig(null, "t"))));
        // Delete 未知名 → 404 AppError
        BizException d = assertThrows(BizException.class, () -> service.delete(TENANT, "no-such-id", false));
        assertEquals(404, d.appError().httpCode());
        assertEquals("sandbox config not found", d.appError().message());
        // 策略行不可经 Update/Delete 面编辑（对照 Go 的 "workspace policy cannot be edited/deleted here"）
        service.setWorkspaceScriptsDisabled(TENANT, true);
        TenantSandboxConfigEntity policyRow = null;
        for (TenantSandboxConfigEntity row : mapper.listByTenant(TENANT)) {
            if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(row)) {
                policyRow = row;
            }
        }
        assertNotNull(policyRow);
        final TenantSandboxConfigEntity policy = policyRow;
        BizException u = assertThrows(BizException.class, () -> service.update(TENANT, policy.getId(),
                new UpdateSandboxConfigInput("x", "", cubeConfig(null, "t"))));
        assertEquals("workspace policy cannot be edited here", u.appError().message());
        BizException del = assertThrows(BizException.class,
                () -> service.delete(TENANT, policy.getId(), false));
        assertEquals("workspace policy cannot be deleted here", del.appError().message());
        // 有租户上下文的普通用户行 Get 正常
        assertNotNull(service.get(TENANT, e.getId()));
    }

    // ── Update：非身份编辑直写；身份编辑 cordon + 接缝失败分支 ────────────

    @Test
    void updateWithoutIdentityChangeWritesDirectly() {
        TenantSandboxConfigEntity e = createCube("before", null);
        OffsetDateTime readUpdatedAt = e.getUpdatedAt();

        // 名字/描述变化（名字不属于身份；载荷无密钥——null-key 环境下带密钥会 400）
        UpdateSandboxConfigInput in = new UpdateSandboxConfigInput(
                "  renamed  ", "new-desc", cubeConfig(null, "tpl-1"));
        TenantSandboxConfigEntity updated = service.update(TENANT, e.getId(), in);

        assertEquals("renamed", updated.getName());
        assertEquals("new-desc", updated.getDescription());
        // 名字/模板/env vars 不属于身份 → 无 cordon、无 provider 盘点（接缝未被触达）
        Long cordonCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_sandbox_configs WHERE cordoned_at IS NOT NULL", Long.class);
        assertEquals(0, cordonCount);
        // GORM map Updates 不回写内存对象：updated_at 保持读时值（Go 一致）
        assertEquals(readUpdatedAt, updated.getUpdatedAt());
    }

    @Test
    void updateIdentityChangeRefusedWhenSnapshotPresent() {
        TenantSandboxConfigEntity e = createCube("with-skill", null);
        // 栽一个 skill 快照（install 面属子批 2；这里直种存储形状）
        jdbc.update("UPDATE tenant_sandbox_configs SET config = ? WHERE id = ?",
                "{\"sandbox_type\":\"cube\",\"skill_image\":{\"snapshot_id\":\"snap-1\","
                        + "\"generation\":1,\"built_at\":\"0001-01-01T00:00:00Z\","
                        + "\"base_template_id\":\"tpl-1\","
                        + "\"owner_fingerprint\":\"" + "f".repeat(64) + "\"}}",
                e.getId());

        // sandbox_domain 是数据面身份字段（无密钥载荷，null-key 环境可用）
        TenantSandboxConfig in = cubeConfig(null, "tpl-1");
        in.getCube().setSandboxDomain("other.app");
        SkillSnapshotBlocksTemplateChangeException ex = assertThrows(
                SkillSnapshotBlocksTemplateChangeException.class,
                () -> service.update(TENANT, e.getId(),
                        new UpdateSandboxConfigInput("with-skill", "d", in)));
        assertEquals("sandbox connection cannot change while this config has a skill snapshot",
                ex.getMessage());
    }

    @Test
    void updateIdentityChangeBlockedByInFlightSkillInstall() {
        TenantSandboxConfigEntity e = createCube("inflight", null);
        jdbc.update("INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, name, status, enabled)"
                        + " VALUES (?, ?, ?, ?, ?, FALSE)",
                "skill-1", TENANT, e.getId(), "searcher", "installing");

        TenantSandboxConfig in = cubeConfig(null, "tpl-1");
        in.getCube().setSandboxDomain("other.app");
        SkillSnapshotBlocksTemplateChangeException ex = assertThrows(
                SkillSnapshotBlocksTemplateChangeException.class,
                () -> service.update(TENANT, e.getId(),
                        new UpdateSandboxConfigInput("inflight", "d", in)));
        assertNotNull(ex);
        // removing 同样拦截；ready 不拦（走 cordon → 接缝失败 → proceed 分支）
        jdbc.update("UPDATE tenant_skills SET status = 'removing' WHERE id = 'skill-1'");
        assertThrows(SkillSnapshotBlocksTemplateChangeException.class,
                () -> service.update(TENANT, e.getId(),
                        new UpdateSandboxConfigInput("inflight", "d", in)));
        jdbc.update("UPDATE tenant_skills SET status = 'ready' WHERE id = 'skill-1'");
        // 旧凭据盘点（接缝）失败 → warn 后 proceed（Go L1002-1015），更新成功
        TenantSandboxConfigEntity updated = service.update(TENANT, e.getId(),
                new UpdateSandboxConfigInput("inflight", "d", in));
        assertEquals("other.app", updated.getConfig().getCube().getSandboxDomain());
        // cordon 已在请求结束时清除（defer 语义）
        Long cordonCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_sandbox_configs WHERE cordoned_at IS NOT NULL", Long.class);
        assertEquals(0, cordonCount);
    }

    @Test
    void cordonLeaseCasViaRepository() {
        TenantSandboxConfigEntity e = createCube("cordoned", null);
        OffsetDateTime now = OffsetDateTime.now();
        // 首次取得 cordon 成功
        assertEquals(1, mapper.setCordon(TENANT, e.getId(), now,
                now.minusNanos(SandboxConstants.SANDBOX_CORDON_LEASE.toNanos())));
        // 租约窗口内的第二次取得被 CAS 拒绝
        assertEquals(0, mapper.setCordon(TENANT, e.getId(), now.plusSeconds(1),
                now.plusSeconds(1).minusNanos(SandboxConstants.SANDBOX_CORDON_LEASE.toNanos())));
        // 过期租约可以被覆盖
        assertEquals(1, mapper.setCordon(TENANT, e.getId(), now.plusSeconds(301),
                now.plusSeconds(301).minusNanos(SandboxConstants.SANDBOX_CORDON_LEASE.toNanos())));
        // 清除
        assertEquals(1, mapper.clearCordon(TENANT, e.getId(), now));
        assertEquals(1, mapper.setCordon(TENANT, e.getId(), now,
                now.minusNanos(SandboxConstants.SANDBOX_CORDON_LEASE.toNanos())));
        mapper.clearCordon(TENANT, e.getId(), now);
    }

    // ── Delete：接缝失败分支与 Go 同形 ──────────────────────────────────

    @Test
    void deleteWithoutForceReportsUnverifiableInventory() {
        TenantSandboxConfigEntity e = createCube("del", null);
        // 接缝未接线 → 盘点无法核实 → !force 拒绝（HTTP 层转 409 固定文案）
        SandboxInventoryUnverifiableException ex = assertThrows(
                SandboxInventoryUnverifiableException.class,
                () -> service.delete(TENANT, e.getId(), false));
        assertTrue(ex.getMessage().startsWith(
                        "cannot verify whether the sandbox config still owns sandboxes: "),
                ex.getMessage());
        // 行未被删
        assertNotNull(mapper.getByID(TENANT, e.getId()));

        // force → 警告后继续，软删成功
        service.delete(TENANT, e.getId(), true);
        assertNull(mapper.getByID(TENANT, e.getId()), "软删后 GetByID 返回 null（deleted_at 过滤）");
    }

    @Test
    void deleteSoftDeletesOnlyTheOneRow() {
        TenantSandboxConfigEntity a = createCube("keep", null);
        TenantSandboxConfigEntity b = createCube("gone", null);
        service.delete(TENANT, b.getId(), true);
        assertNotNull(mapper.getByID(TENANT, a.getId()));
        assertNull(mapper.getByID(TENANT, b.getId()));
        // 软删后同名可再建（部分唯一索引语义在 PG；H2 不建索引，此处验证行数）
        createCube("gone", null);
        assertEquals(2, service.list(TENANT).size());
    }

    // ── Inventory / QueryTemplates（provider 接缝失败分支） ──────────────

    @Test
    void inventoryReportsUnverifiableWhenProviderUnreachable() {
        createCube("inv", null);
        String id = service.list(TENANT).get(0).getId();
        SandboxInventory inv = service.inventory(TENANT, id);
        assertEquals(0, inv.sandboxCount());
        assertTrue(inv.unverifiable(), "接缝未接线 = 无法核实，而非安心的 0");
        assertNull(inv.sessionIds());
        assertNull(inv.agentNames());

        // 未知 id → 404
        BizException e = assertThrows(BizException.class, () -> service.inventory(TENANT, "nope"));
        assertEquals("sandbox config not found", e.appError().message());
    }

    @Test
    void inventoryForDisabledPolicyRowBackendIsVerifiedEmpty() {
        // disabled 类型不构建客户端 → 一次"已核实的空"（Go 的 clientFor nil 分支）
        service.setWorkspaceScriptsDisabled(TENANT, true);
        TenantSandboxConfigEntity policy = null;
        for (TenantSandboxConfigEntity row : mapper.listByTenant(TENANT)) {
            if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(row)) {
                policy = row;
            }
        }
        assertNotNull(policy);
        SandboxInventory inv = service.inventory(TENANT, policy.getId());
        assertEquals(0, inv.sandboxCount());
        assertFalse(inv.unverifiable(), "disabled 后端无需 provider，即为已核实");
    }

    @Test
    void queryTemplatesValidationBeforeSeam() {
        // replace_standard 需要 config_id
        BizException e = assertThrows(BizException.class, () -> service.queryTemplates(TENANT,
                new SandboxTemplateQueryInput(cubeConfig(null, "t"), "", false, true)));
        assertEquals("config_id is required to rebuild the standard template", e.appError().message());

        // config_id 指向不存在的配置
        e = assertThrows(BizException.class, () -> service.queryTemplates(TENANT,
                new SandboxTemplateQueryInput(cubeConfig(null, "t"), "nope", false, false)));
        assertEquals("sandbox config not found", e.appError().message());

        // config 缺失（无 config_id 也无 config）
        e = assertThrows(BizException.class, () -> service.queryTemplates(TENANT,
                new SandboxTemplateQueryInput(null, "", false, false)));
        assertEquals("sandbox config is required", e.appError().message());

        // 不支持的后端
        TenantSandboxConfig disabled = new TenantSandboxConfig();
        disabled.setSandboxType("disabled");
        e = assertThrows(BizException.class, () -> service.queryTemplates(TENANT,
                new SandboxTemplateQueryInput(disabled, "", false, false)));
        assertEquals("sandbox template catalog only supports cube, e2b and docker backends",
                e.appError().message());

        // cube + catalog 占位模板 → effective 校验通过 → 真客户端拨 127.0.0.1:33000
        // 拒连 → RemoteError(UNAVAILABLE)（500 家族；波 3 子批 2 起接真客户端）
        TenantSandboxConfig cube = cubeConfig(null, "");
        com.ragagent.sandbox.runtime.RemoteError re = assertThrows(
                com.ragagent.sandbox.runtime.RemoteError.class,
                () -> service.queryTemplates(TENANT,
                        new SandboxTemplateQueryInput(cube, "", false, false)));
        assertEquals(com.ragagent.sandbox.runtime.RemoteErrorKind.UNAVAILABLE, re.kind);
    }

    // ── QueryTemplates：技能快照拦截 replace_standard（409 skill_snapshot_blocks_template） ──

    @Test
    void queryTemplatesReplaceStandardRefusedWhenSiblingHasSnapshot() {
        // 两份同身份（同 provider/端点/域名/密钥）的配置；A 栽了快照，B 没有
        createCube("twin-a", null);
        TenantSandboxConfigEntity b = createCube("twin-b", null);
        String snapshotConfig = "{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,"
                + "\"cube\":{\"api_url\":\"http://127.0.0.1:33000\","
                + "\"proxy_url\":\"http://127.0.0.1:80\",\"sandbox_domain\":\"cube.app\","
                + "\"template_id\":\"tpl-1\"},\"skill_image\":{\"snapshot_id\":\"snap-1\","
                + "\"generation\":1,\"built_at\":\"0001-01-01T00:00:00Z\","
                + "\"base_template_id\":\"tpl-1\",\"owner_fingerprint\":\"" + "f".repeat(64) + "\"}}";
        String aId = service.list(TENANT).stream()
                .filter(e -> "twin-a".equals(e.getName())).findFirst().orElseThrow().getId();
        jdbc.update("UPDATE tenant_sandbox_configs SET config = ? WHERE id = ?", snapshotConfig, aId);

        // 以 B 的 config_id 发起 replace_standard → 同身份的 A 有快照 → 拒绝
        SkillSnapshotBlocksTemplateChangeException ex = assertThrows(
                SkillSnapshotBlocksTemplateChangeException.class,
                () -> service.queryTemplates(TENANT,
                        new SandboxTemplateQueryInput(null, b.getId(), false, true)));
        assertEquals("sandbox connection cannot change while this config has a skill snapshot",
                ex.getMessage());
    }

    // ── 打码投影（controller 用的域函数，这里做 service 级连通确认） ──────

    @Test
    void storedSecretsAreMaskedInResponseProjection() throws Exception {
        TenantSandboxConfigEntity e = createCube("masked", null);
        // 直种带密钥的存储 JSON（Value 钩子的落库形态；null-key 下等价明文存储）
        jdbc.update("UPDATE tenant_sandbox_configs SET config = ? WHERE id = ?",
                "{\"sandbox_type\":\"cube\",\"cube\":{\"api_url\":\"http://127.0.0.1:33000\","
                        + "\"proxy_url\":\"http://127.0.0.1:80\",\"sandbox_domain\":\"cube.app\","
                        + "\"api_key\":\"stored-secret\",\"template_id\":\"tpl-1\"}}",
                e.getId());
        TenantSandboxConfigEntity fromDb = mapper.getByID(TENANT, e.getId());
        TenantSandboxConfig masked =
                SandboxConfigRedaction.sandboxConfigForResponse(fromDb.getConfig(), true);
        assertEquals(SandboxConstants.REDACTED_SECRET_PLACEHOLDER, masked.getCube().getApiKey());
        assertEquals("stored-secret", fromDb.getConfig().getCube().getApiKey(), "原配置不被打码改动");
        // maskSecrets=false 原样（admin 排查路径的语义面）
        assertEquals("stored-secret", SandboxConfigRedaction
                .sandboxConfigForResponse(fromDb.getConfig(), false).getCube().getApiKey());
    }
}
