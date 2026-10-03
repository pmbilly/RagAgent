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
 * <p>四个动作：写（带 TTL）、原子批量写、读（缺键返回 {@code null}）、读并删除。</p>
 */
public interface OAuthStateRedis {

    /** 写一个键并设置 TTL。 */
    void set(String key, String value, Duration ttl);

    /**
     * 原子地写入多个键（state 与 attempt 必须一起写，
     * 否则"state 在、attempt 不在"的中间态会让状态查询报 attempt 不存在）。
     */
    void setAll(Map<String, String> entries, Duration ttl);

    /** 读一个键；不存在返回 {@code null}（对照 {@code redis.Nil}）。 */
    String get(String key);

    /** 读并删除（单次使用）；不存在返回 {@code null}。 */
    String getAndDelete(String key);
}
