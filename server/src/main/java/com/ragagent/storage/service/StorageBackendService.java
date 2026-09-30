package com.ragagent.storage.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.domain.StorageBackend;
import com.ragagent.common.storage.StorageAllowList;
import com.ragagent.storage.dto.StorageConfig;
import com.ragagent.storage.mapper.StorageBackendRepository;
import org.springframework.stereotype.Service;

/**
 * 对照 Go {@code service.StorageBackendService}（internal/application/service/storagebackend.go）
 * 的 HTTP 面方法：Create / Update / Delete / SetDefault / Test + Validate 链。
 *
 * <p><b>与 Go 的已知差异</b>（见报告）：远端 provider（minio/cos/tos/s3/oss/ks3/obs）的
 * 连通性测试 Go 走各云 SDK（HEAD bucket 等）；Java 以 TCP 拨号到 endpoint 替代——
 * 拒连的**清洗后文案**一致（SanitizeStorageConnectivityError 的中文映射），拨通后的
 * 鉴权/桶存在性检查不实现（成功路径随存储引擎模块收口）；本地 provider 完整对齐
 * （SafeJoin + mkdir + 目录可访问检查）。docker 模式的 env 回填（MINIO_*）按 Go 语义保留。</p>
 */
@Service
public class StorageBackendService {

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_ENV = "env";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
    public static final String REDACTED = "***";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** Go path.Clean 的近似：用于 path_prefix 规范化比较（../ 与 .. 收敛） */
    private static final Pattern LEADING_SLASH = Pattern.compile("^/+");

    private final StorageBackendRepository repo;
    private final StorageAllowList allowList;
    private final SsrfGuard ssrfGuard;
    private final CryptoService crypto;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    /** 对照 Go LOCAL_STORAGE_BASE_DIR（缺省 /data/files）；测试经 weknora.storage.local-base-dir 指向 build 目录 */
    private final String localStorageBaseDir;

    public StorageBackendService(StorageBackendRepository repo, StorageAllowList allowList,
            SsrfGuard ssrfGuard, CryptoService crypto,
            org.springframework.transaction.support.TransactionTemplate tx,
            @org.springframework.beans.factory.annotation.Value(
                    "${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}") String localStorageBaseDir) {
        this.repo = repo;
        this.allowList = allowList;
        this.ssrfGuard = ssrfGuard;
        this.crypto = crypto;
        this.tx = tx;
        this.localStorageBaseDir = localStorageBaseDir;
    }

    // ── 校验（对照 StorageBackend.Validate / Config.ValidateForProvider） ──

    /** Validate：**会就地修改** name（trim）/provider（小写）/status（缺省 active）——Go 同款 */
    public void validate(StorageBackend b) {
        if (b.getTenantId() == null || b.getTenantId() == 0) {
            throw validation("tenant_id is required");
        }
        b.setName(b.getName() == null ? "" : b.getName().trim());
        if (b.getName().isEmpty()) {
            throw validation("name is required");
        }
        b.setProvider(b.getProvider() == null ? "" : b.getProvider().trim().toLowerCase());
        if (!allowList.isAllowed(b.getProvider())) {
            throw validation("storage provider \"" + b.getProvider() + "\" is not allowed");
        }
        if (!allowList.isSupported(b.getProvider())) {
            throw validation("unsupported storage provider: " + b.getProvider());
        }
        if (b.getStatus() == null || b.getStatus().isEmpty()) {
            b.setStatus(STATUS_ACTIVE);
        }
        if (!STATUS_ACTIVE.equals(b.getStatus()) && !STATUS_DISABLED.equals(b.getStatus())) {
            throw validation("status must be active or disabled");
        }
        validateForProvider(configOf(b), b.getProvider());
    }

    /** 对照 ValidateForProvider：required 检查的 map 迭代序在 Go 是**随机的**——
     *  Java 取固定序（golden 全部用单缺失字段钉住，顺序不进契约） */
    public void validateForProvider(StorageConfig c, String provider) {
        String prefix = c.pathPrefix == null ? "" : c.pathPrefix.replace("\\", "/").trim();
        String cleanPrefix = clean(prefix);
        if (prefix.startsWith("/") || cleanPrefix.equals("..") || cleanPrefix.startsWith("../")) {
            throw validation("path_prefix must be a relative path without parent traversal");
        }
        switch (provider) {
            case "local" -> {
                // 无必填
            }
            case "minio" -> {
                if (c.mode == null || c.mode.isEmpty()) {
                    c.mode = "remote";
                }
                if (!"docker".equals(c.mode)) {
                    required("endpoint", c.endpoint);
                    required("access_key_id", c.accessKeyId);
                    required("secret_access_key", c.secretAccessKey);
                }
                required("bucket_name", c.bucketName);
            }
            case "cos" -> {
                required("region", c.region);
                required("access_key_id", c.accessKeyId);
                required("secret_access_key", c.secretAccessKey);
                required("bucket_name", c.bucketName);
            }
            default -> {
                required("endpoint", c.endpoint);
                required("region", c.region);
                required("access_key_id", c.accessKeyId);
                required("secret_access_key", c.secretAccessKey);
                required("bucket_name", c.bucketName);
            }
        }
    }

