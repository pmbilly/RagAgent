package com.ragagent.auth.apikey.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ragagent.auth.apikey.domain.APIKeyCapability;
import com.ragagent.auth.apikey.domain.APIKeyScopeType;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyNotFoundException;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租户 API Key 服务（对照 Go internal/application/service/tenant_api_key.go）。
 *
 * <p><b>生命周期</b>：创建 → （明文 Token 只在创建响应里出现一次）→ 认证命中 →
 * 周期刷新 {@code last_used_at} → 撤销（软删）。</p>
 */
@Service
public class TenantAPIKeyService {

    private static final Logger log = LoggerFactory.getLogger(TenantAPIKeyService.class);

    /**
     * 对照 Go {@code apiKeyLastUsedMinInterval}：同一把 Key 的 {@code last_used_at}
     * 最多每分钟落一次库。UI 只需要分钟级新鲜度，而认证路径上的 UPDATE 在高 QPS
     * 下是实打实的写放大。
     */
    private static final Duration LAST_USED_MIN_INTERVAL = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantAPIKeyRepository repo;
    /**
     * 对照 Go 的 {@code lastUsedTouch sync.Map}：Key ID → 上次**已持久化**的触碰时间。
     * 用 {@link ConcurrentHashMap} 代替（Java 无锁并发 Map，与 sync.Map 同语义）。
     */
    private final ConcurrentHashMap<Long, Instant> lastUsedTouch = new ConcurrentHashMap<>();

    public TenantAPIKeyService(TenantAPIKeyRepository repo) {
        this.repo = repo;
    }

    /** 对照 Go {@code interfaces.TenantAPIKeyCreateResult}：明文 Token 与实体一起返回。 */
    public record CreateResult(TenantAPIKey apiKey, String token) {}

    /**
     * 对照 {@code CreateAPIKey}（Go L34-84）。
     *
     * <p>校验顺序有语义（Go 逐句照抄）：</p>
     * <ol>
     *   <li>tenant 作用域必须有 tenant_id；</li>
     *   <li>platform 作用域**禁止** full_access（平台 Key 必须显式列能力）；</li>
     *   <li>platform 作用域至少一个（归一化后仍成立的）能力；</li>
     *   <li>name trim 后非空；</li>
     *   <li>签发 Token；</li>
     *   <li>full-access 时**清空** KB 白名单与能力清单（冗余数据一律不落库）。</li>
     * </ol>
     */
    public CreateResult create(TenantAPIKeyServiceCreateRequest req) {
        String scopeType = APIKeyScopeType.normalize(req.scopeType());
        if (APIKeyScopeType.TENANT.equals(scopeType) && req.tenantId() == 0L) {
            throw new IllegalStateException("tenant_id is required");
        }
        if (APIKeyScopeType.PLATFORM.equals(scopeType) && req.fullAccess()) {
            throw new IllegalStateException("platform API keys require explicit capabilities");
        }
        List<String> capabilities = APIKeyCapability.normalizeAll(req.capabilities());
        if (APIKeyScopeType.PLATFORM.equals(scopeType) && capabilities.isEmpty()) {
            throw new IllegalStateException("platform API keys require at least one capability");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new IllegalStateException("name is required");
        }
        String token = generateToken();

        TenantAPIKey key = new TenantAPIKey();
        key.setTenantId(APIKeyScopeType.TENANT.equals(scopeType) ? req.tenantId() : null);
        key.setScopeType(scopeType);
        key.setName(name);
        key.setKeyHash(hashToken(token));
        key.setApiKey(token);
        key.setFullAccess(req.fullAccess());
        key.setKnowledgeBaseIds(normalizeApiKeyIds(req.knowledgeBaseIds()));
        key.setCapabilities(capabilities);
        key.setExpiresAt(toUtc(req.expiresAt()));
        if (key.isFullAccess()) {
            // 对照 Go：full-access 的 KB/能力清单一律置 nil（响应里表现为 null / []）
            key.setKnowledgeBaseIds(null);
            key.setCapabilities(null);
        }
        repo.create(key);
        return new CreateResult(key, token);
    }

