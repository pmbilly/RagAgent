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
 * 资源注册表领域服务：resource:// 手柄的解析/授权与注册/绑定/软删。
 *
 * <h2>/r/ 能力 URL 的令牌两态</h2>
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

    /** 位置哈希：SHA-256 hex。 */
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

    /** 非 resource:// 引用 → empty；缺失/已删 → empty。 */
    public Optional<StoredResource> resolve(String reference) {
        String handle = StoragePaths.parseResourcePath(reference);
        if (handle == null) {
            return Optional.empty();
        }
        Optional<StoredResource> resource = repo.getByHandle(handle);
        return resource;
    }

    /**
     * 非 resource:// 的值原样返回、resource 为空、无错误；resource:// 解析失败
     * （缺行/已删/引用非法）→ error=true（文件路由折成 404）。
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

    /** token → 存活资源；任何一步落空 → empty。 */
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
     * opportunistic 清理过期行 → 优先复用派生
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

    /** 复用同窗口已存在的派生令牌；无则按派生值新建。 */
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
     * 窗口 = TTL/2；token = base64url_nopad(
     * HMAC-SHA256(key, "resource_grant:v1:<id>:<windowStart>")[:16])。key 不在位
     * → null（调用方回落随机令牌）。
     *
     * <p>⚠️ 窗口起点以<b>公元 1 年 1 月 1 日（零值锚点）</b>向下取整到窗口倍数，
     * 不是 Unix 纪元。零值时间距纪元 62135596800 秒，先换算再对齐
     * （与既有行派生同一 token 的前提）。</p>
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

    /** 16 随机字节 base64url_nopad（22 字符）。 */
    static String randomToken() {
        byte[] buf = new byte[16];
        new java.security.SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static boolean isUniqueViolation(RuntimeException e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("duplicate") || msg.contains("unique constraint") || msg.contains("unique index");
    }

    /** 先解析引用（别名兜底）再查绑定。 */
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

    /** 消息文件绑定的权威来源：KB 绑定 + 消息 artifact 绑定。 */
    public record MessageFileBindings(java.util.List<String> knowledgeBaseIds, boolean messageArtifact) {
    }

    /**
     * 先解析别名（resolvePath → GetByTenantLocation 兜底），资源不存在或租户不符 →
     * 空结果（不是错误）；命中才读权威绑定。
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

    // ── 注册/绑定写面（Register/Bind/MarkDeleted）───────────────────────────

    /** 注册元数据。 */
    public record ResourceRegistration(String kind, String mimeType, String originalName,
            long size, String contentHash, boolean temporary) {
    }

    /** storage://<backendID>/<providerPath> 的拆分（对照 types.ParseStorageBackendPath）。 */
    record BackendScopedPath(String backendId, String providerPath) {
    }

    /** 对照 types.ParseStorageBackendPath。 */
    static BackendScopedPath parseStorageBackendPath(String path) {
        final String scheme = "storage://";
        if (path == null || !path.startsWith(scheme)) {
            return null;
        }
        String rest = path.substring(scheme.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null;
        }
        return new BackendScopedPath(rest.substring(0, slash), rest.substring(slash + 1));
    }

    /** 对照 types.ParseProviderScheme：provider:// 前缀提取，未知 → ""。 */
    static String parseProviderScheme(String filePath) {
        BackendScopedPath scoped = parseStorageBackendPath(filePath);
        if (scoped != null) {
            filePath = scoped.providerPath();
        }
        for (String provider : new String[]{"local", "minio", "cos", "tos", "s3", "oss", "ks3",
                "obs", "dummy"}) {
            if (filePath.startsWith(provider + "://")) {
                return provider;
            }
        }
        return "";
    }

    /** 16 随机字节的 base64url（无填充）。 */
    private static String randomResourceToken() {
        byte[] buf = new byte[16];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * 物理路径注册为稳定 resource:// 手柄。
     * 同 (tenant, location_hash) 已注册 → 复用既有手柄；handle 撞 unique 重试 4 次。
     */
    public String register(long tenantId, String physicalPath, ResourceRegistration meta) {
        physicalPath = physicalPath == null ? "" : physicalPath.trim();
        if (tenantId == 0 || physicalPath.isEmpty()) {
            throw new IllegalArgumentException("resource registration requires tenant and physical path");
        }
        if (StoragePaths.isResourcePath(physicalPath)) {
            return physicalPath;
        }
        String hash = locationHash(physicalPath);
        Optional<StoredResource> existing = repo.getByTenantLocation(tenantId, hash);
        if (existing.isPresent()) {
            return StoragePaths.buildResourcePath(existing.get().getHandle());
        }
        BackendScopedPath scoped = parseStorageBackendPath(physicalPath);
        String providerPath = scoped == null ? physicalPath : scoped.providerPath();
        String provider = parseProviderScheme(providerPath);
        if (provider.isEmpty()) {
            throw new IllegalArgumentException("resource physical path has unsupported provider scheme");
        }
        String lifecycle = meta != null && meta.temporary()
                ? StoredResource.LIFECYCLE_TEMPORARY : StoredResource.LIFECYCLE_PERSISTENT;
        for (int attempt = 0; attempt < 4; attempt++) {
            StoredResource resource = new StoredResource();
            resource.setHandle(randomResourceToken());
            resource.setTenantId(tenantId);
            resource.setStorageBackendId(scoped == null ? "" : scoped.backendId());
            resource.setProvider(provider);
            resource.setPhysicalPath(physicalPath);
            resource.setLocationHash(hash);
            resource.setKind(meta == null || meta.kind() == null ? "file" : meta.kind());
            resource.setMimeType(meta == null || meta.mimeType() == null ? "" : meta.mimeType());
            resource.setOriginalName(meta == null || meta.originalName() == null ? "" : meta.originalName());
            resource.setSize(meta == null ? 0 : meta.size());
            resource.setContentHash(meta == null || meta.contentHash() == null ? "" : meta.contentHash());
            resource.setLifecycle(lifecycle);
            try {
                repo.createResource(resource);
                return StoragePaths.buildResourcePath(resource.getHandle());
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (!msg.contains("unique") && !msg.contains("duplicate") && !msg.contains("conflict")) {
                    throw e;
                }
                Optional<StoredResource> raced = repo.getByTenantLocation(tenantId, hash);
                if (raced.isPresent()) {
                    return StoragePaths.buildResourcePath(raced.get().getHandle());
                }
            }
        }
        throw new IllegalStateException("failed to allocate unique resource handle");
    }

    /** 解析 → owner 校验 → 绑定行。 */
    public void bind(String reference, String ownerType, String ownerId, String relation) {
        StoredResource resource = resolve(reference).orElse(null);
        if (resource == null) {
            throw new IllegalArgumentException("resource not found: " + reference);
        }
        if (ownerType == null || ownerType.isBlank() || ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("resource binding requires owner type and id");
        }
        String rel = relation == null || relation.isEmpty() ? "attachment" : relation;
        repo.createBinding(resource.getId(), resource.getTenantId(), ownerType, ownerId, rel);
    }

    /** 解析后软删资源行。 */
    public void markDeleted(String reference) {
        StoredResource resource = resolve(reference).orElse(null);
        if (resource == null) {
            throw new IllegalArgumentException("resource not found: " + reference);
        }
        repo.markDeleted(resource.getId());
    }
}
