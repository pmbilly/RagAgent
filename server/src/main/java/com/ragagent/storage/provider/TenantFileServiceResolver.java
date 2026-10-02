package com.ragagent.storage.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.common.storage.StorageRuntimeEnv;

/**
 * 租户级文件服务解析 + 回退（对照 Go
 * {@code ResolveTenantFileServiceWithFallback}，file/resolve_tenant.go 全文）。
 *
 * <p>规则：先按租户配置解析（{@code backendId/provider} 指定，缺失则用租户默认）；
 * 解析失败时，<b>若请求的 provider 与进程级 {@code STORAGE_TYPE} 相同</b>，用全局文件服务兜底
 * （记 WARN，带上租户/ provider / 原因）——否则返回 not-ok，由调用方决定 HTTP 状态。</p>
 */
public final class TenantFileServiceResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantFileServiceResolver.class);

    /** 对照 Go：进程级默认存储类型。 */
    public static final String ENV_STORAGE_TYPE = "STORAGE_TYPE";
    public static final String DEFAULT_STORAGE_TYPE = "local";

    /** 租户解析接缝（对照 {@code interfaces.StorageBackendResolver.ResolveFileService}）。 */
    public interface TenantResolver {
        /**
         * @return 解析结果；失败抛异常（Go 的 {@code (…, err)} 折叠为异常）
         */
        FileServiceFactory.Created resolve(long tenantId, String backendId, String provider,
                                           String localBaseDir);
    }

    /** 回退结果：{@code ok=false} 表示无可用服务（调用方决定 HTTP 状态）。 */
    public record Fallback(FileService service, String provider, boolean ok) {
    }

    private TenantFileServiceResolver() {
    }

    public static Fallback resolveWithFallback(String logTag, long tenantId,
                                               StorageEngineConfig tenantConfig,
                                               String backendId, String provider, String absDir,
                                               TenantResolver storageResolver,
                                               FileService globalFileService) {
        FileServiceFactory.Created created;
        try {
            if (storageResolver != null) {
                created = storageResolver.resolve(tenantId, backendId, provider, absDir);
            } else if (tenantConfig != null) {
                created = FileServiceFactory.fromStorageConfig(provider, tenantConfig, absDir);
            } else {
                created = null;
            }
        } catch (RuntimeException e) {
            created = null;
            log.debug("[Router] {} tenant resolution failed: tenant_id={} provider={} err={}",
                    logTag, tenantId, provider, e.toString());
        }
        if (created != null && created.service() != null) {
            return new Fallback(created.service(), created.provider(), true);
        }

        String globalType = globalStorageType();
        String requested = provider == null ? "" : provider.trim().toLowerCase();
        if (requested.equals(globalType) && globalFileService != null) {
            log.warn("[Router] {} tenant storage config missing or invalid, fallback to global "
                    + "file service: tenant_id={} provider={}", logTag, tenantId, provider);
            return new Fallback(globalFileService, globalType, true);
        }
        log.warn("[Router] {} resolve file service failed without fallback: tenant_id={} "
                        + "provider={} global_storage_type={}",
                logTag, tenantId, provider, globalType);
        return new Fallback(null, "", false);
    }

    /** 对照 Go：{@code STORAGE_TYPE} 小写归一，空 → {@code local}。 */
    public static String globalStorageType() {
        String v = StorageRuntimeEnv.storageType();
        if (v == null || v.trim().isEmpty()) {
            return DEFAULT_STORAGE_TYPE;
        }
        return v.trim().toLowerCase();
    }
}
