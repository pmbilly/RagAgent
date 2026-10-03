package com.ragagent.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.ragagent.datasource.domain.DataSourceSyncPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link InProcessDataSourceSyncTaskQueue} 的语义测试（{@code Scheduler.triggerSync}
 * 路径依赖的三条语义：TaskID 去重、重试预算、任务超时）。
 *
 * <h2>不靠墙钟</h2>
 * <p>默认退避是 {@code n^4 + 15 + rand(30)*(n+1)} 秒（第一次就要 15–45 秒），
 * 单测里跑不动。{@link InProcessDataSourceSyncTaskQueue#setRetryDelayOverrideSeconds(Long)}
 * 就是为这条留的缝（与 wiki / memory 两处同款），测试里设为 0。
 * 等待完成用 {@link CountDownLatch} 而非 sleep——只等"事件发生"，不等"时间流逝"。</p>
 */
class InProcessDataSourceSyncTaskQueueTest {

    /** 最小可用的 {@link ObjectProvider} 替身（Spring 的接口只有这四个方法没有默认实现）。 */
    private static final class Provider implements ObjectProvider<DataSourceSyncHandler> {

        private final DataSourceSyncHandler handler;

        Provider(DataSourceSyncHandler handler) {
            this.handler = handler;
        }

        @Override
        public DataSourceSyncHandler getObject() throws BeansException {
            return handler;
        }

        @Override
        public DataSourceSyncHandler getObject(Object... args) throws BeansException {
            return handler;
        }

        @Override
        public DataSourceSyncHandler getIfAvailable() throws BeansException {
            return handler;
        }

        @Override
        public DataSourceSyncHandler getIfUnique() throws BeansException {
            return handler;
        }

        @Override
        public Iterator<DataSourceSyncHandler> iterator() {
            return List.of(handler).iterator();
        }
    }

    private static DataSourceSyncPayload payload(String dsId) {
        return new DataSourceSyncPayload(null, "schedule", dsId, 10002L, "log-1", false, 0);
    }

    private static InProcessDataSourceSyncTaskQueue queueFor(DataSourceSyncHandler handler) {
        InProcessDataSourceSyncTaskQueue queue =
                new InProcessDataSourceSyncTaskQueue(new Provider(handler));
        queue.setRetryDelayOverrideSeconds(0L);
        return queue;
    }

    // ── TaskID 去重（调度器第 2 层去重的全部） ────────────────────────────

    /**
     * 同一个 TaskID 在"入队到执行结束"期间第二次入队 → {@code TASK_ID_CONFLICT}。
     * 这正是调度器把 sync_log 记成 canceled 的那条路。
     */
    @Test
    void duplicateTaskIdWhileInFlightConflicts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        InProcessDataSourceSyncTaskQueue queue = queueFor(p -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(queue.enqueue(payload("ds-1"), "dssync:ds-1:202601010000", 5,
                Duration.ofSeconds(30))).isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 在途期间：冲突
        assertThat(queue.enqueue(payload("ds-1"), "dssync:ds-1:202601010000", 5,
                Duration.ofSeconds(30))).isEqualTo(DataSourceSyncTaskQueue.Outcome.TASK_ID_CONFLICT);
        assertThat(queue.inflightCount()).isEqualTo(1);

        release.countDown();
        waitUntil(() -> queue.inflightCount() == 0);

        assertThat(runs.get()).isEqualTo(1);
        // TaskID 释放后可以再入队（下一分钟本来就是一个新的 ID）
        assertThat(queue.enqueue(payload("ds-1"), "dssync:ds-1:202601010000", 5,
                Duration.ofSeconds(30))).isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
        waitUntil(() -> queue.inflightCount() == 0);
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void differentTaskIdsRunConcurrently() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        InProcessDataSourceSyncTaskQueue queue = queueFor(p -> {
            both.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(queue.enqueue(payload("ds-1"), "t-1", 5, Duration.ofSeconds(30)))
                .isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
        assertThat(queue.enqueue(payload("ds-2"), "t-2", 5, Duration.ofSeconds(30)))
                .isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
        assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.inflightCount()).isEqualTo(2);

        release.countDown();
        waitUntil(() -> queue.inflightCount() == 0);
    }

    // ── 重试预算 ─────────────────────────────────────────────────────────

    /** 对照 {@code asynq.MaxRetry(5)}：失败后最多再试 5 次，共 6 次尝试。 */
    @Test
    void retriesUpToMaxRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(6);
        InProcessDataSourceSyncTaskQueue queue = queueFor(p -> {
            attempts.incrementAndGet();
            done.countDown();
            throw new IllegalStateException("boom");
        });

        queue.enqueue(payload("ds-1"), "t-1", 5, Duration.ofSeconds(30));
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> queue.inflightCount() == 0);

        assertThat(attempts.get()).isEqualTo(6);
    }

    /** 第一次失败、第二次成功 → 恰好两次调用（重试有效，且不继续重试）。 */
    @Test
    void retryStopsAfterFirstSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        InProcessDataSourceSyncTaskQueue queue = queueFor(p -> {
            done.countDown();
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first attempt fails");
            }
        });

        queue.enqueue(payload("ds-1"), "t-1", 5, Duration.ofSeconds(30));
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> queue.inflightCount() == 0);

        assertThat(attempts.get()).isEqualTo(2);
    }

    /** {@code maxRetry = 0} → 只试一次。 */
    @Test
    void zeroMaxRetryMeansSingleAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        InProcessDataSourceSyncTaskQueue queue = queueFor(p -> {
            attempts.incrementAndGet();
            done.countDown();
            throw new IllegalStateException("boom");
        });

        queue.enqueue(payload("ds-1"), "t-1", 0, Duration.ofSeconds(30));
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> queue.inflightCount() == 0);

        assertThat(attempts.get()).isEqualTo(1);
    }

    // ── 任务超时（超时 → 中断执行线程） ───────────────────────────────────

    /**
     * 超时后执行线程被**中断**，本次尝试算失败、进入重试预算。
     * 这里用"handler 阻塞到被中断"来验证中断确实送达——这正是连接器里
     * {@link Connector#sleep} 依赖的机制。
     */
    @Test
    void timeoutInterruptsTheRunningHandler() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        InProcessDataSourceSyncTaskQueue queue = queueFor(p -> {
            if (attempts.incrementAndGet() == 1) {
                firstStarted.countDown();
                try {
                    // 睡到被中断为止（远超 200ms 的任务超时）
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new ConnectorException("interrupted", e);
                }
                return;
            }
            // 第二次尝试立刻成功
        });

        queue.enqueue(payload("ds-1"), "t-1", 5, Duration.ofMillis(200));
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> queue.inflightCount() == 0);

        assertThat(attempts.get()).isEqualTo(2);
    }

    /** 没有 handler bean 时任务被丢弃（记日志），不入死循环、TaskID 正常释放。 */
    @Test
    void missingHandlerDropsTheTaskWithoutRetrying() throws Exception {
        InProcessDataSourceSyncTaskQueue queue =
                new InProcessDataSourceSyncTaskQueue(new Provider(null));
        queue.setRetryDelayOverrideSeconds(0L);

        assertThat(queue.enqueue(payload("ds-1"), "t-1", 5, Duration.ofSeconds(30)))
                .isEqualTo(DataSourceSyncTaskQueue.Outcome.ENQUEUED);
        waitUntil(() -> queue.inflightCount() == 0);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    /** 轮询等待某个条件成立（最多 5 秒）。条件是"事件已经发生"，不是"时间已经过去"。 */
    private static void waitUntil(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("condition not met within 5s");
    }
}
