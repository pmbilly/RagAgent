package com.ragagent.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.storage.StorageBackendProvisioner;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.mapper.StorageBackendRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * {@link StorageBackendProvisioner} 的实现——「env 快照 → 存储后端实体/config JSON → 落库」。
 *
 * <p>本类整体由 {@code auth/service/TenantService} 搬来（含 Java↔Go 对照注释与键序契约），
 * 只做了一处形态变化：原先私有的 {@code createDefaultStorageBackend} 被拆成
 * "端口方法 {@link #provisionForTenant} + 事务编排留在 auth"——编排里的租户行回写
 * 不是存储域的职责。</p>
 */
@Component
public class DefaultStorageBackendProvisioner implements StorageBackendProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackendRepository repository;

    public DefaultStorageBackendProvisioner(StorageBackendRepository repository) {
        this.repository = repository;
    }

    @Override
    public String provisionForTenant(long tenantId) {
        StorageBackend backend = envDefaultBackend(tenantId);
        if (backend == null) {
            throw new IllegalStateException("no supported default storage backend is configured");
        }
        backend.setLegacyAlias(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        backend.setCreatedAt(now);
        backend.setUpdatedAt(now);
        repository.create(backend, backend.getConfig() == null
                ? "{}" : backend.getConfig().toString());
        return backend.getId();
    }

    @Override
    public void deleteForTenant(long tenantId, String backendId) {
        repository.delete(tenantId, backendId);
    }

    /**
     * 对照 StorageBackendFromEnvironment（storagebackend.go L329-385）：
     * 进程级 env 快照为该空间落一行只读后端。键序按 Go struct 字段声明序；
     * 空串/零值按 omitempty 省略。STORAGE_TYPE 缺省 "local"；未知 provider → null。
     */
    private static StorageBackend envDefaultBackend(long tenantId) {
        String provider = envTrim("STORAGE_TYPE");
        if (provider.isEmpty()) {
            provider = "local";
        }
        StorageBackend b = new StorageBackend();
        b.setId(java.util.UUID.randomUUID().toString());
        b.setTenantId(tenantId);
        b.setName("System " + provider.toUpperCase(java.util.Locale.ROOT));
        b.setProvider(provider);
        b.setSource("env");
        b.setStatus("active");
        ObjectNode c = MAPPER.createObjectNode();
        switch (provider) {
            case "local" -> putNonEmpty(c, "path_prefix", envTrim("LOCAL_STORAGE_PATH_PREFIX"));
            case "minio" -> {
                putNonEmpty(c, "mode", "remote");
                putNonEmpty(c, "endpoint", System.getenv("MINIO_ENDPOINT"));
                putNonEmpty(c, "access_key_id", System.getenv("MINIO_ACCESS_KEY_ID"));
                putNonEmpty(c, "secret_access_key", System.getenv("MINIO_SECRET_ACCESS_KEY"));
                putNonEmpty(c, "bucket_name", System.getenv("MINIO_BUCKET_NAME"));
                putNonEmpty(c, "path_prefix", System.getenv("MINIO_PATH_PREFIX"));
                putTrue(c, "use_ssl", "true".equalsIgnoreCase(System.getenv("MINIO_USE_SSL")));
            }
            case "cos" -> {
                putNonEmpty(c, "region", System.getenv("COS_REGION"));
                putNonEmpty(c, "access_key_id", System.getenv("COS_SECRET_ID"));
                putNonEmpty(c, "secret_access_key", System.getenv("COS_SECRET_KEY"));
                putNonEmpty(c, "bucket_name", System.getenv("COS_BUCKET_NAME"));
                putNonEmpty(c, "path_prefix", System.getenv("COS_PATH_PREFIX"));
                putNonEmpty(c, "app_id", System.getenv("COS_APP_ID"));
                putNonEmpty(c, "temp_bucket_name", System.getenv("COS_TEMP_BUCKET_NAME"));
                putNonEmpty(c, "temp_region", System.getenv("COS_TEMP_REGION"));
            }
            case "tos" -> {
                putNonEmpty(c, "endpoint", System.getenv("TOS_ENDPOINT"));
                putNonEmpty(c, "region", System.getenv("TOS_REGION"));
                putNonEmpty(c, "access_key_id", System.getenv("TOS_ACCESS_KEY"));
                putNonEmpty(c, "secret_access_key", System.getenv("TOS_SECRET_KEY"));
                putNonEmpty(c, "bucket_name", System.getenv("TOS_BUCKET_NAME"));
                putNonEmpty(c, "path_prefix", System.getenv("TOS_PATH_PREFIX"));
                putNonEmpty(c, "temp_bucket_name", System.getenv("TOS_TEMP_BUCKET_NAME"));
                putNonEmpty(c, "temp_region", System.getenv("TOS_TEMP_REGION"));
            }
            case "s3" -> {
                putNonEmpty(c, "endpoint", System.getenv("S3_ENDPOINT"));
                putNonEmpty(c, "region", System.getenv("S3_REGION"));
                putNonEmpty(c, "access_key_id", System.getenv("S3_ACCESS_KEY"));
                putNonEmpty(c, "secret_access_key", System.getenv("S3_SECRET_KEY"));
                putNonEmpty(c, "bucket_name", System.getenv("S3_BUCKET_NAME"));
                putNonEmpty(c, "path_prefix", System.getenv("S3_PATH_PREFIX"));
                // Go: UseSSL = !EqualFold(env, "false") —— 缺省 true（含未设置）
                putTrue(c, "use_ssl", !"false".equalsIgnoreCase(System.getenv("S3_USE_SSL")));
                putTrue(c, "force_path_style", "true".equalsIgnoreCase(System.getenv("S3_FORCE_PATH_STYLE")));
            }
            case "oss" -> {
                putNonEmpty(c, "endpoint", System.getenv("OSS_ENDPOINT"));
                putNonEmpty(c, "region", System.getenv("OSS_REGION"));
                putNonEmpty(c, "access_key_id", System.getenv("OSS_ACCESS_KEY"));
                putNonEmpty(c, "secret_access_key", System.getenv("OSS_SECRET_KEY"));
                putNonEmpty(c, "bucket_name", System.getenv("OSS_BUCKET_NAME"));
                putNonEmpty(c, "path_prefix", System.getenv("OSS_PATH_PREFIX"));
                String tempBucket = System.getenv("OSS_TEMP_BUCKET_NAME");
                putTrue(c, "use_temp_bucket", tempBucket != null && !tempBucket.isEmpty());
                putNonEmpty(c, "temp_bucket_name", tempBucket);
                putNonEmpty(c, "temp_region", System.getenv("OSS_TEMP_REGION"));
            }
            case "obs" -> {
                putNonEmpty(c, "endpoint", System.getenv("OBS_ENDPOINT"));
                putNonEmpty(c, "region", System.getenv("OBS_REGION"));
                putNonEmpty(c, "access_key_id", System.getenv("OBS_ACCESS_KEY"));
                putNonEmpty(c, "secret_access_key", System.getenv("OBS_SECRET_KEY"));
                putNonEmpty(c, "bucket_name", System.getenv("OBS_BUCKET_NAME"));
                putNonEmpty(c, "path_prefix", System.getenv("OBS_PATH_PREFIX"));
                putTrue(c, "use_ssl", !"false".equalsIgnoreCase(System.getenv("OBS_USE_SSL")));
            }
            default -> {
                return null;
            }
        }
        b.setConfig(c);
        return b;
    }

    private static String envTrim(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    private static void putNonEmpty(ObjectNode c, String key, String value) {
        if (value != null && !value.isEmpty()) {
            c.put(key, value);
        }
    }

    private static void putTrue(ObjectNode c, String key, boolean value) {
        if (value) {
            c.put(key, true);
        }
    }
}
