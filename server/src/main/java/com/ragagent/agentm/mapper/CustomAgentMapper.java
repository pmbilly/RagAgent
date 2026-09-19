package com.ragagent.agentm.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.agentm.domain.CustomAgentEntity;

/**
 * custom_agents CRUD（对照 Go internal/application/repository/custom_agent.go）。
 *
 * <p>Go 侧全部经 GORM（软删自动拼 deleted_at IS NULL）。写路径走显式 SQL，
 * config 列经 {@link JsonbRawStringTypeHandler}（setObject(OTHER)→PG 按列类型强转、
 * H2 落 VARCHAR，一套语句两侧通用）；读路径显式 CAST 成文本（org 包 AgentRowMapper 同款）。</p>
 */
public interface CustomAgentMapper extends BaseMapper<CustomAgentEntity> {

    String COLS = "id, name, description, avatar, is_builtin, tenant_id, created_by, "
            + "CAST(config AS VARCHAR(1048576)) AS config, created_at, updated_at";

    /** 对照 GetAgentByID：id + tenant + 软删过滤。 */
    @Select("SELECT " + COLS + " FROM custom_agents "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL LIMIT 1")
    CustomAgentEntity getByIDAndTenant(@Param("id") String id, @Param("tenantId") long tenantId);

    /** 对照 ListAgentsByTenantID：created_at DESC（Go 显式 Order 子句）。 */
    @Select("SELECT " + COLS + " FROM custom_agents "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at DESC")
    List<CustomAgentEntity> listByTenant(@Param("tenantId") long tenantId);

    /** 对照 CreateAgent（GORM Create：全列插入）。 */
    @Insert("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
            + "created_by, config, created_at, updated_at, deleted_at) VALUES "
            + "(#{e.id}, #{e.name}, #{e.description}, #{e.avatar}, #{e.isBuiltin}, #{e.tenantId}, "
            + "#{e.createdBy}, #{e.config, typeHandler=com.ragagent.agentm.mapper.JsonbRawStringTypeHandler}, "
            + "#{e.createdAt}, #{e.updatedAt}, NULL)")
    void insertAgent(@Param("e") CustomAgentEntity e);

    /** 对照 UpdateAgent（GORM Save：全列更新）。 */
    @Update("UPDATE custom_agents SET name = #{e.name}, description = #{e.description}, "
            + "avatar = #{e.avatar}, is_builtin = #{e.isBuiltin}, created_by = #{e.createdBy}, "
            + "config = #{e.config, typeHandler=com.ragagent.agentm.mapper.JsonbRawStringTypeHandler}, "
            + "created_at = #{e.createdAt}, updated_at = #{e.updatedAt} "
            + "WHERE id = #{e.id} AND tenant_id = #{e.tenantId}")
    int updateAgent(@Param("e") CustomAgentEntity e);

    /** 对照 DeleteAgent（GORM 软删：UPDATE deleted_at）。 */
    @Update("UPDATE custom_agents SET deleted_at = NOW() "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int softDelete(@Param("id") String id, @Param("tenantId") long tenantId);
}
