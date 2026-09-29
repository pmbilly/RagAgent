package com.ragagent.wiki.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.mapper.TaskDeadLetterRepository;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import com.ragagent.wiki.service.WikiIngestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code task_pending_ops} / {@code task_dead_letters} 的仓储测试。
 *
 * <p><b>Go 侧没有直接对应的测试文件</b>：Go 的 task_queue 仓储测试依赖真 PG 的
 * {@code FOR UPDATE SKIP LOCKED}，跑在 repository 层的 SQLite 内存库里做不了。
 * Java 侧换了实现（可移植的<b>条件 UPDATE</b> 认领，见
 * {@link TaskPendingOpsRepository} 的类注释），因此<b>必须</b>有这一份测试把
 * 「认领的不变量」逐条钉住——否则"换实现"就成了无凭据的宣称。</p>
 *
 * <p>覆盖的不变量：</p>
 * <ol>
 *   <li>PeekBatch 是 FIFO（id ASC）、不移除行；</li>
 *   <li>ClaimBatch 按 <b>dedup_key</b> 计数（不是按行），同一 key 的多行一起被认领；</li>
 *   <li>新鲜认领会<b>阻塞它整个 dedup_key</b>；陈旧认领可被回收；</li>
 *   <li>ReleaseByIDs 让行立即重新可认领，且<b>保留 fail_count</b>；</li>
 *   <li>IncrFailCount 返回新值；</li>
 *   <li>DeleteByDedupKey 可以按 op 精确清理（保留 retract）；</li>
 *   <li>死信写入后可按 scope / task_type 查到。</li>
 * </ol>
 *
 * <p><b>⚠️ H2 与 PG 的差异</b>：本测试跑在 H2（VARCHAR 承载 jsonb、无 SKIP LOCKED），
 * 按约定 §9 的工具链坑，涉及方言的行必须在真 PG 上复验一次——
 * 已记入报告的"需决策的点"。</p>
 */
