package com.ragagent.mcp.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.mcp.domain.McpToolApproval;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * mcp_tool_approvals 仓储语句。
 *
 * <p><b>缺行语义</b>：没有策略行 = enabled=true、require_approval=false，
 * 所以 {@link #selectEnabled} 返回 {@code null} 时调用方判 true，
 * {@link #selectRequireApproval} 返回 {@code null} 时判 false。</p>
 *
 * <p><b>为什么 UPDATE 用脚本而不是实体</b>：只写 patch 里出现过的列，避免"改 enabled 把
 * require_approval 抹掉"（{@code <if>} 表达）——
 * <b>不能用实体全列 UPDATE</b>，那会把未 patch 的列重置为实体默认值。</p>
 */
@Mapper
public interface McpToolApprovalMapper {

    @Results(id = "mcpToolApprovalResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "service_id", property = "serviceId"),
            @Result(column = "tool_name", property = "toolName"),
            @Result(column = "require_approval", property = "requireApproval"),
            @Result(column = "enabled", property = "enabled"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    @Select("SELECT * FROM mcp_tool_approvals WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} "
            + "ORDER BY tool_name ASC")
    List<McpToolApproval> listByService(@Param("tenantId") long tenantId,
                                        @Param("serviceId") String serviceId);

    /** 缺行 → null（调用方判 false） */
    @Select("SELECT require_approval FROM mcp_tool_approvals "
            + "WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} AND tool_name = #{toolName} "
            + "LIMIT 1")
    Boolean selectRequireApproval(@Param("tenantId") long tenantId,
                                  @Param("serviceId") String serviceId,
                                  @Param("toolName") String toolName);

    /** 缺行 → null（调用方判 true） */
    @Select("SELECT enabled FROM mcp_tool_approvals "
            + "WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} AND tool_name = #{toolName} "
            + "LIMIT 1")
    Boolean selectEnabled(@Param("tenantId") long tenantId,
                          @Param("serviceId") String serviceId,
                          @Param("toolName") String toolName);

    /**
     * 只写 patch 里出现过的策略列（+ updated_at）。
     * null 参数 = 该列不在 patch 中，保持原值。
     *
     * @return 受影响行数（0 = 行不存在）
     */
    @Update("<script>UPDATE mcp_tool_approvals SET updated_at = #{updatedAt}"
            + "<if test='requireApproval != null'>, require_approval = #{requireApproval}</if>"
            + "<if test='enabled != null'>, enabled = #{enabled}</if>"
            + " WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} AND tool_name = #{toolName}"
            + "</script>")
    int updatePolicy(@Param("tenantId") long tenantId,
                     @Param("serviceId") String serviceId,
                     @Param("toolName") String toolName,
                     @Param("requireApproval") Boolean requireApproval,
                     @Param("enabled") Boolean enabled,
                     @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 首次插入：<b>显式传值</b>，绝不依赖实体默认值——Java 的 boolean 零值是 false，
     * 而这里 enabled 的缺省必须是 true。
     */
    @Insert("INSERT INTO mcp_tool_approvals "
            + "(id, tenant_id, service_id, tool_name, require_approval, enabled, created_at, updated_at) "
            + "VALUES (#{id}, #{tenantId}, #{serviceId}, #{toolName}, #{requireApproval}, #{enabled}, "
            + "#{createdAt}, #{updatedAt})")
    int insertPolicy(@Param("id") String id,
                     @Param("tenantId") long tenantId,
                     @Param("serviceId") String serviceId,
                     @Param("toolName") String toolName,
                     @Param("requireApproval") boolean requireApproval,
                     @Param("enabled") boolean enabled,
                     @Param("createdAt") OffsetDateTime createdAt,
                     @Param("updatedAt") OffsetDateTime updatedAt);
}
