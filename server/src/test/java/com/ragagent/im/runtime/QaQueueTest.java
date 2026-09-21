package com.ragagent.im.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * QA 队列行为（对照 Go qaqueue_test 语义面：队满/每用户限额/Remove/排空退出）。
 * Go 的 redis==nil 分支即本实现的唯一路径（波 5 纪律：内存形态先行）。
 */
class QaQueueTest {

    @Test
    void enqueueRejectsWhenFullAndPerUserLimited() {
        QaQueue q = new QaQueue(1, 3, 1, req -> {
        });
        q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "m1")));
        QaQueue.RejectedException perUser = assertThrows(QaQueue.RejectedException.class,
                () -> q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "m2"))));
        assertTrue(perUser.getMessage().contains("per-user queue limit reached (1/1)"));
        q.enqueue(new QaQueue.QaRequest("u2", IncomingMessage.of("wecom", "u2", "m1")));
        q.enqueue(new QaQueue.QaRequest("u3", IncomingMessage.of("wecom", "u3", "m1")));
        QaQueue.RejectedException full = assertThrows(QaQueue.RejectedException.class,
                () -> q.enqueue(new QaQueue.QaRequest("u4", IncomingMessage.of("wecom", "u4", "m1"))));
        assertTrue(full.getMessage().contains("queue full (3/3)"));
        assertEquals(2, q.metrics().totalRejected());
        q.stop();
    }

    @Test
    void removeCancelsQueuedRequestAndFreesUserSlot() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch firstSeen = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        QaQueue q = new QaQueue(1, 5, 3, req -> {
            firstSeen.countDown();
            try {
                block.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            processed.incrementAndGet();
        });
        q.start();
        q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "busy")));
        assertTrue(firstSeen.await(5, TimeUnit.SECONDS), "worker 应该取走首个请求");
        q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "queued")));
        assertEquals(1, q.metrics().depth());
        assertTrue(q.remove("u1"), "Remove 应命中排队请求");
        assertEquals(0, q.metrics().depth());
        // Remove 之后同一用户的限额已释放
        q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "again")));
        block.countDown();
        q.stop();
    }

    @Test
    void workerDrainsQueueAndExitsOnStop() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        QaQueue q = new QaQueue(2, 5, 3, req -> done.countDown());
        q.start();
        q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "a")));
        q.enqueue(new QaQueue.QaRequest("u2", IncomingMessage.of("wecom", "u2", "b")));
        assertTrue(done.await(5, TimeUnit.SECONDS), "两个请求都应被处理");
        q.stop();
        assertEquals(2, q.metrics().totalProcessed());
    }

    @Test
    void cancelWhileQueuedCountsAsTimeout() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch firstSeen = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        QaQueue q = new QaQueue(1, 5, 3, req -> {
            firstSeen.countDown();
            try {
                block.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            processed.incrementAndGet();
        });
        q.start();
        q.enqueue(new QaQueue.QaRequest("u1", IncomingMessage.of("wecom", "u1", "busy")));
        assertTrue(firstSeen.await(5, TimeUnit.SECONDS));
        QaQueue.QaRequest queued = new QaQueue.QaRequest("u2", IncomingMessage.of("wecom", "u2", "drop"));
        q.enqueue(queued);
        queued.cancel();
        block.countDown();
        Thread.sleep(200);
        assertEquals(1, q.metrics().totalTimeout(), "取消的排队请求按 timeout 计");
        assertEquals(1, processed.get());
        q.stop();
    }
}
