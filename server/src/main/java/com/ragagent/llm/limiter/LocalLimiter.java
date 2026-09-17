package com.ragagent.llm.limiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对照 Go limiter.localLimiter（governor.go）：按模型 ID 的进程内计数信号量。
 *
 * Go 用缓冲 channel（{@code map[string]chan struct{}}）实现：容量在首次使用时固定，
 * 发送 = 占槽、接收 = 放槽、Once 幂等释放。Java 的等价物是 {@link Semaphore}：
 * 首次 acquire 时以 limit 建容量，之后不再变更（Go 注释明示 limit 是进程级常量，
 * 跨 acquire 不会变，故无需 resize）。
 *
 * 这是 Go Lite 模式的对应实现（Lite 单进程无 Redis，分布式信号量既不可用也不需要），
 * 但后台摄入仍可能用整个 worker 池冲击同一上游，故仍需本地限流。
 *
 * ⚠️ 与 Go 的差异（后续阶段决策点）：Go 另有 redisLimiter（ZSET + Lua 自愈分布式信号量，
 * 用于多实例部署），本阶段未翻译——多实例下的并发上限不做跨进程协调，与
 * "asynq → 进程内虚拟线程队列"的既有取舍同类（见 translation-conventions §9 阶段 3 已知差异）。
 */
public class LocalLimiter implements ModelConcurrencyLimiter {

    /**
     * 等待槽位时的轮询间隔（毫秒）。Go 用 channel 阻塞 + ctx.Done() 双路 select，
     * Java 信号量没有等价的"阻塞但可取消"原语，用定时 tryAcquire 轮询 + 中断检查实现同一语义
     * （对照 Redis 实现的 defaultPollInterval 思路）。
     */
    private static final long POLL_INTERVAL_MS = 50L;

    private final Object lock = new Object();
    private final Map<String, Semaphore> sems = new HashMap<>();
    private final Map<String, TrackedSemaphore> tracked = new HashMap<>();

    @Override
    public Release acquire(String key, int limit) {
        // 对照 Go: if l == nil || limit <= 0 || key == "" { return noop, nil }
        if (limit <= 0 || key == null || key.isEmpty()) {
            return Release.NOOP;
        }

        Semaphore sem;
        TrackedSemaphore trackedSem;
        synchronized (lock) {
            trackedSem = tracked.get(key);
            if (trackedSem == null) {
                trackedSem = new TrackedSemaphore();
                tracked.put(key, trackedSem);
            }
            trackedSem.limit.set(limit);
            sem = sems.get(key);
            if (sem == null) {
                // 容量在首次使用时固定（对照 Go: make(chan struct{}, limit)）
                sem = new Semaphore(limit);
                trackedSem.capacity.set(limit);
                sems.put(key, sem);
            }
        }

        trackedSem.waiting.incrementAndGet();
        try {
            while (true) {
                try {
                    if (sem.tryAcquire(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                        // 幂等释放（对照 Go 的 sync.Once）
                        return new OnceRelease(sem);
                    }
                } catch (InterruptedException e) {
                    // 等待被中断 = ctx.Done() → fail open（对照 Go 的 noop 分支）
                    Thread.currentThread().interrupt();
                    return Release.NOOP;
                }
                if (Thread.currentThread().isInterrupted()) {
                    // 中断先于信号量授予：同样 fail open，并保住中断标志
                    return Release.NOOP;
                }
            }
        } finally {
            trackedSem.waiting.decrementAndGet();
        }
    }

    /** 对照 Go localLimiter.RuntimeStats：按 model ID 升序（Go 的 sort.Slice） */
    public List<RuntimeStat> runtimeStats() {
        List<RuntimeStat> stats;
        synchronized (lock) {
            stats = new ArrayList<>(sems.size());
            for (Map.Entry<String, Semaphore> e : sems.entrySet()) {
                TrackedSemaphore t = tracked.get(e.getKey());
                Semaphore sem = e.getValue();
                long active = t.capacity.get() - sem.availablePermits();
                stats.add(new RuntimeStat(e.getKey(),
                        t.name.get(),
                        active,
                        t.waiting.get(),
                        (int) t.limit.get()));
            }
        }
        stats.sort((a, b) -> a.modelId().compareTo(b.modelId()));
        return stats;
    }

    @Override
    public void setModelName(String modelId, String name) {
        // 对照 Go: if modelID == "" || name == "" { return }
        if (modelId == null || modelId.isEmpty() || name == null || name.isEmpty()) {
            return;
        }
        TrackedSemaphore t;
        synchronized (lock) {
            t = tracked.get(modelId);
            if (t == null) {
                t = new TrackedSemaphore();
                tracked.put(modelId, t);
            }
        }
        t.name.set(name);
    }

    /** 对照 Go localLimiter.release 闭包里的 sync.Once：重复关闭不多释放槽位 */
    private static final class OnceRelease implements Release {

        private final Semaphore sem;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OnceRelease(Semaphore sem) {
            this.sem = sem;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                sem.release();
            }
        }
    }
}
