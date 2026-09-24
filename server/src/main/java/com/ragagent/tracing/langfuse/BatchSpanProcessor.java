package com.ragagent.tracing.langfuse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 批量 span 处理器（对照 Go otel-sdk 的 {@code BatchSpanProcessor}，按 config.go
 * 的三参数装配：{@code WithBatchTimeout(FlushInterval)}、
 * {@code WithMaxExportBatchSize(FlushAt)}、{@code WithMaxQueueSize(QueueSize)}）。
 *
 * <p>语义照抄：队列满丢最旧并计数；达 {@code FlushAt} 立即导出；定时器按
 * {@code FlushInterval} 刷；{@code shutdown} 先停定时器再终刷。导出失败只记日志
 * （span 丢失不重试——与 Go 的 BatchSpanProcessor 同义）。</p>
 */
final class BatchSpanProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchSpanProcessor.class);

    /** 导出出口（生产 = OTLP/HTTP；测试注入记录器——对照 Go Config.testExporter 钩子）。 */
    interface SpanSink {
        void export(List<RecordedSpan> spans) throws Exception;
    }

    private final LangfuseConfig cfg;
    private final SpanSink sink;
    private final ArrayDeque<RecordedSpan> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private final ScheduledExecutorService flushTimer;
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean stopped;

    BatchSpanProcessor(LangfuseConfig cfg, SpanSink sink) {
        this.cfg = cfg;
        this.sink = sink;
        long interval = Math.max(cfg.flushIntervalMs(), 100);
        this.flushTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "langfuse-flush");
            t.setDaemon(true);
            return t;
        });
        this.flushTimer.scheduleWithFixedDelay(this::flushQuietly, interval, interval,
                TimeUnit.MILLISECONDS);
    }

    /** span End 时入队（对照 otel-sdk OnEnd）。 */
    void enqueue(RecordedSpan span) {
        List<RecordedSpan> batch = null;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            if (queue.size() >= cfg.queueSize()) {
                queue.pollFirst();
                dropped.incrementAndGet();
                if (cfg.debug() && dropped.get() % 100 == 1) {
                    log.warn("[Langfuse] span queue full ({}), dropped {} spans so far",
                            cfg.queueSize(), dropped.get());
                }
            }
            queue.addLast(span);
            if (queue.size() >= cfg.flushAt()) {
                batch = drainLocked();
            }
        }
        if (batch != null) {
            send(batch);
        }
    }

    /** 立即导出当前队列（shutdown/终态用）。 */
    void flush() {
        List<RecordedSpan> batch;
        synchronized (lock) {
            batch = drainLocked();
        }
        send(batch);
    }

    /** 对照 Manager.Shutdown：停定时器 + 终刷；重复调用幂等。 */
    void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        flushTimer.shutdownNow();
        flush();
    }

    private List<RecordedSpan> drainLocked() {
        if (queue.isEmpty()) {
            return List.of();
        }
        List<RecordedSpan> batch = new ArrayList<>(queue);
        queue.clear();
        return batch;
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.debug("[Langfuse] flush failed: {}", e.getMessage());
        }
    }

    private void send(List<RecordedSpan> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            sink.export(batch);
        } catch (Exception e) {
            if (cfg.debug()) {
                log.warn("[Langfuse] export {} spans failed: {}", batch.size(), e.getMessage());
            } else {
                log.debug("[Langfuse] export {} spans failed: {}", batch.size(), e.getMessage());
            }
        }
    }
}
