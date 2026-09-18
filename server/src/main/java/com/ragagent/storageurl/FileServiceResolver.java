package com.ragagent.storageurl;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;

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
 * <h2>⚠️ 已知差异：provider 级文件服务未翻译</h2>
 * <p>Go 的 {@code BuildFileServiceForProvider} 第一步是
 * {@code filesvc.NewFileServiceFromStorageConfig(provider, …)}——它会为
 * local/minio/s3/cos/tos/oss/obs/ks3 造出各自的 SDK 客户端。**那一整层（20+ 文件 + 各家云 SDK）
 * 尚未翻译**，故本类在走到那一步时返回调用方给的 {@code defaultSvc}（可能为 null）。</p>
 *
 * <p>这带来的可见行为是：<b>所有 provider 引用都解析不出 HTTP URL，于是被
 * {@link Rewriter} 原样保留成 handle</b>。这与 Go 在"未配置 {@code APP_EXTERNAL_URL}"的部署里
 * 的表现**逐字节一致**（Go 那时拿到的是 {@code local://…}，同样不是 http(s)，同样原样保留），
 * 差别仅在于日志里那句 WARN 的措辞。真实的公网 URL 生成要在存储后端模块落地后补。</p>
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
     * 对照 Go {@code BuildFileServiceForProvider} 的**可翻译部分**。
     *
     * <p>引用自带的 scheme 优先于租户的 {@code DefaultProvider}；租户配置缺失时回落到
     * 进程级默认 FileService。Go 的第一步（按 provider 造云 SDK 客户端）未翻译，见类注释。</p>
     */
    static FileService buildFileServiceForProvider(String provider, FileService defaultSvc) {
        // Go: svc, _, err := filesvc.NewFileServiceFromStorageConfig(provider, sec, baseDir)
        //     if err == nil { return svc }        ← 这一层未翻译，恒不可达
        // Go: if provider == "local" { return filesvc.NewLocalFileService(baseDir, externalURL) }
        //     externalURL 为空时它返回 local://…（非 http(s) → Rewriter 保留 handle）。
        //     Java 无该实现，直接落到 defaultSvc，**最终可见行为相同**：引用原样保留。
        return defaultSvc;
    }

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
