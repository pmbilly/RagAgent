package com.ragagent.wiki.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * {@link WikiSlugLock} 的 Redis 实现（对照 Go {@code withSlugLock}，
 * internal/application/service/wiki_ingest.go L911-938）。
 *
 * <p>键 {@code wiki:slug:{kbID}:{slug}}，TTL 5 分钟，轮询 50ms、最多等 2 分钟。
 * 与 Go 一致地 <b>fail-open</b>：Redis 报错时记 warn 日志并返回 true
 * （"running unlocked"），因为罕见的一次丢失更新会被 finalize/死链清理兜住，
 * 而静默丢弃整份更新严格更糟。</p>
 *
 * <p><b>释放为什么要用 Lua</b>：Go 的 {@code defer Del(key)} 其实也有「删除别人
 * 误持的锁」的窗口（锁 TTL 到期后由另一个持有者重建，此时原持有者 Del 会删掉
 * 新持有者的锁）。Java 侧按同样的外部契约翻译，但把释放收窄为
 * 「只删自己写进去的 token」，避免在 <b>TTL 到期后</b>出现跨持有者误删——
 * 这是比 Go 更严格的行为，不放宽任何 Go 的保证。</p>
 *
 * <p><b>装配</b>：本类是普通类（<b>不是</b> {@code @Component}），与 MCP 的
 * {@code SpringOAuthStateRedis} 同一模式——由主装配会话显式注册 bean：</p>
 * <pre>{@code
 * @Bean
 * @Primary   // ⚠️ 必须：InProcessWikiSlugLock 是无条件 @Component，
 *            //    两者共存时裸注入 WikiSlugLock 会 NoUniqueBeanDefinitionException
 * public WikiSlugLock redisWikiSlugLock(StringRedisTemplate template) {
 *     return new RedisWikiSlugLock(template);
 * }
 * }</pre>
 *
 * <p>不注册时 {@link InProcessWikiSlugLock} 就是唯一实现，直接生效。</p>
 */
public class RedisWikiSlugLock implements WikiSlugLock {

    private static final Logger log = LoggerFactory.getLogger(RedisWikiSlugLock.class);

    /**
     * 仅在值仍是自己写入的 token 时才删除（GET 比较 + DEL，单脚本原子）。
     * KEYS[1]=锁键；ARGV[1]=持有者 token。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate template;
    private final Duration ttl;

    public RedisWikiSlugLock(StringRedisTemplate template) {
        this(template, Duration.ofSeconds(WikiSlugLock.TTL_SECONDS));
    }

    public RedisWikiSlugLock(StringRedisTemplate template, Duration ttl) {
        this.template = template;
        this.ttl = ttl;
    }

    @Override
    public boolean tryLock(String kbId, String slug, Duration wait) {
        String key = WikiSlugLock.lockKey(kbId, slug);
        // token 唯一标识"本次持有"，供解锁脚本比对
        String token = java.util.UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + Math.max(wait.toMillis(), 0);
        while (true) {
            Boolean ok;
            try {
                ok = template.opsForValue().setIfAbsent(key, token, ttl);
            } catch (RuntimeException e) {
                // 对照 Go：SetNX 报错 → fail-open，不加锁直接跑
                log.warn("wiki slug lock: SetNX failed for {}: {} (running unlocked)", slug, e.toString());
                return true;
            }
            if (Boolean.TRUE.equals(ok)) {
                tokens.set(token);
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("wiki slug lock: timed out waiting for {} after {}s", key, wait.toSeconds());
                return false;
            }
            try {
                Thread.sleep(WikiSlugLock.POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("wiki slug lock: interrupted while waiting for {}", key);
                return false;
            }
        }
    }

    @Override
    public void unlock(String kbId, String slug) {
        String token = tokens.get();
        tokens.remove();
        if (token == null) {
            return;
        }
        try {
            List<String> keys = Collections.singletonList(WikiSlugLock.lockKey(kbId, slug));
            template.execute(UNLOCK_SCRIPT, keys, token);
        } catch (RuntimeException e) {
            // 释放失败只会让锁自然过期（最多 TTL），不需要让调用方感知
            log.warn("wiki slug lock: release failed for {}: {}", slug, e.toString());
        }
    }

    /** 本次线程写入锁的 token（供解锁脚本比对，避免误删他人锁） */
    private final ThreadLocal<String> tokens = new ThreadLocal<>();
}
