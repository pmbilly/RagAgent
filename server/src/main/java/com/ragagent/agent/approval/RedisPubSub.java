package com.ragagent.agent.approval;

import java.time.Duration;

/**
 * gate 用到的 Redis Pub/Sub 最小面（对照 go-redis 的 {@code Subscribe} / {@code Publish}）。
 *
 * <p>存在的意义：把跨实例广播这一最复杂语义与具体 Redis 客户端解耦——
 * 产品实现是 {@link SpringRedisPubSub}（Spring Data Redis / Lettuce），
 * 测试实现是内存版假 pubsub，可完整跑通跨实例分支。</p>
 *
 * <p><b>与 Go 的对应</b>：
 * <ul>
 *   <li>{@code rdb.Subscribe(ctx, ch)} → {@link #subscribe(String)}</li>
 *   <li>{@code sub.Receive(ctx)}（等订阅生效）→ {@link Subscription#awaitSubscribed(Duration)}</li>
 *   <li>{@code sub.ReceiveMessage(ctx)}（带超时收一条）→ {@link Subscription#receiveMessage(Duration)}</li>
 *   <li>{@code sub.Close()} → {@link Subscription#close()}</li>
 *   <li>{@code rdb.Publish(ctx, ch, payload)} → {@link #publish(String, String)}</li>
 * </ul>
 * 消息体统一按 UTF-8 字符串承载（Go 侧 payload 是 JSON 字节）。</p>
 */
public interface RedisPubSub {

    /**
     * 订阅一个频道。返回的订阅**必须**由调用方 close（每个订阅占一条独立连接）。
     * 失败抛运行时异常（Go: error）。
     */
    Subscription subscribe(String channel);

    /** 发布一条消息，返回接收者数量（go-redis 同）。失败抛运行时异常。 */
    long publish(String channel, String payload);

    /** 一次频道订阅（对照 go-redis 的 *redis.PubSub） */
    interface Subscription extends AutoCloseable {

        /**
         * 等待订阅在 Redis 侧生效（对照 {@code sub.Receive(ctx)}：
         * Go 用它确保“先订阅成功、再发布”，否则可能漏掉 ack）。
         * 超时或失败返回 false（调用方可继续，最坏情况是漏一条消息）。
         */
        boolean awaitSubscribed(Duration timeout);

        /**
         * 阻塞收取一条消息（对照 {@code sub.ReceiveMessage(ctx)}）。
         *
         * @return 消息体；超时或订阅已关闭返回 {@code null}
         */
        String receiveMessage(Duration timeout);

        /** 对照 {@code sub.Close()}：退订并释放连接（不抛异常） */
        @Override
        void close();
    }
}
