package com.ragagent.wiki.service.ingest;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link WikiIngestTaskQueue} 的<b>进程内</b>实现：虚拟线程队列
 * （与阶段 3 的 {@code KnowledgeProcessWorker} 同模式）。
 *
 * <p>保留以下行为：</p>
 * <ul>
 *   <li><b>延迟投递</b>：{@link java.util.concurrent.ScheduledExecutorService}。</li>
 *   <li><b>TaskID 合并</b>：{@code activeTaskIds} 的 {@code putIfAbsent}。
 *       条目从入队一直持有到<b>任务执行完毕</b>（含重试）——ID 被 pending
 *       或 active 的任务占用，同 ID 的后续入队一律合并。finalize 的 {@code scheduleFinalize}
 *       正依赖这一点：运行中的 finalize 仍占着 ID，因此 {@code scheduleFinalizeRetry}
 *       刻意不带 TaskID（否则唯一的重试会被自己合并掉）。</li>
 *   <li><b>重试</b>：失败后按 {@link #retryDelaySeconds} 重排，直到
 *       {@code maxRetry} 次用尽；耗尽后写入死信档案。</li>
 *   <li><b>超时</b>：到点<b>中断执行线程</b>；中止与脱钩清理对应
 *       {@link WikiIngestService#cleanupContext}。</li>
 * </ul>
 *
 * <p><b>⚠️ 多实例差异（必须知道）</b>：任务表、TaskID 合并、重试全部只在<b>单个 JVM</b>
 * 内；Redis / MQ 支撑的队列实现才有跨副本共享的任务表与全局唯一 TaskID。因此：</p>
 * <ul>
 *   <li>本实现下多副本部署会各自触发各自的批次——但因为 ingest 的待办队列是
 *       <b>数据库</b>里的 {@code task_pending_ops} 且认领靠条件更新，
 *       重复触发只会让后到的那个看到空队列而空转，不会重复处理文档；</li>
 *   <li>finalize 的"窗口内合并成一次索引重建"在多副本下会退化成"每副本一次"，
 *       即索引重建次数变多（结果仍收敛，只是更贵）。</li>
 * </ul>
 * <p>跨实例语义需换成 Redis / MQ 实现，端口已为此留好。</p>
 */
@Component
public class InProcessWikiIngestTaskQueue implements WikiIngestTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(InProcessWikiIngestTaskQueue.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 锁冲突（{@code ErrWikiIngestConcurrent}）的重试延迟——短到用户无感，
     * 又足以让刚被遗弃的锁过期。
     */
    static final Duration CONCURRENT_CONFLICT_RETRY_DELAY = Duration.ofSeconds(15);

    /** 调度器：只负责"到点把任务丢出去"，本身不跑业务代码。 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wiki-ingest-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 执行器：每个任务一个虚拟线程；并发总量由 KB 的在途上限约束。 */
    private final java.util.concurrent.ExecutorService worker =
            Executors.newVirtualThreadPerTaskExecutor();

    /** TaskID → 占用标记；在任务终态（成功/重试耗尽）时移除。 */
    private final Map<String, Boolean> activeTaskIds = new ConcurrentHashMap<>();

    private final ObjectProvider<WikiIngestTaskHandler> handlerProvider;
    private final ObjectProvider<com.ragagent.wiki.mapper.TaskDeadLetterRepository> deadLetterProvider;

    /**
     * 任务级死信后的槽位收尾入口（B12）。用 {@code ObjectProvider} 懒取：
     * {@code WikiIngestService} 反向持有本队列的 provider，硬注入会成为构造环。
     */
    private final ObjectProvider<WikiIngestService> ingestServiceProvider;

    /**
     * 测试钩子：覆盖重试延迟（秒）。{@code null} = 用 {@link #retryDelaySeconds} 的默认公式。
     *
     * <p>存在的理由很实际：默认退避是 {@code n^4 + 15 + rand(30)*(n+1)} 秒，
     * 第一次重试就要等 15–45 秒。若不覆盖，重试与死信归档这两条路径在单测里
     * 根本跑不动，于是只能靠代码审阅——那等于没测。</p>
     */
    private volatile Long retryDelayOverrideSeconds;

    /** 供测试覆盖重试延迟（秒）。 */
    public void setRetryDelayOverrideSeconds(Long seconds) {
        this.retryDelayOverrideSeconds = seconds;
    }

    public InProcessWikiIngestTaskQueue(
            ObjectProvider<WikiIngestTaskHandler> handlerProvider,
            ObjectProvider<com.ragagent.wiki.mapper.TaskDeadLetterRepository> deadLetterProvider,
            ObjectProvider<WikiIngestService> ingestServiceProvider) {
        this.handlerProvider = handlerProvider;
        this.deadLetterProvider = deadLetterProvider;
        this.ingestServiceProvider = ingestServiceProvider;
    }

    @Override
    public boolean enqueue(WikiIngestTask task) {
        if (task.hasTaskId() && activeTaskIds.putIfAbsent(task.taskId(), Boolean.TRUE) != null) {
            // 已有同 id 的任务在排队/运行 → 合并（TaskID 冲突语义）
            log.debug("wiki task queue: coalesced {} (taskId={})", task.type(), task.taskId());
            return false;
        }
        // 队列没有优先级概念；这里 FIFO + 单线程调度，
        // 因此"同时到期的多个任务"的执行顺序与入队顺序一致。
        try {
            if (task.processIn().isZero() || task.processIn().isNegative()) {
                worker.execute(() -> run(task, 1));
            } else {
                scheduler.schedule(() -> worker.execute(() -> run(task, 1)),
                        task.processIn().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 调度器被关停（应用正在下线）：释放 TaskID，让重启后的新进程可以重新调度
            releaseTaskId(task);
            log.warn("wiki task queue: rejected {} during shutdown", task.type(), e);
            return false;
        }
        return true;
    }

    /** 供测试/关停使用：停止调度并释放资源。 */
    public void shutdown() {
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    /** 当前占用的 TaskID 数（测试/可观测）。 */
    public int activeTaskIdCount() {
        return activeTaskIds.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // 执行 / 重试
    // ═══════════════════════════════════════════════════════════════

    /**
     * 执行一次任务，失败则按重试策略重排。
     *
     * @param attempt 第几次尝试（从 1 起）。{@code attempt-1} 即已重试次数，
     *                作为 {@link #retryDelaySeconds} 的 {@code alreadyRetried} 传入。
     */
    private void run(WikiIngestTask task, int attempt) {
        Throwable failure = null;
        Thread workerThread = Thread.currentThread();
        long timeoutMillis = task.timeout() == null ? 0 : task.timeout().toMillis();

        // 超时看门狗：到点中断执行线程
        ScheduledFuture<?> watchdog = null;
        if (timeoutMillis > 0) {
            watchdog = scheduler.schedule(() -> {
                log.warn("wiki task queue: {} exceeded timeout {}s, interrupting",
                        task.type(), timeoutMillis / 1000);
                workerThread.interrupt();
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        try {
            WikiIngestTaskHandler handler = handlerProvider.getIfAvailable();
            if (handler == null) {
                // 没有处理器 bean 不是可重试的失败：它是"batch/finalize 的翻译还没接线"
                // 临时装配状态的信号，重试 10 次只会刷 10 行无信息的 warn。
                // 但必须<b>释放 TaskID</b>——否则该 KB 的 finalize 合并会被一个
                // 永不存在的任务永久占住（比丢一次任务严重得多）。
                log.warn("wiki task queue: no WikiIngestTaskHandler bean registered, "
                        + "dropping {} task (batch/finalize translation not wired yet)", task.type());
                releaseTaskId(task);
                return;
            }
            dispatch(handler, task);
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            // 中断位可能被看门狗置上；清掉它，避免污染后续复用本线程的调度任务
            Thread.interrupted();
        }

        if (failure == null) {
            releaseTaskId(task);
            return;
        }

        int retriesUsed = attempt - 1;
        if (retriesUsed >= task.maxRetry()) {
            log.warn("wiki task queue: {} exhausted {} retries, archiving to dead letters",
                    task.type(), task.maxRetry(), failure);
            archive(task, failure, attempt);
            releaseTaskId(task);
            return;
        }

        Long override = retryDelayOverrideSeconds;
        long delaySeconds = override != null
                ? override
                : retryDelaySeconds(retriesUsed + 1, failure);
        log.warn("wiki task queue: {} failed (attempt {}/{}), retrying in {}s",
                task.type(), attempt, task.maxRetry() + 1, delaySeconds, failure);
        scheduler.schedule(() -> worker.execute(() -> run(task, attempt + 1)),
                delaySeconds, TimeUnit.SECONDS);
    }

    private void dispatch(WikiIngestTaskHandler handler, WikiIngestTask task) {
        WikiIngestPayload payload = parsePayload(task);
        // 任务侧观测——负载带 traceparent 就续接上游
        // trace，否则以任务类型开独立根；处理体包在 asynq.<type> span 内，收尾记 outcome。
        try (com.ragagent.tracing.langfuse.LangfuseTaskScope scope =
                     com.ragagent.tracing.langfuse.LangfuseTaskScope.start(
                             task.type(), payload.tracing(),
                             java.util.Map.of("knowledge_base_id",
                                     payload.knowledgeBaseId() == null ? "" : payload.knowledgeBaseId()),
                             com.ragagent.tracing.langfuse.LangfuseTaskScope.previewPayload(task.payload()))) {
            if (WikiIngestTask.TYPE_WIKI_FINALIZE.equals(task.type())) {
                handler.processWikiFinalize(payload);
                return;
            }
            handler.processWikiIngest(payload);
        }
    }

    private WikiIngestPayload parsePayload(WikiIngestTask task) {
        try {
            WikiIngestPayload payload = MAPPER.readValue(task.payload(), WikiIngestPayload.class);
            return payload == null ? WikiIngestPayload.of("") : payload;
        } catch (Exception e) {
            // 负载损坏不是可修复的失败：直接抛出，让上层按普通失败路径
            // 走重试/死信，行为与通用队列保持一致
            throw new IllegalArgumentException("wiki task queue: unmarshal payload: " + e.getMessage(), e);
        }
    }

    private void archive(WikiIngestTask task, Throwable failure, int attempt) {
        // 先释放槽位再归档：任务级的终态失败意味着该 KB 的 op 没人结算了，
        // 不释放的话对应文档会一直停在「优化中」（B12；op 保留，等下次触发重跑）。
        releaseAbandonedSubtaskSlots(task);
        com.ragagent.wiki.mapper.TaskDeadLetterRepository repo = deadLetterProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        try {
            WikiIngestPayload payload = parsePayload(task);
            com.ragagent.wiki.domain.TaskDeadLetter dl = new com.ragagent.wiki.domain.TaskDeadLetter();
            dl.setTenantId(payload.tenantId());
            dl.setTaskType(task.type());
            dl.setScope(WikiIngestConstants.TASK_SCOPE);
            dl.setScopeId(payload.knowledgeBaseId());
            dl.setRelatedId("");
            dl.setPayload(MAPPER.readTree(task.payload()));
            dl.setLastError(failure == null ? "" : failure.toString());
            dl.setFailCount(attempt);
            repo.insert(dl);
        } catch (Exception e) {
            // 尽力而为：归档失败不得掩盖底层任务错误
            log.warn("wiki task queue: failed to archive {} to dead letters", task.type(), e);
        }
    }

    /** 任务进死信时，释放该 KB 在途 op 对应文档的 finalizing 槽位（只对 ingest 任务）。 */
    private void releaseAbandonedSubtaskSlots(WikiIngestTask task) {
        if (!WikiIngestTask.TYPE_WIKI_INGEST.equals(task.type())) {
            return;
        }
        WikiIngestService service = ingestServiceProvider.getIfAvailable();
        if (service == null) {
            return;   // 未接线（测试/裁剪装配）
        }
        try {
            WikiIngestPayload payload = parsePayload(task);
            service.releaseSlotsForAbandonedTask(payload.knowledgeBaseId());
        } catch (RuntimeException e) {
            // 尽力而为：收尾失败不得掩盖底层任务错误
            log.warn("wiki task queue: release abandoned subtask slots failed", e);
        }
    }

    private void releaseTaskId(WikiIngestTask task) {
        if (task.hasTaskId()) {
            activeTaskIds.remove(task.taskId());
        }
    }

    /**
     * 重试延迟：锁冲突走固定的 15 秒，其余走默认退避公式。
     *
     * <p>为什么锁冲突不能指数退避：新被遗弃的锁 ≤60 秒就过期，15 秒的固定重试
     * 几乎保证下一次尝试成功。没有这个覆盖，崩溃-重启循环会让一个 KB 在
     * 7–10 分钟内无法推进（等孤儿锁过期 <b>并且</b> 等退避计划追上）。</p>
     *
     * <p>默认公式：{@code n^4 + 15 + rand(30) * (n + 1)} 秒。</p>
     */
    static long retryDelaySeconds(int alreadyRetried, Throwable failure) {
        if (failure instanceof WikiIngestConstants.ConcurrentTaskActiveException) {
            return CONCURRENT_CONFLICT_RETRY_DELAY.toSeconds();
        }
        long n = alreadyRetried;
        return (n * n * n * n) + 15 + ThreadLocalRandom.current().nextLong(30) * (n + 1);
    }
}
