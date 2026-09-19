package com.ragagent.org.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 三列复合主键（迁移 000012）→ 纯 SQL（favorite 批先例）。
 * Add 对照 Go 的 FirstOrCreate：不存在才插入。
 */
public interface TenantDisabledSharedAgentMapper {

    @Select("SELECT agent_id, source_tenant_id FROM tenant_disabled_shared_agents WHERE tenant_id = #{tenantId}")
    List<DisabledRow> listByTenant(@Param("tenantId") long tenantId);

    @Insert("INSERT INTO tenant_disabled_shared_agents (tenant_id, agent_id, source_tenant_id, created_at) "
            + "SELECT #{tenantId}, #{agentId}, #{sourceTenantId}, CURRENT_TIMESTAMP "
            + "WHERE NOT EXISTS (SELECT 1 FROM tenant_disabled_shared_agents "
            + "WHERE tenant_id = #{tenantId} AND agent_id = #{agentId} AND source_tenant_id = #{sourceTenantId})")
    int addIfAbsent(@Param("tenantId") long tenantId, @Param("agentId") String agentId,
            @Param("sourceTenantId") long sourceTenantId);

    @Delete("DELETE FROM tenant_disabled_shared_agents WHERE tenant_id = #{tenantId} "
            + "AND agent_id = #{agentId} AND source_tenant_id = #{sourceTenantId}")
    int remove(@Param("tenantId") long tenantId, @Param("agentId") String agentId,
            @Param("sourceTenantId") long sourceTenantId);

    class DisabledRow {
        private String agentId;
        private Long sourceTenantId;

        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { this.agentId = v; }
        public Long getSourceTenantId() { return sourceTenantId; }
        public void setSourceTenantId(Long v) { this.sourceTenantId = v; }
    }
}
