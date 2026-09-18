package com.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.memory.domain.MemoryExtractionBatch;
import com.ragagent.memory.domain.MemoryExtractionFailure;
import com.ragagent.memory.domain.MemoryExtractionLeaseLostException;
import com.ragagent.memory.domain.MemoryExtractionSession;
import com.ragagent.memory.domain.MemoryMessageCursor;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.mapper.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 抽取进度的租约语义（对照 Go internal/application/repository/memory_extraction.go 全 230 行）。
 *
 * <p>这一块**全靠 {@code withSubject} 的行锁 + 租约判定**撑起来：</p>
 * <ul>
 *   <li>租约没过期 → 别的 worker 只能拿到 {@code retryAt}，不能起第二批；</li>
 *   <li>{@code revision} 在一次并发 enqueue 之后会变，于是 checkpoint 必须让该会话**保持 pending**
 *       ——否则那几轮就永远丢了；</li>
 *   <li>重试预算只在"卡在同一处"时累加；</li>
 *   <li>租约对不上时：checkpoint / recordFailure / finish 抛异常，而 release 静默返回。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryExtractionRepositoryTest {

    private static final long TENANT = 10002L;
    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MemoryRepository repo;

    private MemoryScope scope;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        for (String table : List.of("memory_item_embeddings", "memory_extraction_sessions",
                "memory_items", "memory_tombstones", "memory_topic_stats", "memory_doc_affinity",
                "memory_subjects")) {
            jdbc.execute("DELETE FROM " + table);
        }
        scope = new MemoryScope(TENANT, "web_user:u1");
        repo.ensureSubject(scope);
    }

    private static final OffsetDateTime BASE = OffsetDateTime.now();

    /**
     * 一个固定时刻的游标。
     *
     * <p>⚠️ 用 {@code OffsetDateTime.now()} 会让"后构造的 id"带上**更晚**的时间，
     * 于是 {@code after} 判定被时间而不是 id 决定——测"游标只前进"时必须固定时刻。
     * （Go 的 {@code After} 也是"先比时间、同刻再比 id"。）</p>
     */
    private MemoryMessageCursor cursor(String id) {
        return new MemoryMessageCursor(BASE, id);
    }

    /** 时间更晚的游标。 */
    private MemoryMessageCursor laterCursor(String id) {
        return new MemoryMessageCursor(BASE.plusMinutes(1), id);
    }

    // ── Enqueue ────────────────────────────────────────────────────────────

    @Test
    void enqueueMarksInFlightOnlyWhenNothingElseIsRunning() {
        MemoryRepository.EnqueueResult first =
                repo.enqueuePendingSession(scope, "sess-1", Duration.ofSeconds(90));
        assertThat(first.shouldSend()).isTrue();
        assertThat(first.subject().getExtractScheduledAt()).as("快照是更新前的").isNull();
        assertThat(repo.getSubject(scope).getExtractScheduledAt()).isNotNull();

        // 第二次：已经在途（extract_scheduled_at 很近）→ 不再投递
        MemoryRepository.EnqueueResult second =
                repo.enqueuePendingSession(scope, "sess-2", Duration.ofSeconds(90));
        assertThat(second.shouldSend()).isFalse();
        assertThat(second.subject().getExtractScheduledAt())
                .as("快照里带着上一次的在途标记")
                .isNotNull();
    }

    /** 超过 in-flight 超时窗口之后，又该投递一次。 */
    @Test
    void enqueueSendsAgainOnceTheInflightWindowHasPassed() {
        // ⚠️ **不要靠墙钟造"窗口已经过去"**：写成"第一次用 90s 窗口、第二次用 1ms 窗口"，
        // 就要求两次调用间隔 ≥1ms——全量跑（JIT/GC 压力下）两者可能落在同一毫秒，
        // 于是 `now - scheduled < 1ms` 仍成立、shouldSend 为 false，**测试随机红**。
        // （本轮就是这么发现的：单跑绿、全量红。Go 侧没有这个用例，没有可照抄的写法。）
        //
        // 改成确定性造法：直接把 extract_scheduled_at 推到窗口之外。
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofSeconds(90));
        assertThat(jdbc.update("UPDATE memory_subjects SET extract_scheduled_at = ?",
                OffsetDateTime.now().minusSeconds(300))).isEqualTo(1);

        MemoryRepository.EnqueueResult after =
                repo.enqueuePendingSession(scope, "sess-2", Duration.ofSeconds(5));
        assertThat(after.shouldSend()).isTrue();
    }

    /** 真的有租约在跑时不投递（{@code running} 分支优先于 {@code queued}）。 */
    @Test
    void enqueueIsSilentWhileALeaseIsStillRunning() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofSeconds(90));
        MemoryExtractionBatch claimed =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);
        assertThat(claimed.hasSessions()).isTrue();

        MemoryRepository.EnqueueResult result =
                repo.enqueuePendingSession(scope, "sess-2", Duration.ofMillis(1));
        assertThat(result.shouldSend()).isFalse();
    }

    /** 同一个会话重复 enqueue 只自增 revision，不会插出第二行（唯一键 + DO UPDATE）。 */
    @Test
    void enqueueIsIdempotentPerSessionAndBumpsRevision() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_extraction_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT revision FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Long.class)).isEqualTo(2L);
    }

    @Test
    void enqueueIgnoresEmptySessionId() {
        repo.enqueuePendingSession(scope, "", Duration.ofMillis(1));
        repo.enqueuePendingSession(scope, null, Duration.ofMillis(1));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_extraction_sessions", Integer.class)).isZero();
    }

    // ── Claim ──────────────────────────────────────────────────────────────

    @Test
    void claimReturnsRetryAtWhenTheLeaseIsBusy() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionBatch first = repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);
        assertThat(first.hasSessions()).isTrue();

        MemoryExtractionBatch second = repo.claimPendingSessions(scope, "sess-2", "lease-B", TTL);
        assertThat(second.hasSessions()).as("不该拿到第二批").isFalse();
        assertThat(second.getRetryAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void claimReturnsNullWhenThereIsNoWork() {
        assertThat(repo.claimPendingSessions(scope, "", "lease-A", TTL)).isNull();
    }

    /** 认领时按 {@code updated_at ASC, session_id ASC} 取批，最老的先处理。 */
    @Test
    void claimReturnsTheOldestPendingSessionsFirst() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        jdbc.update("UPDATE memory_extraction_sessions SET updated_at = ? WHERE session_id = 'sess-1'",
                OffsetDateTime.now().minusHours(2));
        repo.enqueuePendingSession(scope, "sess-2", Duration.ofMillis(1));
        jdbc.update("UPDATE memory_extraction_sessions SET updated_at = ? WHERE session_id = 'sess-2'",
                OffsetDateTime.now().minusHours(1));

        MemoryExtractionBatch batch = repo.claimPendingSessions(scope, "", "lease-A", TTL);
        assertThat(batch.getSessions()).extracting(MemoryExtractionSession::getSessionId)
                .containsExactly("sess-1", "sess-2");
    }

    /** 认领**不移除**持久工作：会话行仍在，只是被租下。 */
    @Test
    void claimKeepsDurableWorkInPlace() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_extraction_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT pending FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Boolean.class)).isTrue();
    }

    // ── Checkpoint ─────────────────────────────────────────────────────────

    @Test
    void checkpointAdvancesTheCursorAndKeepsItPendingWhenNotDrained() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionBatch batch = repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);
        MemoryExtractionSession session = batch.getSessions().get(0);

        repo.checkpointExtraction(scope, "lease-A", session, cursor("msg-9"), false);

        assertThat(jdbc.queryForObject(
                "SELECT cursor_id FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                String.class)).isEqualTo("msg-9");
        assertThat(jdbc.queryForObject(
                "SELECT pending FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Boolean.class)).as("没排干 → 仍待处理").isTrue();
    }

    @Test
    void checkpointDrainsWhenDrainedAndRevisionUnchanged() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);

        repo.checkpointExtraction(scope, "lease-A", session, cursor("msg-9"), true);

        assertThat(jdbc.queryForObject(
                "SELECT pending FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Boolean.class)).isFalse();
    }

    /**
     * 并发 enqueue 在 checkpoint 之前把 {@code revision} 抬高了 →
     * 这个会话必须**保持 pending**，否则那几轮永远不会被处理。
     */
    @Test
    void checkpointKeepsPendingWhenRevisionHasMovedOn() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);

        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        repo.checkpointExtraction(scope, "lease-A", session, cursor("msg-9"), true);

        assertThat(jdbc.queryForObject(
                "SELECT pending FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Boolean.class)).isTrue();
    }

    /** 游标只前进：落后者不能把进度推回去。 */
    @Test
    void checkpointNeverMovesTheCursorBackwards() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);

        repo.checkpointExtraction(scope, "lease-A", session, laterCursor("msg-b"), false);
        // 更早的游标必须被忽略
        repo.checkpointExtraction(scope, "lease-A", session, cursor("msg-a"), false);

        assertThat(jdbc.queryForObject(
                "SELECT cursor_id FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                String.class)).isEqualTo("msg-b");
    }

    /** 没失败过的时候 checkpoint 会重置失败计数（Go 的 {@code if progress.FailedAt == nil}）。 */
    @Test
    void checkpointResetsFailureCountersWhenThereWasNoFailure() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);

        repo.checkpointExtraction(scope, "lease-A", session, cursor("m"), false);

        assertThat(jdbc.queryForObject(
                "SELECT failure_count FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT failure_code FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                String.class)).isEmpty();
    }

    @Test
    void checkpointThrowsWhenTheLeaseIsLost() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);

        assertThatThrownBy(() -> repo.checkpointExtraction(scope, "lease-B", session,
                cursor("m"), false))
                .isInstanceOf(MemoryExtractionLeaseLostException.class)
                .hasMessage("memory extraction lease lost");
    }

    /** 租约**过期**（而不只是 id 不符）也算丢失。 */
    @Test
    void checkpointThrowsWhenTheLeaseHasExpired() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", Duration.ofSeconds(1))
                        .getSessions().get(0);
        jdbc.update("UPDATE memory_subjects SET extraction_state = "
                + "'{\"lease_id\":\"lease-A\",\"lease_until\":\"2000-01-01T00:00:00Z\"}'");

        assertThatThrownBy(() -> repo.checkpointExtraction(scope, "lease-A", session,
                cursor("m"), false))
                .isInstanceOf(MemoryExtractionLeaseLostException.class);
    }

    // ── RecordFailure ──────────────────────────────────────────────────────

    /** 重试预算只在"失败区间起点 == 当前游标"（卡在同一处）时累加。 */
    @Test
    void recordFailureAccumulatesOnlyWhenStuckAtTheSameCursor() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);
        repo.checkpointExtraction(scope, "lease-A", session, cursor("stuck"), false);
        // 租约仍在有效期内，所以**不能**再 claim（会拿到 retryAt）——worker 继续用手里那一批
        MemoryExtractionSession stuck = session;

        assertThat(repo.recordExtractionFailure(scope, "lease-A",
                new MemoryExtractionFailure(stuck, cursor("end"), "bad_output"))).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT failure_count FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT failure_code FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                String.class)).isEqualTo("bad_output");

        assertThat(repo.recordExtractionFailure(scope, "lease-A",
                new MemoryExtractionFailure(stuck, cursor("end"), "bad_output"))).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT failure_count FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                Integer.class)).isEqualTo(2);

        assertThat(repo.recordExtractionFailure(scope, "lease-A",
                new MemoryExtractionFailure(stuck, cursor("end"), "bad_output")))
                .as("第三次该跳过了").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT failed_at FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                OffsetDateTime.class)).as("跳过时落失败时刻").isNotNull();
    }

    /** 失败区间保存下来了，但**不存对话原文**。 */
    @Test
    void recordFailurePreservesTheFailureRangeWithoutTranscript() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        MemoryExtractionSession session =
                repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL).getSessions().get(0);
        repo.checkpointExtraction(scope, "lease-A", session, laterCursor("from"), false);
        MemoryExtractionSession stuck = session;

        repo.recordExtractionFailure(scope, "lease-A",
                new MemoryExtractionFailure(stuck, cursor("to"), "bad_output"));

        assertThat(jdbc.queryForObject(
                "SELECT failed_from_id FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                String.class)).isEqualTo("from");
        assertThat(jdbc.queryForObject(
                "SELECT failed_to_id FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                String.class)).isEqualTo("to");
        assertThat(jdbc.queryForObject(
                "SELECT failed_at FROM memory_extraction_sessions WHERE session_id = 'sess-1'",
                OffsetDateTime.class)).as("没到预算时 failed_at 保持 NULL").isNull();
    }

    // ── Finish / Release ───────────────────────────────────────────────────

    @Test
    void finishClearsTheLeaseAndRecordsLastExtractedAt() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);

        repo.finishExtraction(scope, "lease-A");

        assertThat(jdbc.queryForObject("SELECT extraction_state FROM memory_subjects", String.class))
                .isEqualTo("{\"lease_until\":\"0001-01-01T00:00:00Z\"}");
        assertThat(jdbc.queryForObject("SELECT extract_scheduled_at FROM memory_subjects",
                OffsetDateTime.class)).isNull();
        assertThat(repo.getSubject(scope).getLastExtractedAt()).isNotNull();
    }

    /** {@code FinishExtraction} 的租约判定**只看 id、不看是否过期**（与 checkpoint 不同）。 */
    @Test
    void finishThrowsWhenTheLeaseIdDoesNotMatch() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);

        assertThatThrownBy(() -> repo.finishExtraction(scope, "lease-B"))
                .isInstanceOf(MemoryExtractionLeaseLostException.class);
    }

    /** {@code ReleaseExtractionSlot} 的租约不符是**静默返回**，不是错误。 */
    @Test
    void releaseIsSilentWhenTheLeaseIdDoesNotMatch() {
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        repo.claimPendingSessions(scope, "sess-1", "lease-A", TTL);

        repo.releaseExtractionSlot(scope, "lease-B");

        assertThat(jdbc.queryForObject("SELECT extraction_state FROM memory_subjects", String.class))
                .as("别人的租约不能被释放")
                .isNotEqualTo("{\"lease_until\":\"0001-01-01T00:00:00Z\"}");

        repo.releaseExtractionSlot(scope, "lease-A");
        assertThat(jdbc.queryForObject("SELECT extraction_state FROM memory_subjects", String.class))
                .isEqualTo("{\"lease_until\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void hasPendingExtractionReflectsTheProgressRows() {
        assertThat(repo.hasPendingExtraction(scope)).isFalse();
        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));
        assertThat(repo.hasPendingExtraction(scope)).isTrue();
    }

    /** 遗留队列（{@code pending_sessions} 数组）被一次性导入进度行，然后清空。 */
    @Test
    void legacyPendingSessionsAreImportedOnceThenCleared() {
        jdbc.update("UPDATE memory_subjects SET pending_sessions = '[\"legacy-1\",\"legacy-2\"]' "
                + "WHERE tenant_id = ? AND subject_id = ?", TENANT, scope.subjectId());

        repo.enqueuePendingSession(scope, "sess-1", Duration.ofMillis(1));

        assertThat(jdbc.queryForObject("SELECT pending_sessions FROM memory_subjects", String.class))
                .isEqualTo("[]");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_extraction_sessions", Integer.class)).isEqualTo(3);
    }
}
