package com.ragagent.org.mapper;

import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 组织域的计数/条件更新辅助（对照 Go 仓储的计数与更新方法）。 */
public interface OrgSqlMapper {

    @Select("SELECT COUNT(*) FROM organization_tenant_members WHERE organization_id = #{orgId}")
    long countTenantMembers(@Param("orgId") String orgId);

    @Select("SELECT COUNT(*) FROM organization_join_requests WHERE organization_id = #{orgId} AND status = #{status}")
    long countJoinRequests(@Param("orgId") String orgId, @Param("status") String status);

    @Select("SELECT COUNT(*) FROM organization_tenant_members WHERE organization_id = #{orgId} AND tenant_id = #{tenantId}")
    long countMemberOf(@Param("orgId") String orgId, @Param("tenantId") long tenantId);

    @Select("SELECT COUNT(*) FROM organizations WHERE id = #{orgId} AND deleted_at IS NULL")
    long countOrgById(@Param("orgId") String orgId);

    /** 共享读面的 KB 原始两列（H2 VARCHAR / PG jsonb 都按文本取）。 */
    @Select("SELECT CAST(indexing_strategy AS VARCHAR(1048576)) AS indexing_strategy, "
            + "CAST(storage_backend_id AS VARCHAR(64)) AS storage_backend_id "
            + "FROM knowledge_bases WHERE id = #{kbId}")
    KbRawRow selectKbRaw(@Param("kbId") String kbId);

    class KbRawRow {
        private String indexingStrategy;
        private String storageBackendId;

        public String getIndexingStrategy() { return indexingStrategy; }
        public void setIndexingStrategy(String v) { this.indexingStrategy = v; }
        public String getStorageBackendId() { return storageBackendId; }
        public void setStorageBackendId(String v) { this.storageBackendId = v; }
    }

    /** 对照 isAgentWebSearchReady 的 provider 可用性位（id 命中，或未指定时 is_default 命中）。 */
    @Select("SELECT COUNT(*) FROM web_search_providers WHERE tenant_id = #{tenantId} AND deleted_at IS NULL "
            + "AND ((#{providerId} <> '' AND id = #{providerId}) OR (#{providerId} = '' AND is_default = TRUE))")
    long countWebSearchProvider(@Param("tenantId") long tenantId, @Param("providerId") String providerId);

    /** 条件更新：仅 pending 行可复审（RowsAffected=0 → "request has already been reviewed"）。 */
    @Update("UPDATE organization_join_requests SET status = #{status}, reviewed_by = #{reviewedBy}, "
            + "reviewed_at = CURRENT_TIMESTAMP, review_message = #{reviewMessage} "
            + "WHERE id = #{id} AND status = 'pending'")
    int reviewJoinRequest(@Param("id") String id, @Param("status") String status,
            @Param("reviewedBy") String reviewedBy, @Param("reviewMessage") String reviewMessage);

    @Update("UPDATE organization_tenant_members SET role = #{role} "
            + "WHERE organization_id = #{orgId} AND tenant_id = #{tenantId}")
    int updateMemberRole(@Param("orgId") String orgId, @Param("tenantId") long tenantId, @Param("role") String role);

    @Update("UPDATE organizations SET invite_code = #{code}, invite_code_expires_at = #{expiresAt} "
            + "WHERE id = #{orgId} AND deleted_at IS NULL")
    int updateInviteCode(@Param("orgId") String orgId, @Param("code") String code, @Param("expiresAt") OffsetDateTime expiresAt);
}
