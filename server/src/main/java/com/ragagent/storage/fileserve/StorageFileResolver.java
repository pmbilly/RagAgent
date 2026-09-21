package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.knowledge.domain.StorageBackend;
import com.ragagent.storage.mapper.StorageBackendRepository;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * 运行时存储解析器（收尾批 W5c）——对照三段 Go 源的合并移植：
 *
 * <ul>
 *   <li>{@code service/storagebackend.go} 的 {@code ResolveBackend} /
 *       {@code ResolveFileService} / {@code hydrateTenantStorage}（backendID 优先、
 *       provider 是 legacy 回落）；</li>
 *   <li>{@code service/file/factory.go} 的 {@code NewFileServiceFromStorageConfig}
 *       （provider 完备性检查——错误文案会出现在 presigned-preview 的 400 body 里，
 *       逐字照抄）；</li>
 *   <li>{@code service/file/resolve_tenant.go} 的
 *       {@code ResolveTenantFileServiceWithFallback}（租户解析失败且 provider 等于
 *       全局 STORAGE_TYPE 时回落进程级服务）。</li>
 * </ul>
 *
 * <h2>⚠️ 已知差异：云 provider 的 SDK 客户端未翻译</h2>
 * <p>配置完备的 minio/cos/tos/s3/oss/obs/ks3 在 Go 会造出真实 SDK 客户端
 * （20+ 文件），Java 侧只有 local 一支真实实现。配置<b>不完备</b>时的错误文案
 * （"missing minio config" / "incomplete cos config" / {@code unsupported provider "%s"}）
 * 逐字对齐——dev 部署只配 local，完备云配置属于部署态（XDEP）。</p>
 *
 * <p>同时承载 Go {@code resourceCatalogFileService} 装饰器的可见行为：
 * 打开 {@code resource://} 手柄先换物理路径；{@code GetFileURL} 对手柄在
 * APP_EXTERNAL_URL 在位时派生 /r/ 能力令牌。</p>
 */
@Service
public class StorageFileResolver {

    private static final Logger log = LoggerFactory.getLogger(StorageFileResolver.class);

    private final StorageBackendRepository backendRepo;
    private final ResourceCatalogService catalog;

    public StorageFileResolver(StorageBackendRepository backendRepo, ResourceCatalogService catalog) {
        this.backendRepo = backendRepo;
        this.catalog = catalog;
    }

    // ── 解析结果 ────────────────────────────────────────────────────────────

    /** (fileSvc, resolvedProvider, ok)——错误经 {@code error} 通道。 */
    public record Resolution(FileContentService service, String resolvedProvider, String error) {

        boolean ok() {
            return error == null;
        }
    }

    // ── storagebackend.ResolveFileService / ResolveBackend ──────────────────

    /**
     * 对照 Go {@code ResolveFileService(ctx, tenant, backendID, provider, localBaseDir)}。
     * 返回的 Resolution.error 非 null 时 HTTP 面按各自的映射处理。
     */
    public Resolution resolveFileService(Tenant tenant, String backendId, String provider,
            String localBaseDir) {
        if (tenant == null) {
            return new Resolution(null, "", "workspace context missing");
        }
        Tenant hydrated = hydrateTenantStorage(tenant);
        Objects.requireNonNull(hydrated);

        BackendResolution backend = resolveBackend(hydrated, backendId, provider);
        if (backend.error() != null) {
            return new Resolution(null, "", backend.error());
        }
        if (backend.backend() != null) {
            StorageBackend b = backend.backend();
            FactoryResult inner = newFileServiceFromStorageConfig(b.getProvider(),
                    toStorageEngineConfig(b), localBaseDir);
            if (inner.error() != null) {
                return new Resolution(null, inner.provider(), inner.error());
            }
            // 对照 Go：backend 在位 → 包 BackendScoped（GetFileURL 的
            // storage://<id>/ 包装与 GetFile 的 mismatch 守卫都来自它），
            // 再包 resource catalog 装饰器。
            return new Resolution(
                    decorate(new BackendScopedFileService(b.getId(), inner.service())),
                    inner.provider(), null);
        }
        JsonNode sec = hydrated.getStorageEngineConfig();
        String provider0 = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (provider0.isEmpty() && storageEngineDefaultProvider(sec).isEmpty()) {
            StorageBackend env = storageBackendFromEnvironment(hydrated.getId());
            if (env != null) {
                provider0 = env.getProvider();
                if (sec == null) {
                    sec = toStorageEngineConfig(env);
                }
            }
        }
        FactoryResult inner = newFileServiceFromStorageConfig(provider0, sec, localBaseDir);
        if (inner.error() != null) {
            return new Resolution(null, inner.provider(), inner.error());
        }
        return new Resolution(decorate(inner.service()), inner.provider(), null);
    }

    private record BackendResolution(StorageBackend backend, String error) {
    }

    /** 对照 Go {@code ResolveBackend}（含 legacy alias 与默认后端两级回落）。 */
    private BackendResolution resolveBackend(Tenant tenant, String backendId, String provider) {
        String id = backendId == null ? "" : backendId.trim();
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty() && !p.isEmpty()) {
            StorageBackend alias = backendRepo.findLegacyAlias(tenant.getId(), p);
            if (alias != null) {
                return new BackendResolution(alias, null);
            }
        }
        if (id.isEmpty()) {
            id = tenant.getDefaultStorageBackendId() == null ? "" : tenant.getDefaultStorageBackendId().trim();
        }
        if (!id.isEmpty()) {
            StorageBackend backend = backendRepo.getByID(tenant.getId(), id).orElse(null);
            if (backend == null) {
                return new BackendResolution(null, "storage backend not found");
            }
            if (!"active".equals(backend.getStatus())) {
                return new BackendResolution(null, "storage backend is not active");
            }
            return new BackendResolution(backend, null);
        }
        return new BackendResolution(null, null);
    }

    /** 对照 Go {@code hydrateTenantStorage}：stub Tenant 从库行补 DefaultStorageBackendID/配置。 */
    private Tenant hydrateTenantStorage(Tenant tenant) {
        if (tenant.getId() == 0) {
            return tenant;
        }
        if (tenant.getDefaultStorageBackendId() != null && !tenant.getDefaultStorageBackendId().trim().isEmpty()) {
            return tenant;
        }
        String storedDefault = backendRepo.tenantDefaultBackendId(tenant.getId());
        if (storedDefault != null && !storedDefault.isEmpty()) {
            Tenant out = new Tenant();
            out.setId(tenant.getId());
            out.setDefaultStorageBackendId(storedDefault);
            out.setStorageEngineConfig(tenant.getStorageEngineConfig());
            return out;
        }
        return tenant;
    }

    private static String storageEngineDefaultProvider(JsonNode sec) {
        if (sec == null) {
            return "";
        }
        JsonNode dp = sec.get("default_provider");
        if (dp == null || !dp.isTextual()) {
            return "";
        }
        return dp.asText().trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ── factory.go NewFileServiceFromStorageConfig ──────────────────────────

    public record FactoryResult(FileContentService service, String provider, String error) {
    }

    /**
     * 对照 Go {@code NewFileServiceFromStorageConfig}：provider 可空 → 租户
     * default_provider；完备性检查的错误文案逐字（presigned-preview 400 可见）。
     */
    public FactoryResult newFileServiceFromStorageConfig(String provider, JsonNode sec, String localBaseDir) {
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty() && sec != null) {
            p = storageEngineDefaultProvider(sec);
        }
        if (p.isEmpty()) {
            return new FactoryResult(null, "", "empty provider");
        }
        String baseDir = localBaseDir == null || localBaseDir.isEmpty()
                ? StoragePaths.localStorageBaseDir() : localBaseDir;

        switch (p) {
            case "local": {
                String dir = baseDir;
                JsonNode local = sec == null ? null : sec.get("local");
                if (local != null) {
                    String pathPrefix = textOrNull(local.get("path_prefix"));
                    if (pathPrefix != null && !pathPrefix.trim().isEmpty()) {
                        String joined = LocalFileContentService.joinPath(dir, pathPrefix.trim());
                        try {
                            dir = LocalFileContentService.safePathUnderBase(dir, joined);
                        } catch (Exception ignored) {
                            // Go: SafeJoinUnderBase 失败 → 忽略，baseDir 原样
                        }
                    }
                }
                String externalURL = env("APP_EXTERNAL_URL");
                return new FactoryResult(new LocalFileContentService(dir, externalURL), p, null);
            }
            case "minio": {
                JsonNode m = sec == null ? null : sec.get("minio");
                if (m == null) {
                    return new FactoryResult(null, p, "missing minio config");
                }
                boolean remote = "remote".equals(textOr(m.get("mode"), ""));
                String endpoint = remote ? textOr(m.get("endpoint"), "").trim() : env("MINIO_ENDPOINT");
                String accessKey = remote ? textOr(m.get("access_key_id"), "").trim() : env("MINIO_ACCESS_KEY_ID");
                String secretKey = remote ? textOr(m.get("secret_access_key"), "").trim() : env("MINIO_SECRET_ACCESS_KEY");
                String bucket = textOr(m.get("bucket_name"), "").trim();
                if (bucket.isEmpty()) {
                    bucket = env("MINIO_BUCKET_NAME");
                }
                if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucket.isEmpty()) {
                    return new FactoryResult(null, p, "incomplete minio config");
                }
                // Go 此处构造 MinIO SDK 客户端（未翻译层）——完备配置的云连通是部署态
                return cloudUnavailable(p);
            }
            case "cos": {
                JsonNode c = sec == null ? null : sec.get("cos");
                if (c == null || textOr(c.get("secret_id"), "").isEmpty() || textOr(c.get("secret_key"), "").isEmpty()
                        || textOr(c.get("bucket_name"), "").isEmpty() || textOr(c.get("region"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete cos config");
                }
                return cloudUnavailable(p);
            }
            case "tos": {
                JsonNode t = sec == null ? null : sec.get("tos");
                if (t == null || textOr(t.get("endpoint"), "").isEmpty() || textOr(t.get("region"), "").isEmpty()
                        || textOr(t.get("access_key"), "").isEmpty() || textOr(t.get("secret_key"), "").isEmpty()
                        || textOr(t.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete tos config");
                }
                return cloudUnavailable(p);
            }
            case "s3": {
                JsonNode s = sec == null ? null : sec.get("s3");
                if (s == null || textOr(s.get("region"), "").isEmpty() || textOr(s.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete s3 config");
                }
                boolean hasKey = !textOr(s.get("access_key"), "").isEmpty();
                boolean hasSecret = !textOr(s.get("secret_key"), "").isEmpty();
                if (hasKey != hasSecret) {
                    return new FactoryResult(null, p, "incomplete s3 config");
                }
                return cloudUnavailable(p);
            }
            case "obs": {
                JsonNode o = sec == null ? null : sec.get("obs");
                String endpoint = o != null ? textOr(o.get("endpoint"), "").trim() : "";
                String accessKey = o != null ? textOr(o.get("access_key"), "").trim() : "";
                String secretKey = o != null ? textOr(o.get("secret_key"), "").trim() : "";
                String bucket = o != null ? textOr(o.get("bucket_name"), "").trim() : "";
                if (endpoint.isEmpty()) {
                    endpoint = env("OBS_ENDPOINT");
                }
                if (accessKey.isEmpty()) {
                    accessKey = env("OBS_ACCESS_KEY");
                }
                if (secretKey.isEmpty()) {
                    secretKey = env("OBS_SECRET_KEY");
                }
                if (bucket.isEmpty()) {
                    bucket = env("OBS_BUCKET_NAME");
                }
                if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucket.isEmpty()) {
                    return new FactoryResult(null, p, "incomplete obs config");
                }
                return cloudUnavailable(p);
            }
            case "oss": {
                JsonNode o = sec == null ? null : sec.get("oss");
                if (o == null || textOr(o.get("endpoint"), "").isEmpty() || textOr(o.get("region"), "").isEmpty()
                        || textOr(o.get("access_key"), "").isEmpty() || textOr(o.get("secret_key"), "").isEmpty()
                        || textOr(o.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete oss config");
                }
                return cloudUnavailable(p);
            }
            case "ks3": {
                JsonNode k = sec == null ? null : sec.get("ks3");
                if (k == null || textOr(k.get("endpoint"), "").isEmpty() || textOr(k.get("region"), "").isEmpty()
                        || textOr(k.get("access_key"), "").isEmpty() || textOr(k.get("secret_key"), "").isEmpty()
                        || textOr(k.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete ks3 config");
                }
                return cloudUnavailable(p);
            }
            default:
                return new FactoryResult(null, p, "unsupported provider \"" + p + "\"");
        }
    }

    private static FactoryResult cloudUnavailable(String p) {
        // ⚠️ 已知差异（XDEP）：Go 会继续构造 SDK 客户端并成功返回服务。
        // 该分支只在"配置完备的云后端"部署可达——dev 恒 local。错误文案非 Go 原文，
        // 调用方会把 resolution 失败折成 400（Go 会给出 200/预签名 URL）。
        return new FactoryResult(null, p, "cloud storage provider SDK is not available in this build");
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String textOr(JsonNode n, String def) {
        return n == null || n.isNull() ? def : n.asText();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v.trim();
    }

    // ── StorageBackend.ToStorageEngineConfig / FromEnvironment ─────────────

    /** 对照 Go {@code StorageBackend.ToStorageEngineConfig}：实例模型 → 单例配置投影。 */
    static JsonNode toStorageEngineConfig(StorageBackend b) {
        var cfg = new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().getNodeFactory());
        cfg.put("default_provider", b.getProvider());
        JsonNode c = b.getConfig();
        switch (b.getProvider() == null ? "" : b.getProvider()) {
            case "local" -> {
                var local = cfg.putObject("local");
                local.put("path_prefix", textValue(c, "path_prefix"));
            }
            default -> {
                // 云 provider 的投影随 SDK 层回补；local 之外 W5c 只需 default_provider
                // 与 provider 段（完备性检查读 sec.<provider>.* —— 从实例行原样回挂）。
                if (c != null) {
                    cfg.set(b.getProvider(), c.deepCopy());
                }
            }
        }
        return cfg;
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** 对照 Go {@code StorageBackendFromEnvironment}：env 快照的 System 只读行。 */
    static StorageBackend storageBackendFromEnvironment(long tenantId) {
        String provider = env("STORAGE_TYPE").toLowerCase(java.util.Locale.ROOT);
        if (provider.isEmpty()) {
            provider = "local";
        }
        StorageBackend b = new StorageBackend();
        b.setTenantId(tenantId);
        b.setName("System " + provider.toUpperCase(java.util.Locale.ROOT));
        b.setProvider(provider);
        b.setSource("env");
        b.setStatus("active");
        b.setLegacyAlias(true);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cfg = mapper.createObjectNode();
        switch (provider) {
            case "local" -> cfg.put("path_prefix", env("LOCAL_STORAGE_PATH_PREFIX"));
            case "minio" -> {
                cfg.put("mode", "remote");
                cfg.put("endpoint", env("MINIO_ENDPOINT"));
                cfg.put("access_key_id", env("MINIO_ACCESS_KEY_ID"));
                cfg.put("secret_access_key", env("MINIO_SECRET_ACCESS_KEY"));
                cfg.put("bucket_name", env("MINIO_BUCKET_NAME"));
                cfg.put("path_prefix", env("MINIO_PATH_PREFIX"));
                cfg.put("use_ssl", "true".equalsIgnoreCase(env("MINIO_USE_SSL")));
            }
            case "s3" -> {
                cfg.put("endpoint", env("S3_ENDPOINT"));
                cfg.put("region", env("S3_REGION"));
                cfg.put("access_key", env("S3_ACCESS_KEY"));
                cfg.put("secret_key", env("S3_SECRET_KEY"));
                cfg.put("bucket_name", env("S3_BUCKET_NAME"));
                cfg.put("path_prefix", env("S3_PATH_PREFIX"));
                cfg.put("use_ssl", !"false".equalsIgnoreCase(env("S3_USE_SSL")));
                cfg.put("force_path_style", "true".equalsIgnoreCase(env("S3_FORCE_PATH_STYLE")));
            }
            // 其余云 provider 的 env 投影随 SDK 层回补（dev 只 local）
            default -> {
                return null;
            }
        }
        b.setConfig(cfg);
        return b;
    }

    // ── resolve_tenant.go ResolveTenantFileServiceWithFallback ──────────────

    /**
     * 对照 Go {@code ResolveTenantFileServiceWithFallback}：租户级解析失败且
     * provider == 全局 STORAGE_TYPE 时回落进程级服务；否则 ok=false（调用方 400）。
     */
    public Resolution resolveTenantFileServiceWithFallback(String logTag, Tenant tenant,
            String backendId, String provider, String absDir, FileContentService globalFileService) {
        Resolution resolution = resolveFileService(tenant, backendId, provider, absDir);
        if (resolution.ok()) {
            return resolution;
        }
        String globalStorageType = StoragePaths.globalStorageType();
        if (provider != null && provider.equals(globalStorageType) && globalFileService != null) {
            log.warn("[Router] {} tenant storage config missing or invalid, fallback to global file "
                    + "service: tenant_id={} provider={} err={}", logTag, tenant.getId(), provider,
                    resolution.error());
            return new Resolution(new DecoratedFileService(globalFileService, catalog), globalStorageType, null);
        }
        log.warn("[Router] {} resolve file service failed without fallback: tenant_id={} provider={} "
                + "global_storage_type={} err={}", logTag, tenant.getId(), provider, globalStorageType,
                resolution.error());
        return new Resolution(null, "", resolution.error());
    }

    // ── resourceCatalogFileService 装饰器 ───────────────────────────────────

    /**
     * 对照 Go {@code resourceCatalogFileService}：GetFile 先把手柄换成物理路径；
     * GetFileURL 对手柄在外部 URL 在位时派生 /r/ 令牌。
     */
    private record DecoratedFileService(FileContentService inner, ResourceCatalogService catalog)
            implements FileContentService {

        @Override
        public FileTransport.OpenedFile getFile(String filePath) throws IOException {
            ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(filePath);
            if (resolved.error()) {
                throw new IOException("resource not found");
            }
            return inner.getFile(resolved.physicalPath());
        }

        @Override
        public String getFileURL(String filePath) throws IOException {
            ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(filePath);
            if (resolved.error()) {
                throw new IllegalStateException("resource not found");
            }
            String physical = resolved.physicalPath();
            boolean isResource = resolved.resource() != null;
            String externalURL = env("APP_EXTERNAL_URL").replaceAll("/+$", "");
            if (isResource && !externalURL.isEmpty()) {
                var token = catalog.createAccessGrant(filePath, java.time.Duration.ofHours(2));
                if (token.isPresent()) {
                    return externalURL + "/r/" + token.get();
                }
                throw new IllegalStateException("failed to allocate resource access token");
            }
            return inner.getFileURL(physical);
        }
    }

    /**
     * 生产装配的进程级默认服务（对照 Go container 的 globalFileService，恒 local 基座）。
     * baseDir 经 Spring 属性注入（env 缺省，测试期可注入——见 FileProxyService）。
     */
    public FileContentService globalFileService(String localBaseDir) {
        return decorate(new LocalFileContentService(localBaseDir, env("APP_EXTERNAL_URL")));
    }

    /** 测试/装饰辅助：给定 inner 的 resource:// 装饰视图。 */
    public FileContentService decorate(FileContentService inner) {
        return new DecoratedFileService(inner, catalog);
    }
}