    /**
     * 对照 {@code UpdateAPIKey}（Go L135-169）。
     *
     * <p>与创建的同名语义：scoped Key 至少一个能力；full-access 清空细粒度能力与 KB 范围。
     * 注意 Go 这里**没有** "expires_at 必须在未来" 的校验（只有 handler 的 Create 有）——照抄。</p>
     */
    public TenantAPIKey update(TenantAPIKeyServiceUpdateRequest req) {
        if (req.tenantId() == 0L) {
            throw new IllegalStateException("tenant_id is required");
        }
        if (req.apiKeyId() == 0L) {
            throw new IllegalStateException("api_key_id is required");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new IllegalStateException("name is required");
        }
        List<String> capabilities = APIKeyCapability.normalizeAll(req.capabilities());
        if (!req.fullAccess() && capabilities.isEmpty()) {
            throw new IllegalStateException("capabilities are required for scoped API keys");
        }

        TenantAPIKey patch = new TenantAPIKey();
        patch.setName(name);
        patch.setFullAccess(req.fullAccess());
        patch.setKnowledgeBaseIds(normalizeApiKeyIds(req.knowledgeBaseIds()));
        patch.setCapabilities(capabilities);
        patch.setExpiresAt(toUtc(req.expiresAt()));
        if (patch.isFullAccess()) {
            patch.setKnowledgeBaseIds(null);
            patch.setCapabilities(null);
        }
        return repo.update(req.tenantId(), req.apiKeyId(), patch);
    }