    /** 对照 validateStorageBackendEndpoint：SSRF（code 1000 + details=原文） */
    public void validateEndpoint(StorageBackend b) {
        StorageConfig c = configOf(b);
        if ("local".equals(b.getProvider()) || ("minio".equals(b.getProvider()) && "docker".equals(c.mode))) {
            return;
        }
        String endpoint = c.endpoint == null ? "" : c.endpoint.trim();
        if ("cos".equals(b.getProvider()) || endpoint.isEmpty()) {
            return;
        }
        if (!endpoint.contains("://")) {
            String scheme = "https://";
            if ("minio".equals(b.getProvider()) && !c.useSsl) {
                scheme = "http://";
            }
            endpoint = scheme + endpoint;
        }
        try {
            ssrfGuard.validateURLForSSRF(endpoint);
        } catch (SsrfGuard.SsrfException e) {
            throw new BizException(AppError.badRequest("storage endpoint failed SSRF validation")
                    .withDetails(e.getMessage()));
        }
    }

    // ── 连通性测试（对照 Test：local 全对齐，远端以拨号替代 SDK） ──────

    public void test(StorageBackend b) {
        validate(b);
        validateEndpoint(b);
        StorageConfig c = configOf(b);
        switch (b.getProvider()) {
            case "local" -> {
                String baseDir = localStorageBaseDir == null || localStorageBaseDir.trim().isEmpty()
                        ? "/data/files"
                        : localStorageBaseDir.trim();
                // §9 波 1 G5：必须 toAbsolutePath().normalize()——相对 base 下
                // startsWith(absolute) 会误判穿越（本轮实测复现）
                Path base = Path.of(baseDir).toAbsolutePath().normalize();
                Path safe = safeJoinUnderBase(base, c.pathPrefix);
                try {
                    Files.createDirectories(safe);
                } catch (IOException e) {
                    throw new ConnectorFailure("create local storage directory: " + e.getMessage());
                }
                // CheckConnectivity：stat(baseDir) 且是目录
                if (!Files.isDirectory(base)) {
                    throw new ConnectorFailure("storage directory not accessible: " + base);
                }
            }
            case "minio" -> {
                String endpoint = c.endpoint;
                if ("docker".equals(c.mode)) {
                    endpoint = env("MINIO_ENDPOINT");
                    c.accessKeyId = env("MINIO_ACCESS_KEY_ID");
                    c.secretAccessKey = env("MINIO_SECRET_ACCESS_KEY");
                    if (c.bucketName == null || c.bucketName.isEmpty()) {
                        c.bucketName = env("MINIO_BUCKET_NAME");
                    }
                }
                dialEndpoint(endpoint, c.useSsl ? 443 : 80, "minio");
            }
            case "cos" -> {
                // COS 无 endpoint 配置面（前端表单/校验都不收）：按腾讯云规则由
                // region+bucket 构造探测域名（Go 侧 SDK 用 region+密钥 HEAD bucket，
                // 同样不需要 endpoint）。此前 dialEndpoint("") 必败，COS 后端
                // create/update/test 全部 400。
                String region = c.region == null ? "" : c.region.trim();
                String bucket = c.bucketName == null ? "" : c.bucketName.trim();
                if (region.isEmpty() || bucket.isEmpty()) {
                    throw new ConnectorFailure("dial cos: region and bucket are required");
                }
                dialEndpoint(bucket + ".cos." + region + ".myqcloud.com", 443, "cos");
            }
            case "tos", "s3", "oss", "ks3", "obs" -> dialEndpoint(c.endpoint, 443, b.getProvider());
            default -> throw new ConnectorFailure("unsupported storage provider: " + b.getProvider());
        }
    }

