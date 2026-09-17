package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.util.Map;

/**
 * {@link OAuthStateStore} 用到的 Redis 最小面（对照 go-redis 被用到的四个动作）。
 *
 * <p>存在的意义与 approval 包的 {@code RedisPubSub} 相同：把"跨实例"这一最复杂的
 * 语义与具体 Redis 客户端解耦——生产实现是 {@link SpringOAuthStateRedis}
 * （Spring Data Redis），测试实现是内存版假实现。</p>
 *
 * <p><b>与 Go 的对应</b>：
 * <ul>
 *   <li>{@code rdb.Set(ctx, k, v, ttl)} → {@link #set}</li>
 *   <li>{@code TxPipeline + Set + Set + Exec} → {@link #setAll}（一次批量写，语义等价）</li>
 *   <li>{@code rdb.Get(ctx, k).Bytes()} → {@link #get}，缺键返回 {@code null}（对照 {@code redis.Nil}）</li>
 *   <li>{@code rdb.GetDel(ctx, k)} → {@link #getAndDelete}（单次使用的原子消费）</li>
 * </ul>
 */
public interface OAuthStateRedis {

    /** 写一个键并设置 TTL。 */
    void set(String key, String value, Duration ttl);

    /**
     * 原子地写入多个键（对照 Go 的 {@code TxPipeline}：state 与 attempt 必须一起写，
     * 否则"state 在、attempt 不在"的中间态会让状态查询报 attempt 不存在）。
     */
    void setAll(Map<String, String> entries, Duration ttl);

    /** 读一个键；不存在返回 {@code null}（对照 {@code redis.Nil}）。 */
    String get(String key);

    /** 读并删除（单次使用）；不存在返回 {@code null}。 */
    String getAndDelete(String key);
}
