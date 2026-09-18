package com.ragagent.datasource;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;

/**
 * 基于 cron 的数据源周期同步调度器（对照 Go {@code datasource.Scheduler}，
 * internal/datasource/scheduler.go 全文）。
 *
 * <h2>两层去重（Go 的注释逐条照抄）</h2>
 * <p>robfig/cron 按<b>绝对墙钟</b>触发（例如 {@code "0 0 * * * *"} 永远是每小时整点，
 * 与进程何时启动无关），所以多实例会在同一瞬间一起触发。去重靠两层：</p>
 * <ol>
 *   <li>{@code hasRunningSync} —— 上一次同步还在跑就跳过（防重叠）；</li>
 *   <li>{@link DataSourceSyncTaskQueue} 的确定性 TaskID —— 每个
 *       {@code (dataSourceID, 分钟)} 一个 ID，只有第一个入队者获胜，
 *       其余拿到 {@link DataSourceSyncTaskQueue.Outcome#TASK_ID_CONFLICT}。</li>
 * </ol>
 *
 * <h2>cron 引擎映射（robfig/cron → Spring）</h2>
 * <p>Go 用 {@code cron.New(cron.WithSeconds(), cron.WithChain(cron.Recover(...)))}：
 * <b>6 字段</b>（秒 分 时 日 月 周）解析，任务 panic 不终止调度器（Recover）。
 * Java 用 Spring 的 {@link CronExpression}（同样 6 字段、同样字段顺序）+ 虚拟线程池
 * 执行（任务异常由执行器吞掉并记日志，等价于 {@code cron.Recover}）。</p>
 *
 * <p><b>已知差异</b>：robfig/cron 额外支持 {@code @every 5m} / {@code @daily} 之类的描述符，
 * Spring 的 {@code CronExpression} 只支持 {@code @hourly} / {@code @daily} / {@code @weekly} /
 * {@code @monthly} / {@code @yearly} 五个宏、<b>不认识 {@code @every}</b>。
 * 前端 {@code DataSourceEditorDialog.vue} 给出的全是 6 字段表达式
 * （默认 {@code "0 0 * * * *"}、以及 {@code "0 0 *&#47;6 * * *"}——这里用
 * {@code &#47;} 是因为 Javadoc 注释里不能出现字面的"星号斜杠"，那会提前结束注释），
 * 所以线上不受影响；
 * 若有人手写 {@code @every} 会与 Go 分叉（Java 报「invalid cron expression」）。
 * 另：robfig 的 {@code DOM/DOW} 用 {@code ?} 与 Spring 一致。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子</b>：无（本类不落表）。</li>
 *   <li><b>关联预加载</b>：无——{@code findActive} 是仓储里的一条查询。</li>
 *   <li><b>软删除</b>：走 {@code DataSourceRepository}（已实现 {@code deleted_at IS NULL}）。</li>
 *   <li><b>默认排序</b>：无（{@code findActive} 的排序由仓储决定）。</li>
 *   <li><b>唯一索引/外键</b>：无。</li>
 *   <li><b>自动时间戳</b>：{@code SyncLogRepository.create} 已负责
 *       {@code created_at}/{@code updated_at}；本类照 Go 显式设
 *       {@code status=running} + {@code started_at=now(UTC)}。</li>
 * </ol>
 */
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    /** 对照 Go {@code asynq.MaxRetry(5)}。 */
    public static final int MAX_RETRY = 5;

    /** 对照 Go {@code asynq.Timeout(2 * time.Hour)}。 */
    public static final Duration TASK_TIMEOUT = Duration.ofHours(2);

    /** 对照 Go {@code time.Now().UTC().Truncate(time.Minute).Format("200601021504")}。 */
    private static final DateTimeFormatter TASK_ID_MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final DataSourceRepository dsRepo;
    private final SyncLogRepository syncLogRepo;
    private final DataSourceSyncTaskQueue taskQueue;

    private final TaskScheduler cron;
    private final boolean ownsCron;

    private final ReentrantLock mu = new ReentrantLock();
    /** dataSourceID → 已注册的 cron 任务（对照 Go 的 {@code entries map[string]cron.EntryID}）。 */
    private final Map<String, ScheduledFuture<?>> entries = new LinkedHashMap<>();

    public Scheduler(DataSourceRepository dsRepo, SyncLogRepository syncLogRepo,
                     DataSourceSyncTaskQueue taskQueue) {
        this(dsRepo, syncLogRepo, taskQueue, null);
    }

    /**
     * 允许注入外部 {@link TaskScheduler}（测试用可控时钟 / 直接触发；生产传 {@code null}
     * 则自建一个）。<b>只有自建的调度器才会在 {@link #stop()} 里被关停</b>——
     * 注入的归注入方管，避免"一个 bean 关掉全应用的调度线程池"。
     */
    public Scheduler(DataSourceRepository dsRepo, SyncLogRepository syncLogRepo,
                     DataSourceSyncTaskQueue taskQueue, TaskScheduler cron) {
        this.dsRepo = dsRepo;
        this.syncLogRepo = syncLogRepo;
        this.taskQueue = taskQueue;
        if (cron != null) {
            this.cron = cron;
            this.ownsCron = false;
        } else {
            ThreadPoolTaskScheduler own = new ThreadPoolTaskScheduler();
            own.setPoolSize(4);
            own.setThreadNamePrefix("ds-scheduler-");
            own.setRemoveOnCancelPolicy(true);
            own.initialize();
            this.cron = own;
            this.ownsCron = true;
        }
    }

    // ------------------------------------------------------------------
    // Start / Stop
    // ------------------------------------------------------------------

    /**
     * 从数据库加载全部 active 数据源并注册它们的 cron 表达式，然后启动调度器。
     *
     * <p>对照 Go：单个数据源注册失败只记 warn 并继续（
     * {@code failed to register cron for ds=%s schedule=%q}），只有
     * {@code FindActive} 本身失败才会整体失败。</p>
     */
    public void start() {
        List<DataSource> dataSources;
        try {
            dataSources = dsRepo.findActive();
        } catch (RuntimeException e) {
            throw new ConnectorException("load active data sources: " + e.getMessage(), e);
        }

        for (DataSource ds : dataSources) {
            if (ds.getSyncSchedule() == null || ds.getSyncSchedule().isEmpty()) {
                continue;
            }
            try {
                addEntry(ds);
            } catch (RuntimeException e) {
                log.warn("[Scheduler] failed to register cron for ds={} schedule=\"{}\": {}",
                        ds.getId(), ds.getSyncSchedule(), e.getMessage());
            }
        }

        log.info("[Scheduler] started with {} cron entries", entryCount());
    }

    /**
     * 优雅停止：取消全部已注册的 cron 任务。
     *
     * <p>Go 的 {@code cron.Stop()} 会等正在跑的任务结束（{@code <-ctx.Done()}）。
     * Java 侧 {@code ScheduledFuture.cancel(false)} <b>不打断</b>正在执行的任务
     * ——与 Go 的"等它跑完"一致；正在跑的任务由线程池在 shutdown 时等完。
     * 差异是 Go 的 Stop 会阻塞到任务结束，Java 的 stop 只保证"不再触发新的"，
     * 调用方若需要"等到跑完"应自行等待线程池（Spring 关闭时天然如此）。</p>
     */
    public void stop() {
        mu.lock();
        try {
            for (Map.Entry<String, ScheduledFuture<?>> entry : new ArrayList<>(entries.entrySet())) {
                entry.getValue().cancel(false);
            }
            entries.clear();
        } finally {
            mu.unlock();
        }
        if (ownsCron && cron instanceof ThreadPoolTaskScheduler pool) {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // AddOrUpdate / Remove / EntryCount
    // ------------------------------------------------------------------

    /**
     * 注册（或重新注册）给定数据源的 cron 任务。
     *
     * <p>非 active 或没有表达式时<b>只移除、不注册</b>——这正是"暂停一个数据源后
     * 它的定时任务真的停掉"的实现点。</p>
     */
    public void addOrUpdate(DataSource ds) {
        mu.lock();
        try {
            ScheduledFuture<?> existing = entries.remove(ds.getId());
            if (existing != null) {
                existing.cancel(false);
            }
            if (!DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE.equals(ds.getStatus())
                    || ds.getSyncSchedule() == null || ds.getSyncSchedule().isEmpty()) {
                return;
            }
            addEntryLocked(ds);
        } finally {
            mu.unlock();
        }
    }

    /** 移除某数据源的 cron 任务。 */
    public void remove(String dataSourceId) {
        mu.lock();
        try {
            ScheduledFuture<?> existing = entries.remove(dataSourceId);
            if (existing != null) {
                existing.cancel(false);
            }
        } finally {
            mu.unlock();
        }
    }

    /** 当前已注册的 cron 任务数（对照 Go {@code EntryCount}，供测试/监控用）。 */
    public int entryCount() {
        mu.lock();
        try {
            return entries.size();
        } finally {
            mu.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void addEntry(DataSource ds) {
        mu.lock();
        try {
            addEntryLocked(ds);
        } finally {
            mu.unlock();
        }
    }

    /** 与 Go 的 {@code addEntryLocked} 一样：调用方必须已持锁。 */
    private void addEntryLocked(DataSource ds) {
        String dsId = ds.getId();
        long tenantId = ds.getTenantId() == null ? 0L : ds.getTenantId();

        // 先自己 parse 一次，只为拿到与 Go 逐字一致的错误文本；
        // 真正的触发器仍用原始表达式构造（CronTrigger 内部会再解析一次）。
        parseCron(ds.getSyncSchedule());
        ScheduledFuture<?> future = cron.schedule(() -> triggerSync(dsId, tenantId),
                new CronTrigger(ds.getSyncSchedule()));

        entries.put(dsId, future);
    }

    /**
     * 对照 Go 的 {@code s.cron.AddFunc} 错误文本：
     * {@code invalid cron expression %q: <解析器原文>}。
     */
    private static CronExpression parseCron(String schedule) {
        try {
            return CronExpression.parse(schedule);
        } catch (RuntimeException e) {
            throw new ConnectorException(
                    "invalid cron expression \"" + schedule + "\": " + e.getMessage(), e);
        }
    }

    /**
     * cron 每次触发时调用。
     *
     * <p><b>第 1 层（DB）</b>：上一次同步还在跑就跳过——这让"同步耗时超过 cron 间隔"
     * 不会叠起来。</p>
     * <p><b>第 2 层（队列）</b>：确定性 TaskID {@code "dssync:<dsID>:<分钟>"}。
     * 因为 robfig/cron 按绝对墙钟触发，所有实例在同一分钟触发；第一个入队者获胜，
     * 其余拿到冲突。</p>
     */
    // 包可见：测试可直接触发一次（对照 Go 测试里通过 cron 表达式走真实触发）
    void triggerSync(String dataSourceId, long tenantId) {
        DataSource ds;
        try {
            ds = dsRepo.findById(dataSourceId);
        } catch (DataSourceException e) {
            ds = null;
        }
        if (ds == null
                || !DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE.equals(ds.getStatus())) {
            log.info("[Scheduler] skipping sync for ds={} (not active or not found)", dataSourceId);
            return;
        }

        // 第 1 层：与仍在跑的同步防重叠
        boolean running;
        try {
            running = syncLogRepo.hasRunningSync(dataSourceId);
        } catch (RuntimeException e) {
            running = false; // 对照 Go 的 `if running, _ := ...; running`
        }
        if (running) {
            log.info("[Scheduler] skipping sync for ds={} (previous sync still running)", dataSourceId);
            return;
        }

        SyncLog syncLog = new SyncLog();
        syncLog.setDataSourceId(dataSourceId);
        syncLog.setTenantId(tenantId);
        syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        syncLog.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            syncLogRepo.create(syncLog);
        } catch (RuntimeException e) {
            log.error("[Scheduler] failed to create sync log for ds={}: {}", dataSourceId, e.getMessage());
            return;
        }

        DataSourceSyncPayload payload = new DataSourceSyncPayload(
                null, "schedule", dataSourceId, tenantId, syncLog.getId(), false, 0);
        // 第 2 层：确定性 TaskID —— 同一分钟内的所有实例产出同一个 ID
        String taskId = "dssync:" + dataSourceId + ":"
                + TASK_ID_MINUTE.format(
                        Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES));

        DataSourceSyncTaskQueue.Outcome outcome;
        try {
            outcome = taskQueue.enqueue(payload, taskId, MAX_RETRY, TASK_TIMEOUT);
        } catch (RuntimeException e) {
            log.error("[Scheduler] failed to enqueue sync task for ds={}: {}", dataSourceId, e.getMessage());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("enqueue failed: " + e.getMessage());
            try {
                syncLogRepo.update(syncLog);
            } catch (RuntimeException ignored) {
                // 对照 Go 的 `_ = s.syncLogRepo.Update(...)`：写日志失败不改变主流程
            }
            return;
        }

        if (outcome == DataSourceSyncTaskQueue.Outcome.TASK_ID_CONFLICT) {
            log.info("[Scheduler] sync already enqueued by another instance for ds={}", dataSourceId);
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("deduplicated: another instance enqueued first");
            try {
                syncLogRepo.update(syncLog);
            } catch (RuntimeException ignored) {
                // 同上
            }
            return;
        }

        log.info("[Scheduler] sync task enqueued for ds={} syncLog={}", dataSourceId, syncLog.getId());
    }
}
