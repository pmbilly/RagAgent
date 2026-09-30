package com.ragagent.storage.support;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.storage.provider.FileServiceFactory;

/**
 * 按存储 provider 解析并缓存 FileService（对照 Go
 * {@code internal/storageurl/resolver.go}）。
 *
 * <p>缓存的作用域是**单个请求或单条出站消息**，这样一条有很多图的长回答不会为每条引用
 * 重建一次 SDK 客户端。</p>
 *
 * <p><b>非并发安全</b>；由 {@link Rewriter} 一次一个 goroutine/线程地驱动
 * （Rewriter 的锁横跨整次 resolve）。</p>
 *
 * <h2>provider 级文件服务（2026-09-24 A3-3 接线）</h2>
 * <p>Go 的 {@code BuildFileServiceForProvider} 第一步是
 * {@code filesvc.NewFileServiceFromStorageConfig(provider, …)}——为
 * local/minio/s3/cos/tos/oss/obs/ks3 造各自的 SDK 客户端。该层现由 A3 的
 * {@code FileServiceFactory} 承担（八个 provider 全落地），本类按 Go 的兜底顺序
 * （真实服务 → local → {@code defaultSvc}）取用，于是配了云后端的租户引用能被换成
 * 真 HTTP 预签名 URL；未配置/解析失败时仍按 handle 原样保留（与 Go 未配置
 * {@code APP_EXTERNAL_URL} 的部署逐字节一致）。</p>
 */
public class FileServiceResolver implements Resolver {

    private static final Logger log = LoggerFactory.getLogger(FileServiceResolver.class);

    /** {@code resource://} 手柄的固定长度（对照 Go {@code types.ResourceHandleLength}）。 */
    private static final int RESOURCE_HANDLE_LENGTH = 22;
    private static final String RESOURCE_SCHEME = "resource://";
    private static final String STORAGE_BACKEND_SCHEME = "storage://";

    /** 认得的 provider scheme（对照 Go {@code ParseProviderScheme} 的列表，顺序有语义）。 */
    private static final String[] PROVIDERS =
            {"local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs", "dummy"};

    /**
     * 本地存储的磁盘根（对照 Go {@code LocalStorageBaseDir}）——读 env
     * {@code LOCAL_STORAGE_BASE_DIR}，缺省 {@code /data/files}。
     */
    public static String localStorageBaseDir() {
        String baseDir = System.getenv("LOCAL_STORAGE_BASE_DIR");
        if (baseDir == null || baseDir.isBlank()) {
            return "/data/files";
        }
        return baseDir.trim();
    }

    private final Tenant tenant;
    private final FileService defaultSvc;
    private final StorageBackendResolver storageResolver;
    private final Map<String, FileService> cache = new HashMap<>();

    /**
     * 对照 Go {@code NewFileServiceResolver(tenant, defaultSvc, storageResolvers...)}。
     *
     * <p>{@code defaultSvc} 是进程级 FileService，用于 {@code resource://} 手柄，
     * 以及租户存储配置缺失时的兜底。</p>
     */
    public FileServiceResolver(Tenant tenant, FileService defaultSvc, StorageBackendResolver storageResolver) {
        this.tenant = tenant;
        this.defaultSvc = defaultSvc;
        this.storageResolver = storageResolver;
    }

    /** 对照 Go {@code FileServiceResolver.ResolveFileService}。 */
    @Override
    public FileService resolveFileService(String filePath) {
        if (parseResourcePath(filePath)) {
            return defaultSvc;
        }
        String backendId = parseStorageBackendId(filePath);

        String provider = parseProviderScheme(filePath);
        if (provider.isEmpty()) {
            JsonNode config = tenant == null ? null : tenant.getStorageEngineConfig();
            if (config != null && config.isObject()) {
                JsonNode defaultProvider = config.get("default_provider");
                if (defaultProvider != null && defaultProvider.isTextual()) {
                    provider = defaultProvider.asText().trim().toLowerCase(Locale.ROOT);
                }
            }
            if (provider.isEmpty()) {
                return null;
            }
        }

        String cacheKey = backendId + ":" + provider;
        FileService cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (storageResolver != null && tenant != null) {
            try {
                StorageBackendResolver.Resolved resolved = storageResolver.resolveFileService(
                        tenant.getId() == null ? 0L : tenant.getId(), backendId, provider, localStorageBaseDir());
                if (resolved != null && resolved.fileService() != null) {
                    cache.put(cacheKey, resolved.fileService());
                    return resolved.fileService();
                }
            } catch (RuntimeException e) {
                log.warn("resolve storage backend failed: backend_id={} provider={} err={}",
                        backendId, provider, e.toString());
            }
        }
        FileService service = buildFileServiceForProvider(provider, defaultSvc);
        if (service != null) {
            cache.put(cacheKey, service);
        }
        return service;
    }

