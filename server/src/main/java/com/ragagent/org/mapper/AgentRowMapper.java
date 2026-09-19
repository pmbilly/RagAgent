package com.ragagent.org.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.ragagent.org.domain.AgentRow;

/** custom_agents 只读投影（agents CRUD 面属另一批次）；CAST 让 H2/PG 同语句取文本。 */
public interface AgentRowMapper {
    @Select("SELECT id, name, description, avatar, is_builtin, tenant_id, created_by, "
            + "CAST(config AS VARCHAR(1048576)) AS config, created_at, updated_at "
            + "FROM custom_agents WHERE id = #{id} AND deleted_at IS NULL LIMIT 1")
    AgentRow getById(@Param("id") String id);
}
