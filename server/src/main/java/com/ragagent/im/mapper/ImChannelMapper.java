package com.ragagent.im.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.im.domain.ImChannelEntity;

/**
 * im_channels 仓储（对照 Go internal/im/service.go 的 CRUD 段 L3117-3267 与
 * migrations 000021/000023/000024/000028 的列）。
 */
public interface ImChannelMapper extends BaseMapper<ImChannelEntity> {

    /** 对照 GetChannelByIDAndTenant / ToggleChannel 的 First。 */
    @Select("SELECT * FROM im_channels WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_at IS NULL")
    ImChannelEntity getByIdAndTenant(@Param("id") String id, @Param("tenantId") long tenantId);

    /** 对照 GetChannelByID（service.go L1627-1633，W5a 回调面用）：无租户过滤。 */
    @Select("SELECT * FROM im_channels WHERE id = #{id} AND deleted_at IS NULL")
    ImChannelEntity getById(@Param("id") String id);

    /** LoadAndStartChannels 的启动读取（enabled 且未软删）。 */
    @Select("SELECT * FROM im_channels WHERE enabled = TRUE AND deleted_at IS NULL")
    java.util.List<ImChannelEntity> listEnabled();

    /** 对照 ListChannelsByAgent：created_at DESC。 */
    @Select("SELECT * FROM im_channels WHERE agent_id = #{agentId} AND tenant_id = #{tenantId} "
            + "AND deleted_at IS NULL ORDER BY created_at DESC")
    List<ImChannelEntity> listByAgent(@Param("agentId") String agentId,
            @Param("tenantId") long tenantId);

    /**
     * 对照 ListChannelsByTenant（L3151-3186）：LEFT JOIN custom_agents 回填 agent_name；
     * agent 被软删且非内建的行被排除；created_at DESC。返回投影行（键名对照
     * ChannelWithAgent 的列）。
     */
    @Select("<script>"
            + "SELECT c.id AS \"id\", c.tenant_id AS \"tenant_id\", c.agent_id AS \"agent_id\", "
            + "COALESCE(a.name, '') AS \"agent_name\", "
            + "c.platform AS \"platform\", c.name AS \"name\", c.enabled AS \"enabled\", "
            + "c.mode AS \"mode\", c.output_mode AS \"output_mode\", "
            + "c.session_mode AS \"session_mode\", c.bot_identity AS \"bot_identity\", "
            + "c.created_at AS \"created_at\", c.updated_at AS \"updated_at\" "
            + "FROM im_channels c LEFT JOIN custom_agents a "
            + "ON a.id = c.agent_id AND a.tenant_id = c.tenant_id AND a.deleted_at IS NULL "
            + "WHERE c.tenant_id = #{tenantId} AND c.deleted_at IS NULL "
            + "<if test=\"builtinIds != null and builtinIds.size() > 0\">"
            + "AND (a.id IS NOT NULL OR c.agent_id IN "
            + "<foreach item='b' collection='builtinIds' open='(' separator=',' close=')'>#{b}</foreach>)"
            + "</if>"
            + "<if test=\"builtinIds == null or builtinIds.size() == 0\">AND a.id IS NOT NULL</if>"
            + " ORDER BY c.created_at DESC"
            + "</script>")
    List<Map<String, Object>> listByTenantWithAgent(@Param("tenantId") long tenantId,
            @Param("builtinIds") List<String> builtinIds);

    /** 对照 checkDuplicateBot 的 First（部分唯一索引的查询形态）。 */
    @Select("<script>SELECT * FROM im_channels WHERE bot_identity = #{botIdentity} "
            + "AND deleted_at IS NULL "
            + "<if test=\"excludeId != null and excludeId != ''\">AND id != #{excludeId}</if> "
            + "LIMIT 1</script>")
    ImChannelEntity findByBotIdentity(@Param("botIdentity") String botIdentity,
            @Param("excludeId") String excludeId);

    /** 对照 db.Create（BeforeCreate 之后的列已由 service 备齐）。 */
    @Insert("INSERT INTO im_channels (id, tenant_id, agent_id, platform, name, enabled, mode, "
            + "output_mode, knowledge_base_id, bot_identity, session_mode, credentials, "
            + "created_at, updated_at) VALUES "
            + "(#{e.id}, #{e.tenantId}, #{e.agentId}, #{e.platform}, #{e.name}, #{e.enabled}, "
            + "#{e.mode}, #{e.outputMode}, #{e.knowledgeBaseId}, #{e.botIdentity}, #{e.sessionMode}, "
            + "#{e.credentials,typeHandler=com.ragagent.agentm.mapper.JsonbRawStringTypeHandler}, "
            + "#{e.createdAt}, #{e.updatedAt})")
    void insertChannel(@Param("e") ImChannelEntity e);

    /** 对照 db.Save（全列写；BeforeSave 后的 bot_identity 落列）。 */
    @Update("UPDATE im_channels SET agent_id = #{e.agentId}, platform = #{e.platform}, "
            + "name = #{e.name}, enabled = #{e.enabled}, mode = #{e.mode}, "
            + "output_mode = #{e.outputMode}, knowledge_base_id = #{e.knowledgeBaseId}, "
            + "bot_identity = #{e.botIdentity}, session_mode = #{e.sessionMode}, "
            + "credentials = #{e.credentials,typeHandler=com.ragagent.agentm.mapper.JsonbRawStringTypeHandler}, "
            + "updated_at = #{e.updatedAt} "
            + "WHERE id = #{e.id} AND deleted_at IS NULL")
    int saveChannel(@Param("e") ImChannelEntity e);

    /** 对照 DeleteChannel（软删；RowsAffected=0 → "channel not found"）。 */
    @Update("UPDATE im_channels SET deleted_at = #{now} WHERE id = #{id} "
            + "AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int softDelete(@Param("id") String id, @Param("tenantId") long tenantId,
            @Param("now") OffsetDateTime now);

    /** 对照 DeleteChannelsByAgent 的批量软删（agents 批 DeleteAgent 的依赖口，供后续接线）。 */
    @Delete("DELETE FROM im_channels WHERE agent_id = #{agentId} AND tenant_id = #{tenantId} "
            + "AND deleted_at IS NULL")
    int deleteHardByAgent(@Param("agentId") String agentId, @Param("tenantId") long tenantId);
}
