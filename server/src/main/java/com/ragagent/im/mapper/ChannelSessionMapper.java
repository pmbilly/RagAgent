package com.ragagent.im.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.ragagent.im.domain.ChannelSessionEntity;

/**
 * IM 渠道会话映射的 SQL 面（会话解析查询 + /clear 的软删）。
 * 表 DDL：迁移 000021（TestSchema 已有同形）。
 */
@Mapper
public interface ChannelSessionMapper {

    String COLS = "id, platform, user_id, chat_id, thread_id, session_id, tenant_id, "
            + "agent_id, im_channel_id, status, metadata, created_at, updated_at";

    /** resolveUserSession 的查找（user 模式五元组）。 */
    @Select("SELECT " + COLS + " FROM im_channel_sessions "
            + "WHERE platform = #{platform} AND user_id = #{userId} AND chat_id = #{chatId} "
            + "AND tenant_id = #{tenantId} AND agent_id = #{agentId} AND deleted_at IS NULL "
            + "ORDER BY created_at ASC LIMIT 1")
    ChannelSessionEntity findUserSession(@Param("platform") String platform,
            @Param("userId") String userId, @Param("chatId") String chatId,
            @Param("tenantId") long tenantId, @Param("agentId") String agentId);

    /** resolveThreadSession 的查找（thread 模式：platform+chat+thread+tenant+agent）。 */
    @Select("SELECT " + COLS + " FROM im_channel_sessions "
            + "WHERE platform = #{platform} AND chat_id = #{chatId} AND thread_id = #{threadId} "
            + "AND tenant_id = #{tenantId} AND agent_id = #{agentId} AND deleted_at IS NULL "
            + "ORDER BY created_at ASC LIMIT 1")
    ChannelSessionEntity findThreadSession(@Param("platform") String platform,
            @Param("chatId") String chatId, @Param("threadId") String threadId,
            @Param("tenantId") long tenantId, @Param("agentId") String agentId);

    /** 按 session id 查（消息面反查会话来源）。 */
    @Select("SELECT " + COLS + " FROM im_channel_sessions "
            + "WHERE session_id = #{sessionId} AND deleted_at IS NULL "
            + "ORDER BY created_at ASC LIMIT 1")
    ChannelSessionEntity findBySessionId(@Param("sessionId") String sessionId);

    /** 入库前置字段由 service 层备齐（ID 空时 UUID、status 空兜底 active）。 */
    @Insert("INSERT INTO im_channel_sessions (id, platform, user_id, chat_id, thread_id, "
            + "session_id, tenant_id, agent_id, im_channel_id, status, metadata, "
            + "created_at, updated_at, deleted_at) "
            + "VALUES (#{e.id}, #{e.platform}, #{e.userId}, #{e.chatId}, #{e.threadId}, "
            + "#{e.sessionId}, #{e.tenantId}, #{e.agentId}, #{e.imChannelId}, #{e.status}, "
            + "#{e.metadata,typeHandler=com.ragagent.agent.management.mapper.JsonbRawStringTypeHandler}, "
            + "#{now}, #{now}, NULL)")
    int insert(@Param("e") ChannelSessionEntity e, @Param("now") OffsetDateTime now);

    /** /clear 命令：软删当前映射（下条消息解析出新会话；CommandActionClear）。 */
    @Update("UPDATE im_channel_sessions SET deleted_at = #{now} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int softDelete(@Param("id") String id, @Param("now") OffsetDateTime now);

    /** 同一会话的历史映射（/clear 落 LLM 上下文清理时查来源）。 */
    @Select("SELECT id FROM im_channel_sessions "
            + "WHERE platform = #{platform} AND user_id = #{userId} AND chat_id = #{chatId} "
            + "AND tenant_id = #{tenantId} AND agent_id = #{agentId} AND deleted_at IS NULL")
    List<String> findUserSessionIds(@Param("platform") String platform,
            @Param("userId") String userId, @Param("chatId") String chatId,
            @Param("tenantId") long tenantId, @Param("agentId") String agentId);
}
