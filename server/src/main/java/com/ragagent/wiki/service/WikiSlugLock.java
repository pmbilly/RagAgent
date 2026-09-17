package com.ragagent.wiki.service;

import java.time.Duration;

/**
 * 单个共享 wiki 页面的读-改-写互斥（对照 Go
 * internal/application/service/wiki_ingest.go 的 {@code withSlugLock}，L911-938）。
 *
 * <p><b>Go 侧语义</b>：同一 KB 的两个并发批次可能同时产出对同一个共享
 * entity/concept/summary slug 的更新；没有这把锁，它们的
 * {@code GetPageBySlug → UpdatePage} 会竞争并丢掉一份贡献。锁键
 * {@code wiki:slug:{kbID}:{slug}}，TTL {@value #TTL_SECONDS} 秒；
 * 拿不到锁时以 {@value #POLL_MILLIS}ms 轮询、最多等 {@value #WAIT_SECONDS} 秒。
 * <b>Redis 报错时 fail-open</b>（不加锁直接执行）：罕见的一次丢失更新会被
 * finalize/死链清理兜住，而静默丢弃更新严格来说更糟。</p>
 *
 * <p><b>Java 侧的可插拔设计</b>：本接口把 {@code withSlugLock(ctx, kbID, slug, fn)}
 * 拆成 {@link #tryLock} / {@link #unlock}，实现有两份：</p>
 * <ul>
 *   <li>{@link InProcessWikiSlugLock} —— {@code ConcurrentHashMap<String, ReentrantLock>}
 *       的进程内实现（<b>默认</b>）。</li>
 *   <li>{@link RedisWikiSlugLock} —— 对照 Go 的 {@code SetNX} 实现（跨实例）。</li>
 * </ul>
 *
 * <p><b>⚠️ 多实例差异</b>：进程内实现的互斥范围只有<b>单个 JVM</b>。Go 在 Redis
 * 模式下是跨实例互斥的；Java 若只装配 {@code InProcessWikiSlugLock} 而部署了多副本，
 * 同一 slug 的并发 reduce 会退化回 Go 的「Lite 模式」语义——单实例下二者等价
 * （Go Lite 模式一个进程一次只跑一个批次，同一 slug 不可能竞争），多实例下
 * 需要换成 {@code RedisWikiSlugLock} 才能保持 Go 的 Redis 模式保证。</p>
 *
 * <p>便利入口 {@link #withSlugLock} 提供与 Go 相同的整体语义（含 fail-open、
 * 超时与 finally 释放），调用方优先用它。</p>
 */
public interface WikiSlugLock {

    /** 对照 Go {@code wikiSlugLockTTL = 5 * time.Minute}（wiki_ingest.go L95） */
    long TTL_SECONDS = 5 * 60;

    /** 对照 Go {@code wikiSlugLockWait = 2 * time.Minute}（wiki_ingest.go L99） */
    long WAIT_SECONDS = 2 * 60;

    /** 对照 Go {@code wikiSlugLockPoll = 50 * time.Millisecond}（wiki_ingest.go L100） */
    long POLL_MILLIS = 50;

    /** 对照 Go {@code wikiSlugLockPrefix = "wiki:slug:"}（wiki_ingest.go L61） */
    String KEY_PREFIX = "wiki:slug:";

    /** 锁键拼装：{@code wiki:slug:{kbID}:{slug}} */
    static String lockKey(String kbId, String slug) {
        return KEY_PREFIX + kbId + ":" + slug;
    }

    /**
     * 尝试获取锁，最多等待 {@link #WAIT_SECONDS}。成功返回 true。
     *
     * <p><b>注意</b>：Go 在 Redis 报错时是 fail-open 的——本方法返回 true 表示
     * 「可以继续执行」，因此实现里遇到后端故障应当返回 <b>true</b>（并在日志里
     * 写明 running unlocked），而不是抛异常让调用方失败。</p>
     */
    boolean tryLock(String kbId, String slug, Duration wait);

    /** 释放锁。未被持有是安全的 no-op（对照 Go 的 {@code defer Del} 语义）。 */
    void unlock(String kbId, String slug);

    /**
     * 对照 Go {@code withSlugLock} 的整体语义：拿锁 → 执行 → 释放。
     *
     * @return true = 成功在锁内执行完 {@code fn}；false = 等待超时，<b>fn 未执行</b>
     *         （Go 返回 {@code (false, nil)}，调用方把该 slug 当作一次尽力而为的
     *         reduce miss）
     */
    default boolean withSlugLock(String kbId, String slug, Runnable fn) {
        boolean acquired = tryLock(kbId, slug, Duration.ofSeconds(WAIT_SECONDS));
        if (!acquired) {
            return false;
        }
        try {
            fn.run();
            return true;
        } finally {
            unlock(kbId, slug);
        }
    }
}
