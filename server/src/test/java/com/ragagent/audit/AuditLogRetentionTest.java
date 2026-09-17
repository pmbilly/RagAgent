package com.ragagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.mapper.AuditLogRepository;
import com.ragagent.audit.service.AuditLogRetentionRunner;
import com.ragagent.audit.service.AuditLogService;
import org.junit.jupiter.api.Test;

/**
 * 保留期清扫语义测试——对照 Go
 * internal/application/service/audit_log_retention_test.go（L19-296）。
 *
 * <p>Go 的 {@code stubAuditRepoForRetention} 记 {@code DeleteOlderThan} 的 cutoff
 * 并可注入错误；{@code purgeCountingService} 只数 Purge 次数。
 * Java 侧对应 Mockito 桩 + 真实 {@link AuditLogService}（服务本身很薄，
 * 用真货比桩更能钉住 cutoff 计算）。</p>
 */
class AuditLogRetentionTest {

    private static final Instant BASE = Instant.parse("2026-05-14T10:00:00Z");

    /** 记录 cutoff、可注入错误、可设定删除行数（对照 Go 的 stubAuditRepoForRetention）。 */
    private static final class PurgingRepo {
        final AuditLogRepository mock = mock(AuditLogRepository.class);
        final List<OffsetDateTime> cutoffs = new ArrayList<>();
        long deleted;
        RuntimeException deleteError;

        PurgingRepo() {
            when(mock.deleteOlderThan(any(OffsetDateTime.class))).thenAnswer(inv -> {
                cutoffs.add(inv.getArgument(0));
                if (deleteError != null) {
                    throw deleteError;
                }
                return deleted;
            });
        }
    }

    private static AuditLogService serviceWith(AuditLogRepository repo,
                                               AuditLogServiceTest.FakeClock clock) {
        return new AuditLogService(repo, clock);
    }

    // ── Purge ────────────────────────────────────────────────────────────

    /**
     * 对照 {@code TestAuditLog_Purge_NoOpWhenRetentionDisabled}：
     * retention_days &lt;= 0 必须在碰仓储<b>之前</b>短路。否则"关掉"的配置仍会每天
     * 发一条 {@code created_at < cutoff} 的 DELETE，而 cutoff = now() 会静默清空全表。
     */
    @Test
    void purgeNoOpWhenRetentionDisabled() {
        PurgingRepo repo = new PurgingRepo();
        AuditLogService svc = serviceWith(repo.mock,
                new AuditLogServiceTest.FakeClock(BASE, ZoneOffset.UTC));

        for (int days : new int[] {0, -1, -90}) {
            assertThat(svc.purge(days)).isZero();
        }
        assertThat(repo.cutoffs).isEmpty();
    }

