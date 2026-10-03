package com.ragagent.storage.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.storage.domain.StorageBackend;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * storage_backends 行仓储。
 *
 * <p>行载体 {@link StorageBackend}；查询/写入语义：
 * List 是 created_at **DESC**；Update 只写 name/config/status/updated_at；
 * 删除是软删（deleted_at = NOW()）。config jsonb 由 service 序列化（含密钥加密）后写字符串
 * ——PG 走 {@code ?::jsonb} 强转（列类型服务端强转，setString 会被拒），H2 直接写。</p>
 */
@Repository
public class StorageBackendRepository {

    private static final String COLS = "id, tenant_id, name, provider, config, source, status, "
            + "legacy_alias, created_at, updated_at, deleted_at";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbc;
    private final boolean postgres;

    public StorageBackendRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        boolean pg = false;
        try (var conn = dataSource.getConnection()) {
            pg = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            // 默认按非 PG（H2）处理
        }
        this.postgres = pg;
    }

    private static class BackendMapper implements RowMapper<StorageBackend> {
        @Override
        public StorageBackend mapRow(ResultSet rs, int rowNum) throws SQLException {
            StorageBackend b = new StorageBackend();
            b.setId(rs.getString("id"));
            b.setTenantId(rs.getLong("tenant_id"));
            b.setName(rs.getString("name"));
            b.setProvider(rs.getString("provider"));
            String raw = rs.getString("config");
            if (raw != null) {
                try {
                    b.setConfig(MAPPER.readTree(raw));
                } catch (Exception e) {
                    throw new SQLException("parse storage backend config failed", e);
                }
            }
            b.setSource(rs.getString("source"));
            b.setStatus(rs.getString("status"));
            b.setLegacyAlias(rs.getBoolean("legacy_alias"));
            b.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
            b.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
            b.setDeletedAt(rs.getObject("deleted_at", OffsetDateTime.class));
            return b;
        }
    }

    /** Go GetByID：NotFound → (nil, nil)（不是错误） */
    public Optional<StorageBackend> getByID(long tenantId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM storage_backends "
                        + "WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL")
                .params(tenantId, id)
                .query(new BackendMapper())
                .optional();
    }

    /**
     * provider 的 legacy 别名行（<b>不过滤 deleted_at</b>——刻意保留，别"顺手修好"）；
     * 按主键序取第一条。
     */
    public StorageBackend findLegacyAlias(long tenantId, String provider) {
        return jdbc.sql("SELECT " + COLS + " FROM storage_backends "
                        + "WHERE tenant_id = ? AND provider = ? AND legacy_alias = TRUE "
                        + "ORDER BY id LIMIT 1")
                .params(tenantId, provider)
                .query(new BackendMapper())
                .optional()
                .orElse(null);
    }

    /** Go List：created_at DESC */
    public List<StorageBackend> list(long tenantId) {
        return jdbc.sql("SELECT " + COLS + " FROM storage_backends "
                        + "WHERE tenant_id = ? AND deleted_at IS NULL ORDER BY created_at DESC")
                .param(tenantId)
                .query(new BackendMapper())
                .list();
    }

    /** 全列插入；created_at/updated_at 由 service 显式赋值 */
    public void create(StorageBackend b, String configJson) {
        String cast = postgres ? "?::jsonb" : "?";
        jdbc.sql("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                        + "status, legacy_alias, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, " + cast + ", ?, ?, ?, ?, ?, NULL)")
                .params(b.getId(), b.getTenantId(), b.getName(), b.getProvider(), configJson,
                        b.getSource(), b.getStatus(), b.isLegacyAlias(), b.getCreatedAt(), b.getUpdatedAt())
                .update();
    }

    /** Go Update：Select("name","config","status","updated_at") */
    public void update(long tenantId, String id, String name, String configJson,
            String status, OffsetDateTime now) {
        String cast = postgres ? "?::jsonb" : "?";
        jdbc.sql("UPDATE storage_backends SET name = ?, config = " + cast + ", status = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL")
                .params(name, configJson, status, now, tenantId, id)
                .update();
    }

    /** Go Delete：软删 */
    public void delete(long tenantId, String id) {
        jdbc.sql("UPDATE storage_backends SET deleted_at = NOW() "
                        + "WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL")
                .params(tenantId, id)
                .update();
    }

    public int countDefaultReference(long tenantId, String id) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM tenants WHERE id = ? AND default_storage_backend_id = ?")
                .params(tenantId, id)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public int countBoundKnowledgeBases(long tenantId, String id) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM knowledge_bases "
                        + "WHERE tenant_id = ? AND storage_backend_id = ? AND deleted_at IS NULL")
                .params(tenantId, id)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public int countActiveResources(long tenantId, String id) {
        // 资源表名 "resources"（StoredResource 的表名映射，不是按类名推断的字面量）
        Integer n = jdbc.sql("SELECT COUNT(*) FROM resources "
                        + "WHERE tenant_id = ? AND storage_backend_id = ? AND state = 'active'")
                .params(tenantId, id)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public String tenantDefaultBackendId(long tenantId) {
        return jdbc.sql("SELECT default_storage_backend_id FROM tenants WHERE id = ?")
                .param(tenantId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void setTenantDefaultBackendId(long tenantId, String id) {
        jdbc.sql("UPDATE tenants SET default_storage_backend_id = ? WHERE id = ?")
                .params(id, tenantId)
                .update();
    }

    /** 名称唯一性预检（只看未删行；命中时 service 折 409）。 */
    public boolean nameExists(long tenantId, String name) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM storage_backends "
                        + "WHERE tenant_id = ? AND name = ? AND deleted_at IS NULL")
                .params(tenantId, name)
                .query(Integer.class)
                .single();
        return n != null && n > 0;
    }
}
