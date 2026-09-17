package com.ragagent.llm.limiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/models/limiter/governor_test.go 与 limiter_test.go 的本地信号量部分：
 * TestGateOnlyGovernsBackground / TestGateDisabledWhenNoGovernor / TestLocalLimiterCaps /
 * TestLocalLimiterIndependentKeys / TestLocalLimiterFailOpen / TestLocalLimiterReleaseIdempotent。
 *
 * <p>Go 的 ctx（超时/取消）在 Java 侧映射为线程中断；Go 的 goroutine 用虚拟线程。</p>
 *
 * <p>未翻译/未覆盖的部分见 {@link LocalLimiter} 的类注释：Go 的 redisLimiter（分布式 ZSET
 * 信号量，需真实 Redis/miniredis）不在本阶段范围。</p>
 */
class ConcurrencyGovernorTest {

    /** 对照 Go withBackground()：在后台任务标记作用域内取槽 */
    private static Release gateAsBackground(ConcurrencyGovernor governor, String modelId) {
        try (BackgroundTaskContext.Scope ignored = BackgroundTaskContext.mark()) {
            return governor.gate(modelId);
        }
    }

    /**
     * 对照 Go TestGateOnlyGovernsBackground：交互式调用永不节流（no-op release），
     * 只有后台调用才查已装配的限流器。
     */
    @Test
    void gateOnlyGovernsBackground() throws Exception {
        ConcurrencyGovernor governor = new ConcurrencyGovernor();
        governor.setGovernor(new LocalLimiter(), 1);
        try {
            // 交互式（未标记后台）：limit=1 也不节流，直接两次放行
            assertSame(Release.NOOP, governor.gate("m"));
            assertSame(Release.NOOP, governor.gate("m"));
            // 交互式路径也不该把 key 注册进信号量表
            assertTrue(governor.runtimeStats().stats().isEmpty());

            // 后台：被节流。第一个占槽，第二个等待中被中断 → 必须 fail open（返回可用 release）
            Release held = gateAsBackground(governor, "m");
            assertNotNull(held);
            assertFalse(governor.runtimeStats().stats().isEmpty());

            AtomicReference<Release> second = new AtomicReference<>();
            Thread waiter = Thread.ofVirtual().start(() -> second.set(gateAsBackground(governor, "m")));
            Thread.sleep(30);
            waiter.interrupt();
            waiter.join(2000);

            Release rel = second.get();
            assertNotNull(rel, "cancelled background gate must fail open with a usable release");
            assertSame(Release.NOOP, rel);
            rel.close();

            held.close();
        } finally {
            governor.setGovernor(null, 0);
        }
    }

    /** 对照 Go TestGateDisabledWhenNoGovernor：未装配 governor 时 Gate 是 passthrough */
    @Test
    void gateDisabledWhenNoGovernor() {
        ConcurrencyGovernor governor = new ConcurrencyGovernor();
        Release rel = gateAsBackground(governor, "m");
        assertNotNull(rel);
        rel.close();

        // 装配了 limiter 但默认上限 <= 0 → 同样放行
        governor.setGovernor(new LocalLimiter(), 0);
        assertSame(Release.NOOP, gateAsBackground(governor, "m"));

        // limiter 为 null（limit 有效）→ 同样放行
        governor.setGovernor(null, 4);
        assertSame(Release.NOOP, gateAsBackground(governor, "m"));

        // SetGlobalLimit 不改后端：先装配再改上限
        governor.setGovernor(new LocalLimiter(), 0);
        governor.setGlobalLimit(1);
        Release gated = gateAsBackground(governor, "m");
        assertNotNull(gated);
        assertFalse(governor.runtimeStats().stats().isEmpty());
        gated.close();
    }

