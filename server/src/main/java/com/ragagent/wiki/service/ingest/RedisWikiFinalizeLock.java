package com.ragagent.wiki.service.ingest;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.ragagent.wiki.service.page.RedisWikiSlugLock;
import com.ragagent.wiki.service.page.WikiSlugLock;

/**
 * {@link WikiFinalizeLock} 的 <b>Redis</b> 实现。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>SetNX 取锁 —— TTL
 *       {@link WikiIngestConstants#FINALIZE_LOCK_TTL}（60 秒）；</li>
 *   <li><b>失败即 fail CLOSED</b>：SetNX 报错返回 {@link AcquireResult#FAILED}，
 *       让调用方让任务失败并重试，而不是在无锁状态下与并发 finalize 抢同一条索引页
 *       （读→改→写的丢失更新）；</li>
 *   <li>持有期间每 {@link WikiIngestConstants#FINALIZE_LOCK_RENEW}（20 秒）续期一次
 *       （{@link ScheduledExecutorService} 调度，见 {@link WikiSlugLock} 的同类取舍）；</li>
 *   <li>释放时先停掉续期任务再 DEL 键。</li>
 * </ul>
 *
 * <p><b>装配</b>：与 {@link RedisWikiSlugLock} 同一模式——本类是普通类
 * （<b>不是</b> {@code @Component}），由主装配会话显式注册 bean：</p>
 * <pre>{@code
 * @Bean
 * @Primary   // ⚠️ 必须：InProcessWikiFinalizeLock 是无条件 @Component
 * public WikiFinalizeLock redisWikiFinalizeLock(StringRedisTemplate template) {
 *     return new RedisWikiFinalizeLock(template);
 * }
 * }</pre>
 */
public class RedisWikiFinalizeLock implements WikiFinalizeLock {

    private static final Logger log = LoggerFactory.getLogger(RedisWikiFinalizeLock.class);

    private final StringRedisTemplate template;
    private final Duration ttl;
    private final Duration renew;

    /**
     * 续期协程池。守护线程，单个 finalize 的续期任务极轻（每 20 秒一次 EXPIRE），
     * 因此进程内一个共享调度器足够。
     */
    private final ScheduledExecutorService renewer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wiki-finalize-lock-renew");
                t.setDaemon(true);
                return t;
            });

    /** kbID → 当前持有者的续期任务（用于 release 时取消） */
    private final java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<?>> renewals =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RedisWikiFinalizeLock(StringRedisTemplate template) {
        this(template, WikiIngestConstants.FINALIZE_LOCK_TTL,
                WikiIngestConstants.FINALIZE_LOCK_RENEW);
    }

    public RedisWikiFinalizeLock(StringRedisTemplate template, Duration ttl, Duration renew) {
        this.template = template;
        this.ttl = ttl;
        this.renew = renew;
    }

    @Override
    public AcquireResult tryAcquire(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return AcquireResult.BUSY;
        }
        String key = WikiFinalizeLock.lockKey(kbId);
        Boolean acquired;
        try {
            acquired = template.opsForValue().setIfAbsent(key, "1", ttl);
        } catch (RuntimeException e) {
            // fail CLOSED：无锁放行会让两个 finalize 批次排空同一批待办、重复重建索引页
            log.warn("wiki finalize: SetNX failed for KB {}: {} (retrying)", kbId, e.toString());
            return AcquireResult.FAILED;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            // 另一个 finalize 正在跑；它会排空通道并在还有行时重排。安全地 no-op。
            return AcquireResult.BUSY;
        }
        // 续期：每 renew 一次 EXPIRE，上限 ttl
        ScheduledFuture<?> task = renewer.scheduleAtFixedRate(() -> {
            try {
                template.expire(key, ttl);
            } catch (RuntimeException e) {
                log.warn("wiki finalize: lock renew failed for KB {}: {}", kbId, e.toString());
            }
        }, renew.toMillis(), renew.toMillis(), TimeUnit.MILLISECONDS);
        renewals.put(kbId, task);
        return AcquireResult.ACQUIRED;
    }

    @Override
    public void release(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return;
        }
        // 先停续期，再删键。
        // 用 token 无关的 DEL：finalize 锁是"至多一个"语义，
        // 不像 slug 锁那样需要防误删他人锁——持有者之间不会重入。
        ScheduledFuture<?> task = renewals.remove(kbId);
        if (task != null) {
            task.cancel(false);
        }
        try {
            template.delete(WikiFinalizeLock.lockKey(kbId));
        } catch (RuntimeException e) {
            log.warn("wiki finalize: lock release failed for KB {}: {}", kbId, e.toString());
        }
    }

    /** 供测试：生成一个唯一的持有者标识（预留扩展点） */
    static String newToken() {
        return UUID.randomUUID().toString();
    }
}
