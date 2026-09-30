package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.mapper.TaskDeadLetterRepository;
import com.ragagent.wiki.service.WikiKnowledgeFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 摄取的失败结算面：批内失败的重试记账（incr fail count → 释放认领 / 归档死信）、
 * 子任务 finalizing 计数的排空。结算错误逐条返回，调用方据此判定批次是否可标记为已结算。
 */
final class WikiIngestSettleOps {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestSettleOps.class);

    private final WikiIngestService service;

    WikiIngestSettleOps(WikiIngestService service) {
        this.service = service;
    }

    /**
     * 该文档的 wiki op 到达终态
     * （成功映射或已进死信）时，释放它在 finalizing 计数里的槽位。
     *
     * <p>对应的 +1 是由 {@code KnowledgeProcessWorker} 在确定要生成 wiki 时
     * 晋升 finalizing 时播种的。<b>只能对 ingest op 调用</b>——
     * retract op 针对的是已删除的知识，没有计数器需要排空。</p>
     *
     * <p>对已完成、或计数已为 0 的行调用是安全的 no-op（递减与晋升都带条件守卫）。
     * 使用<b>脱钩的执行路径</b>：wiki 批次 worker
     * 可能正在关闭或父作用域已被取消，吞掉失败会把父文档永久留在 "finalizing"。</p>
     */
    void finalizeWikiSubtask(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        WikiKnowledgeFinalizer finalizer = service.knowledgeFinalizer.getIfAvailable();
        if (finalizer == null) {
            // finalizer 未接线：跳过（测试/裁剪装配）
            log.debug("wiki ingest: knowledge finalizer not wired, skipping subtask finalize for {}",
                    knowledgeId);
            return;
        }
        try (WikiCleanupScope scope = service.cleanupScope()) {
            scope.run(() -> finalizer.finalizeWikiSubtask(knowledgeId));
        }
    }

    /**
     * 记录批内失败。
     *
     * <p>对每个失败的 op：</p>
     * <ul>
     *   <li>对源行 {@code IncrFailCount}。仓储返回新总数，因此一次往返同时完成记账与
     *       重试预算判定。</li>
     *   <li>计数 {@code <= MAX_FAIL_RETRIES}：把行留在原处。下一个后续批次的
     *       PeekBatch 会自然拾起它（行按 id ASC 排序，我们从没动过它），
     *       并释放认领让它立即可再次认领。</li>
     *   <li>计数超限：把 op 归档到 {@code task_dead_letters} 并 {@code DeleteByIDs}
     *       把它从队列里移除。</li>
     * </ul>
     *
     * <p><b>返回结算错误列表</b>（返回列表比只留第一个更能暴露问题）。
     * 调用方在列表非空时不应把认领标记为"已结算"——行还在被认领或未被删除。</p>
     */
    List<Exception> requeueFailedOps(WikiIngestPayload payload, List<WikiPendingOp> ops) {
        List<Exception> settleErrors = new ArrayList<>();
        if (ops == null || ops.isEmpty()) {
            return settleErrors;
        }
        TaskDeadLetterRepository deadLetters = service.deadLetterRepo.getIfAvailable();

        for (WikiPendingOp op : ops) {
            if (op.getDbId() == 0) {
                // op 从未被持久化（合成 / 测试）—— 没有可重试的对象
                continue;
            }
            int count;
            try {
                count = service.pendingRepo.incrFailCount(op.getDbId());
            } catch (Exception e) {
                log.warn("wiki ingest: failed to increment fail count for {} (id={}): {}",
                        op.getKnowledgeId(), op.getDbId(), e.getMessage());
                settleErrors.add(new IllegalStateException(
                        "increment fail count id=" + op.getDbId() + ": " + e.getMessage(), e));
                // 拿不到新计数就无法判断该不该丢弃。保守处理：把行留在原处，
                // 下一次 PeekBatch 还会看到它，我们再试一次。
                continue;
            }
            if (count <= WikiIngestConstants.MAX_FAIL_RETRIES) {
                // 释放认领，让该行立刻可被下一个触发的 ClaimBatch 认领，
                // 而不必等 CLAIM_STALE_AFTER 过去。Lite 模式下是 no-op
                // （行只被窥视、从未被认领）。ReleaseByIDs 保留 fail_count，
                // 因此重试预算仍在递减。
                try {
                    service.pendingRepo.releaseByIds(List.of(op.getDbId()));
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to release claim for retry id={}: {}",
                            op.getDbId(), e.getMessage());
                    settleErrors.add(new IllegalStateException(
                            "release retry claim id=" + op.getDbId() + ": " + e.getMessage(), e));
                }
                log.info("wiki ingest: re-queued failed op {} ({}) for retry (attempt {}/{})",
                        op.getKnowledgeId(), op.docTitleOrEmpty(),
                        count, WikiIngestConstants.MAX_FAIL_RETRIES);
                continue;
            }

            // 批内重试已耗尽 —— 归档并移除。这是该 op 的终态失败点，
            // 因此释放它在文档 finalizing 计数里的槽位（只对 ingest op；
            // retract 针对的是已删除的知识，没有计数器要排空）。
            if (op.isIngest()) {
                finalizeWikiSubtask(op.getKnowledgeId());
            }
            log.warn("wiki ingest: dropping op {} ({}) after {} failures (limit {})",
                    op.getKnowledgeId(), op.docTitleOrEmpty(),
                    count, WikiIngestConstants.MAX_FAIL_RETRIES);
            if (deadLetters != null) {
                try {
                    TaskDeadLetter dl = new TaskDeadLetter();
                    dl.setTenantId(payload.tenantId());
                    dl.setTaskType(WikiIngestConstants.TASK_TYPE);
                    dl.setScope(WikiIngestConstants.TASK_SCOPE);
                    dl.setScopeId(payload.knowledgeBaseId());
                    dl.setRelatedId(op.getKnowledgeId());
                    dl.setPayload(WikiIngestService.MAPPER.valueToTree(op));
                    dl.setLastError("exceeded wikiMaxFailRetries="
                            + WikiIngestConstants.MAX_FAIL_RETRIES + " (in-batch retries)");
                    dl.setFailCount(count);
                    deadLetters.insert(dl);
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to archive op {} to dead letters: {}",
                            op.getKnowledgeId(), e.getMessage());
                    settleErrors.add(new IllegalStateException(
                            "archive dead letter id=" + op.getDbId() + ": " + e.getMessage(), e));
                }
            }
            try {
                service.pendingRepo.deleteByIds(List.of(op.getDbId()));
            } catch (Exception e) {
                log.warn("wiki ingest: failed to drop dead-lettered row id={}: {}",
                        op.getDbId(), e.getMessage());
                settleErrors.add(new IllegalStateException(
                        "drop dead-lettered row id=" + op.getDbId() + ": " + e.getMessage(), e));
            }
        }
        return settleErrors;
    }

    /**
     * 在脱钩路径上结算失败——批次超时/被取消时
     * 仍必须把失败记账与归档做完。
     */
    List<Exception> requeueFailedOpsDetached(WikiIngestPayload payload, List<WikiPendingOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return List.of();
        }
        java.util.concurrent.atomic.AtomicReference<List<Exception>> holder =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        try (WikiCleanupScope scope = service.cleanupScope()) {
            scope.run(() -> holder.set(requeueFailedOps(payload, ops)));
        }
        return holder.get();
    }
}
