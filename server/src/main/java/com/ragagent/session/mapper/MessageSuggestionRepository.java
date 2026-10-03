package com.ragagent.session.mapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.session.domain.MessageSuggestionEvent;
import com.ragagent.session.domain.MessageSuggestionSet;
import com.ragagent.session.domain.MessageSuggestionSetNotFoundException;
import org.springframework.stereotype.Component;

/**
 * 追问建议仓储。
 *
 * <h2>落库隐式行为清单</h2>
 * <ol>
 *   <li><b>Create 钩子</b>：ID 为空时才生成 UUID、nil 的 questions 置空切片
 *       → {@link #acquireGeneration} 里调 {@code normalizeForInsert()}。</li>
 *   <li><b>失败/抑制收尾的落库</b>：逐列写（绕开零值跳过），保证
 *       {@code suppression_reason}/{@code error_code} 会被**写成空串**、
 *       {@code generated_at} 会被**清成 NULL**。用字符串列名的 UpdateWrapper，
 *       不能用 lambda 形式（jsonb 列要显式带 typeHandler）。</li>
 *   <li><b>无软删除列</b>：本表没有 {@code DeletedAt}，两处 Delete 是**硬删**——
 *       与 sessions/messages 的软删不同，别套用。</li>
 *   <li><b>唯一索引</b>：{@code AcquireGeneration} 的"插入或什么都不做"依赖
 *       {@code (tenant_id, assistant_message_id, placement, config_hash, locale)} 唯一。</li>
 * </ol>
 */
@Component
public class MessageSuggestionRepository {

    /** 生成租约时长。 */
    private static final Duration LEASE_TTL = Duration.ofMinutes(3);

    private static final String QUESTIONS_HANDLER =
            "com.ragagent.session.domain.SuggestionItemListTypeHandler";

    private final MessageSuggestionMapper mapper;
    private final boolean postgres;

    public MessageSuggestionRepository(MessageSuggestionMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.postgres = detectPostgres(dataSource);
    }

    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /** 五元组命中；零行抛错。 */
    public MessageSuggestionSet getByCacheKey(long tenantId, String assistantMessageId,
            String placement, String configHash, String locale) {
        MessageSuggestionSet set = mapper.selectOne(new LambdaQueryWrapper<MessageSuggestionSet>()
                .eq(MessageSuggestionSet::getTenantId, tenantId)
                .eq(MessageSuggestionSet::getAssistantMessageId, assistantMessageId)
                .eq(MessageSuggestionSet::getPlacement, placement)
                .eq(MessageSuggestionSet::getConfigHash, configHash)
                .eq(MessageSuggestionSet::getLocale, locale));
        if (set == null) {
            throw new MessageSuggestionSetNotFoundException();
        }
        return set;
    }

    public MessageSuggestionSet getById(long tenantId, String sessionId, String id) {
        MessageSuggestionSet set = mapper.selectOne(new LambdaQueryWrapper<MessageSuggestionSet>()
                .eq(MessageSuggestionSet::getId, id)
                .eq(MessageSuggestionSet::getTenantId, tenantId)
                .eq(MessageSuggestionSet::getSessionId, sessionId));
        if (set == null) {
            throw new MessageSuggestionSetNotFoundException();
        }
        return set;
    }

    /**
     * 抢占某条消息的生成权。
     *
     * <p>返回值第二项 {@code acquired} 表示"这次是否真的由本方开始生成"：
     * 已有 ready/suppressed 的结果（且未要求重新生成）会直接复用，
     * 别人还握着未过期的租约时也复用。</p>
     */
    public AcquireResult acquireGeneration(MessageSuggestionSet candidate, boolean regenerate) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseUntil = now.plus(LEASE_TTL);

        candidate.setStatus(MessageSuggestionSet.STATUS_GENERATING);
        candidate.setLeaseUntil(leaseUntil);
        candidate.setQuestions(List.of());
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        candidate.normalizeForInsert();

        int inserted = postgres
                ? mapper.insertIfAbsentPostgres(candidate)
                : mapper.insertIfAbsentOther(candidate);
        if (inserted == 1) {
            return new AcquireResult(candidate, true);
        }

        MessageSuggestionSet existing = getByCacheKey(candidate.getTenantId(),
                candidate.getAssistantMessageId(), candidate.getPlacement(),
                candidate.getConfigHash(), candidate.getLocale());

