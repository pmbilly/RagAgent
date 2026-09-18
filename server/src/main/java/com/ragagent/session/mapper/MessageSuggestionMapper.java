package com.ragagent.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.session.domain.MessageSuggestionEvent;
import com.ragagent.session.domain.MessageSuggestionSet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 追问建议两张表的 MyBatis-Plus 基础仓储（对照 Go
 * internal/application/repository/message_suggestion.go）。
 *
 * <p><b>ON CONFLICT DO NOTHING 的方言分叉</b>：Go 用 GORM 的
 * {@code clause.OnConflict{DoNothing: true}}，在 PG / SQLite 上都能生成
 * {@code ON CONFLICT (...) DO NOTHING}；<b>H2 不支持这个子句</b>，所以这里按方言给两条 SQL。
 * 语义相同（唯一键冲突时插入零行），区别只是 H2 那条不是原子的——测试环境够用。</p>
 */
@Mapper
public interface MessageSuggestionMapper extends BaseMapper<MessageSuggestionSet> {

    /**
     * 抢占生成权：靠唯一索引 {@code (tenant_id, assistant_message_id, placement, config_hash, locale)}
     * 做"插入或什么都不做"（对照 Go {@code AcquireGeneration} 的前半段，L70-78）。
     *
     * @return 1 = 这次真的插入了（抢到）；0 = 已存在（没抢到）
     */
    @Insert("INSERT INTO message_suggestion_sets "
            + "(id, tenant_id, session_id, assistant_message_id, agent_id, agent_tenant_id, "
            + " placement, config_hash, locale, status, allow_regenerate, suppression_reason, "
            + " questions, model_id, prompt_tokens, completion_tokens, latency_ms, error_code, "
            + " lease_until, generated_at, created_at, updated_at) "
            + "VALUES (#{s.id}, #{s.tenantId}, #{s.sessionId}, #{s.assistantMessageId}, "
            + "        #{s.agentId}, #{s.agentTenantId}, #{s.placement}, #{s.configHash}, "
            + "        #{s.locale}, #{s.status}, #{s.allowRegenerate}, #{s.suppressionReason}, "
            + "        #{s.questions, typeHandler=com.ragagent.session.domain.SuggestionItemListTypeHandler, "
            + "         jdbcType=OTHER}, "
            + "        #{s.modelId}, #{s.promptTokens}, #{s.completionTokens}, #{s.latencyMs}, "
            + "        #{s.errorCode}, #{s.leaseUntil}, #{s.generatedAt}, "
            + "        #{s.createdAt}, #{s.updatedAt}) "
            + "ON CONFLICT (tenant_id, assistant_message_id, placement, config_hash, locale) DO NOTHING")
    int insertIfAbsentPostgres(@Param("s") MessageSuggestionSet set);

    /** H2 没有 {@code ON CONFLICT}——用等价的条件插入（见类注释）。 */
    @Insert("INSERT INTO message_suggestion_sets "
            + "(id, tenant_id, session_id, assistant_message_id, agent_id, agent_tenant_id, "
            + " placement, config_hash, locale, status, allow_regenerate, suppression_reason, "
            + " questions, model_id, prompt_tokens, completion_tokens, latency_ms, error_code, "
            + " lease_until, generated_at, created_at, updated_at) "
            + "SELECT #{s.id}, #{s.tenantId}, #{s.sessionId}, #{s.assistantMessageId}, "
            + "       #{s.agentId}, #{s.agentTenantId}, #{s.placement}, #{s.configHash}, "
            + "       #{s.locale}, #{s.status}, #{s.allowRegenerate}, #{s.suppressionReason}, "
            + "       #{s.questions, typeHandler=com.ragagent.session.domain.SuggestionItemListTypeHandler, "
            + "        jdbcType=OTHER}, "
            + "       #{s.modelId}, #{s.promptTokens}, #{s.completionTokens}, #{s.latencyMs}, "
            + "       #{s.errorCode}, #{s.leaseUntil}, #{s.generatedAt}, "
            + "       #{s.createdAt}, #{s.updatedAt} "
            + "WHERE NOT EXISTS (SELECT 1 FROM message_suggestion_sets WHERE "
            + "  tenant_id = #{s.tenantId} AND assistant_message_id = #{s.assistantMessageId} "
            + "  AND placement = #{s.placement} AND config_hash = #{s.configHash} "
            + "  AND locale = #{s.locale})")
    int insertIfAbsentOther(@Param("s") MessageSuggestionSet set);

    /** 分析事件表是另一张表，单独一个 BaseMapper 入口不方便，这里直接给插入语句。 */
    @Insert("INSERT INTO message_suggestion_events "
            + "(tenant_id, session_id, suggestion_set_id, question_id, event_type, actor_id, created_at) "
            + "VALUES (#{e.tenantId}, #{e.sessionId}, #{e.suggestionSetId}, #{e.questionId}, "
            + "        #{e.eventType}, #{e.actorId}, #{e.createdAt})")
    int insertEvent(@Param("e") MessageSuggestionEvent event);
}
