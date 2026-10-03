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
import com.ragagent.common.storage.StorageBackendProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租户 service。
 *
 * 落库行为：软删除过滤（WHERE deleted_at IS NULL）。
 * retriever_engines 归一化：历史裸数组格式 [{...}] 包装成 {"engines":[...]}，
 * 响应恒为包装格式 → 读取后归一化。
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TenantMapper tenantMapper;
    private final com.ragagent.auth.mapper.TenantMemberMapper memberMapper;
    private final StorageBackendProvisioner storageProvisioner;

    public TenantService(TenantMapper tenantMapper,
                         com.ragagent.auth.mapper.TenantMemberMapper memberMapper,
                         StorageBackendProvisioner storageProvisioner) {
        this.tenantMapper = tenantMapper;
        this.memberMapper = memberMapper;
        this.storageProvisioner = storageProvisioner;
    }

    /** 按 id 查：不存在返回 null（调用方区分语义） */
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

    /** 批量按 id 查（map 形态，供 memberships 组装） */
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

    /** 全量列表，created_at DESC，软删除过滤 */
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
     * 租户搜索：
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

    /** LIKE 转义：\ → \\、% → \%、_ → \_（顺序敏感，先转义反斜杠） */
    private static String escapeLikeKeyword(String kw) {
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 更新租户：先做存储桶唯一性校验
     * （KV storage PUT / api-principal PUT 共用这条 service 路径），
     * 再显式刷 updated_at 写回。updateById 跳过 null 字段——
     * 对"从 DB 读出再写回"
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
     * 存储桶唯一性校验：
     * minio/cos/tos/s3/oss 五族的 bucket_name 跨租户唯一；仅当本租户**改动**
     * 了某族桶名（与库存旧值不同）且该桶名被别的租户占用时拒绝。
     * 旧租户行读取失败/不存在按"无旧值"处理（selectById 返回 null）。
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
        // 汇总其他租户的占用（软删除行不参与）
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

    /** 取 minio/cos/tos/s3/oss 五族的非空 bucket_name */
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
     * 创建租户：status=active + created_at/updated_at 显式赋值
     * + retriever_engines null → 空数组 + id 回填 + 默认存储后端创建。
     * 存储配额等列落 DB 默认值（10GiB，迁移 000000）。
     * validateStorageBucketUniqueness 对自助路径是 no-op（StorageEngineConfig 恒 null），
     * 超管全字段路径可能携带 → 在 insert 前跑。
     */
    public Tenant createTenant(Tenant tenant) {
        if (tenant.getName() == null || tenant.getName().isEmpty()) {
            throw new IllegalArgumentException("workspace name cannot be empty");
        }
        tenant.setStatus("active");
        // 真表 business 列 NOT NULL 且无默认（空串兜底）
        if (tenant.getBusiness() == null) {
            tenant.setBusiness("");
        }
        // storage_used 零值兜底：恒 0 落库并回显（响应实体即在内存对象）
        if (tenant.getStorageUsed() == null) {
            tenant.setStorageUsed(0L);
        }
        // retriever_engines null → 空数组
        if (tenant.getRetrieverEngines() == null) {
            ObjectNode engines = MAPPER.createObjectNode();
            engines.putArray("engines");
            tenant.setRetrieverEngines(engines);
        }
        // context_config null → 落库 jsonb 'null' 字面量（既定注册路径行为），
        // 不是 SQL NULL
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
            // 默认后端创建失败 → 删租户回滚，错误上抛
            // （此时还没有关联行，回滚是安全的）
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
     * 默认存储后端编排：**只留本域事务编排**——
     * 建后端（委派 {@link StorageBackendProvisioner}，env→实体/config 的规则已在存储域）→
     * 回写 default_storage_backend_id → 回写失败则删后端行再上抛。
     * 注册路径 storageEngineConfig 恒 null → 走 env 兜底分支。
     */
    private void createDefaultStorageBackend(Tenant tenant) {
        String backendId = storageProvisioner.provisionForTenant(tenant.getId());
        tenant.setDefaultStorageBackendId(backendId);
        // 回写 updated_at（第二次写，晚于 created_at）
        tenant.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            tenantMapper.updateById(tenant);
        } catch (RuntimeException e) {
            try {
                storageProvisioner.deleteForTenant(tenant.getId(), backendId);
            } catch (RuntimeException cleanupErr) {
                log.warn("createDefaultStorageBackend cleanup: failed to delete backend {}: {}",
                        backendId, cleanupErr.toString());
            }
            throw e;
        }
    }


    /**
     * 删除租户（register 失败回滚等路径共用）：先软删
     * tenant_members（tenant_id=?）再软删 tenants（id=?）——
     * 显式写 deleted_at（不用 @TableLogic）。删不存在的 id 不报错（幂等成功）。
     */
    public void deleteTenant(long id) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        memberMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.ragagent.auth.domain.TenantMember>()
                .eq("tenant_id", id)
                .isNull("deleted_at")
                .set("deleted_at", now));
        tenantMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Tenant>()
                .eq("id", id)
                .isNull("deleted_at")
                .set("deleted_at", now));
    }

    /**
     * retriever_engines 归一化：NULL/裸数组 → 包装格式；
     * 响应恒输出包装格式。
     * NULL → {"engines":null}；裸数组 → {"engines":[...]}。
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
     * context_config 归一化（读取路径与响应输出共用）。
     * 注册路径恒写 jsonb 'null'，
     * 读回时 "null" 落在已分配的零值对象上 → 响应恒输出零值对象。
     * 对象形态则只保留 4 个已知键并按既定声明序重排（反序列化丢未知键，
     * 且 jsonb 存储序 ≠ 声明序——实测 PG 10002 行的键序与输出序不同）。
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
