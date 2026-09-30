package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.wiki.domain.TaskPendingOp;
import org.slf4j.Logger;

import com.ragagent.wiki.service.ingest.WikiIngestService.PendingBatch;
import org.slf4j.LoggerFactory;

/**
 * wiki 摄取的队列消费面：按 FIFO 窥视/原子认领待办 op（last-write-wins 去重）、
 * 已消费行的删除（含脱钩路径）。PendingBatch 载体留在门面，跨包消费方不受影响。
 */
final class WikiIngestQueueOps {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestQueueOps.class);

    private final WikiIngestService service;

    WikiIngestQueueOps(WikiIngestService service) {
        this.service = service;
    }

    /**
     * 为该 KB 按 FIFO 载入最多
     * {@code limit} 条 op。<b>行不会被移除</b>；消费后必须
     * {@code DeleteByIDs}（或 {@code IncrFailCount} 后留着给下一轮）。
     *
     * <p>{@code peekedIds} 返回被窥视到的<b>每一行</b>的 db id（不只是通过去重的那些），
     * 好让 {@code trimPendingList} 在批次末尾一条语句删光——这对应历史的
     * "LTrim peekedCount 条"语义：被消费者按 dedup 折叠掉的重复行，
     * 也在其规范兄弟被处理之后一并排空。</p>
     */
    PendingBatch peekPendingList(String kbId, int limit) {
        int effective = limit <= 0 ? WikiIngestConstants.MAX_DOCS_PER_BATCH : limit;
        List<TaskPendingOp> rows = service.pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, kbId, effective);
        return decodePendingRows(rows);
    }

    /**
     * {@code peekPendingList} 在
     * standard（分布式协调）模式下的对应物——原子地<b>认领</b>最多 {@code limit}
     * 条 op（标记 {@code claimed_at}），让同一 KB 的并发批次拉到<b>互不相交</b>的
     * 文档而不是重复处理。
     * 陈旧认领（早于 {@code CLAIM_STALE_AFTER}，即来自崩溃 worker）会被回收。
     *
     * <p>去重 / peekedIds 语义与 {@code peekPendingList} 相同；返回的 peekedIds 是
     * 已认领、调用方必须在成功时 {@code DeleteByIDs} 或失败时 {@code ReleaseByIDs}
     * 的那些行。</p>
     */
    PendingBatch claimPendingList(String kbId, int limit) {
        int effective = limit <= 0 ? WikiIngestConstants.MAX_DOCS_PER_BATCH : limit;
        java.time.OffsetDateTime staleBefore =
                java.time.OffsetDateTime.now().minus(WikiIngestConstants.CLAIM_STALE_AFTER);
        List<TaskPendingOp> rows = service.pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, kbId, effective, staleBefore);
        return decodePendingRows(rows);
    }

    /**
     * 把原始行转成
     * {@link WikiPendingOp}，并按 knowledge_id 施加 last-write-wins 去重。
     *
     * <p>去重只保留每篇文档<b>最后</b>一个操作，从而优化掉冗余序列
     * （例如"刚上传就删除"：{@code [ingest, retract]} → {@code [retract]}）。
     * 非规范行仍会在 trim 时被排空——它们的 dbID 就在 peekedIDs 里。</p>
     */
    PendingBatch decodePendingRows(List<TaskPendingOp> rows) {
        if (rows == null || rows.isEmpty()) {
            return new PendingBatch(List.of(), List.of());
        }
        List<WikiPendingOp> all = new ArrayList<>(rows.size());
        List<Long> peekedIds = new ArrayList<>(rows.size());
        for (TaskPendingOp r : rows) {
            peekedIds.add(r.getId());
            WikiPendingOp op;
            JsonNode payload = r.getPayload();
            if (payload != null) {
                try {
                    op = WikiIngestService.MAPPER.treeToValue(payload, WikiPendingOp.class);
                    if (op == null) {
                        op = new WikiPendingOp();
                    }
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to unmarshal pending op id={}: {}",
                            r.getId(), e.getMessage());
                    continue;
                }
            } else {
                // 防御：载荷丢失时回落到列数据，让该行仍可被排空
                // （否则它会每批次都因"删不掉"而空转）
                op = new WikiPendingOp(r.getOp(), r.getDedupKey());
            }
            op.setDbId(r.getId());
            all.add(op);
        }

        Set<String> seen = new HashSet<>();
        List<WikiPendingOp> reversedUnique = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) {
            WikiPendingOp op = all.get(i);
            if (op.getKnowledgeId().isEmpty()) {
                // 没有去重键 —— 原样保留（罕见；留给未来没有知识锚点的 op）
                reversedUnique.add(op);
                continue;
            }
            if (!seen.add(op.getKnowledgeId())) {
                continue;
            }
            reversedUnique.add(op);
        }

        List<WikiPendingOp> ops = new ArrayList<>(reversedUnique.size());
        for (int i = reversedUnique.size() - 1; i >= 0; i--) {
            ops.add(reversedUnique.get(i));
        }
        return new PendingBatch(ops, peekedIds);
    }

    /**
     * 删除已消费的行。
     * 空入参是 no-op，因此调用方可以在批次结束时无条件调用。
     *
     * @throws RuntimeException 删除失败；调用方据此让批次结算失败
     */
    void trimPendingList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            service.pendingRepo.deleteByIds(ids);
        } catch (RuntimeException e) {
            log.warn("wiki ingest: failed to trim {} pending rows: {}", ids.size(), e.getMessage());
            throw e;
        }
    }

    /**
     * 在<b>脱钩</b>的清理路径上删除已消费的行——父作用域已被取消/中断时
     * 删除仍要执行（有回归测试覆盖该行为）。
     */
    void trimPendingListDetached(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try (WikiCleanupScope scope = service.cleanupScope()) {
            scope.run(() -> trimPendingList(ids));
        }
    }
}
