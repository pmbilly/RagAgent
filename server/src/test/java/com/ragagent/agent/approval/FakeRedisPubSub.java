package com.ragagent.agent.approval;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 内存版 Pub/Sub（测试替身，语义对照 Redis 频道：发布投递给该频道的所有订阅者，
 * 包括发布者自己的订阅——Go 侧 go-redis 亦如此，靠 OriginID 过滤自发报文）。
 */
class FakeRedisPubSub implements RedisPubSub {

    private final Map<String, List<FakeSubscription>> subscribers = new ConcurrentHashMap<>();
    private final List<String> published = new CopyOnWriteArrayList<>();

    @Override
    public Subscription subscribe(String channel) {
        FakeSubscription sub = new FakeSubscription(channel);
        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    @Override
    public long publish(String channel, String payload) {
        published.add(channel + "|" + payload);
        List<FakeSubscription> subs = subscribers.get(channel);
        if (subs == null) {
            return 0;
        }
        for (FakeSubscription sub : subs) {
            sub.enqueue(payload);
        }
        return subs.size();
    }

    /** 等订阅者就位，避免测试里“发布早于订阅”的偶然失败 */
    void awaitSubscribers(String channel, int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<FakeSubscription> subs = subscribers.get(channel);
            if (subs != null && subs.size() >= count) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("no subscriber for channel " + channel);
    }

    List<String> published() {
        return published;
    }

    private final class FakeSubscription implements RedisPubSub.Subscription {

        private final String channel;
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private volatile boolean open = true;

        FakeSubscription(String channel) {
            this.channel = channel;
        }

        void enqueue(String payload) {
            if (open) {
                queue.offer(payload);
            }
        }

        @Override
        public boolean awaitSubscribed(Duration timeout) {
            return open;
        }

        @Override
        public String receiveMessage(Duration timeout) {
            try {
                return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {
            open = false;
            List<FakeSubscription> subs = subscribers.get(channel);
            if (subs != null) {
                subs.remove(this);
            }
        }
    }
}
