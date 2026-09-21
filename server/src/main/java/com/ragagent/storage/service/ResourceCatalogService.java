package com.ragagent.storage.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.ragagent.storage.domain.StoredResource;
import com.ragagent.storage.fileserve.StoragePaths;
import com.ragagent.storage.mapper.ResourceRepository;

/**
 * 资源注册表领域服务（对照 Go {@code service/resource.go resourceCatalog}）。
 * W5c 只翻文件代理面消费的解析/授权子集；Register/Bind/Release 随 chat 与
 * 知识库写入链（波 5）回补。
 *
 * <h2>/r/ 能力 URL 的令牌两态（照抄 Go）</h2>
 * <ul>
 *   <li><b>派生令牌</b>：SYSTEM_AES_KEY 在位时优先——token = 前 16 字节 HMAC 的
 *       base64url，输入 {@code "resource_grant:v1:<resourceID>:<窗口起点>"}；窗口 = TTL/2，
 *       行存的是 SHA-256(token) 哈希，明文不可从库里还原。授权完全在行上
 *       （撤销/过期后同一 token 立即失效）。</li>
 *   <li><b>随机令牌</b>：key 不在位时的回落（每请求一枚）。</li>
 * </ul>
 */
@Service
public class ResourceCatalogService {

    private static final java.time.Duration DEFAULT_GRANT_TTL = java.time.Duration.ofHours(2);

    private final ResourceRepository repo;

    public ResourceCatalogService(ResourceRepository repo) {
        this.repo = repo;
    }

    /** 对照 Go {@code resourceLocationHash}：SHA-256 hex。 */
    public static String locationHash(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] sum = md.digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 Go {@code Resolve}：非 resource:// 引用 → error；缺失/已删 → error。 */
    public Optional<StoredResource> resolve(String reference) {
        String handle = StoragePaths.parseResourcePath(reference);
        if (handle == null) {
            return Optional.empty();
        }
        Optional<StoredResource> resource = repo.getByHandle(handle);
        return resource;
    }

    /**
     * 对照 Go {@code ResolvePath}：(physicalPath, resource, error)。
     * 非 resource:// 的值原样返回、resource 为空、无错误；resource:// 解析失败
     * （缺行/已删/引用非法）→ Go 返回 error（文件路由把 error 折成 404）。
     */
    public record ResolvedPath(String physicalPath, StoredResource resource, boolean error) {
    }

    public ResolvedPath resolvePath(String value) {
        if (!StoragePaths.isResourcePath(value)) {
            return new ResolvedPath(value, null, false);
        }
        Optional<StoredResource> resource = resolve(value);
        if (resource.isEmpty()) {
            return new ResolvedPath("", null, true);
        }
        return new ResolvedPath(resource.get().getPhysicalPath(), resource.get(), false);
    }

    /** 对照 Go {@code ResolveAccessGrant}：token → 存活资源；任何一步落空 → empty。 */
    public Optional<StoredResource> resolveAccessGrant(String token) {
        String trimmed = token == null ? "" : token.trim();
        Optional<String> resourceId =
                repo.getValidGrantResourceId(locationHash(trimmed), OffsetDateTime.now(ZoneOffset.UTC));
        if (resourceId.isEmpty()) {
            return Optional.empty();
        }
        return repo.getByID(resourceId.get());
    }

    /**
     * 对照 Go {@code CreateAccessGrant}： opportunistic 清理过期行 → 优先复用派生
     * 令牌 → 回落随机令牌。供 {@code GetFileURL} 的 resource:// + APP_EXTERNAL_URL
     * 分支（dev 未设该 env 时不可达，属部署态）。
     */
    public Optional<String> createAccessGrant(String reference, java.time.Duration ttl) {
        Optional<StoredResource> resource = resolve(reference);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        java.time.Duration effective = ttl == null || ttl.isNegative() || ttl.isZero()
                ? DEFAULT_GRANT_TTL : ttl;
        repo.deleteExpiredGrants(OffsetDateTime.now(ZoneOffset.UTC));

        Optional<String> derived = reuseOrCreateDerivedGrant(resource.get().getId(), effective);
        if (derived.isPresent()) {
            return derived;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            String token = randomToken();
            try {
                repo.createGrant(UUID.randomUUID().toString(), locationHash(token), resource.get().getId(),
                        "read", OffsetDateTime.now(ZoneOffset.UTC).plus(effective));
                return Optional.of(token);
            } catch (RuntimeException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
            }
        }
        return Optional.empty();
    }

