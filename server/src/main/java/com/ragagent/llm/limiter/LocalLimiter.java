package com.ragagent.llm.limiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按模型 ID 的进程内计数信号量。
 *
 * 容量在首次使用时以 limit 固定，之后不再变更（limit 是进程级常量，
 * 跨 acquire 不会变，故无需 resize）。acquire = 占槽、release = 放槽，释放幂等。
 *
 * 单进程部署下分布式信号量既不可用也不需要；
 * 但后台摄入仍可能用整个 worker 池冲击同一上游，故仍需本地限流。
 *
 * ⚠️ 本实现仅进程内生效：多实例部署下的并发上限不做跨进程协调。
 */
public class LocalLimiter implements ModelConcurrencyLimiter {

    /**
     * 等待槽位时的轮询间隔（毫秒）。信号量没有"阻塞但可取消"的单步原语，
     * 用定时 tryAcquire 轮询 + 中断检查实现可取消的等待。
     */
    private static final long POLL_INTERVAL_MS = 50L;

    private final Object lock = new Object();
    private final Map<String, Semaphore> sems = new HashMap<>();
    private final Map<String, TrackedSemaphore> tracked = new HashMap<>();

    @Override
    public Release acquire(String key, int limit) {
        // limit<=0 或 key 为空 → 直接放行（noop）
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
                // 容量在首次使用时固定
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
                        // 幂等释放
                        return new OnceRelease(sem);
                    }
                } catch (InterruptedException e) {
                    // 等待被中断 → fail open
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

    /** 按 model ID 升序返回各模型信号量的观测数据 */
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
        // modelID 或 name 为空时忽略
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

    /** 幂等释放：重复关闭不多释放槽位 */
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