    /**
     * 对照 Go {@code BuildFileServiceForProvider(tenant, provider, defaultSvc)}
     * （2026-09-24 A3-3 接线，此前恒返回 defaultSvc）：
     *
     * <ol>
     *   <li>按 provider + 租户存储配置造真实服务（A3 的 {@code FileServiceFactory}，
     *       八个 provider 全落地：local + S3 协议族 + oss/cos/tos）——成功即用，
     *       于是引用能被换成真 HTTP URL（s3 族/三家云的预签名 URL）；</li>
     *   <li>provider == {@code "local"}：Go 返回本地服务（externalURL 为空时给出
     *       {@code local://…} 非 http → handle 原样保留）。Java 的等价物就是
     *       {@code defaultSvc}（调用方传入的进程级服务），故不额外造；</li>
     *   <li>其余失败 → 回落 {@code defaultSvc}（可能为 null → 引用原样保留）。</li>
     * </ol>
     */
    private FileService buildFileServiceForProvider(String provider, FileService defaultSvc) {
        try {
            JsonNode sec = tenant == null ? null : tenant.getStorageEngineConfig();
            StorageEngineConfig typed = sec == null ? new StorageEngineConfig()
                    : CONFIG_MAPPER.convertValue(sec, StorageEngineConfig.class);
            FileServiceFactory.Created created = FileServiceFactory.fromStorageConfig(
                    provider, typed, localStorageBaseDir());
            if (created != null && created.service() != null) {
                return new ProviderUrlFileService(created.service());
            }
        } catch (RuntimeException e) {
            log.warn("build file service for provider failed: provider={} err={}",
                    provider, e.toString());
        }
        return defaultSvc;
    }

    /** storageurl 窄口（只有 GetFileURL）到 A3 provider 服务的适配。 */
    private record ProviderUrlFileService(com.ragagent.storage.provider.FileService inner)
            implements FileService {

        @Override
        public String getFileURL(String filePath) {
            return inner.getFileURL(filePath);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CONFIG_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature
                            .FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 对照 Go {@code types.ParseResourcePath}：{@code resource://} 后必须恰好 22 个
     * {@code [A-Za-z0-9_-]} 字符。
     */
    private static boolean parseResourcePath(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith(RESOURCE_SCHEME)) {
            return false;
        }
        String handle = trimmed.substring(RESOURCE_SCHEME.length());
        if (handle.length() != RESOURCE_HANDLE_LENGTH) {
            return false;
        }
        for (int i = 0; i < handle.length(); i++) {
            if (!isResourceHandleChar(handle.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isResourceHandleChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /**
     * 对照 Go {@code types.ParseStorageBackendPath}：解析 {@code storage://<id>/<providerPath>}，
     * 只取 id（providerPath 在本包用不到）。
     */
    private static String parseStorageBackendId(String path) {
        if (path == null || !path.startsWith(STORAGE_BACKEND_SCHEME)) {
            return "";
        }
        String rest = path.substring(STORAGE_BACKEND_SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return "";
        }
        return rest.substring(0, slash);
    }

    /**
     * 对照 Go {@code types.ParseProviderScheme}：先剥掉 {@code storage://<id>/} 前缀，
     * 再按已知 provider 列表前缀匹配。列表**顺序有语义**（Go 里就是按这个数组顺序试的）。
     */
    private static String parseProviderScheme(String filePath) {
        String candidate = filePath == null ? "" : filePath;
        if (candidate.startsWith(STORAGE_BACKEND_SCHEME)) {
            String rest = candidate.substring(STORAGE_BACKEND_SCHEME.length());
            int slash = rest.indexOf('/');
            if (slash > 0 && slash < rest.length() - 1) {
                candidate = rest.substring(slash + 1);
            }
        }
        for (String provider : PROVIDERS) {
            if (candidate.startsWith(provider + "://")) {
                return provider;
            }
        }
        return "";
    }
}
