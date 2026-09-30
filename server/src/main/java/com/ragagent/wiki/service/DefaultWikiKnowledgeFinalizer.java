package com.ragagent.wiki.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link WikiKnowledgeFinalizer} 的默认实现：把文档从 {@code finalizing} 推向
 * {@code completed}。
 *
 * <h2>两步写入</h2>
 * <ol>
 *   <li><b>原子递减、钳在零</b>：{@code WHERE id = ? AND pending_subtasks_count > 0}
 *       ——那道守卫纯粹是记账 bug 的安全网（正常运行时每个子任务处理器每个任务至多
 *       递减一次，计数不可能变负）。</li>
 *   <li><b>带守卫的晋升</b>：<b>每一个</b>调用方在递减之后都<i>无条件</i>尝试这一步
 *       ——<b>绝不能</b>用一次单独的 SELECT 去判断计数器。
 *       那个读可能由滞后的只读副本（或陈旧连接快照）服务，即使主库上计数真的已经归零
 *       也可能返回非零；如果所有调用方都信那个陈旧读，就没有任何人执行晋升，
 *       行会永远搁浅在 {@code finalizing}（观测到的 "stuck pending_subtasks_count" bug）。
 *       晋升是一次<b>写</b>，因此在主库执行，其
 *       {@code pending_subtasks_count = 0} 的 WHERE 子句是对活行的唯一权威、原子检查：
 *       只有那个真正把计数减到零的调用方能匹配上，而 cancel/delete 也不会被迟到的晋升覆盖。</li>
 * </ol>
 *
 * <h2>Java 侧的处理</h2>
 * <p>上述陈旧读风险在当前单数据源装配下并不存在，但这里<b>仍然</b>保持
 * "无 SELECT、无条件尝试晋升"的形状——它同时也是并发正确性的来源（多个子任务同时归零时
 * 只有一个能匹配晋升的 WHERE）。{@code rows == 0} 视为静默成功（计数已被他人归零并晋升）。</p>
 */
@Component
public class DefaultWikiKnowledgeFinalizer implements WikiKnowledgeFinalizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultWikiKnowledgeFinalizer.class);

    private final KnowledgeMapper knowledgeMapper;

    public DefaultWikiKnowledgeFinalizer(KnowledgeMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    /** 供测试/可观测：本次调用是否真正发生了"计数归零后的晋升" */
    public record Outcome(boolean decremented, boolean promoted) { }

    @Override
    public void finalizeWikiSubtask(String knowledgeId) {
        Outcome outcome = finalizeSubtask(knowledgeId);
        if (outcome.promoted()) {
            log.info("wiki ingest: knowledge {} promoted to completed (wiki subtask drained)",
                    knowledgeId);
        }
    }

    /**
     * <p>用<b>脱钩的执行路径</b>（Java 侧没有贯穿调用的取消上下文，因此这里的要点是"不依赖调用方的中断
     * 状态"）：调用方在 wiki 批次 worker 可能正在关闭或父作用域已被取消时调用它，
     * 吞掉失败会把父文档永久留在 finalizing。</p>
     */
    public Outcome finalizeSubtask(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return new Outcome(false, false);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 1) 原子递减、钳在零
        boolean decremented;
        try {
            int rows = knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .gt("pending_subtasks_count", 0)
                    .setSql("pending_subtasks_count = pending_subtasks_count - 1")
                    .set("updated_at", now));
            decremented = rows > 0;
        } catch (RuntimeException e) {
            log.warn("finalize subtask decrement failed source=wiki knowledge={} err={}",
                    knowledgeId, e.getMessage());
            return new Outcome(false, false);
        }

        // 2) 带守卫的晋升（无条件尝试，见类注释）
        boolean promoted;
        try {
            int rows = knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .eq("parse_status", Knowledge.PARSE_FINALIZING)
                    .eq("pending_subtasks_count", 0)
                    .set("parse_status", Knowledge.PARSE_COMPLETED)
                    .set("error_message", "")
                    .set("processed_at", now)
                    .set("updated_at", now));
            promoted = rows > 0;
        } catch (RuntimeException e) {
            log.warn("finalize subtask promote failed source=wiki knowledge={} err={}",
                    knowledgeId, e.getMessage());
            return new Outcome(decremented, false);
        }
        return new Outcome(decremented, promoted);
    }
}
