package com.ragagent.auth.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.mapper.TenantMapper;
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

    public TenantService(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    /** 对照 GetTenantByID：不存在返回 null（调用方区分语义） */
    public Tenant getTenantById(long id) {
        Tenant t = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getId, id)
                .isNull(Tenant::getDeletedAt)
                .last("LIMIT 1"));
        if (t != null) {
            normalizeRetrieverEngines(t);
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
            out.put(t.getId(), t);
        }
        return out;
    }

    /**
     * 对照 UpdateTenant（api-principal PUT 专用子集）：写回整行配置。
     * GORM 的 Update 会自动刷 updated_at——api_principal_config 的 PUT 响应虽不回显，
     * 但落库行为保持一致（显式 set updated_at，别让列停在旧值）。
     */
    public Tenant updateTenant(Tenant tenant) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        tenant.setUpdatedAt(now);
        tenantMapper.updateById(tenant);
        return tenant;
    }

    /**
     * 对照 CreateTenant（注册/系统管理员建用户路径的最小子集）：status=active +
     * created_at/updated_at 显式赋值 + id 回填。存储配额等列落 DB 默认值
     * （10GiB，迁移 000001）。storage bucket 唯一性校验与默认存储后端创建
     * 随租户管理面（routes_auth_tenant.go 的租户 CRUD 组）一起翻译。
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
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        tenantMapper.insert(tenant);
        return tenant;
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
}
