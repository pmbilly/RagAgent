package com.ragagent.embed.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ragagent.embed.EmbedTokens;

/**
 * Redis 版 token store（对照 Go 的 go-redis 调用）。键空间与 Go 逐字一致：
 * {@code embed:session:<token>} → channelID，TTL 30 分钟——Go/Java 双端可互读。
 */
@Component
public class RedisEmbedTokenStore implements EmbedTokenStore {

    private final StringRedisTemplate template;

    public RedisEmbedTokenStore(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public void put(String token, String channelId, Duration ttl) {
        template.opsForValue().set(EmbedTokens.SESSION_REDIS_PREFIX + token, channelId, ttl);
    }

    @Override
    public String get(String token) {
        return template.opsForValue().get(EmbedTokens.SESSION_REDIS_PREFIX + token);
    }
}
