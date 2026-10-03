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
 * 基于 cron 的数据源周期同步调度器。
 *
 * <h2>两层去重</h2>
 * <p>cron 按<b>绝对墙钟</b>触发（例如 {@code "0 0 * * * *"} 永远是每小时整点，
 * 与进程何时启动无关），所以多实例会在同一瞬间一起触发。去重靠两层：</p>
 * <ol>
 *   <li>{@code hasRunningSync} —— 上一次同步还在跑就跳过（防重叠）；</li>
 *   <li>{@link DataSourceSyncTaskQueue} 的确定性 TaskID —— 每个
 *       {@code (dataSourceID, 分钟)} 一个 ID，只有第一个入队者获胜，
 *       其余拿到 {@link DataSourceSyncTaskQueue.Outcome#TASK_ID_CONFLICT}。</li>
 * </ol>
 *
 * <h2>cron 引擎</h2>
 * <p>用 Spring 的 {@link CronExpression}：
 * <b>6 字段</b>（秒 分 时 日 月 周）解析，任务异常由执行器吞掉并记日志、
 * 不终止调度器。</p>
 *
 * <p><b>限制</b>：Spring 的 {@code CronExpression} 只支持 {@code @hourly} / {@code @daily} /
 * {@code @weekly} / {@code @monthly} / {@code @yearly} 五个宏、<b>不认识 {@code @every}</b>。
 * 前端 {@code DataSourceEditorDialog.vue} 给出的全是 6 字段表达式
 * （默认 {@code "0 0 * * * *"}、以及 {@code "0 0 *&#47;6 * * *"}——这里用
 * {@code &#47;} 是因为 Javadoc 注释里不能出现字面的"星号斜杠"，那会提前结束注释），
 * 所以线上不受影响；
 * 若有人手写 {@code @every} 会在解析时报「invalid cron expression」。
 * 另：{@code DOM/DOW} 的 {@code ?} 写法 Spring 同样支持。</p>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子</b>：无（本类不落表）。</li>
 *   <li><b>关联预加载</b>：无——{@code findActive} 是仓储里的一条查询。</li>
 *   <li><b>软删除</b>：走 {@code DataSourceRepository}（已实现 {@code deleted_at IS NULL}）。</li>
 *   <li><b>默认排序</b>：无（{@code findActive} 的排序由仓储决定）。</li>
 *   <li><b>唯一索引/外键</b>：无。</li>
 *   <li><b>自动时间戳</b>：{@code SyncLogRepository.create} 已负责
 *       {@code created_at}/{@code updated_at}；本类显式设
 *       {@code status=running} + {@code started_at=now(UTC)}。</li>
 * </ol>
 */
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    /** 任务失败后的最大重试次数。 */
    public static final int MAX_RETRY = 5;

    /** 单次任务执行的超时。 */
    public static final Duration TASK_TIMEOUT = Duration.ofHours(2);

    /** TaskID 的分钟粒度时间戳（UTC，格式 {@code yyyyMMddHHmm}）。 */
    private static final DateTimeFormatter TASK_ID_MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final DataSourceRepository dsRepo;
    private final SyncLogRepository syncLogRepo;
    private final DataSourceSyncTaskQueue taskQueue;

    private final TaskScheduler cron;
    private final boolean ownsCron;

    private final ReentrantLock mu = new ReentrantLock();
    /** dataSourceID → 已注册的 cron 任务。 */
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
     * <p>单个数据源注册失败只记 warn 并继续
     * （日志文案 {@code failed to register cron for ds=... schedule=...}），只有
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
     * <p>{@code ScheduledFuture.cancel(false)} <b>不打断</b>正在执行的任务；
     * 正在跑的任务由线程池在 shutdown 时等完。
     * 本方法只保证"不再触发新的"，
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

    /** 当前已注册的 cron 任务数（供测试/监控用）。 */
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

    /** 调用方必须已持锁。 */
    private void addEntryLocked(DataSource ds) {
        String dsId = ds.getId();
        long tenantId = ds.getTenantId() == null ? 0L : ds.getTenantId();

        // 先自己 parse 一次，为的是拿到统一的错误文本（含原始表达式）；
        // 真正的触发器仍用原始表达式构造（CronTrigger 内部会再解析一次）。
        parseCron(ds.getSyncSchedule());
        ScheduledFuture<?> future = cron.schedule(() -> triggerSync(dsId, tenantId),
                new CronTrigger(ds.getSyncSchedule()));

        entries.put(dsId, future);
    }

    /**
     * 解析失败统一抛 {@code invalid cron expression "<表达式>": <解析器原文>}。
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
    // 包可见：测试可直接触发一次
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
            running = false; // 查询失败时不视为"有同步在跑"
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
                // 写日志失败不改变主流程
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