    /**
     * 对照 {@code AuthenticateAPIKey}（Go L86-103）。
     *
     * <p>三态失败（空 Token / 摘要未命中 / 已撤销 / 已过期）**一律**抛
     * {@link TenantAPIKeyNotFoundException}——调用方无从区分，这是刻意的信息隐藏。</p>
     */
    public TenantAPIKey authenticate(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            throw new TenantAPIKeyNotFoundException();
        }
        TenantAPIKey key = repo.getByHash(hashToken(trimmed));
        if (key.getRevokedAt() != null) {
            throw new TenantAPIKeyNotFoundException();
        }
        if (key.getExpiresAt() != null
                && OffsetDateTime.now(ZoneOffset.UTC).toInstant().isAfter(key.getExpiresAt().toInstant())) {
            throw new TenantAPIKeyNotFoundException();
        }
        touchLastUsedAsync(key.getId());
        return key;
    }

    /**
     * 对照 {@code touchAPIKeyLastUsedAsync}（Go L108-123）：节流 + 分离线程。
     *
     * <p>Go 用 {@code go func(...)}；Java 用虚拟线程（application.yml 已开
     * {@code spring.threads.virtual.enabled}）。写失败时**删除节流标记**，
     * 让下一次认证立刻重试——对照 Go 的 {@code s.lastUsedTouch.Delete(id)}。</p>
     */
    void touchLastUsedAsync(long keyId) {
        Instant now = Instant.now();
        Instant previous = lastUsedTouch.get(keyId);
        if (previous != null && Duration.between(previous, now).compareTo(LAST_USED_MIN_INTERVAL) < 0) {
            return;
        }
        lastUsedTouch.put(keyId, now);
        Thread.ofVirtual().name("apikey-touch-" + keyId).start(() -> {
            try {
                repo.updateLastUsed(keyId, OffsetDateTime.now(ZoneOffset.UTC));
            } catch (RuntimeException e) {
                log.warn("failed to update tenant api key last_used_at (id={}): {}", keyId, e.toString());
                lastUsedTouch.remove(keyId);
            }
        });
    }

    /** 对照 {@code ListAPIKeys}。 */
    public List<TenantAPIKey> listByTenant(long tenantId) {
        return repo.listByTenant(tenantId);
    }

    /** 对照 {@code ListPlatformAPIKeys}。 */
    public List<TenantAPIKey> listPlatform() {
        return repo.listPlatform();
    }

    /** 对照 {@code RevokeAPIKey}。 */
    public void revoke(long tenantId, long id) {
        repo.revoke(tenantId, id);
    }

    /** 对照 {@code RevokePlatformAPIKey}。 */
    public void revokePlatform(long id) {
        repo.revokePlatform(id);
    }

    /**
     * 对照 {@code BackfillMissingKeyHashes}（Go L179-206）：
     * 迁移 000065 给历史行写的是占位摘要 {@code migrated-tenant-<id>}，
     * 这些行从未被真实认证过；用库里的明文重算 SHA-256 并回填。
     *
     * <p>Go 在每次启动时调用一次（cmd/server/bootstrap.go L44-52，常量 EXISTS
     * 短路使稳态零开销）。Java 侧由本服务的 ApplicationReadyEvent 钩子等价调用
     * （2026-09-23 第二轮走查批接线——原"待接线"备案清除）。</p>
     *
     * @return 实际回填的行数；第二条相同的 Key 摘要直接跳过
     */
    /**
     * 对照 Go bootstrap 的启动回填（cmd/server/bootstrap.go L44-52）：每次启动
     * 尽力而为执行一次；失败只记 warn 不阻断启动。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        try {
            int n = backfillMissingKeyHashes();
            if (n > 0) {
                log.info("[bootstrap] backfilled {} legacy tenant api key hash(es)", n);
            }
        } catch (RuntimeException e) {
            log.warn("[bootstrap] tenant api key hash backfill failed: {}", e.toString());
        }
    }

    public int backfillMissingKeyHashes() {
        if (!repo.hasKeysWithPlaceholderHash()) {
            return 0;
        }
        List<TenantAPIKey> keys = repo.listKeysWithPlaceholderHash();
        int backfilled = 0;
        for (TenantAPIKey key : keys) {
            if (key == null || key.getApiKey() == null || key.getApiKey().trim().isEmpty()) {
                continue;
            }
            String hash = hashToken(key.getApiKey());
            if (hash.equals(key.getKeyHash())) {
                continue;
            }
            repo.updateKeyHash(key.getId(), hash);
            backfilled++;
        }
        return backfilled;
    }

    // ── Token 生成与摘要 ──

    /**
     * 对照 {@code generateTenantAPIKeyToken}（Go L208-214）：32 字节密码学随机 →
     * {@code "sk-" + base64url(无填充)}。前缀是集成方可见的约定（测试也钉住了它）。
     */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 对照 {@code hashTenantAPIKey}：SHA-256 → 小写十六进制（64 字符，列宽 varchar(64)）。
     * public 是为了让跨包测试能像 Go 的包内测试一样直接驱动它。
     */
    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 对照 {@code normalizeAPIKeyIDs}（Go L221-236）：trim + 去空 + 去重，
     * **返回非 null 的可变空列表**（Go 的 {@code types.StringArray{}}）。
     * 这个"非 nil 空切片"决定了 scoped Key 落库是 jsonb {@code []} 而不是 {@code null}。
     */
    static List<String> normalizeApiKeyIds(List<String> in) {
        List<String> out = new ArrayList<>(in == null ? 0 : in.size());
        Set<String> seen = new LinkedHashSet<>();
        if (in == null) {
            return out;
        }
        for (String id : in) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (seen.add(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 对照 Go 的 {@code expiresAt.UTC()}：统一存 UTC（repository 测试专门钉了这一点）。 */
    private static OffsetDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    // ── 请求载体（对照 Go interfaces.TenantAPIKeyCreateRequest / UpdateRequest） ──

    /** 对照 {@code interfaces.TenantAPIKeyCreateRequest}。 */
    public record TenantAPIKeyServiceCreateRequest(
            long tenantId, String scopeType, String name, boolean fullAccess,
            List<String> knowledgeBaseIds, List<String> capabilities, OffsetDateTime expiresAt) {}

    /** 对照 {@code interfaces.TenantAPIKeyUpdateRequest}。 */
    public record TenantAPIKeyServiceUpdateRequest(
            long tenantId, long apiKeyId, String name, boolean fullAccess,
            List<String> knowledgeBaseIds, List<String> capabilities, OffsetDateTime expiresAt) {}
}