    /** Go Create：validate → endpoint → test → 时间戳 → 落库（唯一冲突 → 409） */
    public void create(StorageBackend b) {
        // Go BeforeCreate：id 为空生成 UUID、source 缺省 user、status 缺省 active
        if (b.getId() == null || b.getId().isEmpty()) {
            b.setId(java.util.UUID.randomUUID().toString());
        }
        if (b.getSource() == null || b.getSource().isEmpty()) {
            b.setSource(SOURCE_USER);
        }
        validate(b);
        validateEndpoint(b);
        try {
            test(b);
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.appError().message())));
        } catch (ConnectorFailure e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.getMessage())));
        }
        b.setCreatedAt(OffsetDateTime.now());
        b.setUpdatedAt(OffsetDateTime.now());
        // 唯一性：PG 靠部分唯一索引（deleted_at IS NULL）抛错 → 409；H2 无该索引 →
        // 先查再插（单实例语义等价；PG 侧保留插入后 catch 兜底竞态窗口）
        if (repo.nameExists(b.getTenantId(), b.getName())) {
            throw BizException.conflict("a storage backend with this name already exists");
        }
        try {
            repo.create(b, serializeConfig(b));
        } catch (RuntimeException e) {
            if (repo.nameExists(b.getTenantId(), b.getName())) {
                throw BizException.conflict("a storage backend with this name already exists");
            }
            throw e;
        }
    }

    /** Go Update：守卫顺序 get → env → merge → immutable → disable-guard → validate → test → 落库 */
    public void update(StorageBackend incoming) {
        StorageBackend existing = repo.getByID(tenant(incoming), incoming.getId()).orElse(null);
        if (existing == null) {
            throw BizException.notFound("storage backend not found");
        }
        if (SOURCE_ENV.equals(existing.getSource())) {
            throw BizException.badRequest("environment storage backend is read-only");
        }
        incoming.setProvider(existing.getProvider());
        StorageConfig incomingConfig = configOf(incoming);
        StorageConfig existingConfig = configOf(existing);
        // MergeSecrets（PreserveIfRedacted）：incoming 为**空串或 *** 占位**都保留存量
        if (incomingConfig.accessKeyId == null || incomingConfig.accessKeyId.isEmpty()
                || REDACTED.equals(incomingConfig.accessKeyId)) {
            incomingConfig.accessKeyId = existingConfig.accessKeyId;
        }
        if (incomingConfig.secretAccessKey == null || incomingConfig.secretAccessKey.isEmpty()
                || REDACTED.equals(incomingConfig.secretAccessKey)) {
            incomingConfig.secretAccessKey = existingConfig.secretAccessKey;
        }
        incoming.setConfig(jsonNodeOf(incomingConfig));
        // LocationKey（provider 固定为 existing.provider——Go 两边都传 existing.Provider）
        if (!locationKey(incomingConfig, existing.getProvider())
                .equals(locationKey(existingConfig, existing.getProvider()))) {
            throw BizException.badRequest(
                    "endpoint, region, bucket and path prefix are immutable; use storage migration instead");
        }
        if (incoming.getStatus() == null || incoming.getStatus().isEmpty()) {
            incoming.setStatus(existing.getStatus());
        }
        long tenantId = tenant(incoming);
        if (STATUS_DISABLED.equals(incoming.getStatus()) && !STATUS_DISABLED.equals(existing.getStatus())) {
            int references = repo.countDefaultReference(tenantId, incoming.getId());
            if (references == 0) {
                references = repo.countBoundKnowledgeBases(tenantId, incoming.getId());
            }
            if (references == 0) {
                references = repo.countActiveResources(tenantId, incoming.getId());
            }
            if (references > 0) {
                throw BizException.badRequest("a default or bound storage backend cannot be disabled");
            }
        }
        validate(incoming);
        validateEndpoint(incoming);
        try {
            test(incoming);
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.appError().message())));
        } catch (ConnectorFailure e) {
            throw new BizException(AppError.badRequest("storage connection test failed")
                    .withDetails(sanitizeConnectivity(e.getMessage())));
        }
        incoming.setUpdatedAt(OffsetDateTime.now());
        repo.update(tenantId, incoming.getId(), incoming.getName(), serializeConfig(incoming),
                incoming.getStatus(), incoming.getUpdatedAt());
    }

    /** Go Delete：事务内 get → env → default → KB → 资源 → legacy → 软删 */
    public void delete(long tenantId, String id) {
        tx.executeWithoutResult(status -> {
            StorageBackend backend = repo.getByID(tenantId, id).orElse(null);
            if (backend == null) {
                throw BizException.notFound("storage backend not found");
            }
            if (SOURCE_ENV.equals(backend.getSource())) {
                throw BizException.badRequest("environment storage backend is read-only");
            }
            if (repo.countDefaultReference(tenantId, id) > 0) {
                throw BizException.badRequest("default storage backend cannot be deleted");
            }
            int kbCount = repo.countBoundKnowledgeBases(tenantId, id);
            if (kbCount > 0) {
                throw BizException.badRequest("storage backend still has " + kbCount
                        + " knowledge base(s) bound to it");
            }
            int resourceCount = repo.countActiveResources(tenantId, id);
            if (resourceCount > 0) {
                throw BizException.badRequest("storage backend still has " + resourceCount
                        + " active resource(s)");
            }
            if (backend.isLegacyAlias()) {
                throw BizException.badRequest(
                        "legacy storage backend cannot be deleted while old file paths may reference it");
            }
            repo.delete(tenantId, id);
        });
    }

    /** Go SetDefault：事务内 get → 仅 active → 更新租户默认 */
    public void setDefault(long tenantId, String id) {
        tx.executeWithoutResult(status -> {
            StorageBackend backend = repo.getByID(tenantId, id).orElse(null);
            if (backend == null) {
                throw BizException.notFound("storage backend not found");
            }
            if (!STATUS_ACTIVE.equals(backend.getStatus())) {
                throw BizException.badRequest("only an active storage backend can be the default");
            }
            repo.setTenantDefaultBackendId(tenantId, id);
        });
    }

    // ── 辅助 ───────────────────────────────────────────────────────────

    /** 对照 StorageBackendConfig.LocationKey（mode 的 minio remote 缺省语义） */
    public static String locationKey(StorageConfig c, String provider) {
        String mode = c.mode == null ? "" : c.mode.trim();
        if ("minio".equals(provider) && mode.isEmpty()) {
            mode = "remote";
        }
        return String.join("|",
                provider,
                mode,
                trim(c.endpoint),
                trim(c.region),
                trim(c.bucketName),
                stripSlashes(trim(c.pathPrefix)));
    }

    /** config 读取：JsonNode → typed（jsonb 内的密文在序列化写回时再处理；读路径严格解密） */
    public StorageConfig configOf(StorageBackend b) {
        if (b.getConfig() == null || b.getConfig().isNull()) {
            return new StorageConfig();
        }
        try {
            StorageConfig c = MAPPER.treeToValue(b.getConfig(), StorageConfig.class);
            // Scan：严格解密（失败拖垮行加载——与 Go 一致）
            c.accessKeyId = crypto.decryptStoredSecret(c.accessKeyId);
            c.secretAccessKey = crypto.decryptStoredSecret(c.secretAccessKey);
            return c;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse storage backend config failed", e);
        }
    }

    /** config 写出：typed → 密钥加密 → JSON 字符串（对照 Go Value()） */
    public String serializeConfig(StorageBackend b) {
        try {
            StorageConfig c = b.getConfig() == null || b.getConfig().isNull()
                    ? new StorageConfig()
                    : MAPPER.treeToValue(b.getConfig(), StorageConfig.class);
            byte[] key = crypto.getAESKey();
            if (key != null) {
                if (c.accessKeyId != null && !c.accessKeyId.isEmpty()
                        && !c.accessKeyId.startsWith(CryptoService.ENC_PREFIX)) {
                    c.accessKeyId = crypto.encryptAESGCM(c.accessKeyId, key);
                }
                if (c.secretAccessKey != null && !c.secretAccessKey.isEmpty()
                        && !c.secretAccessKey.startsWith(CryptoService.ENC_PREFIX)) {
                    c.secretAccessKey = crypto.encryptAESGCM(c.secretAccessKey, key);
                }
            }
            return MAPPER.writeValueAsString(c);
        } catch (Exception e) {
            throw new IllegalStateException("serialize storage backend config failed", e);
        }
    }

    /** 对照 MaskSensitiveFields：非空密钥 → "***"（掩码只作用于响应） */
    public com.fasterxml.jackson.databind.JsonNode maskedConfig(StorageBackend b) {
        try {
            StorageConfig c = configOf(b);
            if (c.accessKeyId != null && !c.accessKeyId.isEmpty()) {
                c.accessKeyId = REDACTED;
            }
            if (c.secretAccessKey != null && !c.secretAccessKey.isEmpty()) {
                c.secretAccessKey = REDACTED;
            }
            return MAPPER.valueToTree(c);
        } catch (Exception e) {
            throw new IllegalStateException("mask storage backend config failed", e);
        }
    }

    /** 对照 SanitizeStorageConnectivityError：驱动错误 → 中文运维提示 */
    public static String sanitizeConnectivity(String msg) {
        if (msg == null) {
            return "";
        }
        if (msg.contains("Endpoint url cannot have fully qualified paths")) {
            return "Endpoint 地址格式错误：请去除 http:// 或 https:// 前缀，只填写域名或 IP 地址和端口（例如：minio.example.com:9000）";
        }
        if (msg.contains("no such host")) {
            return "DNS 解析失败，请检查地址是否正确";
        }
        if (msg.contains("connection refused")) {
            return "连接被拒绝，请确认服务已启动且端口正确";
        }
        if (msg.contains("no route to host")) {
            return "无法路由到目标地址，请检查网络配置";
        }
        if (msg.contains("i/o timeout") || msg.contains("deadline exceeded") || msg.contains("context deadline")) {
            return "连接超时，请检查网络或服务状态";
        }
        if (msg.contains("403") || msg.contains("AccessDenied") || msg.contains("access denied")) {
            return "认证失败，请检查访问凭证是否正确";
        }
        if (msg.contains("certificate") || msg.contains("tls") || msg.contains("x509")) {
            return "TLS/SSL 证书错误，请检查 SSL 配置";
        }
        if (msg.contains("404") || msg.contains("NoSuchBucket")) {
            return "Bucket 不存在，请检查名称和 Region";
        }
        return "连接失败，请检查配置参数是否正确";
    }

    /** 对照 storageTestErrorMessage：AppError 取 Message，其余清洗（controller 用） */
    public String testErrorMessage(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz.appError().message();
        }
        if (e instanceof ConnectorFailure cf) {
            return sanitizeConnectivity(cf.getMessage());
        }
        return sanitizeConnectivity(e.getMessage());
    }

    // ── 私有 ──

    private void dialEndpoint(String endpoint, int fallbackPort, String provider) {
        String host;
        int port = fallbackPort;
        try {
            String normalized = endpoint == null ? "" : endpoint.trim();
            if (!normalized.contains("://")) {
                normalized = "https://" + normalized;
            }
            URI uri = URI.create(normalized);
            host = uri.getHost();
            if (uri.getPort() > 0) {
                port = uri.getPort();
            }
        } catch (RuntimeException e) {
            host = null;
        }
        if (host == null || host.isEmpty()) {
            throw new ConnectorFailure("dial " + provider + ": connection refused");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
        } catch (IOException e) {
            throw new ConnectorFailure("dial " + provider + ": connection refused");
        }
    }

    /** 对照 secutils.SafeJoinUnderBase：越界即拒 */
    private static Path safeJoinUnderBase(Path base, String prefix) {
        Path result = base;
        if (prefix != null && !prefix.trim().isEmpty()) {
            Path resolved = base.resolve(prefix.trim()).normalize();
            if (!resolved.startsWith(base.toAbsolutePath().normalize())) {
                throw new ConnectorFailure("path_prefix escapes the storage base directory");
            }
            result = resolved;
        }
        return result;
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v;
    }

    private static long tenant(StorageBackend b) {
        return b.getTenantId() == null ? 0 : b.getTenantId();
    }

    private static void required(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw validation(name + " is required");
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripSlashes(String s) {
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** path.Clean 近似（Go：Clean("..")=".."、Clean("a/../..")=".."） */
    private static String clean(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.startsWith("/");
        String[] parts = path.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty() && !stack.peek().equals("..")) {
                    stack.pop();
                } else if (!rooted) {
                    stack.push("..");
                }
            } else {
                stack.push(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (rooted) {
            sb.append('/');
        }
        for (java.util.Iterator<String> it = stack.descendingIterator(); it.hasNext();) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append('/');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? (rooted ? "/" : ".") : out;
    }

    private com.fasterxml.jackson.databind.JsonNode jsonNodeOf(StorageConfig c) {
        return MAPPER.valueToTree(c);
    }

    private static BizException validation(String message) {
        return new BizException(AppError.validation(message));
    }

    /** 远端探测的失败载体（走 SanitizeStorageConnectivityError 映射） */
    public static class ConnectorFailure extends RuntimeException {
        ConnectorFailure(String message) {
            super(message);
        }
    }
}
