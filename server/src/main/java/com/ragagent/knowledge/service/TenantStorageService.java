package com.ragagent.knowledge.service;

import java.sql.Timestamp;
import java.time.Instant;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.mapper.TenantMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * 租户存储用量调整（对照 Go tenantRepository.AdjustStorageUsed，
 * internal/application/repository/tenant.go L134-152）——FAQ 索引/删除的执行面依赖
 * （Go 悲观锁 + Save；Java 用 SQL 增量 + 负数钳位，并发语义等价，2026-09-22 走查批）。
 */
@Service
public class TenantStorageService {

    private final TenantMapper tenantMapper;
    private final JdbcTemplate jdbc;

    public TenantStorageService(TenantMapper tenantMapper, DataSource dataSource) {
        this.tenantMapper = tenantMapper;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 租户行（含 storage_quota / storage_used；不存在返回 null）。 */
    public Tenant getTenant(long tenantId) {
        return tenantMapper.selectById(tenantId);
    }

    /**
     * 对照 Go AdjustStorageUsed：{@code storage_used += delta}；负数钳 0
     * （Go 的日志 + 归零行为）。updated_at 随写刷新（Go 的 Save 全列语义）。
     */
    public void adjustStorageUsed(long tenantId, long delta) {
        jdbc.update("UPDATE tenants SET storage_used = GREATEST(COALESCE(storage_used, 0) + ?, 0), "
                + "updated_at = ? WHERE id = ?", delta, Timestamp.from(Instant.now()), tenantId);
    }
}
