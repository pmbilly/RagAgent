package com.ragagent.embed;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * embed 公开面的滑动窗口限流器（对照 Go internal/ratelimit 的
 * <b>local 回退分支</b>：单实例进程内 ZSET 语义）。
 *
 * <p><b>刻意只实现本地回退</b>（与项目对 asynq→进程内队列、Redis 分布式限流器未翻的
 * 取舍一致，= Go 的 Lite 模式）：Go 的 Redis 路径（Lua ZREMRANGEBYSCORE + ZADD）在
 * 多副本部署才有语义差，单实例下两个路径判定结果一致。golden/A-B 的请求量都在
 * 预算内（emb-main 500/min、CID_MIN 30/min），限流命中路径（429）不在录制范围。</p>
 */
@Component
public class EmbedRateLimiter {

    private static final long CLEANUP_EVERY_CALLS = 4096;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private long calls;

    /** 对照 Allow(key, max)：窗口内命中数 &lt; max 才放行并记录本次。 */
    public synchronized boolean allow(String key, long windowMillis, int max) {
        if (max <= 0) {
            return true;
        }
        if (++calls % CLEANUP_EVERY_CALLS == 0) {
            sweepAll(windowMillis);
        }
        long now = System.currentTimeMillis();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
            deque.pollFirst();
        }
        if (deque.size() < max) {
            deque.addLast(now);
            return true;
        }
        return false;
    }

    private void sweepAll(long windowMillis) {
        long now = System.currentTimeMillis();
        hits.values().removeIf(deque -> {
            while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });
    }

    /** 窗口常量（对照 embed_auth.go 的 limiter 构造）。 */
    public static final long MINUTE_MILLIS = 60_000L;
    public static final long DAY_MILLIS = 24L * 60 * 60_000L;

    /** 对照 embedGlobalPerMinute：全局每分钟预算 = per-IP × 20，下限 120。 */
    public static int globalPerMinute(int perIp) {
        int budget = perIp * 20;
        return Math.max(budget, 120);
    }

    /** 仅测试用：清空窗口。 */
    public void reset() {
        hits.clear();
        calls = 0;
    }
}
