package com.ragagent.knowledge.storage;

import java.sql.Timestamp;
import java.time.Instant;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.mapper.TenantMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;


/**
 * 租户存储用量调整——FAQ 索引/删除的执行面依赖
 */
@Service
public class TenantStorageService {

    private final TenantMapper tenantMapper;
    private final JdbcTemplate jdbc;

    public TenantStorageService(TenantMapper tenantMapper, JdbcTemplate jdbc) {
        this.tenantMapper = tenantMapper;
        this.jdbc = jdbc;
    }

    /** 租户行（含 storage_quota / storage_used；不存在返回 null）。 */
    public Tenant getTenant(long tenantId) {
        return tenantMapper.selectById(tenantId);
    }

    /**
     * {@code storage_used += delta}；负数钳 0
     */
    public void adjustStorageUsed(long tenantId, long delta) {
        jdbc.update("UPDATE tenants SET storage_used = GREATEST(COALESCE(storage_used, 0) + ?, 0), "
                + "updated_at = ? WHERE id = ?", delta, Timestamp.from(Instant.now()), tenantId);
    }
}
