package com.ragagent.embed.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.embed.domain.EmbedChannelEntity;

/**
 * embed_channels 仓储（对照 Go internal/application/repository/embed_channel.go）。
 *
 * <p>GORM 的 {@code gorm.DeletedAt} 按约定翻成显式 {@code deleted_at IS NULL} 条件
 * （不用 @TableLogic）；{@code Save}（全列写）翻成显式 UPDATE 全列。</p>
 */
public interface EmbedChannelMapper extends BaseMapper<EmbedChannelEntity> {

    /** 对照 GetByID：软删行不可见（Go First + ErrRecordNotFound → nil, nil）。 */
    @Select("SELECT * FROM embed_channels WHERE id = #{id} AND deleted_at IS NULL")
    EmbedChannelEntity getById(@Param("id") String id);

    /** 对照 ListByAgent：created_at DESC。 */
    @Select("SELECT * FROM embed_channels WHERE tenant_id = #{tenantId} AND agent_id = #{agentId} "
            + "AND deleted_at IS NULL ORDER BY created_at DESC")
    List<EmbedChannelEntity> listByAgent(@Param("tenantId") long tenantId,
            @Param("agentId") String agentId);

    /** 对照 ListByTenant：created_at DESC。 */
    @Select("SELECT * FROM embed_channels WHERE tenant_id = #{tenantId} AND deleted_at IS NULL "
            + "ORDER BY created_at DESC")
    List<EmbedChannelEntity> listByTenant(@Param("tenantId") long tenantId);

    /**
     * 对照 repo.Create（GORM Create 全列写）。⚠️ Go 的 default:true 零值列
     * （enabled / show_suggested_questions）在 service 层已归一为 true 后才到这里。
     */
    @Insert("INSERT INTO embed_channels (id, tenant_id, agent_id, name, enabled, publish_token, "
            + "allowed_origins, welcome_message, rate_limit_per_minute, rate_limit_per_day, "
            + "primary_color, page_title, header_title_mode, show_suggested_questions, show_thinking, "
            + "widget_position, allow_web_search, allow_file_upload, default_locale, webhook_url, "
            + "webhook_secret, launcher_icon, created_at, updated_at) VALUES "
            + "(#{e.id}, #{e.tenantId}, #{e.agentId}, #{e.name}, #{e.enabled}, #{e.publishToken}, "
            + "#{e.allowedOrigins,typeHandler=com.ragagent.agent.management.mapper.JsonbRawStringTypeHandler}, "
            + "#{e.welcomeMessage}, #{e.rateLimitPerMinute}, #{e.rateLimitPerDay}, "
            + "#{e.primaryColor}, #{e.pageTitle}, #{e.headerTitleMode}, #{e.showSuggestedQuestions}, "
            + "#{e.showThinking}, #{e.widgetPosition}, #{e.allowWebSearch}, #{e.allowFileUpload}, "
            + "#{e.defaultLocale}, #{e.webhookUrl}, #{e.webhookSecret}, #{e.launcherIcon}, "
            + "#{e.createdAt}, #{e.updatedAt})")
    void insertChannel(@Param("e") EmbedChannelEntity e);

    /** 对照 repo.Update（GORM Save = 全列写，含零值）。 */
    @Update("UPDATE embed_channels SET agent_id = #{e.agentId}, name = #{e.name}, "
            + "enabled = #{e.enabled}, publish_token = #{e.publishToken}, "
            + "allowed_origins = #{e.allowedOrigins,typeHandler=com.ragagent.agent.management.mapper.JsonbRawStringTypeHandler}, "
            + "welcome_message = #{e.welcomeMessage}, rate_limit_per_minute = #{e.rateLimitPerMinute}, "
            + "rate_limit_per_day = #{e.rateLimitPerDay}, primary_color = #{e.primaryColor}, "
            + "page_title = #{e.pageTitle}, header_title_mode = #{e.headerTitleMode}, "
            + "show_suggested_questions = #{e.showSuggestedQuestions}, show_thinking = #{e.showThinking}, "
            + "widget_position = #{e.widgetPosition}, allow_web_search = #{e.allowWebSearch}, "
            + "allow_file_upload = #{e.allowFileUpload}, default_locale = #{e.defaultLocale}, "
            + "webhook_url = #{e.webhookUrl}, webhook_secret = #{e.webhookSecret}, "
            + "launcher_icon = #{e.launcherIcon}, updated_at = #{e.updatedAt} "
            + "WHERE id = #{e.id} AND deleted_at IS NULL")
    int saveChannel(@Param("e") EmbedChannelEntity e);

    /** 对照 repo.Delete（GORM Delete → 软删：deleted_at = now）。 */
    @Delete("DELETE FROM embed_channels WHERE tenant_id = #{tenantId} AND id = #{id} "
            + "AND deleted_at IS NULL")
    int deleteHard(@Param("tenantId") long tenantId, @Param("id") String id);

    /** 软删版（GORM Delete 的真实语义）。 */
    @Update("UPDATE embed_channels SET deleted_at = #{now} WHERE tenant_id = #{tenantId} "
            + "AND id = #{id} AND deleted_at IS NULL")
    int softDelete(@Param("tenantId") long tenantId, @Param("id") String id,
            @Param("now") OffsetDateTime now);
}
