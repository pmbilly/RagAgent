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
