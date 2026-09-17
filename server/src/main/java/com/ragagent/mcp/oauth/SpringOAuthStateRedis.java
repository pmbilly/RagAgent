package com.ragagent.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

/**
 * {@link OAuthStateRedis} 的 Spring Data Redis（Lettuce）实现。
 *
 * <p>接线方式（主会话注册 bean 时一行即可）：
 * <pre>{@code
 * @Bean
 * public OAuthStateRedis oauthStateRedis(StringRedisTemplate template) {
 *     return new SpringOAuthStateRedis(template);
 * }
 * }</pre></p>
 *
 * <p>对照 go-redis 的 {@code TxPipeline}：这里用一次 pipeline 回调提交全部 SET，
 * 消除"state 写进去了、attempt 还没写"的可见中间态。</p>
 */
public class SpringOAuthStateRedis implements OAuthStateRedis {

    private final StringRedisTemplate template;

    public SpringOAuthStateRedis(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        template.opsForValue().set(key, value, ttl);
    }

    @Override
    public void setAll(Map<String, String> entries, Duration ttl) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        template.executePipelined((RedisCallback<Object>) connection -> {
            long seconds = Math.max(1L, ttl.toSeconds());
            for (Map.Entry<String, String> e : entries.entrySet()) {
                connection.stringCommands().set(
                        bytes(e.getKey()), bytes(e.getValue()),
                        Expiration.from(java.time.Duration.ofSeconds(seconds)),
                        RedisStringCommands.SetOption.UPSERT);
            }
            return null;
        });
    }

    @Override
    public String get(String key) {
        return template.opsForValue().get(key);
    }

    @Override
    public String getAndDelete(String key) {
        return template.opsForValue().getAndDelete(key);
    }

    private static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
