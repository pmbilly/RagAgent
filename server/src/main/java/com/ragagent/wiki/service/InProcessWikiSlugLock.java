package com.ragagent.wiki.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link WikiSlugLock} 的<b>进程内</b>实现（默认装配）。
 *
 * <p>用 {@code ConcurrentHashMap<String, ReentrantLock>} 做
 * 按 slug 互斥。任务书明确允许在 Java 侧没有 Redis 时退化为此实现。</p>
 *
 * <p><b>⚠️ 多实例下的差异</b>：互斥范围只有单个 JVM，
 * {@link RedisWikiSlugLock} 才是跨实例的。
 * 单实例部署下两者等价（一个进程一次只跑一个批次，同 slug 不可能竞争）。
 * 若部署多副本，必须把本 bean 换成 {@link RedisWikiSlugLock}。</p>
 *
 * <p><b>与 Redis 版的另一个差异</b>：Redis 版的锁带 TTL（5 分钟），持锁进程崩溃后锁会
 * 自动过期。{@code ReentrantLock} 没有 TTL 概念——但进程内崩溃即整个 JVM 消失，
 * 锁表也随之消失，不存在"残留锁"问题。真正的风险是<b>持锁线程死等</b>导致后续
 * 等待者超时（{@link WikiSlugLock#WAIT_SECONDS} 会兜住等待超时）。</p>
 *
 * <p>锁对象<b>永不回收</b>——这是为了避免"锁被回收后两个线程各持一把不同实例"
 * 的经典竞态；KB 内 slug 数量有界（页面数），内存占用可接受。</p>
 */
@Component
public class InProcessWikiSlugLock implements WikiSlugLock {

    private static final Logger log = LoggerFactory.getLogger(InProcessWikiSlugLock.class);

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String kbId, String slug, Duration wait) {
        String key = WikiSlugLock.lockKey(kbId, slug);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            boolean ok = lock.tryLock(Math.max(wait.toMillis(), 0), TimeUnit.MILLISECONDS);
            if (!ok) {
                log.warn("wiki slug lock: timed out waiting for {} after {}s", key, wait.toSeconds());
            }
            return ok;
        } catch (InterruptedException e) {
            // 中断即放弃：恢复中断位让上层感知
            Thread.currentThread().interrupt();
            log.warn("wiki slug lock: interrupted while waiting for {}", key);
            return false;
        }
    }

    @Override
    public void unlock(String kbId, String slug) {
        String key = WikiSlugLock.lockKey(kbId, slug);
        ReentrantLock lock = locks.get(key);
        if (lock == null) {
            return;
        }
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
