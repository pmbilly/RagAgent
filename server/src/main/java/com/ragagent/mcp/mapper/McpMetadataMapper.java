package com.ragagent.mcp.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpMetadataSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * mcp_metadata 仓储语句（对照 Go internal/application/repository/mcp_metadata.go）。
 *
 * <p>复合主键 (tenant_id, service_id, principal)，MyBatis-Plus 的 BaseMapper
 * 无法表达，故本接口**刻意不继承 BaseMapper**，所有语句显式书写。</p>
 *
 * <p>版本拒绝旧写：Go 的 {@code OnConflict{... Where: "mcp_metadata.synced_at <= excluded.synced_at"}}
 * 等价于「只有当行内 synced_at 不新于本次 synced_at 时才覆盖」——
 * Java 用 {@code UPDATE ... AND synced_at <= #{syncedAt}} 表达，未命中则视为陈旧写入、
 * **不报错也不落库**（与 Go 完全相同：慢刷新不得覆盖新快照）。</p>
 */
@Mapper
public interface McpMetadataMapper {

    @Results(id = "mcpMetadataResult", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "service_id", property = "serviceId"),
            @Result(column = "principal", property = "principal"),
            @Result(column = "config_fingerprint", property = "configFingerprint"),
            @Result(column = "tools", property = "tools",
                    typeHandler = com.ragagent.mcp.domain.McpToolListTypeHandler.class),
            @Result(column = "instructions", property = "instructions"),
            @Result(column = "server_name", property = "serverName"),
            @Result(column = "server_version", property = "serverVersion"),
            @Result(column = "server_description", property = "serverDescription"),
            @Result(column = "synced_at", property = "syncedAt"),
    })
    @Select("SELECT * FROM mcp_metadata "
            + "WHERE tenant_id = #{tenant} AND service_id = #{service} AND principal = #{principal} "
            + "LIMIT 1")
    McpMetadata getMetadata(@Param("tenant") long tenant,
                            @Param("service") String service,
                            @Param("principal") String principal);

    /**
     * 对照 metadataToolCountExpr(db) == "postgres" 分支：jsonb_array_length。
     * 计数摘要**不返回 payload**——只 SELECT 计数与卡片字段。
     */
    @Select("<script>SELECT service_id, principal, config_fingerprint, synced_at, server_name, "
            + "jsonb_array_length(tools) AS tool_count FROM mcp_metadata "
            + "WHERE tenant_id = #{tenant} AND principal IN "
            + "<foreach collection='principals' item='p' open='(' separator=',' close=')'>#{p}</foreach>"
            + "</script>")
    List<McpMetadataSummary> listSummariesPostgres(@Param("tenant") long tenant,
                                                   @Param("principals") List<String> principals);

    /**
     * 非 postgres 分支：json_array_length。
     * （PostgreSQL 的 jsonb 列不能直接喂给 json_array_length，所以 Go 才按 db.Name() 分叉；
     * Java 侧同样分叉，测试库 H2 由 TestSchema 注册同名 ALIAS 提供该函数。）
     */
    @Select("<script>SELECT service_id, principal, config_fingerprint, synced_at, server_name, "
            + "json_array_length(tools) AS tool_count FROM mcp_metadata "
            + "WHERE tenant_id = #{tenant} AND principal IN "
            + "<foreach collection='principals' item='p' open='(' separator=',' close=')'>#{p}</foreach>"
            + "</script>")
    List<McpMetadataSummary> listSummariesDefault(@Param("tenant") long tenant,
                                                  @Param("principals") List<String> principals);

    /** 对照 OnConflict DO UPDATE 的列集（不含主键列），带 synced_at 版本护栏 */
    @Update("UPDATE mcp_metadata SET config_fingerprint = #{configFingerprint}, "
            + "tools = #{tools, typeHandler=com.ragagent.mcp.domain.McpToolListTypeHandler}, "
            + "instructions = #{instructions}, server_name = #{serverName}, "
            + "server_version = #{serverVersion}, server_description = #{serverDescription}, "
            + "synced_at = #{syncedAt} "
            + "WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} AND principal = #{principal} "
            + "AND synced_at <= #{syncedAt}")
    int updateIfNotOlder(McpMetadata snapshot);

    @Select("SELECT COUNT(*) FROM mcp_metadata "
            + "WHERE tenant_id = #{tenant} AND service_id = #{service} AND principal = #{principal}")
    int countByKey(@Param("tenant") long tenant,
                   @Param("service") String service,
                   @Param("principal") String principal);

    @Insert("INSERT INTO mcp_metadata (tenant_id, service_id, principal, config_fingerprint, tools, "
            + "instructions, server_name, server_version, server_description, synced_at) VALUES ("
            + "#{tenantId}, #{serviceId}, #{principal}, #{configFingerprint}, "
            + "#{tools, typeHandler=com.ragagent.mcp.domain.McpToolListTypeHandler}, "
            + "#{instructions}, #{serverName}, #{serverVersion}, #{serverDescription}, #{syncedAt})")
    int insertSnapshot(McpMetadata snapshot);

    /** 供测试/上层判定「行是否存在」，避免误用 count 的语义 */
    @Select("SELECT synced_at FROM mcp_metadata WHERE tenant_id = #{tenant} AND service_id = #{service} "
            + "AND principal = #{principal}")
    OffsetDateTime selectSyncedAt(@Param("tenant") long tenant,
                                  @Param("service") String service,
                                  @Param("principal") String principal);
}
