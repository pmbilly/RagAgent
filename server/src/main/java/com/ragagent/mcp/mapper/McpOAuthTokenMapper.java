package com.ragagent.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpSecretTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * mcp_oauth_tokens 仓储语句。
 *
 * <p><b>principal 隔离</b>：唯一键
 * (tenant_id, principal_type, principal_id, service_id)，
 * 索引名 idx_mcp_oauth_tokens_tenant_principal_svc。<b>以 struct 为准，不按迁移。</b></p>
 *
 * <p><b>refresh 租约 CAS</b>：TryAcquire 是「条带式单所有者」语义——
 * 只有 {@code refresh_lease_until IS NULL OR < now} 的行才能被抢占，
 * 返回值以受影响行数 == 1 判定（0 行 = 未抢到/行不存在）。
 * <b>不能用「读-改-写」代替</b>，那会让两个实例同时成为所有者。</p>
 */
@Mapper
public interface McpOAuthTokenMapper extends BaseMapper<McpOAuthToken> {

    @Results(id = "mcpOAuthTokenResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "principal_type", property = "principalType"),
            @Result(column = "principal_id", property = "principalId"),
            @Result(column = "service_id", property = "serviceId"),
            @Result(column = "access_token", property = "accessToken",
                    typeHandler = McpSecretTypeHandler.class),
            @Result(column = "refresh_token", property = "refreshToken",
                    typeHandler = McpSecretTypeHandler.class),
            @Result(column = "token_type", property = "tokenType"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "refresh_lease_id", property = "refreshLeaseId"),
            @Result(column = "refresh_lease_until", property = "refreshLeaseUntil"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    @Select("SELECT * FROM mcp_oauth_tokens "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND service_id = #{serviceId} LIMIT 1")
    McpOAuthToken findForPrincipal(@Param("tenantId") long tenantId,
                                   @Param("principalType") String principalType,
                                   @Param("principalId") String principalId,
                                   @Param("serviceId") String serviceId);

    /**
     * ON CONFLICT ... DO UPDATE SET user_id, access_token, refresh_token,
     * token_type, expires_at, updated_at —— 租约列**不在**更新集里（Go 一致）。
     */
    @Update("UPDATE mcp_oauth_tokens SET user_id = #{userId}, "
            + "access_token = #{accessToken, typeHandler=com.ragagent.mcp.domain.McpSecretTypeHandler}, "
            + "refresh_token = #{refreshToken, typeHandler=com.ragagent.mcp.domain.McpSecretTypeHandler}, "
            + "token_type = #{tokenType}, expires_at = #{expiresAt}, updated_at = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND service_id = #{serviceId}")
    int updateExisting(McpOAuthToken token);

    @Delete("DELETE FROM mcp_oauth_tokens WHERE tenant_id = #{tenantId} "
            + "AND principal_type = #{principalType} AND principal_id = #{principalId} "
            + "AND service_id = #{serviceId}")
    int deleteForPrincipal(@Param("tenantId") long tenantId,
                           @Param("principalType") String principalType,
                           @Param("principalId") String principalId,
                           @Param("serviceId") String serviceId);

    /**
     * 对照 TryAcquireTokenRefreshLease：CAS 抢占，返回受影响行数。
     * 调用方以 {@code == 1} 判成功（行不存在或未过期都是 0）。
     */
    @Update("UPDATE mcp_oauth_tokens SET refresh_lease_id = #{leaseId}, "
            + "refresh_lease_until = #{leaseUntil} "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND service_id = #{serviceId} "
            + "AND (refresh_lease_until IS NULL OR refresh_lease_until < #{now})")
    int tryAcquireLease(@Param("tenantId") long tenantId,
                        @Param("principalType") String principalType,
                        @Param("principalId") String principalId,
                        @Param("serviceId") String serviceId,
                        @Param("leaseId") String leaseId,
                        @Param("leaseUntil") java.time.OffsetDateTime leaseUntil,
                        @Param("now") java.time.OffsetDateTime now);

    /**
     * 对照 ReleaseTokenRefreshLease：只有仍持有 leaseId 的调用者能释放
     * （release_lease_id 置回空串、until 置 NULL）。
     */
    @Update("UPDATE mcp_oauth_tokens SET refresh_lease_id = '', refresh_lease_until = NULL "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND service_id = #{serviceId} "
            + "AND refresh_lease_id = #{leaseId}")
    int releaseLease(@Param("tenantId") long tenantId,
                     @Param("principalType") String principalType,
                     @Param("principalId") String principalId,
                     @Param("serviceId") String serviceId,
                     @Param("leaseId") String leaseId);
}
