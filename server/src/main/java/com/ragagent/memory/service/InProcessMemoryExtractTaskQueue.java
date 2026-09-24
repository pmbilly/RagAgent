package com.ragagent.memory.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link MemoryExtractTaskQueue} 的<b>进程内</b>实现：asynq → 虚拟线程队列
 * （与 {@code wiki.service.InProcessWikiIngestTaskQueue}、
 * {@code KnowledgeProcessWorker} 同一模式）。
 *
 * <p>对照 Go 的 asynq 客户端 + worker 池，本实现保留：延迟投递
 * （{@link ScheduledExecutorService}）、重试预算（{@code MaxRetry(2)}）、
 * 重试退避（asynq 的默认公式）、以及"一个任务一个 worker"的执行模型。</p>
 *
 * <h2>⚠️ 多实例差异（必须知道）</h2>
 * <p>队列只存在于<b>单个 JVM</b> 内。Go 的 asynq 是 Redis 支撑的，多副本时任务会被任意
 * 一个 worker 取走。多副本部署下本实现表现为：每份副本各自按自己收到的轮次投递，
 * 于是同一个主体上可能有多次蒸馏——<b>结果仍然正确</b>，因为
 * {@code ClaimPendingSessions} 的租约会让同一时刻只有一个 worker 持有它，
 * 重复触发只会让后到的那个看到空队列或租约忙而空转。要恢复 Go 的语义需换成
 * Redis / MQ 实现，端口已为此留好。</p>
 *
 * <h2>为什么 handler 用 {@link ObjectProvider} 而不是直接注入</h2>
 * <p>{@code MemoryExtractionService} 自己也要投递任务（follow-up、租约重投），
 * 直接注入会构成环。惰性取用与 wiki 的处置一致。</p>
 */
@Component
public class InProcessMemoryExtractTaskQueue implements MemoryExtractTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(InProcessMemoryExtractTaskQueue.class);

    /** 对照 Go {@code asynq.MaxRetry(2)}。 */
    static final int MAX_RETRY = 2;

    /** 对照 Go {@code types.TypeMemoryExtract}：任务观测的 span/根名。 */
    static final String TASK_TYPE_MEMORY_EXTRACT = "memory:extract";

    /** 调度器：只负责"到点把任务丢出去"，本身不跑业务代码。 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "memory-extract-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 执行器：每个重试尝试一个虚拟线程（对照 asynq 的 worker 池）。 */
    private final java.util.concurrent.ExecutorService worker =
            Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectProvider<MemoryExtractionService> handlerProvider;

    /**
     * 测试钩子：覆盖重试延迟（秒）。{@code null} = 用 asynq 的默认公式。
     *
     * <p>存在的理由与 wiki 那份完全一样：asynq 的默认退避是
     * {@code n^4 + 15 + rand(30)*(n+1)} 秒，第一次重试就要等 15–45 秒。
     * 不覆盖的话重试路径在单测里根本跑不动，于是只能靠代码审阅——那等于没测。</p>
     */
    private volatile Long retryDelayOverrideSeconds;

    public InProcessMemoryExtractTaskQueue(ObjectProvider<MemoryExtractionService> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    /** 供测试覆盖重试延迟（秒）。 */
    public void setRetryDelayOverrideSeconds(Long seconds) {
        this.retryDelayOverrideSeconds = seconds;
    }

    @Override
    public void enqueue(MemoryExtractPayload payload, Duration delay) {
        String body = payload.toJson();
        long delayMillis = delay == null ? 0L : Math.max(delay.toMillis(), 0L);
        try {
            if (delayMillis == 0L) {
                worker.execute(() -> run(body, 1));
            } else {
                scheduler.schedule(() -> worker.execute(() -> run(body, 1)), delayMillis,
                        TimeUnit.MILLISECONDS);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 调度器已关停（应用正在下线）：把失败报给调用方，让它释放在途槽位。
            throw new IllegalStateException("memory extract queue is shut down", e);
        }
    }

    /** 供测试/关停使用：停止调度并释放资源。 */
    public void shutdown() {
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    /**
     * 跑一次任务，失败按 asynq 的退避重排，直到重试预算用尽。
     *
     * <p>Go 里 {@code Handle} 返回 error 时 asynq 负责这一步；Java 侧在队列里等价实现。
     * 注意 handler 内部的 {@code return nil}（例如"负载没有作用域"、"主体被禁用"、
     * "租约忙"）都<b>不是</b>异常，所以不会触发重试——与 Go 一致。</p>
     */
    private void run(String body, int attempt) {
        MemoryExtractionService handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            log.warn("memory: no extraction handler bean, dropping task");
            return;
        }
        try {
            MemoryExtractPayload payload = MemoryExtractPayload.fromJson(body);
            // C 批：任务侧观测（对照 Go 的 AsynqMiddleware）——负载带 traceparent 就续接
            // 上游 trace，否则以任务类型开独立根；处理体包在 asynq.<type> span 内。
            try (com.ragagent.tracing.langfuse.LangfuseTaskScope scope =
                         com.ragagent.tracing.langfuse.LangfuseTaskScope.start(
                                 TASK_TYPE_MEMORY_EXTRACT, payload.tracing(),
                                 java.util.Map.of("subject_id", payload.subjectId(),
                                         "message_id", payload.messageId()),
                                 com.ragagent.tracing.langfuse.LangfuseTaskScope
                                         .previewPayload(body))) {
                handler.handle(payload);
            }
        } catch (Exception e) {
            if (attempt > MAX_RETRY) {
                log.warn("memory: extraction task gave up after {} attempts: {}",
                        attempt, e.toString());
                return;
            }
            long delaySeconds = retryDelaySeconds(attempt);
            log.warn("memory: extraction task failed (attempt {}/{}), retrying in {}s: {}",
                    attempt, MAX_RETRY + 1, delaySeconds, e.toString());
            try {
                scheduler.schedule(() -> worker.execute(() -> run(body, attempt + 1)),
                        delaySeconds, TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                log.warn("memory: extraction retry rejected during shutdown");
            }
        }
    }

    /**
     * 对照 asynq 的默认重试延迟 {@code n^4 + 15 + rand(30)*(n+1)} 秒
     * （{@code n} 是从 1 开始的第几次失败）。
     */
    long retryDelaySeconds(int attempt) {
        Long override = retryDelayOverrideSeconds;
        if (override != null) {
            return override;
        }
        long n = attempt;
        return n * n * n * n + 15 + ThreadLocalRandom.current().nextLong(30) * (n + 1);
    }
}
