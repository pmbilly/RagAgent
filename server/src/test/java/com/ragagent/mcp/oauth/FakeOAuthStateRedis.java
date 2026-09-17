package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 内存版 {@link OAuthStateRedis}（对照 Go 测试里 Lite 分支以外的"Redis 存在"路径）。
 *
 * <p>刻意实现成"带 TTL 的 map"而不是 no-op，才能验证 Redis 分支真正的差异点：
 * state / attempt 成对写入、{@code getAndDelete} 的<b>原子单次消费</b>、
 * 以及 attempt 在 state 被取走后仍可查。</p>
 */
final class FakeOAuthStateRedis implements OAuthStateRedis {

    private final Map<String, String> values = new HashMap<>();

    /** 记录收到过的 TTL（断言流程传的就是 10 分钟）。 */
    Duration lastTtl;

    @Override
    public synchronized void set(String key, String value, Duration ttl) {
        lastTtl = ttl;
        values.put(key, value);
    }

    @Override
    public synchronized void setAll(Map<String, String> entries, Duration ttl) {
        lastTtl = ttl;
        values.putAll(entries);
    }

    @Override
    public synchronized String get(String key) {
        return values.get(key);
    }

    @Override
    public synchronized String getAndDelete(String key) {
        return values.remove(key);
    }

    synchronized boolean contains(String key) {
        return values.containsKey(key);
    }
}