        if (!regenerate && (MessageSuggestionSet.STATUS_READY.equals(existing.getStatus())
                || MessageSuggestionSet.STATUS_SUPPRESSED.equals(existing.getStatus()))) {
            return new AcquireResult(existing, false);
        }
        if (MessageSuggestionSet.STATUS_GENERATING.equals(existing.getStatus())
                && existing.getLeaseUntil() != null && existing.getLeaseUntil().isAfter(now)) {
            return new AcquireResult(existing, false);
        }

        UpdateWrapper<MessageSuggestionSet> w = new UpdateWrapper<MessageSuggestionSet>()
                .eq("id", existing.getId());
        if (MessageSuggestionSet.STATUS_READY.equals(existing.getStatus()) && regenerate) {
            // 重新生成只能从 ready 抢；其余状态走下面那条更宽的条件
            w.eq("status", MessageSuggestionSet.STATUS_READY);
        } else {
            w.and(q -> q.ne("status", MessageSuggestionSet.STATUS_GENERATING)
                    .or().isNull("lease_until")
                    .or().lt("lease_until", now));
        }
        w.set("status", MessageSuggestionSet.STATUS_GENERATING)
                .set("lease_until", leaseUntil)
                .set("suppression_reason", "")
                .set("questions", List.of(), "typeHandler=" + QUESTIONS_HANDLER)
                .set("error_code", "")
                .set("generated_at", null)
                .set("updated_at", now);

        int affected = mapper.update(null, w);
        if (affected == 0) {
            // 没抢到：别人在这几步之间改写了它，重新读一遍当前值返回
            return new AcquireResult(getByCacheKey(candidate.getTenantId(),
                    candidate.getAssistantMessageId(), candidate.getPlacement(),
                    candidate.getConfigHash(), candidate.getLocale()), false);
        }

        existing.setStatus(MessageSuggestionSet.STATUS_GENERATING);
        existing.setLeaseUntil(leaseUntil);
        existing.setQuestions(List.of());
        return new AcquireResult(existing, true);
    }

    /**
     * 保存结果：先 UPDATE，零行则 INSERT。
     *
     * <p>注意不是 upsert，语义上有细微差别（并发下的插入冲突仍会报错）。</p>
     */
    public void save(MessageSuggestionSet set) {
        if (set == null) {
            throw new IllegalArgumentException("message suggestion set is nil");
        }
        set.setUpdatedAt(OffsetDateTime.now());
        UpdateWrapper<MessageSuggestionSet> w = new UpdateWrapper<MessageSuggestionSet>()
                .eq("id", set.getId())
                .set("status", set.getStatus())
                .set("allow_regenerate", set.isAllowRegenerate())
                .set("suppression_reason", set.getSuppressionReason())
                .set("questions", set.getQuestions(), "typeHandler=" + QUESTIONS_HANDLER)
                .set("model_id", set.getModelId())
                .set("prompt_tokens", set.getPromptTokens())
                .set("completion_tokens", set.getCompletionTokens())
                .set("latency_ms", set.getLatencyMs())
                .set("error_code", set.getErrorCode())
                .set("lease_until", set.getLeaseUntil())
                .set("generated_at", set.getGeneratedAt())
                .set("updated_at", set.getUpdatedAt());
        if (mapper.update(null, w) == 0) {
            mapper.insert(set);
        }
    }

    public void createEvent(MessageSuggestionEvent event) {
        event.setCreatedAt(OffsetDateTime.now());
        mapper.insertEvent(event);
    }

    /** **硬删**（本表无软删列）。 */
    public void deleteByMessageId(long tenantId, String sessionId, String messageId) {
        mapper.delete(new LambdaQueryWrapper<MessageSuggestionSet>()
                .eq(MessageSuggestionSet::getTenantId, tenantId)
                .eq(MessageSuggestionSet::getSessionId, sessionId)
                .eq(MessageSuggestionSet::getAssistantMessageId, messageId));
    }

    /** **硬删**。 */
    public void deleteBySessionId(long tenantId, String sessionId) {
        mapper.delete(new LambdaQueryWrapper<MessageSuggestionSet>()
                .eq(MessageSuggestionSet::getTenantId, tenantId)
                .eq(MessageSuggestionSet::getSessionId, sessionId));
    }

    /** {@code AcquireGeneration} 的返回：命中的集合 + 是否由本方抢到生成权。 */
    public record AcquireResult(MessageSuggestionSet set, boolean acquired) {
    }
}