    /**
     * 对照 {@code TestAuditLog_Purge_UsesClockMinusRetention}：
     * 喂给仓储的 cutoff 必须<b>恰好</b>是时钟减去 retention_days × 24h。
     * 差一天会静默留下太多（表涨）或删掉太多（丢数据）。
     */
    @Test
    void purgeUsesClockMinusRetention() {
        PurgingRepo repo = new PurgingRepo();
        repo.deleted = 42;
        AuditLogService svc = serviceWith(repo.mock,
                new AuditLogServiceTest.FakeClock(BASE, ZoneOffset.UTC));

        assertThat(svc.purge(90)).isEqualTo(42L);

        assertThat(repo.cutoffs).hasSize(1);
        assertThat(repo.cutoffs.get(0)).isEqualTo(
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC).minus(Duration.ofDays(90)));
    }

    /**
     * 对照 {@code TestAuditLog_Purge_PropagatesRepoError}：保留期失败必须冒到 runner
     * （那里按 WARN 记）。静默吞掉错误会让降级的 DB 被掩盖好几天——下次清扫在 24h 后。
     */
    @Test
    void purgePropagatesRepoError() {
        PurgingRepo repo = new PurgingRepo();
        repo.deleteError = new IllegalStateException("connection lost");
        AuditLogService svc = serviceWith(repo.mock,
                new AuditLogServiceTest.FakeClock(BASE, ZoneOffset.UTC));

        assertThatThrownBy(() -> svc.purge(30)).isInstanceOf(IllegalStateException.class);
    }

    // ── Runner ───────────────────────────────────────────────────────────

    /** 对照 Go {@code purgeCountingService}：只数 Purge 次数。 */
    private static final class CountingRepo {
        final AuditLogRepository mock = mock(AuditLogRepository.class);
        final AtomicInteger purges = new AtomicInteger();

        CountingRepo() {
            when(mock.deleteOlderThan(any(OffsetDateTime.class))).thenAnswer(inv -> {
                purges.incrementAndGet();
                return 0L;
            });
        }
    }

    private static AuditLogService countingService(CountingRepo repo) {
        return new AuditLogService(repo.mock,
                new AuditLogServiceTest.FakeClock(BASE, ZoneOffset.UTC));
    }

    /**
     * 对照 {@code TestAuditLogRetentionRunner_StartIsNoOpWhenDisabled}：
     * retention_days &lt;= 0 让循环保持休眠，且不能为它起线程。
     */
    @Test
    void startIsNoOpWhenDisabled() {
        CountingRepo repo = new CountingRepo();
        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                countingService(repo), 0, Duration.ofMillis(1), Duration.ZERO);

        runner.start();
        runner.stop();

        assertThat(repo.purges).hasValue(0);
    }

    /** 对照 {@code TestAuditLogRetentionRunner_StopIsIdempotent}：二次 Stop 不能炸。 */
    @Test
    void stopIsIdempotent() {
        CountingRepo repo = new CountingRepo();
        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                countingService(repo), 0, Duration.ofMillis(1), Duration.ZERO);

        runner.start();
        runner.stop();
        runner.stop();
    }

    /** 对照 {@code TestAuditLogRetentionRunner_StartIsIdempotent}：误调两次不能双跑。 */
    @Test
    void startIsIdempotent() {
        CountingRepo repo = new CountingRepo();
        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                countingService(repo), 0, Duration.ofMillis(1), Duration.ZERO);

        runner.start();
        runner.start();
        runner.stop();
    }

    /**
     * 对照 {@code TestAuditLogRetentionRunner_NilSvcShortCircuits}：
     * 装配失败（service 构造不出来）不能拖垮应用；Start 是 no-op，
     * 随后的 Stop 也<b>不能</b>挂在没人关闭的 done 上（Go 的回归：
     * startOnce 提前返回却不关 doneCh → Stop 永久阻塞，优雅关停死锁）。
     */
    @Test
    void nilSvcShortCircuits() {
        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                null, 90, Duration.ofMillis(1), Duration.ZERO);

        runner.start();
        // 能返回 = 通过（Go 用 2s 超时 + 子协程探测；Java 这边同步调用即可，
        // 真挂了这条测试会超时失败）
        runner.stop();
    }

    /**
     * 对照 {@code TestAuditLogRetentionRunner_StopBeforeStart}：
     * 容器关停顺序可能让 Stop 早于 Start（早期初始化失败、测试清理）——
     * 必须当作 no-op 而不是阻塞在 done 上。
     */
    @Test
    void stopBeforeStart() {
        CountingRepo repo = new CountingRepo();
        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                countingService(repo), 90, Duration.ofMillis(1), Duration.ZERO);

        runner.stop();
        assertThat(repo.purges).hasValue(0);
    }

    /**
     * 对照 {@code TestAuditLogRetentionRunner_PurgesOnTickerCadence}：
     * 把启动延迟塌成 0、间隔缩到 30ms，100ms 窗口内至少应有 2 次 Purge。
     * 钉住"协程确实会随时间触发 Purge"这个头号行为，而<b>不</b>耦合具体次数
     * （那在慢 CI 上会 flaky）。
     */
    @Test
    void purgesOnTickerCadence() throws Exception {
        CountingRepo repo = new CountingRepo();
        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                countingService(repo), 30, Duration.ofMillis(30), Duration.ZERO);

        runner.start();
        Thread.sleep(100);
        runner.stop();

        assertThat(repo.purges.get()).isGreaterThanOrEqualTo(2);
    }

    /**
     * 对照 {@code TestAuditLogRetentionRunner_RunOnceLogsButDoesNotPanicOnError}：
     * runOnce 必须吞掉 Purge 错误——卡住的 DB 不该把异常冒出循环、拖垮应用。
     * 行为是"WARN 记一条，下个 tick 再试"。
     */
    @Test
    void runOnceLogsButDoesNotPanicOnError() {
        PurgingRepo repo = new PurgingRepo();
        repo.deleteError = new IllegalStateException("simulated");
        AuditLogService svc = serviceWith(repo.mock,
                new AuditLogServiceTest.FakeClock(BASE, ZoneOffset.UTC));

        AuditLogRetentionRunner runner = new AuditLogRetentionRunner(
                svc, 30, Duration.ofMillis(1), Duration.ZERO);

        runner.runOnce(); // 不抛 = 通过
        assertThat(repo.cutoffs).hasSize(1);
    }

    /**
     * 对照 {@code TestAuditLogModel_HasCreatedAtField} 的等价保护：
     * 保留期路径依赖 CreatedAt 存在（少了它 cutoff 过滤就会静默失效）。
     */
    @Test
    void auditLogModelKeepsCreatedAtForRetentionPath() {
        AuditLog entry = new AuditLog();
        entry.setCreatedAt(OffsetDateTime.now());
        entry.setAction(AuditAction.MEMBER_ADDED);
        assertThat(entry.getCreatedAt()).isNotNull();
    }
}
