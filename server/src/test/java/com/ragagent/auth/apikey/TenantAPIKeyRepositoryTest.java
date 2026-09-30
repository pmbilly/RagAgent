package com.ragagent.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyMapper;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyNotFoundException;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 仓储语义测试（H2）——对照 Go
 * internal/application/repository/tenant_api_key_test.go（L1-85）与
 * internal/application/repository/tenant_api_key.go 的 SQL 行为。
 *
 * <p>覆盖 mock 测不出来的部分：真实 UPDATE 的影响行数（租户边界）、
 * 复查 SELECT 的条件、{@code revoked_at IS NULL} 过滤、
 * 以及 jsonb 字符串数组列的 **null / [] / 有值** 三态往返。</p>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 看似多余（本类不用 MockMvc），但它让本类与
 * 其余契约测试**共用同一个 Spring 上下文缓存键**。原因是既有的
 * {@code McpContractTest} 依赖"最近创建的上下文里的 SsrfGuard 就是全局那一个"
 * （{@code McpServiceUrls.setSsrfGuard} 是**静态**赋值）：多一个独立的上下文键、
 * 且创建时机早于 {@code com.ragagent.mcp.*}，就会把全局指向另一个白名单为空的实例，
 * 导致 MCP 契约测试 400（已实测复现并逐层定位）。这是 MCP 模块的既有脆弱点
 * （记入任务报告"需决策点"）；这里选择"不引入新上下文键"的最小规避，
 * 不改动其它模块的代码。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantAPIKeyRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TenantAPIKeyRepository repo;
    @Autowired
    private TenantAPIKeyMapper mapper;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private static TenantAPIKey key(long tenantId, String name, String hash, String apiKey, boolean full) {
        TenantAPIKey key = new TenantAPIKey();
        key.setTenantId(tenantId);
        key.setScopeType("tenant");
        key.setName(name);
        key.setKeyHash(hash);
        key.setApiKey(apiKey);
        key.setFullAccess(full);
        return key;
    }

    /** 对照 Go {@code TestTenantAPIKeyRepositoryPersistsUTCExpiry}。 */
    @Test
    void persistsUtcExpiry() {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
        TenantAPIKey key = key(42L, "integration", "hash-expiry", "sk-test", true);
        key.setExpiresAt(expiresAt);
        repo.create(key);

        TenantAPIKey loaded = repo.getByHash("hash-expiry");
        assertThat(loaded.getExpiresAt()).isNotNull();
        assertThat(loaded.getExpiresAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(loaded.getExpiresAt().toInstant()).isEqualTo(expiresAt.toInstant());
    }

    /** 对照 Go {@code TestTenantAPIKeyRepositoryUpdateIsTenantScoped}。 */
    @Test
    void updateIsTenantScoped() {
        TenantAPIKey scoped = key(42L, "scoped", "hash-scoped", "sk-scoped", false);
        scoped.setKnowledgeBaseIds(List.of());
        scoped.setCapabilities(List.of("retrieve"));
        TenantAPIKey other = key(43L, "other", "hash-other", "sk-other", false);
        TenantAPIKey full = key(42L, "full", "hash-full", "sk-full", true);
        repo.create(scoped);
        repo.create(other);
        repo.create(full);

        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
        TenantAPIKey patch = new TenantAPIKey();
        patch.setName("updated");
        patch.setFullAccess(false);
        patch.setKnowledgeBaseIds(List.of("kb-1", "kb-2"));
        patch.setCapabilities(List.of("retrieve", "chat"));
        patch.setExpiresAt(expiresAt);

        TenantAPIKey updated = repo.update(42L, scoped.getId(), patch);
        assertThat(updated.getName()).isEqualTo("updated");
        assertThat(updated.getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
        assertThat(updated.getCapabilities()).containsExactly("retrieve", "chat");
        assertThat(updated.getExpiresAt().toInstant()).isEqualTo(expiresAt.toInstant());

        // 跨租户必须"未找到"（UPDATE 的租户边界让 RowsAffected == 0）
        TenantAPIKey blocked = new TenantAPIKey();
        blocked.setName("blocked");
        assertThatThrownBy(() -> repo.update(42L, other.getId(), blocked))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);

        // 同租户的 full-access Key 也能被降级为 scoped
        TenantAPIKey downgraded = new TenantAPIKey();
        downgraded.setName("full updated");
        downgraded.setFullAccess(false);
        downgraded.setCapabilities(List.of("retrieve"));
        TenantAPIKey result = repo.update(42L, full.getId(), downgraded);
        assertThat(result.isFullAccess()).isFalse();
        assertThat(result.getCapabilities()).containsExactly("retrieve");
    }

    /**
     * jsonb 三态：full-access（null）与 scoped（[]）必须能分别往返。
     * 这是 H2 上唯一能提前发现"把 null 写成 SQL NULL 会炸 NOT NULL"的地方。
     */
    @Test
    void preservesNullVersusEmptyArrayForKbColumns() {
        TenantAPIKey fullAccess = key(42L, "full", "hash-null", "sk-1", true);
        fullAccess.setKnowledgeBaseIds(null);
        fullAccess.setCapabilities(null);
        repo.create(fullAccess);

        TenantAPIKey scoped = key(42L, "scoped", "hash-empty", "sk-2", false);
        scoped.setKnowledgeBaseIds(List.of());
        scoped.setCapabilities(List.of("chat"));
        repo.create(scoped);

        List<TenantAPIKey> keys = repo.listByTenant(42L);
        assertThat(keys).hasSize(2);
        TenantAPIKey loadedFull = keys.stream().filter(k -> "hash-null".equals(k.getKeyHash())).findFirst().orElseThrow();
        TenantAPIKey loadedScoped = keys.stream().filter(k -> "hash-empty".equals(k.getKeyHash())).findFirst().orElseThrow();

        // full-access：读回 null（Go 的 jsonb `null` → nil 切片）
        assertThat(loadedFull.getKnowledgeBaseIds()).isNull();
        assertThat(loadedFull.getCapabilities()).isNull();
        // scoped：读回**空列表**（Go 的 `[]` → 非 nil 空切片），二者不可混淆
        assertThat(loadedScoped.getKnowledgeBaseIds()).isNotNull().isEmpty();
        assertThat(loadedScoped.getCapabilities()).containsExactly("chat");
    }

    /** 撤销：只影响目标行，且第二次撤销 → 未找到。 */
    @Test
    void revokeIsScopedAndIdempotentFailure() {
        TenantAPIKey k = key(42L, "revoke-me", "hash-revoke", "sk-3", false);
        repo.create(k);
        repo.revoke(42L, k.getId());
        assertThat(repo.listByTenant(42L)).isEmpty();
        assertThatThrownBy(() -> repo.revoke(42L, k.getId()))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
    }

    /** 平台级 Key：tenant_id 为 NULL；只出现在 listPlatform 里。 */
    @Test
    void platformKeysHaveNullTenantAndSeparateListing() {
        TenantAPIKey platform = new TenantAPIKey();
        platform.setTenantId(null);
        platform.setScopeType("platform");
        platform.setName("automation");
        platform.setKeyHash("hash-platform");
        platform.setApiKey("sk-platform");
        platform.setFullAccess(false);
        platform.setCapabilities(List.of("system_tenants_read"));
        repo.create(platform);

        assertThat(repo.listByTenant(42L)).isEmpty();
        List<TenantAPIKey> platformKeys = repo.listPlatform();
        assertThat(platformKeys).hasSize(1);
        assertThat(platformKeys.get(0).isPlatform()).isTrue();
        assertThat(platformKeys.get(0).getTenantId()).isNull();
        assertThat(platformKeys.get(0).getCapabilities()).containsExactly("system_tenants_read");

        // revokePlatform 只认平台级
        repo.revokePlatform(platform.getId());
        assertThat(repo.listPlatform()).isEmpty();
        assertThatThrownBy(() -> repo.revokePlatform(platform.getId()))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
    }

    /** 撤销后的 Key 不出现在任何列表里（撤销是软删 + revoked_at IS NULL 过滤）。 */
    @Test
    void revokedKeysAreHiddenFromLists() {
        TenantAPIKey k = key(42L, "temp", "hash-temp", "sk-4", false);
        k.setKnowledgeBaseIds(List.of());
        k.setCapabilities(List.of("retrieve"));
        repo.create(k);
        assertThat(repo.listByTenant(42L)).hasSize(1);
        repo.revoke(42L, k.getId());
        assertThat(repo.listByTenant(42L)).isEmpty();
    }

    /** 占位摘要回填：检测 / 列举 / 回填后摘要真的换了。 */
    @Test
    void placeholderHashBackfill() {
        TenantAPIKey legacy = key(42L, "legacy", "migrated-tenant-42", "sk-legacy", true);
        repo.create(legacy);

        assertThat(repo.hasKeysWithPlaceholderHash()).isTrue();
        List<TenantAPIKey> pending = repo.listKeysWithPlaceholderHash();
        assertThat(pending).hasSize(1);
        // 关键：这条读路径**不能** SkipHooks —— 调用方需要明文来重算摘要
        assertThat(pending.get(0).getApiKey()).isEqualTo("sk-legacy");

        String realHash = com.ragagent.auth.apikey.service.TenantAPIKeyService.hashToken("sk-legacy");
        repo.updateKeyHash(legacy.getId(), realHash);
        assertThat(repo.hasKeysWithPlaceholderHash()).isFalse();
        // 回填后可按真实摘要认证（getByHash 命中，且不解密）
        TenantAPIKey byHash = repo.getByHash(realHash);
        assertThat(byHash.getId()).isEqualTo(legacy.getId());
    }

    /** last_used_at 写入（对照 Go 的 UpdateAPIKeyLastUsed）。 */
    @Test
    void updateLastUsed() {
        TenantAPIKey k = key(42L, "touch", "hash-touch", "sk-5", false);
        repo.create(k);
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        repo.updateLastUsed(k.getId(), at);

        TenantAPIKey loaded = repo.getByHash("hash-touch");
        assertThat(loaded.getLastUsedAt()).isNotNull();
        assertThat(loaded.getLastUsedAt().toInstant()).isEqualTo(at.toInstant());
    }

    /** 按 id + 租户复查（UpdateAPIKey 的第二步）。 */
    @Test
    void selectByIdForTenantRespectsBoundary() {
        TenantAPIKey k = key(42L, "probe", "hash-probe", "sk-6", false);
        repo.create(k);
        assertThat(mapper.selectByIdForTenant(k.getId(), 42L)).isNotNull();
        assertThat(mapper.selectByIdForTenant(k.getId(), 43L)).isNull();
    }
}
