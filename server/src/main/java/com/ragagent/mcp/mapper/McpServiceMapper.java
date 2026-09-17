package com.ragagent.mcp.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.mcp.domain.McpAuthConfigTypeHandler;
import com.ragagent.mcp.domain.McpService;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MCP 服务仓储（对照 Go internal/application/repository/mcp_service.go 的
 * mcpServiceRepository + mcp_metadata.go 挂在同一 struct 上的方法）。
 *
 * GORM 隐式行为清单（约定 §3）：
 * <ul>
 *   <li><b>软删除</b>：Go 的 {@code gorm.DeletedAt} 会自动给每条查询加
 *       {@code deleted_at IS NULL}——Java 侧**每个查询显式写出**（§9：不用 @TableLogic）</li>
 *   <li><b>内置服务可见性</b>：{@code tenant_id = ? OR is_builtin = true} 的括号必须保留，
 *       否则 ListEnabled 的 AND enabled 会把内置行漏掉</li>
 *   <li><b>排序</b>：List/ListEnabled 显式 {@code ORDER BY created_at DESC}（repository L51/L66）</li>
 *   <li><b>Update 的部分列语义</b>：Go 用 map + Updates 绕开零值省略——见 {@link #updatePartial}</li>
 *   <li>jsonb 列（headers/auth_config/advanced_config/stdio_config/env_vars）走 TypeHandler</li>
 * </ul>
 */
@Mapper
public interface McpServiceMapper extends BaseMapper<McpService> {

    /** 复用一份显式 ResultMap（jsonb 列需要 handler；其余按 map-underscore-to-camel-case 自动映射） */
    @Results(id = "mcpServiceResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "enabled", property = "enabled"),
            @Result(column = "transport_type", property = "transportType"),
            @Result(column = "url", property = "url"),
            @Result(column = "headers", property = "headers", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "auth_config", property = "authConfig",
                    typeHandler = McpAuthConfigTypeHandler.class),
            @Result(column = "advanced_config", property = "advancedConfig",
                    typeHandler = PgJsonTypeHandler.class),
            @Result(column = "stdio_config", property = "stdioConfig", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "env_vars", property = "envVars", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "is_builtin", property = "isBuiltin"),
            @Result(column = "usage_instructions", property = "usageInstructions"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "deleted_at", property = "deletedAt"),
    })
    @Select("SELECT * FROM mcp_services WHERE id = #{id} "
            + "AND (tenant_id = #{tenantId} OR is_builtin = TRUE) "
            + "AND deleted_at IS NULL LIMIT 1")
    McpService getByIdForTenant(@Param("tenantId") long tenantId, @Param("id") String id);

    /** 对照 repository List：租户自有 + 全部内置，created_at DESC */
    @ResultMap("mcpServiceResult")
    @Select("SELECT * FROM mcp_services "
            + "WHERE (tenant_id = #{tenantId} OR is_builtin = TRUE) AND deleted_at IS NULL "
            + "ORDER BY created_at DESC")
    List<McpService> listForTenant(@Param("tenantId") long tenantId);

    /** 对照 repository ListEnabled */
    @ResultMap("mcpServiceResult")
    @Select("SELECT * FROM mcp_services "
            + "WHERE (tenant_id = #{tenantId} OR is_builtin = TRUE) AND enabled = TRUE "
            + "AND deleted_at IS NULL ORDER BY created_at DESC")
    List<McpService> listEnabledForTenant(@Param("tenantId") long tenantId);

    /** 对照 repository ListByIDs：ids 为空时由 service 层短路，这里不做空 IN */
    @ResultMap("mcpServiceResult")
    @Select("<script>SELECT * FROM mcp_services "
            + "WHERE (tenant_id = #{tenantId} OR is_builtin = TRUE) AND deleted_at IS NULL "
            + "AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<McpService> listByIdsForTenant(@Param("tenantId") long tenantId, @Param("ids") List<String> ids);

    /**
     * 对照 repository Update（mcp_service.go:98-140）：**部分列**更新。
     *
     * Go 用 map + {@code Updates} 表达「未提供的字段不动」，Java 用 {@code <if>} 等价表达：
     * <ul>
     *   <li>恒写：updated_at（调用方赋值）、enabled、description、usage_instructions</li>
     *   <li>非空才写：name、transport_type</li>
     *   <li>非 nil 才写：url、stdio_config、env_vars、headers、auth_config、advanced_config</li>
     * </ul>
     * 因此 <b>无法通过本方法把 url 置为 NULL</b>（Go 同样如此，置空只能写空串）。
     *
     * <p>密钥语义：本方法会写 auth_config（若非 nil），但 **service 层保证
     * main PUT 路径不会把 apiKey/token 合进来**——见 {@code McpServiceService#updateMCPService}。</p>
     */
    @Update("<script>"
            + "UPDATE mcp_services SET updated_at = #{updatedAt}, enabled = #{enabled}"
            + "<if test=\"name != null and name != ''\">, name = #{name}</if>"
            + ", description = #{description}"
            + ", usage_instructions = #{usageInstructions}"
            + "<if test=\"transportType != null and transportType != ''\">"
            + ", transport_type = #{transportType}</if>"
            + "<if test=\"url != null\">, url = #{url}</if>"
            + "<if test=\"stdioConfig != null\">, stdio_config = "
            + "#{stdioConfig, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}</if>"
            + "<if test=\"envVars != null\">, env_vars = "
            + "#{envVars, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}</if>"
            + "<if test=\"headers != null\">, headers = "
            + "#{headers, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}</if>"
            + "<if test=\"authConfig != null\">, auth_config = "
            + "#{authConfig, typeHandler=com.ragagent.mcp.domain.McpAuthConfigTypeHandler}</if>"
            + "<if test=\"advancedConfig != null\">, advanced_config = "
            + "#{advancedConfig, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}</if>"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL"
            + "</script>")
    int updatePartial(McpService service);

    /** 对照 repository Delete：软删除（写 deleted_at），非物理删除 */
    @Update("UPDATE mcp_services SET deleted_at = #{deletedAt} "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int softDelete(@Param("tenantId") long tenantId, @Param("id") String id,
                   @Param("deletedAt") java.time.OffsetDateTime deletedAt);
}
