package com.ragagent.datasource;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.ragagent.datasource.domain.DataSourceSyncPayload;

/**
 * {@link DataSourceSyncTaskQueue} 的<b>进程内</b>实现：asynq → 虚拟线程队列
 * （与 {@code wiki.service.InProcessWikiIngestTaskQueue}、
 * {@code memory.service.InProcessMemoryExtractTaskQueue} 同一模式）。
 *
 * <p>保留 asynq 在调度器这条路径上真正被依赖的三条语义：</p>
 * <ol>
 *   <li><b>TaskID 去重</b>（{@link #enqueue} 的返回值）——这是调度器第 2 层去重的全部；</li>
 *   <li><b>重试预算 + 退避</b>（{@code MaxRetry}，asynq 的默认退避公式）；</li>
 *   <li><b>任务超时</b>（{@code Timeout}）→ 中断执行线程（见 {@link Connector#sleep}）。</li>
 * </ol>
 *
 * <h2>⚠️ 多实例差异（必须知道）</h2>
 * <p>去重集合只存在于<b>单个 JVM</b> 内。多副本部署下本实现表现为：<b>每个副本各自都会
 * 触发一次同步</b>——调度器第 1 层（{@code hasRunningSync}）只能挡住"同一副本上的重叠"，
 * 挡不住跨副本。</p>
 * <p>这与 memory / wiki 的进程内队列取舍同族。
 * 要恢复跨实例语义，换一个 Redis/MQ 实现即可——端口就是为此留的。</p>
 *
 * <h2>去重窗口</h2>
 * <p>任务 ID 在"入队到执行结束"期间被占用，结束后释放。asynq 的实际窗口略有不同
 * （它在任务完成一段时间后才彻底释放 unique 锁），但在调度器的用法下
 * （每分钟一个新 ID）两者不可区分。</p>
 *
 * <h2>为什么 handler 用 {@link ObjectProvider} 而不是直接注入</h2>
 * <p>service 层（下一步）会同时依赖注册表与队列，直接注入容易成环；
 * 与 memory 的处置一致，惰性取用。</p>
 */
@Component
public class InProcessDataSourceSyncTaskQueue implements DataSourceSyncTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(InProcessDataSourceSyncTaskQueue.class);

    /** 任务观测的 span/根名。 */
    static final String TASK_TYPE_DATASOURCE_SYNC = "datasource:sync";

    private final ObjectProvider<DataSourceSyncHandler> handlerProvider;

    /** 在途 / 待跑的 TaskID。对照 asynq 的 unique-task 锁。 */
    private final Set<String> inflightTaskIds = ConcurrentHashMap.newKeySet();

    /** 执行器：每个尝试一个虚拟线程（对照 asynq 的 worker 池）。 */
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 测试钩子：覆盖重试退避（秒）。{@code null} = 用 asynq 的默认公式。
     *
     * <p>存在的理由与 wiki/memory 那两份完全一样：asynq 的默认退避是
     * {@code n^4 + 15 + rand(30)*(n+1)} 秒，第一次重试就要等 15–45 秒，
     * 不覆盖的话重试路径在单测里根本跑不动。</p>
     */
    private volatile Long retryDelayOverrideSeconds;

    public InProcessDataSourceSyncTaskQueue(ObjectProvider<DataSourceSyncHandler> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    /** 供测试覆盖重试退避（秒）。 */
    public void setRetryDelayOverrideSeconds(Long seconds) {
        this.retryDelayOverrideSeconds = seconds;
    }

    @Override
    public Outcome enqueue(DataSourceSyncPayload payload, String taskId, int maxRetry,
                           Duration timeout) {
        if (!inflightTaskIds.add(taskId)) {
            return Outcome.TASK_ID_CONFLICT;
        }
        String body = payload.toJson();
        long timeoutMillis = timeout == null || timeout.isNegative() ? 0L : timeout.toMillis();
        try {
            worker.execute(() -> run(body, taskId, maxRetry, timeoutMillis));
        } catch (RejectedExecutionException e) {
            inflightTaskIds.remove(taskId);
            // 执行器已关停（应用正在下线）：把失败报给调用方，
            // 让它把 sync_log 落成 failed + "enqueue failed: ..."
            throw new DataSourceSyncEnqueueException("sync queue is shut down", e);
        }
        return Outcome.ENQUEUED;
    }

    /** 在途任务数（供测试断言去重窗口）。 */
    public int inflightCount() {
        return inflightTaskIds.size();
    }

    private void run(String body, String taskId, int maxRetry, long timeoutMillis) {
        try {
            for (int attempt = 1; ; attempt++) {
                if (runOnce(body, timeoutMillis)) {
                    return;
                }
                if (attempt > maxRetry) {
                    return;
                }
                long backoffSeconds = retryDelaySeconds(attempt);
                log.info("[DataSourceSyncQueue] task {} attempt {}/{} failed, retrying in {}s",
                        taskId, attempt, maxRetry + 1, backoffSeconds);
                try {
                    Connector.sleep(backoffSeconds * 1000L);
                } catch (ConnectorException e) {
                    // 被中断（进程下线）：放弃重试
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            inflightTaskIds.remove(taskId);
        }
    }

    /** @return true = 本次尝试成功 */
    private boolean runOnce(String body, long timeoutMillis) {
        DataSourceSyncPayload payload;
        try {
            payload = DataSourceSyncPayload.fromJson(body);
        } catch (RuntimeException e) {
            // 载荷坏掉是确定性的失败：重试多少次都一样，直接放弃
            log.error("[DataSourceSyncQueue] undecodable payload: {}", e.getMessage());
            return true;
        }

        DataSourceSyncHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            log.error("[DataSourceSyncQueue] no DataSourceSyncHandler bean registered; dropping task");
            return true;
        }

        // 独立线程执行，这样超时能靠中断取消
        Future<?> future;
        try {
            // 任务侧观测：在 worker 线程上续接上游
            // trace（无则开独立根），处理体包在 asynq.<type> span 内
            future = worker.submit(() -> {
                try (com.ragagent.tracing.langfuse.LangfuseTaskScope scope =
                             com.ragagent.tracing.langfuse.LangfuseTaskScope.start(
                                     TASK_TYPE_DATASOURCE_SYNC, payload.tracing(),
                                     java.util.Map.of("data_source_id", payload.dataSourceId(),
                                             "sync_log_id", payload.syncLogId()),
                                     com.ragagent.tracing.langfuse.LangfuseTaskScope
                                             .previewPayload(body))) {
                    handler.handle(payload);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[DataSourceSyncQueue] executor shut down; dropping task");
            return true;
        }

        try {
            if (timeoutMillis <= 0L) {
                future.get();
            } else {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            return true;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[DataSourceSyncQueue] task timed out after {}ms", timeoutMillis);
            return false;
        } catch (ExecutionException e) {
            log.error("[DataSourceSyncQueue] task failed: {}",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return false;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return true; // 被要求停止：不再重试
        }
    }

    /**
     * 对照 asynq 的默认重试退避 {@code n^4 + 15 + rand(30)*(n+1)} 秒
     * （wiki / memory 两份实现用的是同一公式）。
     */
    private long retryDelaySeconds(int attempt) {
        Long override = retryDelayOverrideSeconds;
        if (override != null) {
            return Math.max(override, 0L);
        }
        long n = attempt;
        return n * n * n * n + 15 + ThreadLocalRandom.current().nextInt(30) * (n + 1);
    }
}
