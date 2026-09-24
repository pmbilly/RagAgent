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
 * <h2>云 provider 的 SDK 客户端（2026-09-24 A3-3 接线）</h2>
 * <p>配置完备的 minio/cos/tos/s3/oss/obs/ks3 经 {@code FileServiceFactory}
 * （A3 的八个 provider 实现：local + S3 协议族 + 三家厂商原生 SDK）造真实客户端，
 * 再由 {@link ProviderFileContentService} 适配回本包的 {@link FileContentService}。
 * 配置<b>不完备</b>时的错误文案仍由工厂逐字产出
 * （"missing minio config" / "incomplete cos config" / {@code unsupported provider "%s"}）。
 * local 一支仍走 W5c 的 {@link LocalFileContentService}（本地语义完全一致，二者都照
 * Go local.go；收敛为单一实现属清理项，不在本批）。</p>
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
        if (tenant.getId() == 0 || backendRepo == null) {
            // 仓储缺位（纯单元测试构造）时跳过补全：本方法只是"从库行补 stub 字段"的优化，
            // 补不了就按调用方给的实体走。
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
                return providerBacked(p, sec, baseDir);
            }
            case "cos": {
                JsonNode c = sec == null ? null : sec.get("cos");
                if (c == null || textOr(c.get("secret_id"), "").isEmpty() || textOr(c.get("secret_key"), "").isEmpty()
                        || textOr(c.get("bucket_name"), "").isEmpty() || textOr(c.get("region"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete cos config");
                }
                return providerBacked(p, sec, baseDir);
            }
            case "tos": {
                JsonNode t = sec == null ? null : sec.get("tos");
                if (t == null || textOr(t.get("endpoint"), "").isEmpty() || textOr(t.get("region"), "").isEmpty()
                        || textOr(t.get("access_key"), "").isEmpty() || textOr(t.get("secret_key"), "").isEmpty()
                        || textOr(t.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete tos config");
                }
                return providerBacked(p, sec, baseDir);
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
                return providerBacked(p, sec, baseDir);
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
                return providerBacked(p, sec, baseDir);
            }
            case "oss": {
                JsonNode o = sec == null ? null : sec.get("oss");
                if (o == null || textOr(o.get("endpoint"), "").isEmpty() || textOr(o.get("region"), "").isEmpty()
                        || textOr(o.get("access_key"), "").isEmpty() || textOr(o.get("secret_key"), "").isEmpty()
                        || textOr(o.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete oss config");
                }
                return providerBacked(p, sec, baseDir);
            }
            case "ks3": {
                JsonNode k = sec == null ? null : sec.get("ks3");
                if (k == null || textOr(k.get("endpoint"), "").isEmpty() || textOr(k.get("region"), "").isEmpty()
                        || textOr(k.get("access_key"), "").isEmpty() || textOr(k.get("secret_key"), "").isEmpty()
                        || textOr(k.get("bucket_name"), "").isEmpty()) {
                    return new FactoryResult(null, p, "incomplete ks3 config");
                }
                return providerBacked(p, sec, baseDir);
            }
            default:
                return new FactoryResult(null, p, "unsupported provider \"" + p + "\"");
        }
    }

    /**
     * 配置完备的云 provider → 经 A3 的 {@code FileServiceFactory} 造真实 SDK 客户端
     * （2026-09-24 A3-3 接线；此前恒返回 {@code cloudUnavailable} 错误）。
     *
     * <p>完备性校验的文案由工厂产出（与上面各分支的字符串一致，逐字照 Go），因此
     * 这里不需要重复校验；{@code IllegalArgumentException} 就是"配置不完整"的通道。
     * 构造期的凭据/网络失败（真连桶）折成同一 error 通道——调用方按各自映射处理
     * （presigned-preview 折 400），与 Go 的 {@code NewFileService*} 返回 err 同形。</p>
     */
    private static FactoryResult providerBacked(String p, JsonNode sec, String baseDir) {
        ProviderResolution pr = buildProviderRaw(p, sec, baseDir);
        if (!pr.ok()) {
            return new FactoryResult(null, pr.provider(), pr.error());
        }
        return new FactoryResult(new ProviderFileContentService(pr.service()), pr.provider(), null);
    }

    // ── 写面（A3-3 尾批）：知识上传/读取用的**原始** provider 服务 ──────────────

    /** provider 服务的原始解析结果（未经 resource catalog 装饰——写面用）。 */
    public record ProviderResolution(com.ragagent.storage.provider.FileService service,
                                     String provider, String error) {
        public boolean ok() {
            return error == null && service != null;
        }
    }

    /**
     * 对照 Go {@code ResolveTenantFileServiceWithFallback} 的写面：按
     * backend 优先 → 环境回归 → 租户 default_provider 的顺序解析出**原始** provider
     * 服务（知识上传的 {@code SaveFile} / 读取的 {@code GetFile} 直连它，不经过
     * resource catalog 装饰——装饰层是给 HTTP 流式面用的）。
     *
     * <p>{@code local} 或 provider 为空时 {@code service == null} 且 {@code error == null}：
     * 调用方走 Java 既有的本地契约（{@code resource://…} + {@code LocalStorageService}），
     * 那是 golden 锁定的落盘形态，不能换成 provider 的 {@code local://…}。</p>
     */
    public ProviderResolution resolveProviderService(Tenant tenant, String backendId, String provider,
            String localBaseDir) {
        if (tenant == null) {
            return new ProviderResolution(null, "", "workspace context missing");
        }
        Tenant hydrated = hydrateTenantStorage(tenant);
        BackendResolution backend = resolveBackend(hydrated, backendId, provider);
        if (backend.error() != null) {
            return new ProviderResolution(null, "", backend.error());
        }
        if (backend.backend() != null) {
            return buildProviderRaw(backend.backend().getProvider(),
                    toStorageEngineConfig(backend.backend()), localBaseDir);
        }
        JsonNode sec = hydrated.getStorageEngineConfig();
        String p0 = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (p0.isEmpty() && storageEngineDefaultProvider(sec).isEmpty()) {
            StorageBackend env = storageBackendFromEnvironment(hydrated.getId());
            if (env != null) {
                p0 = env.getProvider();
                if (sec == null) {
                    sec = toStorageEngineConfig(env);
                }
            }
        }
        return buildProviderRaw(p0, sec, localBaseDir);
    }

    /** {@code local}/空 provider → (null, provider, null)；云 → 真客户端或错误文案。 */
    private static ProviderResolution buildProviderRaw(String provider, JsonNode sec, String baseDir) {
        String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty() && sec != null) {
            p = storageEngineDefaultProvider(sec);
        }
        if (p.isEmpty()) {
            return new ProviderResolution(null, "", "empty provider");
        }
        if ("local".equals(p)) {
            return new ProviderResolution(null, p, null);
        }
        String dir = baseDir == null || baseDir.isEmpty()
                ? StoragePaths.localStorageBaseDir() : baseDir;
        try {
            com.ragagent.auth.domain.tenantconfig.StorageEngineConfig typed = sec == null
                    ? new com.ragagent.auth.domain.tenantconfig.StorageEngineConfig()
                    : CONFIG_MAPPER.convertValue(sec,
                            com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.class);
            com.ragagent.storage.provider.FileServiceFactory.Created created =
                    com.ragagent.storage.provider.FileServiceFactory.fromStorageConfig(p, typed, dir);
            if (created == null || created.service() == null) {
                return new ProviderResolution(null, p, "unsupported provider \"" + p + "\"");
            }
            return new ProviderResolution(created.service(), p, null);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("build provider file service failed: provider={} err={}", p, msg);
            return new ProviderResolution(null, p, msg);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CONFIG_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature
                            .FAIL_ON_UNKNOWN_PROPERTIES, false);

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
            case "cos" -> {
                cfg.put("secret_id", env("COS_SECRET_ID"));
                cfg.put("secret_key", env("COS_SECRET_KEY"));
                cfg.put("region", env("COS_REGION"));
                cfg.put("bucket_name", env("COS_BUCKET_NAME"));
                cfg.put("app_id", env("COS_APP_ID"));
                cfg.put("path_prefix", env("COS_PATH_PREFIX"));
                cfg.put("temp_bucket_name", env("COS_TEMP_BUCKET_NAME"));
                cfg.put("temp_region", env("COS_TEMP_REGION"));
            }
            case "tos" -> {
                cfg.put("endpoint", env("TOS_ENDPOINT"));
                cfg.put("region", env("TOS_REGION"));
                cfg.put("access_key", env("TOS_ACCESS_KEY"));
                cfg.put("secret_key", env("TOS_SECRET_KEY"));
                cfg.put("bucket_name", env("TOS_BUCKET_NAME"));
                cfg.put("path_prefix", env("TOS_PATH_PREFIX"));
                cfg.put("temp_bucket_name", env("TOS_TEMP_BUCKET_NAME"));
                cfg.put("temp_region", env("TOS_TEMP_REGION"));
            }
            case "oss" -> {
                cfg.put("endpoint", env("OSS_ENDPOINT"));
                cfg.put("region", env("OSS_REGION"));
                cfg.put("access_key", env("OSS_ACCESS_KEY"));
                cfg.put("secret_key", env("OSS_SECRET_KEY"));
                cfg.put("bucket_name", env("OSS_BUCKET_NAME"));
                cfg.put("path_prefix", env("OSS_PATH_PREFIX"));
                // 对照 Go：没有独立的 use_temp_bucket env，非空临时桶名即启用
                cfg.put("use_temp_bucket", !env("OSS_TEMP_BUCKET_NAME").isEmpty());
                cfg.put("temp_bucket_name", env("OSS_TEMP_BUCKET_NAME"));
                cfg.put("temp_region", env("OSS_TEMP_REGION"));
            }
            case "obs" -> {
                cfg.put("endpoint", env("OBS_ENDPOINT"));
                cfg.put("region", env("OBS_REGION"));
                cfg.put("access_key", env("OBS_ACCESS_KEY"));
                cfg.put("secret_key", env("OBS_SECRET_KEY"));
                cfg.put("bucket_name", env("OBS_BUCKET_NAME"));
                cfg.put("path_prefix", env("OBS_PATH_PREFIX"));
                cfg.put("use_ssl", !"false".equalsIgnoreCase(env("OBS_USE_SSL")));
            }
            // 其余 provider（如 ks3）：Go 的 StorageBackendFromEnvironment 也没有 case
            // （走 default），此处保持同形——返回 null 表示"环境里没有这个 provider 的快照"。
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
            implements WritableFileContentService {

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

        /**
         * 对照 Go {@code resourceCatalogFileService.SaveBytes}：物理落盘 →
         * SHA-256 内容哈希 → 资源注册（失败回删物理文件）→ 返回 resource:// 手柄。
         */
        @Override
        public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp)
                throws IOException {
            String physical;
            if (inner instanceof WritableFileContentService writable) {
                physical = writable.saveBytes(data, tenantId, fileName, temp);
            } else {
                throw new IOException("storage provider does not support saving bytes");
            }
            String hash = sha256Hex(data);
            try {
                String kind = resourceKind(fileName);
                String mimeType = probeMimeType(fileName);
                return catalog.register(tenantId, physical, new ResourceCatalogService.ResourceRegistration(
                        kind, mimeType, baseName(fileName), data.length, hash, temp));
            } catch (RuntimeException e) {
                if (inner instanceof WritableFileContentService w) {
                    try {
                        w.deleteFile(physical);
                    } catch (IOException ignored) {
                        // 对照 Go：register 失败尽力回删物理文件
                    }
                }
                throw new IOException("register stored resource: " + e.getMessage(), e);
            }
        }

        /** 对照 Go {@code resourceCatalogFileService.DeleteFile}：物理删除 + 资源软删。 */
        @Override
        public void deleteFile(String filePath) throws IOException {
            ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(filePath);
            if (resolved.error()) {
                throw new IOException("resource not found");
            }
            if (!(inner instanceof WritableFileContentService writable)) {
                throw new IOException("storage provider does not support deleting files");
            }
            writable.deleteFile(resolved.physicalPath());
            if (resolved.resource() != null) {
                try {
                    catalog.markDeleted(filePath);
                } catch (RuntimeException e) {
                    throw new IOException("mark resource deleted: " + e.getMessage(), e);
                }
            }
        }

        private static String sha256Hex(byte[] data) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(data)) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 对照 Go resourceKind：mime 前缀 → image/audio/video，否则 file。 */
        private static String resourceKind(String name) {
            String mimeType = probeMimeType(name);
            if (mimeType.startsWith("image/")) {
                return "image";
            }
            if (mimeType.startsWith("audio/")) {
                return "audio";
            }
            if (mimeType.startsWith("video/")) {
                return "video";
            }
            return "file";
        }

        /** mime.TypeByExtension 的有界版：内置表 + Files 探测，未知 → ""。 */
        private static String probeMimeType(String name) {
            String ext = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
            int dot = ext.lastIndexOf('.');
            ext = dot < 0 ? "" : ext.substring(dot);
            String builtIn = switch (ext) {
                case ".html", ".htm" -> "text/html; charset=utf-8";
                case ".css" -> "text/css; charset=utf-8";
                case ".js", ".mjs" -> "text/javascript; charset=utf-8";
                case ".json" -> "application/json";
                case ".pdf" -> "application/pdf";
                case ".txt" -> "text/plain; charset=utf-8";
                case ".md", ".markdown" -> "text/markdown; charset=utf-8";
                case ".svg" -> "image/svg+xml";
                case ".png" -> "image/png";
                case ".jpg", ".jpeg" -> "image/jpeg";
                case ".gif" -> "image/gif";
                case ".webp" -> "image/webp";
                case ".avif" -> "image/avif";
                case ".wasm" -> "application/wasm";
                case ".xml" -> "text/xml; charset=utf-8";
                default -> null;
            };
            if (builtIn != null) {
                return builtIn;
            }
            try {
                String probed = java.nio.file.Files.probeContentType(java.nio.file.Path.of("f" + ext));
                return probed == null ? "" : probed;
            } catch (IOException e) {
                return "";
            }
        }

        private static String baseName(String name) {
            String n = name == null ? "" : name;
            int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
            return slash < 0 ? n : n.substring(slash + 1);
        }
    }

    /**
     * 生产装配的进程级默认服务（对照 Go container 的 globalFileService，恒 local 基座）。
     * baseDir 经 Spring 属性注入（env 缺省，测试期可注入——见 FileProxyService）。
     */
    public WritableFileContentService globalFileService(String localBaseDir) {
        return decorate(new LocalFileContentService(localBaseDir, env("APP_EXTERNAL_URL")));
    }

    /** 测试/装饰辅助：给定 inner 的 resource:// 装饰视图（读+写）。 */
    public WritableFileContentService decorate(FileContentService inner) {
        return new DecoratedFileService(inner, catalog);
    }
}
