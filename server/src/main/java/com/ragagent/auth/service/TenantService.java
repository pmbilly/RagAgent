package com.ragagent.auth.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.knowledge.domain.StorageBackend;
import com.ragagent.storage.mapper.StorageBackendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对照 Go TenantService 的读路径（GetTenantByID / GetTenantsByIDs）。
 *
 * GORM 隐式行为：软删除过滤（WHERE deleted_at IS NULL）。
 * RetrieverEngines 归一化：Go Scan 把历史裸数组格式 [{...}] 包装成 {"engines":[...]}，
 * 响应恒为包装格式 → 读取后归一化。
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TenantMapper tenantMapper;
    private final StorageBackendRepository storageBackendRepository;

    public TenantService(TenantMapper tenantMapper, StorageBackendRepository storageBackendRepository) {
        this.tenantMapper = tenantMapper;
        this.storageBackendRepository = storageBackendRepository;
    }

    /** 对照 GetTenantByID：不存在返回 null（调用方区分语义） */
    public Tenant getTenantById(long id) {
        Tenant t = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getId, id)
                .isNull(Tenant::getDeletedAt)
                .last("LIMIT 1"));
        if (t != null) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
        }
        return t;
    }

    /** 对照 GetTenantsByIDs：批量按 id 查（map 形态，供 memberships 组装） */
    public Map<Long, Tenant> getTenantsByIds(Collection<Long> ids) {
        Map<Long, Tenant> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (Tenant t : tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .in(Tenant::getId, ids)
                .isNull(Tenant::getDeletedAt))) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
            out.put(t.getId(), t);
        }
        return out;
    }

    /** 对照 ListTenants（repo）：全量列表，created_at DESC，软删除过滤 */
    public List<Tenant> listAllTenants() {
        List<Tenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .isNull(Tenant::getDeletedAt)
                .orderByDesc(Tenant::getCreatedAt));
        for (Tenant t : tenants) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
        }
        return tenants;
    }

    /**
     * 对照 repo.SearchTenants（repository/tenant.go L77-119）：
     * tenant_id>0 且 keyword 非空 → (id=? OR name LIKE OR description LIKE)；
     * 仅 tenant_id → id=?；仅 keyword → name/description LIKE。
     * LIKE 参数先过 escapeLikeKeyword（\ % _ 反斜杠转义，PG LIKE 默认 ESCAPE 就是反斜杠）。
     * 先 Count 再 Offset/Limit，恒 created_at DESC。
     */
    public record TenantSearchPage(List<Tenant> tenants, long total) {}

    public TenantSearchPage searchTenants(String keyword, long tenantId, int page, int pageSize) {
        String kw = keyword == null ? "" : keyword;
        var wrapper = new LambdaQueryWrapper<Tenant>().isNull(Tenant::getDeletedAt);
        if (tenantId > 0 && !kw.isEmpty()) {
            String like = escapeLikeKeyword(kw);
            final long id = tenantId;
            wrapper.and(w -> w.eq(Tenant::getId, id)
                    .or().like(Tenant::getName, like)
                    .or().like(Tenant::getDescription, like));
        } else if (tenantId > 0) {
            wrapper.eq(Tenant::getId, tenantId);
        } else if (!kw.isEmpty()) {
            String like = escapeLikeKeyword(kw);
            wrapper.and(w -> w.like(Tenant::getName, like)
                    .or().like(Tenant::getDescription, like));
        }
        Long total = tenantMapper.selectCount(wrapper);
        if (page > 0 && pageSize > 0) {
            wrapper.last("LIMIT " + pageSize + " OFFSET " + ((long) (page - 1) * pageSize));
        }
        wrapper.orderByDesc(Tenant::getCreatedAt);
        List<Tenant> tenants = tenantMapper.selectList(wrapper);
        for (Tenant t : tenants) {
            normalizeRetrieverEngines(t);
            normalizeContextConfig(t);
        }
        return new TenantSearchPage(tenants, total == null ? 0 : total);
    }

    /** 对照 Go 的 escapeLikeKeyword：\ → \\、% → \%、_ → \_（顺序敏感，先转义反斜杠） */
    private static String escapeLikeKeyword(String kw) {
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 对照 UpdateTenant（service/tenant.go L138-166）：先做存储桶唯一性校验
     * （KV storage PUT / api-principal PUT 共用这条 service 路径，Go 同），
     * 再显式刷 updated_at 写回。GORM Updates(struct) 跳过零值字段；
     * MyBatis-Plus updateById 跳过 null 字段——两条路径对"从 DB 读出再写回"
     * 的用法等价（值未变的列重写同值）。
     */
    public Tenant updateTenant(Tenant tenant) {
        validateStorageBucketUniqueness(tenant);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        tenant.setUpdatedAt(now);
        tenantMapper.updateById(tenant);
        return tenant;
    }

    /**
     * 对照 validateStorageBucketUniqueness（service/tenant.go L287-345）：
     * minio/cos/tos/s3/oss 五族的 bucket_name 跨租户唯一；仅当本租户**改动**
     * 了某族桶名（与库存旧值不同）且该桶名被别的租户占用时拒绝。
     * Go 对"旧租户读取失败"仅容忍 not-found 两类错误——Java 侧 selectById
     * 返回 null 即等价语义。
     */
    private void validateStorageBucketUniqueness(Tenant tenant) {
        JsonNode cfg = tenant.getStorageEngineConfig();
        if (cfg == null || cfg.isNull()) {
            return;
        }
        Map<String, String> oldBuckets = Map.of();
        if (tenant.getId() != null && tenant.getId() != 0) {
            Tenant old = tenantMapper.selectById(tenant.getId());
            if (old != null) {
                oldBuckets = bucketNames(old.getStorageEngineConfig());
            }
        }
        Map<String, String> newBuckets = bucketNames(cfg);
        // 汇总其他租户的占用（软删除行不参与——Go 的 ListTenants 走 GORM 软删除过滤）
        Map<String, java.util.Set<String>> usedByOthers = new HashMap<>();
        for (Tenant t : tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .isNull(Tenant::getDeletedAt))) {
            if (t.getId() != null && t.getId().equals(tenant.getId())) {
                continue;
            }
            bucketNames(t.getStorageEngineConfig()).forEach((p, b) ->
                    usedByOthers.computeIfAbsent(p, k -> new java.util.HashSet<>()).add(b));
        }
        for (Map.Entry<String, String> e : newBuckets.entrySet()) {
            String oldB = oldBuckets.get(e.getKey());
            if (!e.getValue().equals(oldB)) {
                java.util.Set<String> used = usedByOthers.get(e.getKey());
                if (used != null && used.contains(e.getValue())) {
                    throw new com.ragagent.common.error.BizException(
                            com.ragagent.common.error.AppError.badRequest(
                                    "存储桶名称「" + e.getValue() + "」已被其他空间使用，为保证数据隔离，请使用其他名称"));
                }
            }
        }
    }

    /** 对照 getBuckets 闭包：取 minio/cos/tos/s3/oss 五族的非空 bucket_name */
    private static Map<String, String> bucketNames(JsonNode cfg) {
        if (cfg == null || cfg.isNull() || !cfg.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String provider : new String[]{"minio", "cos", "tos", "s3", "oss"}) {
            JsonNode node = cfg.get(provider);
            if (node == null || !node.isObject()) {
                continue;
            }
            JsonNode bucket = node.get("bucket_name");
            if (bucket != null && bucket.isTextual() && !bucket.asText().isEmpty()) {
                out.put(provider, bucket.asText());
            }
        }
        return out;
    }

    /**
     * 对照 CreateTenant（tenant.go L34-73）：status=active + created_at/updated_at 显式赋值
     * + RetrieverEngines BeforeCreate 钩子（nil → 空数组）+ id 回填 + 默认存储后端创建。
     * 存储配额等列落 DB 默认值（10GiB，迁移 000000）。
     * validateStorageBucketUniqueness 对自助路径是 no-op（StorageEngineConfig 恒 null），
     * 超管全字段路径可能携带 → 与 Go 一样在 insert 前跑（tenant.go L51-58）。
     */
    public Tenant createTenant(Tenant tenant) {
        if (tenant.getName() == null || tenant.getName().isEmpty()) {
            throw new IllegalArgumentException("workspace name cannot be empty");
        }
        tenant.setStatus("active");
        // 真表 business 列 NOT NULL 且无默认（Go 非指针 string 零值 "" 由 GORM 写入）
        if (tenant.getBusiness() == null) {
            tenant.setBusiness("");
        }
        // Go 非指针 int64 零值：StorageUsed 恒 0 落库并回显（响应实体即在内存对象）
        if (tenant.getStorageUsed() == null) {
            tenant.setStorageUsed(0L);
        }
        // 对照 Tenant.BeforeCreate（tenant.go L144-150）：nil engines → 空数组
        if (tenant.getRetrieverEngines() == null) {
            ObjectNode engines = MAPPER.createObjectNode();
            engines.putArray("engines");
            tenant.setRetrieverEngines(engines);
        }
        // 对照 GORM Create 对 nil *ContextConfig 调 Value() → json.Marshal(nil)：
        // 落库的是 jsonb 'null' 字面量（实测 Go 注册路径），不是 SQL NULL
        if (tenant.getContextConfig() == null) {
            tenant.setContextConfig(MAPPER.nullNode());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        validateStorageBucketUniqueness(tenant);
        tenantMapper.insert(tenant);
        try {
            createDefaultStorageBackend(tenant);
        } catch (RuntimeException e) {
            // 对照 Go：默认后端创建失败 → 删租户回滚，错误上抛
            // （"No related rows exist yet, so rolling the tenant back is safe"）
            try {
                tenantMapper.deleteById(tenant.getId());
            } catch (RuntimeException rollbackErr) {
                log.warn("createTenant rollback: failed to delete tenant {}: {}",
                        tenant.getId(), rollbackErr.toString());
            }
            throw e;
        }
        return tenant;
    }

    /**
     * 对照 createDefaultStorageBackend（tenant.go L75-100）。
     * 注册路径 tenant.StorageEngineConfig 恒 null → StorageBackendFromLegacy 恒 nil
     * → 走 StorageBackendFromEnvironment（storagebackend.go L329）。写库失败回滚租户；
     * 回写 default_storage_backend_id 失败则删后端行再上抛。
     */
    private void createDefaultStorageBackend(Tenant tenant) {
        StorageBackend backend = envDefaultBackend(tenant.getId());
        if (backend == null) {
            throw new IllegalStateException("no supported default storage backend is configured");
        }
        backend.setLegacyAlias(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        backend.setCreatedAt(now);
        backend.setUpdatedAt(now);
        storageBackendRepository.create(backend, backend.getConfig() == null
                ? "{}" : backend.getConfig().toString());
        tenant.setDefaultStorageBackendId(backend.getId());
        // 对照 repo.UpdateTenant：GORM 自动刷 updated_at（第二次写，晚于 created_at）
        tenant.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            tenantMapper.updateById(tenant);
        } catch (RuntimeException e) {
            try {
                storageBackendRepository.delete(tenant.getId(), backend.getId());
            } catch (RuntimeException cleanupErr) {
                log.warn("createDefaultStorageBackend cleanup: failed to delete backend {}: {}",
                        backend.getId(), cleanupErr.toString());
            }
            throw e;
        }
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

    /** 对照 DeleteTenant（register 失败回滚用；此处行尚无引用，物理删除无害）。 */
    public void deleteTenant(long id) {
        tenantMapper.deleteById(id);
    }

    /**
     * 对照 RetrieverEngines.Scan：NULL/裸数组 → 归一化；
     * 响应恒输出包装格式（Go RetrieverEngines 为值类型，无 omitempty）。
     * NULL → {"engines":null}（Go 零值 struct 的序列化结果）；裸数组 → {"engines":[...]}。
     */
    private static void normalizeRetrieverEngines(Tenant t) {
        JsonNode engines = t.getRetrieverEngines();
        ObjectNode wrapped = MAPPER.createObjectNode();
        if (engines == null) {
            wrapped.putNull("engines");
        } else if (engines.isArray()) {
            wrapped.set("engines", engines);
        } else {
            return;
        }
        t.setRetrieverEngines(wrapped);
    }

    /**
     * 对照 ContextConfig.Scan + dto 输出（session.go L327-340）。
     * Go 注册路径恒写 jsonb 'null'（GORM 对 nil 指针调 Value() → json.Marshal(nil)="null"），
     * 读回时 json.Unmarshal("null") 落在已分配的零值 struct 上 → 响应恒输出零值对象。
     * 对象形态则只保留 4 个已知键并按 Go struct 声明序重排（Unmarshal 丢未知键，
     * 且 jsonb 存储序 ≠ struct 序——实测 PG 10002 行的键序与 Go 输出序不同）。
     */
    private static void normalizeContextConfig(Tenant t) {
        JsonNode cfg = t.getContextConfig();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("max_tokens", intOrZero(cfg, "max_tokens"));
        out.put("compression_strategy", textOrEmpty(cfg, "compression_strategy"));
        out.put("recent_message_count", intOrZero(cfg, "recent_message_count"));
        out.put("summarize_threshold", intOrZero(cfg, "summarize_threshold"));
        t.setContextConfig(out);
    }

    private static int intOrZero(JsonNode cfg, String key) {
        JsonNode v = cfg == null ? null : cfg.get(key);
        return v != null && v.isNumber() ? v.asInt(0) : 0;
    }

    private static String textOrEmpty(JsonNode cfg, String key) {
        JsonNode v = cfg == null ? null : cfg.get(key);
        return v != null && v.isTextual() ? v.asText("") : "";
    }
}
