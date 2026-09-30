package com.ragagent.common.approval;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;

/**
 * {@link RedisPubSub} 的 Spring Data Redis（Lettuce）实现。
 *
 * <p>接线方式（后续容器配置里一行即可）：
 * <pre>{@code
 * @Bean(destroyMethod = "close")
 * public Gate toolApprovalGate(RedisConnectionFactory factory, McpToolApprovalService svc) {
 *     return new Gate(GateOptions.fromConfig(agentProperties.toolApprovalTimeoutSeconds()),
 *                     new Adapter(theServiceBridge), new SpringRedisPubSub(factory));
 * }
 * }</pre></p>
 *
 * <p><b>实现要点</b>：订阅模式下的连接不能被复用——每次 {@link #subscribe(String)}
 * 独占一条连接，且消息接收方（本类的监听器）同时实现
 * {@link SubscriptionListener}，Redis 确认 SUBSCRIBE 后会触发
 * {@code onChannelSubscribed}，这正是 {@link Subscription#awaitSubscribed} 所等的信号
 * （与 {@code RedisMessageListenerContainer} 的用法一致：先 subscribe 再取订阅确认）。
 * 发布则每次借一条短连接。</p>
 */
public class SpringRedisPubSub implements RedisPubSub {

    private final RedisConnectionFactory connectionFactory;

    public SpringRedisPubSub(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public long publish(String channel, String payload) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Long receivers = connection.publish(bytes(channel), bytes(payload));
            return receivers == null ? 0L : receivers;
        }
    }

    @Override
    public Subscription subscribe(String channel) {
        return new LettuceSubscription(connectionFactory.getConnection(), channel);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 一条独占连接的频道订阅。 */
    private static final class LettuceSubscription implements RedisPubSub.Subscription, MessageListener, SubscriptionListener {

        private final RedisConnection connection;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        LettuceSubscription(RedisConnection connection, String channel) {
            this.connection = connection;
            // 本对象既是消息接收方也是订阅确认的接收方
            connection.subscribe(this, bytes(channel));
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            byte[] body = message.getBody();
            if (body != null) {
                messages.offer(new String(body, StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onChannelSubscribed(byte[] channel, long count) {
            subscribed.countDown();
        }

        @Override
        public boolean awaitSubscribed(Duration timeout) {
            try {
                return subscribed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public String receiveMessage(Duration timeout) {
            try {
                return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                // 关闭订阅连接即完成退订（与 RedisMessageListenerContainer.closeConnection 同）
                connection.close();
            } catch (RuntimeException ignored) {
                // 关闭失败无需处理
            }
        }
    }
}
