package com.ragagent.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpSecretTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * mcp_oauth_clients 仓储（对照 Go mcpOAuthRepository 的 client 部分，
 * internal/application/repository/mcp_oauth.go:25-55）。
 *
 * <p>唯一键 (tenant_id, service_id)，故 SaveClient 用 upsert 语义：
 * UPDATE 命中即更新，未命中再 INSERT（并用唯一键冲突兜住并发）。
 * Go 用 {@code clause.OnConflict + AssignmentColumns}，
 * DO UPDATE 列恰为 client_id / client_secret / redirect_uri / updated_at
 * ——<b>不含 created_at</b>。</p>
 */
@Mapper
public interface McpOAuthClientMapper extends BaseMapper<McpOAuthClient> {

    @Results(id = "mcpOAuthClientResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "service_id", property = "serviceId"),
            @Result(column = "client_id", property = "clientId"),
            @Result(column = "client_secret", property = "clientSecret",
                    typeHandler = McpSecretTypeHandler.class),
            @Result(column = "redirect_uri", property = "redirectUri"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    @Select("SELECT * FROM mcp_oauth_clients "
            + "WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} LIMIT 1")
    McpOAuthClient find(@Param("tenantId") long tenantId, @Param("serviceId") String serviceId);

    /** ON CONFLICT ... DO UPDATE SET client_id, client_secret, redirect_uri, updated_at */
    @Update("UPDATE mcp_oauth_clients SET client_id = #{clientId}, "
            + "client_secret = #{clientSecret, typeHandler=com.ragagent.mcp.domain.McpSecretTypeHandler}, "
            + "redirect_uri = #{redirectUri}, updated_at = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND service_id = #{serviceId}")
    int updateExisting(McpOAuthClient client);

    @Delete("DELETE FROM mcp_oauth_clients WHERE tenant_id = #{tenantId} AND service_id = #{serviceId}")
    int deleteForService(@Param("tenantId") long tenantId, @Param("serviceId") String serviceId);
}
