package com.ragagent.storage.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ragagent.storage.domain.StoredResource;

/**
 * 资源注册表仓储（对照 Go {@code repository/resource.go resourceRepository} +
 * {@code resource_references.go}，收尾批 W5c 只翻文件代理面用到的读方法子集）。
 *
 * <p>H2/PG 双跑：列序以迁移 {@code 000069_resource_registry.up.sql} 为准
 * （TestSchema 同源）。查询语义逐条对照 Go——GetByID/GetByHandle/GetByTenantLocation
 * 都带 {@code state = 'active'}；GetValidGrant 是
 * {@code token_hash = ? AND revoked_at IS NULL AND expires_at > now}。</p>
 */
@Repository
public class ResourceRepository {

    private static final String COLS = "id, handle, tenant_id, storage_backend_id, provider, physical_path, "
            + "location_hash, kind, mime_type, original_name, size, content_hash, lifecycle, "
            + "expires_at, state, created_at, updated_at, deleted_at";

    private final JdbcClient jdbc;

    public ResourceRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
    }

    private static class ResourceMapper implements RowMapper<StoredResource> {
        @Override
        public StoredResource mapRow(ResultSet rs, int rowNum) throws SQLException {
            StoredResource r = new StoredResource();
            r.setId(rs.getString("id"));
            r.setHandle(rs.getString("handle"));
            r.setTenantId(rs.getLong("tenant_id"));
            String backendId = rs.getString("storage_backend_id");
            r.setStorageBackendId(rs.wasNull() ? "" : backendId);
            r.setProvider(rs.getString("provider"));
            r.setPhysicalPath(rs.getString("physical_path"));
            r.setLocationHash(rs.getString("location_hash"));
            r.setKind(rs.getString("kind"));
            r.setMimeType(rs.getString("mime_type"));
            r.setOriginalName(rs.getString("original_name"));
            r.setSize(rs.getLong("size"));
            r.setContentHash(rs.getString("content_hash"));
            r.setLifecycle(rs.getString("lifecycle"));
            r.setExpiresAt(rs.getObject("expires_at", OffsetDateTime.class));
            r.setState(rs.getString("state"));
            r.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
            r.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
            r.setDeletedAt(rs.getObject("deleted_at", OffsetDateTime.class));
            return r;
        }
    }

    private static String cols() {
        return COLS;
    }

    /** 对照 Go GetByID：NotFound → empty（Go 是 (nil, nil)，不是错误）。 */
    public Optional<StoredResource> getByID(String id) {
        return jdbc.sql("SELECT " + COLS + " FROM resources WHERE id = ? AND state = ?")
                .params(id, StoredResource.STATE_ACTIVE)
                .query(new ResourceMapper())
                .optional();
    }

    /** 对照 Go GetByHandle：同上。 */
    public Optional<StoredResource> getByHandle(String handle) {
        return jdbc.sql("SELECT " + COLS + " FROM resources WHERE handle = ? AND state = ?")
                .params(handle, StoredResource.STATE_ACTIVE)
                .query(new ResourceMapper())
                .optional();
    }

    /** 对照 Go GetByTenantLocation（partial unique index：deleted_at IS NULL 由 state 承担）。 */
    public Optional<StoredResource> getByTenantLocation(long tenantId, String locationHash) {
        return jdbc.sql("SELECT " + COLS + " FROM resources "
                        + "WHERE tenant_id = ? AND location_hash = ? AND state = ?")
                .params(tenantId, locationHash, StoredResource.STATE_ACTIVE)
                .query(new ResourceMapper())
                .optional();
    }

    /** 对照 Go CreateGrant。 */
    public void createGrant(String id, String tokenHash, String resourceId, String accessScope,
            OffsetDateTime expiresAt) {
        jdbc.sql("INSERT INTO resource_access_grants (id, token_hash, resource_id, access_scope, "
                        + "expires_at, revoked_at, created_at) VALUES (?, ?, ?, ?, ?, NULL, ?)")
                .params(id, tokenHash, resourceId, accessScope, expiresAt, OffsetDateTime.now())
                .update();
    }

    /** 对照 Go GetValidGrant：行存在但 revoked → empty（NotFound 语义）。 */
    public Optional<String> getValidGrantResourceId(String tokenHash, OffsetDateTime now) {
        return jdbc.sql("SELECT resource_id FROM resource_access_grants "
                        + "WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?")
                .params(tokenHash, now)
                .query(String.class)
                .optional();
    }

    /**
     * 对照 Go DeleteExpiredGrants：过期行整体删除；<b>被撤销的行保留到过期为止</b>
     * （它是"同窗口派生 token 不能复活访问"的墓碑，Go 注释原文）。
     */
    public void deleteExpiredGrants(OffsetDateTime before) {
        jdbc.sql("DELETE FROM resource_access_grants WHERE expires_at <= ?")
                .param(before)
                .update();
    }

    /**
     * 对照 Go {@code IsReferencedByKnowledgeBase}（resource_references.go）：只认
     * 指向<b>存活文档</b>的显式绑定——文本里出现 handle 不算所有权证据。
     */
    public boolean isReferencedByKnowledgeBase(long tenantId, String kbId, String resourceId) {
        if (tenantId == 0 || kbId == null || kbId.isEmpty() || resourceId == null || resourceId.isEmpty()) {
            return false;
        }
        Integer count = jdbc.sql("SELECT COUNT(*) FROM resource_bindings AS b "
                        + "JOIN knowledges AS k ON k.id = b.owner_id AND k.tenant_id = b.tenant_id "
                        + "JOIN knowledge_bases AS kb ON kb.id = k.knowledge_base_id "
                        + "AND kb.tenant_id = k.tenant_id AND kb.deleted_at IS NULL "
                        + "WHERE b.resource_id = ? AND b.owner_type = ? AND b.tenant_id = ? "
                        + "AND k.knowledge_base_id = ? AND k.deleted_at IS NULL")
                .params(resourceId, "knowledge", tenantId, kbId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return count != null && count > 0;
    }

    /**
     * 对照 Go {@code GetMessageFileBindings} 的 KnowledgeBaseIDs 段（跨租户消息分支用，
     * W5c 的同租户主路径不可达，但按 Go 原样补齐）。
     */
    public List<String> knowledgeBaseIdsForBinding(long tenantId, String resourceId) {
        if (tenantId == 0 || resourceId == null || resourceId.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT k.knowledge_base_id FROM resource_bindings AS b "
                        + "JOIN knowledges AS k ON k.id = b.owner_id AND k.tenant_id = b.tenant_id "
                        + "JOIN knowledge_bases AS kb ON kb.id = k.knowledge_base_id "
                        + "AND kb.tenant_id = k.tenant_id AND kb.deleted_at IS NULL "
                        + "WHERE b.resource_id = ? AND b.owner_type = ? AND b.tenant_id = ? "
                        + "AND k.deleted_at IS NULL")
                .params(resourceId, "knowledge", tenantId)
                .query(String.class)
                .list();
    }

    /** 对照 Go GetMessageFileBindings 的 MessageArtifact 段。 */
    public boolean hasMessageArtifactBinding(long tenantId, String resourceId, String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return false;
        }
        Integer count = jdbc.sql("SELECT COUNT(*) FROM resource_bindings "
                        + "WHERE resource_id = ? AND tenant_id = ? AND owner_type = ? "
                        + "AND owner_id = ? AND relation = ?")
                .params(resourceId, tenantId, "message", messageId, "artifact")
                .query(Integer.class)
                .optional()
                .orElse(0);
        return count != null && count > 0;
    }
}