    /** 对照 Go TestLocalLimiterCaps：上限生效，且释放一个槽位后等待者能进来 */
    @Test
    void localLimiterCaps() throws Exception {
        LocalLimiter limiter = new LocalLimiter();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Release r1 = limiter.acquire("k", 2);
            Release r2 = limiter.acquire("k", 2);

            // 满槽时第三次 acquire 必须阻塞
            Future<Release> third = executor.submit(() -> limiter.acquire("k", 2));
            assertThrows(TimeoutException.class, () -> third.get(100, TimeUnit.MILLISECONDS));

            r1.close(); // 放槽 → 等待者进入
            Release r3 = third.get(2, TimeUnit.SECONDS);
            r3.close();
            r2.close();
        }
    }

    /** 对照 Go TestLocalLimiterIndependentKeys：per-key 预算相互独立 */
    @Test
    void localLimiterIndependentKeys() {
        LocalLimiter limiter = new LocalLimiter();
        Release ra = limiter.acquire("a", 1);
        Release rb = limiter.acquire("b", 1); // 不同 key 不得阻塞
        ra.close();
        rb.close();
    }

    /** 对照 Go TestLocalLimiterFailOpen：退化输入一律放行 */
    @Test
    void localLimiterFailOpen() {
        LocalLimiter limiter = new LocalLimiter();
        assertSame(Release.NOOP, limiter.acquire("", 4));
        assertSame(Release.NOOP, limiter.acquire("k", 0));
        assertSame(Release.NOOP, limiter.acquire("k", -1));
        assertSame(Release.NOOP, limiter.acquire(null, 4));
    }

    /** 对照 Go TestLocalLimiterReleaseIdempotent：双重释放不得多放一个槽位 */
    @Test
    void localLimiterReleaseIdempotent() throws Exception {
        LocalLimiter limiter = new LocalLimiter();
        Release r1 = limiter.acquire("k", 1);

        Thread a = Thread.ofVirtual().start(r1::close);
        Thread b = Thread.ofVirtual().start(r1::close);
        a.join(2000);
        b.join(2000);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 幂等释放后唯一槽位应已空闲
            Release r2 = limiter.acquire("k", 1);
            Future<Release> third = executor.submit(() -> limiter.acquire("k", 1));
            assertThrows(TimeoutException.class, () -> third.get(100, TimeUnit.MILLISECONDS));

            r2.close();
            Release r3 = third.get(2, TimeUnit.SECONDS);
            r3.close();
        }
    }

    /** RuntimeStats/SetModelName 观测通道（对照 Go limiter.RuntimeStat 的字段语义） */
    @Test
    void runtimeStatsReportsActiveWaitingAndLimit() {
        LocalLimiter limiter = new LocalLimiter();
        Release r = limiter.acquire("model-a", 2);
        limiter.setModelName("model-a", "gpt-4o");

        List<RuntimeStat> stats = limiter.runtimeStats();
        assertEquals(1, stats.size());
        RuntimeStat stat = stats.get(0);
        assertEquals("model-a", stat.modelId());
        assertEquals("gpt-4o", stat.name());
        assertEquals(1, stat.active());
        assertEquals(0, stat.waiting());
        assertEquals(2, stat.limit());

        r.close();
        assertEquals(0, limiter.runtimeStats().get(0).active());

        // 未装配可观测后端（或未装配 governor）时 enabled=false
        assertFalse(new ConcurrencyGovernor().runtimeStats().enabled());
    }

    /** 后台任务标记（对照 Go WithBackgroundTask/IsBackgroundTask）：作用域退出即恢复 */
    @Test
    void backgroundTaskMarkIsScoped() {
        assertFalse(BackgroundTaskContext.isBackgroundTask());
        try (BackgroundTaskContext.Scope ignored = BackgroundTaskContext.mark()) {
            assertTrue(BackgroundTaskContext.isBackgroundTask());
            try (BackgroundTaskContext.Scope nested = BackgroundTaskContext.mark()) {
                assertTrue(BackgroundTaskContext.isBackgroundTask());
            }
            assertTrue(BackgroundTaskContext.isBackgroundTask(), "嵌套作用域退出后仍应保持标记");
        }
        assertFalse(BackgroundTaskContext.isBackgroundTask());

        // 标记是线程本地的：不跨虚拟线程传递（对照 Go 需要 CloneContext 才保留）
        AtomicReference<Boolean> inWorker = new AtomicReference<>(true);
        try (BackgroundTaskContext.Scope ignored = BackgroundTaskContext.mark()) {
            Thread worker = Thread.ofVirtual().start(
                    () -> inWorker.set(BackgroundTaskContext.isBackgroundTask()));
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertFalse(inWorker.get(), "ThreadLocal 标记不得泄漏到其它线程");
    }
}
