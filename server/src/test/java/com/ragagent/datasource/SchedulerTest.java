package com.ragagent.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import com.ragagent.TestSchema;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * {@link Scheduler} 的语义测试（对照 Go {@code internal/datasource/scheduler.go}）。
 *
 * <h2>为什么用真仓储 + H2，而不是 Go 那样的内存 fake</h2>
 * <p>Go 的 {@code scheduler_test.go} 用 {@code fakeDataSourceRepo} / {@code fakeSyncLogRepo}
 * 替身。Java 侧的 {@code DataSourceRepository} / {@code SyncLogRepository} 是**具体类**
 * （不是接口），且 {@code TestSchema} 里已有 {@code data_sources} / {@code sync_logs} 两张表
 * ——直接跑真仓储更强：{@code triggerSync} 里"创建 running 日志 → 按结果改写状态"这条
 * 落库路径本来就是它的关键行为之一（与 {@code DataSourceRepositoryTest} 同一处置）。</p>
 *
 * <h2>⚠️ 不靠墙钟造时间</h2>
 * <p>Go 的测试靠 6 字段 cron 等真实触发（{@code "*&#47;2 * * * * *"}——用 {@code &#47;}
 * 是因为 Javadoc 注释里不能出现字面的"星号斜杠"，那会提前结束注释）。Java 侧改为
 * <b>注入一个假的 {@link TaskScheduler}</b>（Mockito）：它只记录"注册了哪个 Runnable +
 * 哪个 Trigger"，由测试在自己挑选的时刻手动调用那个 Runnable。于是"cron 注册"与
 * "触发逻辑"分成两条互不依赖计时的断言，不会出现单跑绿、全量红的抖动。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchedulerTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSourceRepository dsRepo;
    @Autowired
    private SyncLogRepository syncLogRepo;

    private TaskScheduler cron;
    private final List<Runnable> registered = new ArrayList<>();

    private final List<DataSourceSyncPayload> enqueued = new ArrayList<>();
    private final List<String> enqueuedTaskIds = new ArrayList<>();
    private DataSourceSyncTaskQueue.Outcome outcome = DataSourceSyncTaskQueue.Outcome.ENQUEUED;
    private RuntimeException enqueueFailure;

    private Scheduler scheduler;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        // TestSchema.resetData 不含 datasource 的两张表（该文件不归本模块改），显式清
        jdbc.update("DELETE FROM sync_logs");
        jdbc.update("DELETE FROM data_sources");

        registered.clear();
        enqueued.clear();
        enqueuedTaskIds.clear();
        outcome = DataSourceSyncTaskQueue.Outcome.ENQUEUED;
        enqueueFailure = null;

        cron = mock(TaskScheduler.class);
        when(cron.schedule(any(Runnable.class), any(Trigger.class))).thenAnswer(inv -> {
            registered.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        });

        DataSourceSyncTaskQueue queue = (payload, taskId, maxRetry, timeout) -> {
            if (enqueueFailure != null) {
                throw enqueueFailure;
            }
            enqueued.add(payload);
            enqueuedTaskIds.add(taskId);
            assertThat(maxRetry).isEqualTo(Scheduler.MAX_RETRY);
            assertThat(timeout).isEqualTo(Scheduler.TASK_TIMEOUT);
            return outcome;
        };

        scheduler = new Scheduler(dsRepo, syncLogRepo, queue, cron);
    }

    private DataSource newDataSource(String name, String schedule, String status) {
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId("kb-1");
        ds.setName(name);
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        ds.setSyncSchedule(schedule);
        dsRepo.create(ds);
        if (status != null) {
            // create() 会把空 status 补成 DDL 默认值 'active'；这里落到调用方要的取值
            jdbc.update("UPDATE data_sources SET status = ? WHERE id = ?", status, ds.getId());
            ds.setStatus(status);
        }
        return ds;
    }

    // ── start：只注册 active 且表达式非空的 ──────────────────────────────

    @Test
    void startRegistersOnlyActiveDataSourcesWithSchedule() {
        DataSource withSchedule = newDataSource("a", "0 0 * * * *", null);
        newDataSource("no-schedule", "", null);
        newDataSource("paused", "0 0 * * * *", DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);

        scheduler.start();

        assertThat(scheduler.entryCount()).isEqualTo(1);
        verify(cron, times(1)).schedule(any(Runnable.class), any(Trigger.class));

        // 注册的那个 Runnable 确实指向 active 的那条
        registered.get(0).run();
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).dataSourceId()).isEqualTo(withSchedule.getId());
    }

    /**
     * 单个数据源的 cron 表达式非法只记 warn 并继续（对照 Go 的
     * {@code failed to register cron for ds=%s schedule=%q}），不整体失败。
     */
    @Test
    void startSkipsInvalidCronWithoutFailing() {
        newDataSource("bad", "not a cron", null);
        newDataSource("good", "0 0 * * * *", null);

        scheduler.start();

        assertThat(scheduler.entryCount()).isEqualTo(1);
    }

    /** 表达式非法时 {@code addOrUpdate} 直接抛，错误文本与 Go 的 {@code %q} 同形。 */
    @Test
    void addOrUpdateThrowsOnInvalidCronWithGoMessage() {
        DataSource ds = newDataSource("bad", "not a cron", null);
        assertThatThrownBy(() -> scheduler.addOrUpdate(ds))
                .isInstanceOf(ConnectorException.class)
                .hasMessageStartingWith("invalid cron expression \"not a cron\": ");
    }

    // ── addOrUpdate / remove ─────────────────────────────────────────────

    @Test
    void addOrUpdateRegistersActiveAndRemovesPausedOrEmpty() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);
        scheduler.addOrUpdate(ds);
        assertThat(scheduler.entryCount()).isEqualTo(1);

        // 暂停 → 只移除、不注册
        ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
        scheduler.addOrUpdate(ds);
        assertThat(scheduler.entryCount()).isZero();

        // 恢复 active 但表达式为空 → 同样不注册
        ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        ds.setSyncSchedule("");
        scheduler.addOrUpdate(ds);
        assertThat(scheduler.entryCount()).isZero();

        // 再恢复 → 注册；重复 addOrUpdate 不叠加
        ds.setSyncSchedule("0 0 * * * *");
        scheduler.addOrUpdate(ds);
        scheduler.addOrUpdate(ds);
        assertThat(scheduler.entryCount()).isEqualTo(1);
    }

    @Test
    void removeDropsTheEntry() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);
        scheduler.addOrUpdate(ds);
        scheduler.addOrUpdate(ds);
        assertThat(scheduler.entryCount()).isEqualTo(1);

        scheduler.remove(ds.getId());
        assertThat(scheduler.entryCount()).isZero();

        // 移除不存在的 ID 是 no-op
        scheduler.remove("nope");
        assertThat(scheduler.entryCount()).isZero();
    }

    // ── triggerSync 的四条分支 ───────────────────────────────────────────

    @Test
    void triggerSkipsWhenDataSourceIsNotActive() {
        DataSource ds = newDataSource("paused", "0 0 * * * *",
                DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
        scheduler.triggerSync(ds.getId(), TENANT);

        assertThat(enqueued).isEmpty();
        assertThat(syncLogRepo.hasRunningSync(ds.getId())).isFalse();
    }

    @Test
    void triggerSkipsWhenDataSourceIsGone() {
        scheduler.triggerSync("does-not-exist", TENANT);
        assertThat(enqueued).isEmpty();
    }

    /** 第 1 层去重：上一次同步还在跑就跳过（对照 Go 的 {@code HasRunningSync}）。 */
    @Test
    void triggerSkipsWhenPreviousSyncStillRunning() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);

        SyncLog running = new SyncLog();
        running.setDataSourceId(ds.getId());
        running.setTenantId(TENANT);
        running.setStatus(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        running.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        syncLogRepo.create(running);

        scheduler.triggerSync(ds.getId(), TENANT);

        assertThat(enqueued).isEmpty();
        // 没有新建第二条 running 日志
        assertThat(syncLogRepo.findByDataSource(ds.getId(), 10, 0)).hasSize(1);
    }

    @Test
    void triggerCreatesSyncLogAndEnqueuesWithDeterministicTaskId() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);

        scheduler.triggerSync(ds.getId(), TENANT);

        assertThat(enqueued).hasSize(1);
        DataSourceSyncPayload payload = enqueued.get(0);
        assertThat(payload.dataSourceId()).isEqualTo(ds.getId());
        assertThat(payload.tenantId()).isEqualTo(TENANT);
        assertThat(payload.trigger()).isEqualTo("schedule");
        assertThat(payload.forceFull()).isFalse();
        assertThat(payload.maxItems()).isZero();

        // 日志先落 running，且 sync_log_id 就是那条日志的 id
        List<SyncLog> logs = syncLogRepo.findByDataSource(ds.getId(), 10, 0);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getId()).isEqualTo(payload.syncLogId());
        assertThat(logs.get(0).getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        assertThat(logs.get(0).getStartedAt()).isNotNull();

        // TaskID = "dssync:<dsID>:<yyyyMMddHHmm UTC>"
        String expected = "dssync:" + ds.getId() + ":"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
        assertThat(enqueuedTaskIds.get(0)).isEqualTo(expected);
    }

    /** 第 2 层去重：TaskID 冲突 → sync_log 落 canceled + Go 的原文案。 */
    @Test
    void triggerMarksLogCanceledOnTaskIdConflict() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);
        outcome = DataSourceSyncTaskQueue.Outcome.TASK_ID_CONFLICT;

        scheduler.triggerSync(ds.getId(), TENANT);

        List<SyncLog> logs = syncLogRepo.findByDataSource(ds.getId(), 10, 0);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
        assertThat(logs.get(0).getErrorMessage())
                .isEqualTo("deduplicated: another instance enqueued first");
        assertThat(logs.get(0).getFinishedAt()).isNotNull();
    }

    /** 投递失败 → sync_log 落 failed + {@code "enqueue failed: <原因>"}。 */
    @Test
    void triggerMarksLogFailedWhenEnqueueThrows() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);
        enqueueFailure = new DataSourceSyncEnqueueException("queue is down");

        scheduler.triggerSync(ds.getId(), TENANT);

        List<SyncLog> logs = syncLogRepo.findByDataSource(ds.getId(), 10, 0);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        assertThat(logs.get(0).getErrorMessage()).isEqualTo("enqueue failed: queue is down");
        assertThat(logs.get(0).getFinishedAt()).isNotNull();
    }

    /** 成功入队后不再改写 sync log（对照 Go：只在两个失败分支里 Update）。 */
    @Test
    void successfulTriggerLeavesLogRunning() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);
        scheduler.triggerSync(ds.getId(), TENANT);

        SyncLog latest = syncLogRepo.findLatest(ds.getId());
        assertThat(latest.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        assertThat(latest.getFinishedAt()).isNull();
        assertThat(latest.getErrorMessage()).isEmpty();
    }

    // ── stop ─────────────────────────────────────────────────────────────

    /**
     * {@code stop()} 清空所有 entry，且之后不再注册新的。
     *
     * <p>注入的 {@link TaskScheduler} 只有 {@code schedule(...)} 会被调用——
     * 关停逻辑在 {@code ownsCron} 为真时才走
     * ({@code ThreadPoolTaskScheduler.shutdown()})，注入的归注入方管。
     * 这里用 {@code verify(cron, times(1)).schedule(...)} 间接证明"stop 没有再次碰调度器"。</p>
     */
    @Test
    void stopClearsEntriesAndIsIdempotent() {
        DataSource ds = newDataSource("a", "0 0 * * * *", null);
        scheduler.addOrUpdate(ds);
        assertThat(scheduler.entryCount()).isEqualTo(1);
        verify(cron, times(1)).schedule(any(Runnable.class), any(Trigger.class));

        scheduler.stop();
        assertThat(scheduler.entryCount()).isZero();

        // 幂等：再 stop 一次不抛
        scheduler.stop();
        assertThat(scheduler.entryCount()).isZero();

        // stop 之后 remove 是 no-op
        scheduler.remove(ds.getId());
        assertThat(scheduler.entryCount()).isZero();

        // schedule 仍然只被调用过那一次（注入的调度器没有被 Scheduler 关停/重启）
        verify(cron, times(1)).schedule(any(Runnable.class), any(Trigger.class));
        verify(cron, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }
}
