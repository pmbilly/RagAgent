package com.ragagent.knowledge.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 进程内分块抽取队列（对照 Go asynq 的 {@code TypeChunkExtract} 任务：Queue=graph、
 * MaxRetry=3、Timeout=30min 的语义子集）。
 *
 * <p>与内存抽取队列同款纪律：虚拟线程执行、失败按 asynq 的重试延迟公式退避
 * （{@code n^4 + 15 + rand(30)*(n+1)} 秒）、超过 {@link #MAX_RETRY} 次放弃并记日志
 * （死信归档在此不落表——Go 有 asynqdl 中间件，Java 侧的 task_dead_letter 表由 wiki
 * 队列维护；此处只保证"不无限重试、不吞异常"）。</p>
 */
@Component
public class InProcessChunkExtractTaskQueue implements ChunkExtractTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(InProcessChunkExtractTaskQueue.class);

    /** 对照 {@code asynq.MaxRetry(3)}。 */
    static final int MAX_RETRY = 3;

    private final ChunkExtractService service;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chunk-extract-retry");
                t.setDaemon(true);
                return t;
            });

    /** 测试可覆盖重试延迟（秒）。 */
    Long retryDelayOverrideSeconds;

    public InProcessChunkExtractTaskQueue(ChunkExtractService service) {
        this.service = service;
    }

    @Override
    public void enqueue(ExtractChunkPayload payload) {
        String body = payload.toJson();
        try {
            worker.execute(() -> run(body, 1));
        } catch (RejectedExecutionException e) {
            // 关闭中：投递失败必须上抛，让 fan-out 释放该分块的 finalizing 槽
            throw new IllegalStateException("chunk extract queue is shutting down", e);
        }
    }

    private void run(String body, int attempt) {
        try {
            service.handleJson(body);
        } catch (RuntimeException e) {
            if (attempt > MAX_RETRY) {
                log.warn("chunk extract task gave up after {} attempts: {}", attempt, e.toString());
                return;
            }
            long delaySeconds = retryDelaySeconds(attempt);
            log.warn("chunk extract task failed (attempt {}/{}), retrying in {}s: {}",
                    attempt, MAX_RETRY + 1, delaySeconds, e.toString());
            try {
                scheduler.schedule(() -> worker.execute(() -> run(body, attempt + 1)),
                        delaySeconds, TimeUnit.SECONDS);
            } catch (RejectedExecutionException rejected) {
                log.warn("chunk extract retry rejected during shutdown");
            }
        }
    }

    /** 对照 asynq 的默认重试延迟 {@code n^4 + 15 + rand(30)*(n+1)} 秒（n 从 1 起）。 */
    long retryDelaySeconds(int attempt) {
        if (retryDelayOverrideSeconds != null) {
            return retryDelayOverrideSeconds;
        }
        long base = (long) Math.pow(attempt, 4) + 15;
        long jitter = (long) (java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                * 30 * (attempt + 1));
        return base + jitter;
    }
}
