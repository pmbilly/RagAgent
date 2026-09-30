package com.ragagent.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyNotFoundException;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyRepository;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * API Key 服务语义测试——逐条对照 Go
 * internal/application/service/tenant_api_key_test.go（L1-361）。
 *
 * <p>Go 的测试用一个手写的 {@code fakeTenantAPIKeyRepo}；Java 侧用 Mockito 打桩
 * 同一组行为（约定 §7：Go mock → Mockito）。被替换掉的"仓储语义"部分
 * （租户边界、撤销行数、jsonb 三态）由
 * {@code TenantAPIKeyRepositoryTest} 在 H2 上真跑。</p>
 */
class TenantAPIKeyServiceTest {

    private TenantAPIKeyRepository repo = mock(TenantAPIKeyRepository.class);
    private TenantAPIKeyService service = new TenantAPIKeyService(repo);

    // ── 创建（对照 Go L21-39 / L266-294） ──

    @Test
    void createUsesSkPrefix() {
        TenantAPIKeyService.CreateResult result = service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        42L, null, "integration", false, null, List.of("retrieve"), null));
        assertThat(result.token()).startsWith("sk-");
        // 内存对象保留明文（响应要回显），密文化只发生在写库路径
        assertThat(result.apiKey().getApiKey()).isEqualTo(result.token());
        assertThat(result.apiKey().getKeyHash()).isNotEqualTo(result.token());
        assertThat(result.apiKey().getKeyHash()).hasSize(64);
        assertThat(result.apiKey().getScopeType()).isEqualTo("tenant");
        assertThat(result.apiKey().getTenantId()).isEqualTo(42L);
        verify(repo).create(result.apiKey());
    }

    @Test
    void createsPlatformKeyWithoutTenant() {
        TenantAPIKeyService.CreateResult created = service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        0L, "platform", "automation", false, null, List.of("retrieve"), null));
        assertThat(created.apiKey().isPlatform()).isTrue();
        assertThat(created.apiKey().getTenantId()).isNull();
        assertThat(created.apiKey().isFullAccess()).isFalse();
    }

    @Test
    void rejectsFullAccessPlatformKey() {
        assertThatThrownBy(() -> service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        0L, "platform", "unsafe", true, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform API keys require explicit capabilities");
        verify(repo, never()).create(any());
    }

    @Test
    void rejectsPlatformKeyWithoutCapabilities() {
        assertThatThrownBy(() -> service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        0L, "platform", "no-caps", false, null, List.of("bogus"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform API keys require at least one capability");
    }

    @Test
    void rejectsTenantKeyWithoutTenantId() {
        assertThatThrownBy(() -> service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        0L, "tenant", "orphan", false, null, List.of("retrieve"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id is required");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        42L, "tenant", "   ", false, null, List.of("retrieve"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void fullAccessKeyDropsKbScopeAndCapabilities() {
        TenantAPIKeyService.CreateResult created = service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        42L, "tenant", "owner", true, List.of("kb-ignored"),
                        List.of("retrieve"), null));
        assertThat(created.apiKey().isFullAccess()).isTrue();
        // 对照 Go：full-access 时两者一律置 nil（★注意不是空列表）
        assertThat(created.apiKey().getKnowledgeBaseIds()).isNull();
        assertThat(created.apiKey().getCapabilities()).isNull();
    }

    @Test
    void createNormalizesExpiryToUtc() {
        OffsetDateTime input = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.ofHours(8));
        TenantAPIKeyService.CreateResult created = service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        42L, "tenant", "utc", false, null, List.of("retrieve"), input));
        assertThat(created.apiKey().getExpiresAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(created.apiKey().getExpiresAt().toInstant()).isEqualTo(input.toInstant());
    }

    @Test
    void createNormalizesKnowledgeBaseIds() {
        TenantAPIKeyService.CreateResult created = service.create(
                new TenantAPIKeyService.TenantAPIKeyServiceCreateRequest(
                        42L, "tenant", "scoped", false, List.of(" kb-1 ", "", "kb-2", "kb-1"),
                        List.of("retrieve"), null));
        assertThat(created.apiKey().getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
    }

    // ── 更新（对照 Go L118-165） ──

    @Test
    void updateNormalizesConfiguration() {
        TenantAPIKey stored = new TenantAPIKey();
        stored.setId(7L);
        stored.setTenantId(42L);
        stored.setScopeType("tenant");
        stored.setName("updated");
        stored.setKnowledgeBaseIds(List.of("kb-1", "kb-2"));
        stored.setCapabilities(List.of("retrieve", "chat"));
        stored.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(repo.update(eq(42L), eq(7L), any())).thenReturn(stored);

        OffsetDateTime expiresAt = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.ofHours(8));
        TenantAPIKey updated = service.update(new TenantAPIKeyService.TenantAPIKeyServiceUpdateRequest(
                42L, 7L, " updated ", false,
                List.of(" kb-1 ", "", "kb-2", "kb-1"),
                List.of("retrieve", "chat", "retrieve"),
                expiresAt));

        ArgumentCaptor<TenantAPIKey> patch = ArgumentCaptor.forClass(TenantAPIKey.class);
        verify(repo).update(eq(42L), eq(7L), patch.capture());
        // 名称 trim、KB 去重去空、能力去重
        assertThat(patch.getValue().getName()).isEqualTo("updated");
        assertThat(patch.getValue().getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
        assertThat(patch.getValue().getCapabilities()).containsExactly("retrieve", "chat");
        // UTC 归一（Go: updated.ExpiresAt.Location() == time.UTC）
        assertThat(patch.getValue().getExpiresAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(updated.getName()).isEqualTo("updated");
    }

    @Test
    void updateToFullAccessClearsScope() {
        TenantAPIKey stored = new TenantAPIKey();
        stored.setFullAccess(true);
        when(repo.update(eq(42L), eq(7L), any())).thenReturn(stored);

        service.update(new TenantAPIKeyService.TenantAPIKeyServiceUpdateRequest(
                42L, 7L, "full", true, List.of("kb-ignored"), List.of("retrieve"), null));

        ArgumentCaptor<TenantAPIKey> patch = ArgumentCaptor.forClass(TenantAPIKey.class);
        verify(repo).update(eq(42L), eq(7L), patch.capture());
        assertThat(patch.getValue().isFullAccess()).isTrue();
        assertThat(patch.getValue().getKnowledgeBaseIds()).isNull();
        assertThat(patch.getValue().getCapabilities()).isNull();
    }

    @Test
    void updateRejectsScopedKeyWithoutCapabilities() {
        assertThatThrownBy(() -> service.update(new TenantAPIKeyService.TenantAPIKeyServiceUpdateRequest(
                42L, 7L, "scoped", false, null, List.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capabilities are required for scoped API keys");
    }

    @Test
    void updateRejectsZeroIds() {
        assertThatThrownBy(() -> service.update(new TenantAPIKeyService.TenantAPIKeyServiceUpdateRequest(
                0L, 7L, "x", false, null, List.of("retrieve"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id is required");
        assertThatThrownBy(() -> service.update(new TenantAPIKeyService.TenantAPIKeyServiceUpdateRequest(
                42L, 0L, "x", false, null, List.of("retrieve"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api_key_id is required");
    }

    // ── 认证（对照 Go L296-361） ──

    @Test
    void authenticateRevokedKeyFails() {
        TenantAPIKey key = keyWith("hash-revoked");
        key.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(repo.getByHash(anyString())).thenReturn(key);

        assertThatThrownBy(() -> service.authenticate("sk-whatever"))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
    }

    @Test
    void authenticateRejectsExpiredKey() {
        TenantAPIKey key = keyWith("hash-expired");
        key.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(repo.getByHash(anyString())).thenReturn(key);

        assertThatThrownBy(() -> service.authenticate("sk-whatever"))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
    }

    @Test
    void authenticateRejectsBlankTokenWithoutTouchingRepo() {
        assertThatThrownBy(() -> service.authenticate("   "))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
        verify(repo, never()).getByHash(anyString());
    }

    @Test
    void authenticateRejectsUnknownHash() {
        when(repo.getByHash(anyString())).thenThrow(new TenantAPIKeyNotFoundException());
        assertThatThrownBy(() -> service.authenticate("sk-nope"))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
    }

    /**
     * 对照 Go {@code TestTenantAPIKeyServiceAuthenticateThrottlesLastUsedUpdates}：
     * 同一把 Key 连续认证 5 次，{@code last_used_at} 只应落库**一次**
     * （1 分钟节流窗口），且写入发生在分离线程上。
     */
    @Test
    void authenticateThrottlesLastUsedUpdates() throws Exception {
        TenantAPIKey key = keyWith("hash-throttle");
        when(repo.getByHash(anyString())).thenReturn(key);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(inv -> {
            writes.incrementAndGet();
            return null;
        }).when(repo).updateLastUsed(anyLong(), any());

        for (int i = 0; i < 5; i++) {
            service.authenticate("sk-token-" + i);
        }

        // 异步写：等一会儿（Go 用 500ms deadline 轮询）
        long deadline = System.currentTimeMillis() + 2000;
        while (writes.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(50); // 给可能的重复写留出暴露窗口
        assertThat(writes.get()).isEqualTo(1);
        verify(repo, times(1)).updateLastUsed(eq(key.getId()), any());
    }

    /** 写失败时必须清掉节流标记，让下一次认证立刻重试（对照 Go 的 lastUsedTouch.Delete）。 */
    @Test
    void lastUsedWriteFailureClearsThrottle() throws Exception {
        TenantAPIKey key = keyWith("hash-fail");
        when(repo.getByHash(anyString())).thenReturn(key);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(inv -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("db down");
        }).when(repo).updateLastUsed(anyLong(), any());

        service.authenticate("sk-1");
        long deadline = System.currentTimeMillis() + 2000;
        while (attempts.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(attempts.get()).isEqualTo(1);

        // 节流标记已清除 → 立即重试一次（不等 1 分钟）
        service.authenticate("sk-1");
        deadline = System.currentTimeMillis() + 2000;
        while (attempts.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }

    // ── 回填（对照 Go L232-262） ──

    @Test
    void backfillMissingKeyHashes() {
        String token = "sk-legacy-token-value";
        TenantAPIKey legacy = new TenantAPIKey();
        legacy.setId(9L);
        legacy.setTenantId(7L);
        legacy.setName("legacy");
        legacy.setKeyHash("migrated-tenant-7");
        legacy.setApiKey(token);
        legacy.setFullAccess(true);

        when(repo.hasKeysWithPlaceholderHash()).thenReturn(true);
        when(repo.listKeysWithPlaceholderHash()).thenReturn(List.of(legacy));

        assertThat(service.backfillMissingKeyHashes()).isEqualTo(1);
        // 回填的是真实 SHA-256，不是原占位串
        verify(repo).updateKeyHash(9L, TenantAPIKeyService.hashToken(token));

        // 第二次：库里已无占位行
        when(repo.hasKeysWithPlaceholderHash()).thenReturn(false);
        assertThat(service.backfillMissingKeyHashes()).isZero();
    }

    @Test
    void backfillSkipsKeysWithoutPlaintext() {
        TenantAPIKey empty = new TenantAPIKey();
        empty.setId(9L);
        empty.setKeyHash("migrated-tenant-9");
        empty.setApiKey("   ");
        when(repo.hasKeysWithPlaceholderHash()).thenReturn(true);
        when(repo.listKeysWithPlaceholderHash()).thenReturn(List.of(empty));

        assertThat(service.backfillMissingKeyHashes()).isZero();
        verify(repo, never()).updateKeyHash(anyLong(), anyString());
    }

    // ── 撤销（对照 Go L296-314） ──

    @Test
    void revokePropagatesNotFound() {
        doThrow(new TenantAPIKeyNotFoundException()).when(repo).revoke(42L, 7L);
        assertThatThrownBy(() -> service.revoke(42L, 7L))
                .isInstanceOf(TenantAPIKeyNotFoundException.class);
    }

    // ── 摘要 / Token 形态 ──

    @Test
    void hashTokenIsStableSha256Hex() {
        // 与 Go 的 sha256 + hex.EncodeToString 一致：64 位小写十六进制
        String h = TenantAPIKeyService.hashToken("sk-abc");
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(TenantAPIKeyService.hashToken("sk-abc")).isEqualTo(h);
        assertThat(TenantAPIKeyService.hashToken("sk-abd")).isNotEqualTo(h);
    }

    @Test
    void generatedTokensAreUniqueAndRawUrlBase64() {
        String t1 = TenantAPIKeyService.generateToken();
        String t2 = TenantAPIKeyService.generateToken();
        assertThat(t1).startsWith("sk-").isNotEqualTo(t2);
        // 32 字节 base64url 无填充 = 43 字符
        assertThat(t1.substring(3)).hasSize(43).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    private static TenantAPIKey keyWith(String hash) {
        TenantAPIKey key = new TenantAPIKey();
        key.setId(7L);
        key.setTenantId(42L);
        key.setScopeType("tenant");
        key.setName("integration");
        key.setKeyHash(hash);
        key.setApiKey("sk-plaintext");
        return key;
    }
}
