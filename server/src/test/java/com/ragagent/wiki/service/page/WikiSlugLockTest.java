package com.ragagent.wiki.service.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link WikiSlugLock} 的行为钉桩。
 *
 * <p>针对默认的 {@link InProcessWikiSlugLock} 覆盖：键拼装、同 slug 互斥、
 * 不同 slug 不互斥、超时返回 false、以及异常路径仍会释放锁。</p>
 */
class WikiSlugLockTest {

    /** 键拼装：前缀 + kbID + ":" + slug */
    @Test
    void lockKeyFormat() {
        assertThat(WikiSlugLock.lockKey("kb-1", "entity/a")).isEqualTo("wiki:slug:kb-1:entity/a");
        assertThat(WikiSlugLock.KEY_PREFIX).isEqualTo("wiki:slug:");
        // 常量钉桩
        assertThat(WikiSlugLock.TTL_SECONDS).isEqualTo(300);
        assertThat(WikiSlugLock.WAIT_SECONDS).isEqualTo(120);
        assertThat(WikiSlugLock.POLL_MILLIS).isEqualTo(50);
    }

    /** 同一把锁必须让两个线程串行；第二个线程看不到"重叠执行" */
    @Test
    void sameSlugIsMutuallyExclusive() throws Exception {
        WikiSlugLock lock = new InProcessWikiSlugLock();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        Runnable body = () -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            done.countDown();
        };

        for (int i = 0; i < 2; i++) {
            Thread.ofVirtual().start(() -> lock.withSlugLock("kb-1", "entity/a", body));
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).as("同一 slug 不得并发进入").isEqualTo(1);
    }

    /**
     * 不同 slug / 不同 KB 使用不同的锁对象，互不阻塞。
     *
     * <p>「同 slug 已被占用」这一条必须在<b>另一个线程</b>上断言：进程内实现用的是
     * {@link java.util.concurrent.locks.ReentrantLock}，同线程重入必然成功。
     * 这是已知、刻意的差异：单实例下语义等价，且调用方不存在同 slug 嵌套加锁。</p>
     */
    @Test
    void differentSlugsDoNotBlockEachOther() throws Exception {
        WikiSlugLock lock = new InProcessWikiSlugLock();
        assertThat(lock.tryLock("kb-1", "entity/a", Duration.ZERO)).isTrue();
        try {
            // 同 slug 已在别的线程持有 → 立即失败（等待时长 0）
            AtomicInteger heldElsewhere = new AtomicInteger(-1);
            Thread other = Thread.ofVirtual().start(() -> heldElsewhere.set(
                    lock.tryLock("kb-1", "entity/a", Duration.ZERO) ? 1 : 0));
            other.join(5_000);
            assertThat(heldElsewhere.get()).as("同 slug 在其它线程上必须拿不到").isZero();

            // 不同 slug：不受影响
            assertThat(lock.tryLock("kb-1", "entity/b", Duration.ZERO)).isTrue();
            lock.unlock("kb-1", "entity/b");
            // 不同 KB 的同一 slug 也不互斥
            assertThat(lock.tryLock("kb-2", "entity/a", Duration.ZERO)).isTrue();
            lock.unlock("kb-2", "entity/a");
        } finally {
            lock.unlock("kb-1", "entity/a");
        }
        // 释放后可以重新获取
        assertThat(lock.tryLock("kb-1", "entity/a", Duration.ZERO)).isTrue();
        lock.unlock("kb-1", "entity/a");
    }

    /** 等不到锁时 fn 不执行（整体返回 false） */
    @Test
    void withSlugLockTimesOutWithoutRunningBody() {
        WikiSlugLock lock = new InProcessWikiSlugLock() {
            @Override
            public boolean tryLock(String kbId, String slug, Duration wait) {
                return false; // 模拟 2 分钟等待超时
            }
        };
        AtomicInteger runs = new AtomicInteger();
        boolean acquired = lock.withSlugLock("kb-1", "entity/a", runs::incrementAndGet);
        assertThat(acquired).isFalse();
        assertThat(runs.get()).as("超时后正文不得执行").isZero();
    }

    /** fn 抛异常也必须释放锁 */
    @Test
    void lockIsReleasedOnFailure() {
        WikiSlugLock lock = new InProcessWikiSlugLock();
        try {
            lock.withSlugLock("kb-1", "entity/a", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // 预期
        }
        assertThat(lock.tryLock("kb-1", "entity/a", Duration.ZERO))
                .as("异常路径也必须释放锁").isTrue();
        lock.unlock("kb-1", "entity/a");
    }

    /** 未持有时 unlock 是安全的 no-op */
    @Test
    void unlockWithoutHoldingIsSafe() {
        WikiSlugLock lock = new InProcessWikiSlugLock();
        lock.unlock("kb-1", "never-locked");
        lock.unlock("kb-1", "never-locked");
        assertThat(lock.tryLock("kb-1", "never-locked", Duration.ZERO)).isTrue();
        lock.unlock("kb-1", "never-locked");
    }
}
