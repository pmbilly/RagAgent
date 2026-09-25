package com.ragagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.TestSchema;

/**
 * 知识管家清扫（对照 Go {@code knowledge_housekeeping.go} + 其测试套件）的 H2 钉子：
 *
 * <ul>
 *   <li>清扫 A：卡死的 pending/processing 行 → failed（含阈值文案与子任务计数清零）；</li>
 *   <li>心跳过滤：有新鲜 span 心跳的行不被误杀、心跳过期的仍被回收；</li>
 *   <li>第二道闸：瞬时队列仍有活的行保留（探测失败按仍卡死处理）；wiki 持久 op 命中的行
 *       保留（<b>不依赖 inspector</b>——Lite 装配下 inspector 为 null）；</li>
 *   <li>清扫 B：summary_status=processing 且陈旧 → failed；</li>
 *   <li>阈值与开关：{@code WEKNORA_DOCUMENT_PROCESS_TIMEOUT} 的 Go duration 解析与回落、
 *       {@code WEKNORA_HOUSEKEEPING_ENABLED} 的缺省开启语义。</li>
 * </ul>
 *
 * <p>阈值口径照 Go 测试：DocumentProcessTimeout 传 1 小时 ⇒ 1h 下限 + 10min 缓冲 =
 * 70 分钟 cutoff，测试里的相对时间（-3h / 现在）因此远离边界。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HousekeepingServiceTest {

    private static final Duration TEST_DOCUMENT_PROCESS_TIMEOUT = Duration.ofHours(1);

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    /** 可控 inspector：{@code queued} 命中的知识算"队列里还有活"；{@code fail} 强制探测出错。 */
    private static final class FakeInspector implements HousekeepingService.KnowledgeQueueInspector {
        private final java.util.Set<String> queued = new java.util.LinkedHashSet<>();
        private boolean fail;
        private int calls;

        @Override
        public boolean hasQueuedTasksForKnowledge(String knowledgeId) {
            calls++;
            if (fail) {
                throw new IllegalStateException("probe unavailable");
            }
            return queued.contains(knowledgeId);
        }
    }

    private HousekeepingService service(HousekeepingService.KnowledgeQueueInspector inspector) {
        return new HousekeepingService(jdbc, inspector, TEST_DOCUMENT_PROCESS_TIMEOUT, true);
    }

    private HousekeepingService service() {
        return service(new FakeInspector());
    }

    private String knowledge(String parseStatus, String summaryStatus, int staleMinutes,
                             int pendingSubtasks) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                        + "source, channel, parse_status, pending_subtasks_count, summary_status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 10002, 'kb-1', 'file', 't', 'file', '', ?, ?, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, parseStatus, pendingSubtasks, summaryStatus);
        ageRow(id, staleMinutes);
        return id;
    }

    private void ageRow(String id, int minutes) {
        jdbc.update("UPDATE knowledges SET updated_at = DATEADD('MINUTE', -" + minutes
                + ", CURRENT_TIMESTAMP) WHERE id = ?", id);
    }

    private void insertSpan(String knowledgeId, int staleMinutes) {
        jdbc.update("INSERT INTO knowledge_processing_spans (knowledge_id, attempt, span_id, name, "
                        + "kind, status, started_at, updated_at) "
                        + "VALUES (?, 1, ?, 'docreader', 'stage', 'running', "
                        + "DATEADD('MINUTE', -" + staleMinutes + ", CURRENT_TIMESTAMP), "
                        + "DATEADD('MINUTE', -" + staleMinutes + ", CURRENT_TIMESTAMP))",
                knowledgeId, UUID.randomUUID().toString());
    }

    private void insertDurableWikiOp(String knowledgeId) {
        jdbc.update("INSERT INTO task_pending_ops (tenant_id, task_type, scope, scope_id, op, "
                        + "dedup_key) VALUES (10002, ?, ?, 'kb-1', ?, ?)",
                HousekeepingService.WIKI_TASK_TYPE, HousekeepingService.WIKI_TASK_SCOPE,
                HousekeepingService.WIKI_OP_INGEST, knowledgeId);
    }

    private String parseStatus(String id) {
        return jdbc.queryForObject("SELECT parse_status FROM knowledges WHERE id = ?",
                String.class, id);
    }

    private String errorMessage(String id) {
        return jdbc.queryForObject("SELECT error_message FROM knowledges WHERE id = ?",
                String.class, id);
    }

    private int pendingSubtasks(String id) {
        return jdbc.queryForObject("SELECT pending_subtasks_count FROM knowledges WHERE id = ?",
                Integer.class, id);
    }

    // ── 清扫 A ────────────────────────────────────────────────────────────

    @Test
    void recoversAbandonedProcessingRow() {
        String id = knowledge("processing", "none", 180, 2);
        service().runSweep();

        assertThat(parseStatus(id)).isEqualTo("failed");
        assertThat(errorMessage(id)).isEqualTo(
                "task stuck in processing > 1h10m0s, recovered by housekeeping");
        assertThat(pendingSubtasks(id)).isZero();
    }

    @Test
    void recoversPendingRowMissingFromQueue() {
        String id = knowledge("pending", "none", 180, 0);
        service().runSweep();
        assertThat(parseStatus(id)).isEqualTo("failed");
    }

    @Test
    void recoversFinalizingRowToo() {
        String id = knowledge("finalizing", "none", 180, 0);
        service().runSweep();
        assertThat(parseStatus(id)).isEqualTo("failed");
    }

    @Test
    void preservesRecentlyTouchedRow() {
        String id = knowledge("processing", "none", 0, 0);
        service().runSweep();
        assertThat(parseStatus(id)).isEqualTo("processing").as("未过阈值的行不动");
    }

    // ── 心跳过滤 ──────────────────────────────────────────────────────────

    @Test
    void preservesRowWithActiveSpanHeartbeat() {
        String id = knowledge("processing", "none", 180, 0);
        insertSpan(id, 0); // 行陈旧但 span 心跳新鲜 → 仍在推进
        service().runSweep();
        assertThat(parseStatus(id)).isEqualTo("processing");
    }

    @Test
    void recoversRowWithStaleSpanHeartbeat() {
        String id = knowledge("processing", "none", 180, 0);
        insertSpan(id, 180); // span 也过期 → 真卡死
        service().runSweep();
        assertThat(parseStatus(id)).isEqualTo("failed");
    }

    // ── 第二道闸（队列仍有活）────────────────────────────────────────────

    @Test
    void preservesRowStillQueued() {
        FakeInspector inspector = new FakeInspector();
        String id = knowledge("pending", "none", 180, 0);
        inspector.queued.add(id);
        service(inspector).runSweep();

        assertThat(parseStatus(id)).isEqualTo("pending").as("积压 ≠ 孤儿");
        assertThat(inspector.calls).isEqualTo(1);
    }

    @Test
    void recoversWhenQueueProbeFails() {
        FakeInspector inspector = new FakeInspector();
        inspector.fail = true;
        String id = knowledge("processing", "none", 180, 0);
        service(inspector).runSweep();

        assertThat(parseStatus(id)).isEqualTo("failed").as("探测失败按仍卡死处理（失败安全）");
    }

    @Test
    void preservesRowWithDurableWikiIngestEvenWithoutInspector() {
        String id = knowledge("finalizing", "none", 180, 1);
        insertDurableWikiOp(id);
        // Lite 装配：inspector 为 null，持久表这道闸必须独立生效
        service(null).runSweep();

        assertThat(parseStatus(id)).isEqualTo("finalizing");
    }

    @Test
    void stillRecoversWhenNoDurableOp() {
        String id = knowledge("finalizing", "none", 180, 1);
        service(null).runSweep();
        assertThat(parseStatus(id)).isEqualTo("failed");
    }

    // ── 清扫 B（摘要）─────────────────────────────────────────────────────

    @Test
    void recoversStuckSummaryAndPreservesFreshOne() {
        String stale = knowledge("completed", "processing", 120, 0);
        String fresh = knowledge("completed", "processing", 0, 0);
        service().runSweep();

        assertThat(jdbc.queryForObject("SELECT summary_status FROM knowledges WHERE id = ?",
                String.class, stale)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT summary_status FROM knowledges WHERE id = ?",
                String.class, fresh)).isEqualTo("processing");
    }

    // ── 阈值与开关 ────────────────────────────────────────────────────────

    @Test
    void staleThresholdFollowsDocumentProcessTimeoutWithFloorAndBuffer() {
        assertThat(new HousekeepingService(jdbc, null, Duration.ofHours(1), true).staleThreshold())
                .isEqualTo(Duration.ofMinutes(70));
        // 小于 1 小时下限 → 仍取 1h + 10min
        assertThat(new HousekeepingService(jdbc, null, Duration.ofMinutes(30), true).staleThreshold())
                .isEqualTo(Duration.ofMinutes(70));
        assertThat(new HousekeepingService(jdbc, null, Duration.ofHours(3), true).staleThreshold())
                .isEqualTo(Duration.ofMinutes(190));
    }

    @Test
    void documentProcessTimeoutFromEnvParsesGoDurations() {
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv((String) null))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv(""))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("  "))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("2h"))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("90m"))
                .isEqualTo(Duration.ofMinutes(90));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("1h30m"))
                .isEqualTo(Duration.ofMinutes(90));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("45s"))
                .isEqualTo(Duration.ofSeconds(45));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("500ms"))
                .isEqualTo(Duration.ofMillis(500));
        // 文法错误 / 非正数 → 回落缺省（照 Go 的 ParseDuration 失败分支）
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("bogus"))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("5"))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("-5m"))
                .isEqualTo(Duration.ofHours(2));
        assertThat(HousekeepingService.documentProcessTimeoutFromEnv("0s"))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void housekeepingEnabledDefaultsOnAndOptsOutExplicitly() {
        assertThat(HousekeepingService.housekeepingEnabledFromEnv((String) null)).isTrue();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("")).isTrue();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("true")).isTrue();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("yes")).isTrue();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("0")).isFalse();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("FALSE")).isFalse();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("off")).isFalse();
        assertThat(HousekeepingService.housekeepingEnabledFromEnv("no")).isFalse();
    }

    @Test
    void disabledServiceLogsAndDoesNotSweep() {
        String id = knowledge("processing", "none", 180, 0);
        HousekeepingService disabled =
                new HousekeepingService(jdbc, null, TEST_DOCUMENT_PROCESS_TIMEOUT, false);
        disabled.start();

        assertThat(disabled.enabled()).isFalse();
        assertThat(parseStatus(id)).isEqualTo("processing").as("关停时不启动清扫循环");
    }
}
