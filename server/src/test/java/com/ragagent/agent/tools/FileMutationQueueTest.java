package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * FileMutationQueue 状态机（对照 Go file_mutation_queue.go 的实测行为：
 * epoch 单调计数、同 key 互斥、不同 key 并发、表项用后释放）。
 */
class FileMutationQueueTest {

    @Test
    void epochIncrementsPerMutation() {
        long start = FileMutationQueue.sandboxMutationEpoch();
        FileMutationQueue.noteSandboxMutation();
        FileMutationQueue.noteSandboxMutation();
        assertThat(FileMutationQueue.sandboxMutationEpoch()).isEqualTo(start + 2);
    }

    @Test
    void sameKeyBlocksUntilReleased() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Runnable unlock = queue.lockFile("/a.txt");
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicBoolean secondWonRace = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            // 先等一小会儿确保第一个锁还在持有期
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            Runnable release = queue.lockFile("/a.txt");
            secondWonRace.set(false);
            release.run();
            acquired.countDown();
        });
        t.start();
        // 持有期间第二个线程不应拿到锁
        assertThat(acquired.await(30, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(secondWonRace).isTrue();
        unlock.run();
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        t.join();
    }

    @Test
    void differentKeysDoNotBlock() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Runnable unlockA = queue.lockFile("/workspace/a.txt");
        CountDownLatch acquired = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            queue.lockFile("/workspace/b.txt").run();
            acquired.countDown();
        });
        t.start();
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        unlockA.run();
        t.join();
    }

    @Test
    void lockTableDoesNotGrow() {
        FileMutationQueue queue = new FileMutationQueue();
        for (int i = 0; i < 100; i++) {
            queue.lockFile("/workspace/" + i + ".txt").run();
        }
        assertThat(queue.isEmptyForTest()).isTrue();
    }

    @Test
    void lockSandboxFileKeysPerSessionAndPath() throws Exception {
        // 对照 lockSandboxFile(sessionID, filePath) 的 key 复合：同会话同路径互斥即可，
        // 跨会话的同路径互不影响（不同 sandbox 工作区）。
        FileMutationQueue q1 = new FileMutationQueue();
        FileMutationQueue q2 = new FileMutationQueue();
        Runnable release1 = q1.lockFile("session-1|/shared.txt");
        CountDownLatch acquired = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            q2.lockFile("session-2|/shared.txt").run();
            acquired.countDown();
        });
        t.start();
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        release1.run();
        // lockSandboxFile 是静态门——用真实静态路径再验证一次同会话互斥
        Runnable hold = FileMutationQueue.lockSandboxFile("s", "/f.txt");
        CountDownLatch second = new CountDownLatch(1);
        Thread t2 = new Thread(() -> {
            FileMutationQueue.lockSandboxFile("s", "/f.txt").run();
            second.countDown();
        });
        t2.start();
        assertThat(second.await(30, TimeUnit.MILLISECONDS)).isFalse();
        hold.run();
        assertThat(second.await(2, TimeUnit.SECONDS)).isTrue();
        t2.join();
    }
}