    /** 对照 Go {@code reuseOrCreateDerivedGrant}。 */
    private Optional<String> reuseOrCreateDerivedGrant(String resourceId, java.time.Duration ttl) {
        DerivedToken derived = derivedGrantToken(resourceId, ttl);
        if (derived == null) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<String> existing = repo.getValidGrantResourceId(derived.tokenHash(), now);
        if (existing.isPresent()) {
            if (!existing.get().equals(resourceId)) {
                return Optional.empty(); // 跨资源哈希碰撞（实际不可能）→ 回落随机
            }
            return Optional.of(derived.token());
        }
        try {
            repo.createGrant(UUID.randomUUID().toString(), derived.tokenHash(), resourceId,
                    "read", derived.expiresAt());
            return Optional.of(derived.token());
        } catch (RuntimeException e) {
            if (!isUniqueViolation(e)) {
                throw e;
            }
            Optional<String> winner = repo.getValidGrantResourceId(derived.tokenHash(), now);
            if (winner.isPresent() && winner.get().equals(resourceId)) {
                return Optional.of(derived.token());
            }
            return Optional.empty();
        }
    }

    private record DerivedToken(String token, String tokenHash, OffsetDateTime expiresAt) {
    }

    /**
     * 对照 Go {@code derivedGrantToken}：窗口 = TTL/2；token = base64url_nopad(
     * HMAC-SHA256(key, "resource_grant:v1:<id>:<windowStart>")[:16])。key 不在位
     * → null（调用方回落随机令牌）。
     *
     * <p>⚠️ 窗口起点是 Go 的 {@code time.Now().UTC().Truncate(window)}——Truncate 以
     * <b>Go 零值时间（公元 1 年 1 月 1 日）</b>为锚点向下取整，不是 Unix 纪元。
     * 零值时间距纪元 62135596800 秒，先换算再对齐（跨语言派生同一 token 的前提）。</p>
     */
    private static DerivedToken derivedGrantToken(String resourceId, java.time.Duration ttl) {
        byte[] key = StoragePaths.systemHmacKey();
        java.time.Duration window = ttl.dividedBy(2);
        if (key == null || window.isZero() || window.isNegative() || resourceId == null || resourceId.isEmpty()) {
            return null;
        }
        long windowSeconds = window.toSeconds();
        long goZeroToEpoch = 62135596800L;
        long sinceZero = OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond() + goZeroToEpoch;
        long truncated = sinceZero - (sinceZero % windowSeconds);
        long windowStartEpoch = truncated - goZeroToEpoch;
        OffsetDateTime windowStart = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(windowStartEpoch), ZoneOffset.UTC);
        String payload = "resource_grant:v1:" + resourceId + ":" + windowStartEpoch;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sum = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] first16 = new byte[16];
            System.arraycopy(sum, 0, first16, 0, 16);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(first16);
            return new DerivedToken(token, locationHash(token), windowStart.plus(ttl));
        } catch (Exception e) {
            throw new IllegalStateException("hmac-sha256 unavailable", e);
        }
    }

    /** 对照 Go {@code randomResourceToken}：16 随机字节 base64url_nopad（22 字符）。 */
    static String randomToken() {
        byte[] buf = new byte[16];
        new java.security.SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static boolean isUniqueViolation(RuntimeException e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("duplicate") || msg.contains("unique constraint") || msg.contains("unique index");
    }

    /** 对照 Go {@code IsReferencedByKnowledgeBase}（catalog 层：先解析引用再查绑定）。 */
    public boolean isReferencedByKnowledgeBase(long tenantId, String kbId, String reference) {
        ResolvedPath resolved = resolvePath(reference);
        if (resolved.error()) {
            return false;
        }
        String physical = resolved.physicalPath();
        StoredResource resource = resolved.resource();
        if (resource == null) {
            resource = repo.getByTenantLocation(tenantId, locationHash(physical)).orElse(null);
        }
        if (resource == null || resource.getTenantId() != tenantId) {
            return false;
        }
        return repo.isReferencedByKnowledgeBase(tenantId, kbId, resource.getId());
    }

    /** 对照 Go types.MessageFileBindings（消息文件的权威来源：KB 绑定 + 消息 artifact 绑定）。 */
    public record MessageFileBindings(java.util.List<String> knowledgeBaseIds, boolean messageArtifact) {
    }

    /**
     * 对照 Go {@code resourceCatalog.GetMessageFileBindings}（service/resource.go L179-197）：
     * 先解析别名（ResolvePath → GetByTenantLocation 兜底），资源不存在或租户不符 →
     * 空 origins（不是错误）；命中才读权威绑定。
     */
    public MessageFileBindings getMessageFileBindings(long tenantId, String reference, String messageId) {
        ResolvedPath resolved = resolvePath(reference);
        if (resolved.error()) {
            return new MessageFileBindings(java.util.List.of(), false);
        }
        StoredResource resource = resolved.resource();
        if (resource == null) {
            resource = repo.getByTenantLocation(tenantId, locationHash(resolved.physicalPath())).orElse(null);
        }
        if (resource == null || resource.getTenantId() != tenantId) {
            return new MessageFileBindings(java.util.List.of(), false);
        }
        return new MessageFileBindings(
                repo.knowledgeBaseIdsForBinding(tenantId, resource.getId()),
                repo.hasMessageArtifactBinding(tenantId, resource.getId(), messageId));
    }
}
