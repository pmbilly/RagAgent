package com.ragagent.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流管理器配置（对照 Go {@code internal/stream/factory.go} 读的那组环境变量，
 * 以及 {@code config.StreamManagerConfig}，internal/config/config.go:445-450）。
 *
 * <p>Go 的工厂读的是裸环境变量：{@code STREAM_MANAGER_TYPE} / {@code REDIS_ADDR} /
 * {@code REDIS_USERNAME} / {@code REDIS_PASSWORD} / {@code REDIS_DB} /
 * {@code REDIS_PREFIX}，TTL 则硬编码 {@code time.Hour}。Java 侧把选择与 TTL 收进
 * 这里，连接参数交给 {@code spring.data.redis.*}（同一批环境变量，见 application.yml）。</p>
 *
 * @param type   {@code "redis"} 选 Redis 后端，**其余一切值**（含空、大小写不符）都是内存后端
 *               ——Go 的 {@code switch} 是精确匹配，别改成 equalsIgnoreCase
 * @param prefix Redis 键前缀；空则回落 {@code "stream:events"}。**原样使用**：
 *               .env 里是 {@code stream:}，拼出来的键是 {@code stream::sess:msg}
 * @param ttl    事件列表与 live-run 标记的 TTL，默认 1h
 */
@ConfigurationProperties(prefix = "weknora.stream")
public record StreamProperties(String type, String prefix, Duration ttl) {

    private static final String TYPE_REDIS = "redis";

    /** 是否使用 Redis 后端。对照 Go 的 {@code case TypeRedis:}（精确匹配）。 */
    public boolean useRedis() {
        return TYPE_REDIS.equals(type);
    }
}