@SpringBootTest
class TaskQueueRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TaskPendingOpsRepository pendingRepo;
    @Autowired
    private TaskDeadLetterRepository deadLetterRepo;

    private static final String KB = "kb-1";

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    // ──────────────────────────── 夹具 ────────────────────────────

    private TaskPendingOp ingestRow(String knowledgeId, String op) {
        TaskPendingOp row = new TaskPendingOp();
        row.setTenantId(7L);
        row.setTaskType(WikiIngestConstants.TASK_TYPE);
        row.setScope(WikiIngestConstants.TASK_SCOPE);
        row.setScopeId(KB);
        row.setOp(op);
        row.setDedupKey(knowledgeId);
        try {
            row.setPayload(MAPPER.readTree("{\"op\":\"" + op + "\",\"knowledgeId\":\"" + knowledgeId + "\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private TaskDeadLetter deadLetter() throws Exception {
        TaskDeadLetter dl = new TaskDeadLetter();
        dl.setTenantId(7L);
        dl.setTaskType(WikiIngestConstants.TASK_TYPE);
        dl.setScope(WikiIngestConstants.TASK_SCOPE);
        dl.setScopeId(KB);
        dl.setRelatedId("k1");
        dl.setPayload(MAPPER.readTree("{\"op\":\"ingest\"}"));
        dl.setLastError("exceeded wikiMaxFailRetries=5 (in-batch retries)");
        dl.setFailCount(6);
        return dl;
    }

    private static OffsetDateTime staleBefore() {
        return OffsetDateTime.now().minus(WikiIngestConstants.CLAIM_STALE_AFTER);
    }

    // ──────────────────────────── PeekBatch ────────────────────────────

    @Test
    @DisplayName("PeekBatch 按 id ASC 返回、不删除行、不跨 scope_id")
    void peekBatchIsFifoAndNonDestructive() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k2", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k3", WikiIngestConstants.OP_INGEST));

        TaskPendingOp other = ingestRow("k9", WikiIngestConstants.OP_INGEST);
        other.setScopeId("kb-2");
        pendingRepo.enqueue(other);

        List<TaskPendingOp> first = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 2);
        assertThat(first).hasSize(2);
        assertThat(first).extracting(TaskPendingOp::getDedupKey).containsExactly("k1", "k2");

        // 行没有被移除
        List<TaskPendingOp> again = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10);
        assertThat(again).hasSize(3);
        assertThat(pendingRepo.pendingCount(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB)).isEqualTo(3L);
        assertThat(pendingRepo.pendingCount(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, "kb-2")).isEqualTo(1L);
    }

    @Test
    @DisplayName("PeekBatch 的 limit <= 0 返回空（避免一次拉全表）")
    void peekBatchNonPositiveLimit() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        assertThat(pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 0)).isEmpty();
    }

    // ──────────────────────────── ClaimBatch ────────────────────────────

    @Test
    @DisplayName("ClaimBatch 按 dedup_key 计数：同一文档的多行一起被认领，绝不跨批次拆分")
    void claimBatchCountsDistinctDedupKeys() {
        // k1 排了两个 op（ingest 后又 retract），k2 一个
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_RETRACT));
        pendingRepo.enqueue(ingestRow("k2", WikiIngestConstants.OP_INGEST));

        List<TaskPendingOp> claimed = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 1, staleBefore());

        // limit=1 表示"至多 1 个文档"，k1 的两行必须一起回来
        assertThat(claimed).hasSize(2);
        assertThat(claimed).extracting(TaskPendingOp::getDedupKey).containsOnly("k1");
        assertThat(claimed).allMatch(r -> r.getClaimedAt() != null);
    }

    @Test
    @DisplayName("新鲜认领阻塞它整个 dedup_key：第二批看不到任何 k1 的行")
    void freshClaimBlocksWholeDedupKey() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k2", WikiIngestConstants.OP_INGEST));

        List<TaskPendingOp> first = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10, staleBefore());
        assertThat(first).extracting(TaskPendingOp::getDedupKey).containsExactly("k1", "k2");

        // 第二批：全部被新鲜认领挡住 → 空
        List<TaskPendingOp> second = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10, staleBefore());
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("陈旧认领可被回收（崩溃 worker 留下的行）")
    void staleClaimsAreRecovered() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.claimBatch(WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10,
                staleBefore());

        // 把认领时间改成"很久以前"，模拟崩溃 worker
        jdbc.update("UPDATE task_pending_ops SET claimed_at = ? WHERE dedup_key = 'k1'",
                OffsetDateTime.now().minus(WikiIngestConstants.CLAIM_STALE_AFTER).minusMinutes(1));

        List<TaskPendingOp> reclaimed = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10, staleBefore());
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).getDedupKey()).isEqualTo("k1");
    }

    @Test
    @DisplayName("ReleaseByIDs 让行立刻重新可认领，并**保留** fail_count")
    void releaseByIdsMakesRowsClaimableAgain() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        List<TaskPendingOp> claimed = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10, staleBefore());
        long id = claimed.get(0).getId();

        pendingRepo.incrFailCount(id);
        pendingRepo.releaseByIds(List.of(id));

        List<TaskPendingOp> again = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10, staleBefore());
        assertThat(again).hasSize(1);
        assertThat(again.get(0).getFailCount())
                .as("重试预算必须继续递减，否则失败文档会无限重试")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ReleaseByIDs / DeleteByIDs 空入参是 no-op")
    void emptyIdListsAreNoops() {
        pendingRepo.releaseByIds(List.of());
        pendingRepo.releaseByIds(null);
        pendingRepo.deleteByIds(List.of());
        pendingRepo.deleteByIds(null);
    }

    // ──────────────────────────── 失败计数与删除 ────────────────────────────

    @Test
    @DisplayName("IncrFailCount 自增并返回新值；行不存在时返回 0")
    void incrFailCount() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        TaskPendingOp row = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 1).get(0);

        assertThat(pendingRepo.incrFailCount(row.getId())).isEqualTo(1);
        assertThat(pendingRepo.incrFailCount(row.getId())).isEqualTo(2);
        assertThat(pendingRepo.incrFailCount(999_999L)).isZero();
    }

    @Test
    @DisplayName("DeleteByIDs 删掉指定行")
    void deleteByIds() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k2", WikiIngestConstants.OP_INGEST));
        List<TaskPendingOp> rows = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10);

        pendingRepo.deleteByIds(List.of(rows.get(0).getId()));
        assertThat(pendingRepo.pendingCount(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB)).isEqualTo(1L);
    }

    @Test
    @DisplayName("DeleteByDedupKey 带 op 时只删该 op（保留 retract 以便清理 wiki 页）")
    void deleteByDedupKeyScopedByOp() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_RETRACT));

        pendingRepo.deleteByDedupKey(WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE,
                KB, "k1", WikiIngestConstants.OP_INGEST);

        List<TaskPendingOp> left = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10);
        assertThat(left).hasSize(1);
        assertThat(left.get(0).getOp()).isEqualTo(WikiIngestConstants.OP_RETRACT);

        // op 为空 → 不分 op 全删
        pendingRepo.deleteByDedupKey(WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE,
                KB, "k1", "");
        assertThat(pendingRepo.pendingCount(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB)).isZero();
    }

    @Test
    @DisplayName("DeleteByScope 丢弃已删除 scope 名下的全部待办（KB 被删时用）")
    void deleteByScope() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));
        pendingRepo.enqueue(ingestRow("k2", WikiIngestConstants.OP_RETRACT));
        TaskPendingOp other = ingestRow("k3", WikiIngestConstants.OP_INGEST);
        other.setScopeId("kb-2");
        pendingRepo.enqueue(other);

        pendingRepo.deleteByScope(WikiIngestConstants.TASK_SCOPE, KB);

        assertThat(pendingRepo.pendingCount(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB)).isZero();
        assertThat(pendingRepo.pendingCount(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, "kb-2")).isEqualTo(1L);
    }

    @Test
    @DisplayName("finalize 与 ingest 是同表不同 task_type 的两条通道，互不干扰")
    void finalizeLaneIsSeparate() {
        pendingRepo.enqueue(ingestRow("k1", WikiIngestConstants.OP_INGEST));

        TaskPendingOp finalizeRow = ingestRow("slug-row", WikiIngestConstants.FINALIZE_OP_SLUG);
        finalizeRow.setTaskType(WikiIngestConstants.FINALIZE_TASK_TYPE);
        finalizeRow.setDedupKey("entity/acme");
        pendingRepo.enqueue(finalizeRow);

        List<TaskPendingOp> ingest = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10);
        List<TaskPendingOp> finalize = pendingRepo.peekBatch(
                WikiIngestConstants.FINALIZE_TASK_TYPE, WikiIngestConstants.TASK_SCOPE, KB, 10);

        assertThat(ingest).hasSize(1);
        assertThat(ingest.get(0).getTaskType()).isEqualTo(WikiIngestConstants.TASK_TYPE);
        assertThat(finalize).hasSize(1);
        assertThat(finalize.get(0).getOp()).isEqualTo(WikiIngestConstants.FINALIZE_OP_SLUG);
    }

    // ──────────────────────────── 死信档案 ────────────────────────────

    @Test
    @DisplayName("死信可写入并按 scope / task_type 查到，limit 被钳到 [1,200]")
    void deadLetters() throws Exception {
        TaskDeadLetter dl = deadLetter();
        deadLetterRepo.insert(dl);

        assertThat(deadLetterRepo.listByScope(WikiIngestConstants.TASK_SCOPE, KB, "", 10).rows())
                .hasSize(1)
                .first()
                .satisfies(row -> {
                    assertThat(row.getRelatedId()).isEqualTo("k1");
                    assertThat(row.getFailCount()).isEqualTo(6);
                    assertThat(row.getFailedAt()).isNotNull();
                });

        assertThat(deadLetterRepo.listByTaskType(WikiIngestConstants.TASK_TYPE, "", 10).rows())
                .hasSize(1);

        assertThat(deadLetterRepo.listByScope(WikiIngestConstants.TASK_SCOPE, "other-kb", "", 10).rows())
                .isEmpty();

        // 游标分页：满页时给出下一页游标。
        // 注意必须新建对象：id 插入后被回填，复用同一个实例会带着已存在的主键再插一次
        // （这与 GORM 对非零主键的行为一致——两边都不会"自动忽略 id"）。
        TaskDeadLetter second = deadLetter();
        deadLetterRepo.insert(second);
        TaskDeadLetterRepository.CursorPage page =
                deadLetterRepo.listByScope(WikiIngestConstants.TASK_SCOPE, KB, "", 1);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.nextCursor()).isNotEmpty();

        // 按游标翻到第二页
        TaskDeadLetterRepository.CursorPage page2 =
                deadLetterRepo.listByScope(WikiIngestConstants.TASK_SCOPE, KB, page.nextCursor(), 10);
        assertThat(page2.rows()).hasSize(1);

        deadLetterRepo.deleteById(second.getId());
        assertThat(deadLetterRepo.listByScope(WikiIngestConstants.TASK_SCOPE, KB, "", 10).rows())
                .hasSize(1);
    }
}
