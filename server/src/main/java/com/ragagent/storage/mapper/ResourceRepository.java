package com.ragagent.storage.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ragagent.storage.domain.StoredResource;

/**
 * 资源注册表仓储。
 *
 * <p>H2/PG 双跑：列序以迁移 {@code 000069_resource_registry.up.sql} 为准
 * （TestSchema 同源）。GetByID/GetByHandle/GetByTenantLocation
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

    /** NotFound → empty（不是错误）。 */
    public Optional<StoredResource> getByID(String id) {
        return jdbc.sql("SELECT " + COLS + " FROM resources WHERE id = ? AND state = ?")
                .params(id, StoredResource.STATE_ACTIVE)
                .query(new ResourceMapper())
                .optional();
    }

    /** NotFound → empty，同 {@link #getByID}。 */
    public Optional<StoredResource> getByHandle(String handle) {
        return jdbc.sql("SELECT " + COLS + " FROM resources WHERE handle = ? AND state = ?")
                .params(handle, StoredResource.STATE_ACTIVE)
                .query(new ResourceMapper())
                .optional();
    }

    /** 按租户 + 位置哈希取行（唯一性由 state='active' 承担）。 */
    public Optional<StoredResource> getByTenantLocation(long tenantId, String locationHash) {
        return jdbc.sql("SELECT " + COLS + " FROM resources "
                        + "WHERE tenant_id = ? AND location_hash = ? AND state = ?")
                .params(tenantId, locationHash, StoredResource.STATE_ACTIVE)
                .query(new ResourceMapper())
                .optional();
    }

    /**
     * 注册表行插入。id/时间戳在代码侧显式生成
     * （H2 测试库无列默认；PG 同样接受显式值）。
     */
    public void createResource(StoredResource r) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("INSERT INTO resources (id, handle, tenant_id, storage_backend_id, provider, "
                        + "physical_path, location_hash, kind, mime_type, original_name, size, "
                        + "content_hash, lifecycle, state, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(r.getId() == null ? UUID.randomUUID().toString() : r.getId(),
                        r.getHandle(), r.getTenantId(), r.getStorageBackendId(), r.getProvider(),
                        r.getPhysicalPath(), r.getLocationHash(), r.getKind(), r.getMimeType(),
                        r.getOriginalName(), r.getSize(), r.getContentHash(), r.getLifecycle(),
                        r.getState() == null ? StoredResource.STATE_ACTIVE : r.getState(),
                        now, now)
                .update();
    }

    /**
     * 绑定插入：重复绑定幂等成功——按"插入失败即视为已绑定"吞掉冲突
     * （PG 的 unique violation 与 H2 的主键冲突文案都归入此分支）。
     */
    public void createBinding(String resourceId, long tenantId, String ownerType,
            String ownerId, String relation) {
        try {
            jdbc.sql("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, "
                            + "owner_id, relation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), resourceId, tenantId, ownerType,
                            ownerId, relation, OffsetDateTime.now())
                    .update();
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (msg.contains("unique") || msg.contains("duplicate") || msg.contains("conflict")
                    || msg.contains("primary key")) {
                return;
            }
            throw e;
        }
    }

    /** state=deleted + deleted_at（软删）。 */
    public void markDeleted(String resourceId) {
        jdbc.sql("UPDATE resources SET state = ?, deleted_at = ? WHERE id = ?")
                .params(StoredResource.STATE_DELETED, OffsetDateTime.now(), resourceId)
                .update();
    }

    /** 写入 access grant 行。 */
    public void createGrant(String id, String tokenHash, String resourceId, String accessScope,
            OffsetDateTime expiresAt) {
        jdbc.sql("INSERT INTO resource_access_grants (id, token_hash, resource_id, access_scope, "
                        + "expires_at, revoked_at, created_at) VALUES (?, ?, ?, ?, ?, NULL, ?)")
                .params(id, tokenHash, resourceId, accessScope, expiresAt, OffsetDateTime.now())
                .update();
    }

    /** 行存在但 revoked → empty（NotFound 语义）。 */
    public Optional<String> getValidGrantResourceId(String tokenHash, OffsetDateTime now) {
        return jdbc.sql("SELECT resource_id FROM resource_access_grants "
                        + "WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?")
                .params(tokenHash, now)
                .query(String.class)
                .optional();
    }

    /**
     * 过期行整体删除；<b>被撤销的行保留到过期为止</b>
     * （它是"同窗口派生 token 不能复活访问"的墓碑）。
     */
    public void deleteExpiredGrants(OffsetDateTime before) {
        jdbc.sql("DELETE FROM resource_access_grants WHERE expires_at <= ?")
                .param(before)
                .update();
    }

    /**
     * 只认指向<b>存活文档</b>的显式绑定——文本里出现 handle 不算所有权证据。
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
     * 资源绑定的 KnowledgeBaseIDs 集合（消息文件绑定的事实来源之一）。
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

    /** 消息 artifact 绑定存在性。 */
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
