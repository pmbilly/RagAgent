package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 进行中的 OAuth state 存储（对照 Go internal/mcp/oauth_state.go:41-80 的
 * {@code oauthStateStore}）。
 *
 * <p><b>双实现</b>：注入 {@link OAuthStateRedis} 时走 Redis（回调可以落到<b>任意</b>后端副本）；
 * 未注入时退化为带 TTL 的内存 map（单实例 / Lite 部署），并由一个 GC 线程定期清理。</p>
 *
 * <p><b>三态语义（保真要点）</b>：
 * <ol>
 *   <li>{@link #take} 是<b>单次使用</b>的：取走即删（Redis 用 GETDEL，内存用 remove 后再判过期）；</li>
 *   <li>{@link #completeAttempt} <b>只在 code 交换成功落库 token 之后</b>才把 attempt 置为完成——
 *       防止"同一服务上早已存在的旧 token"满足一个新开的授权弹窗；</li>
 *   <li>{@link #put} 同时写 state 与 attempt（Redis 用一次批量写），故取走 state 之后
 *       attempt 依然可查。</li>
 * </ol>
 */
public class OAuthStateStore {

    /** 对照 Go {@code oauthStateTTL}：从"发出 authorize-url"到"收到回调"的时限。 */
    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    /** 对照 Go {@code key()} 读取的环境变量：多套部署共享同一 Redis 时用命名空间隔开。 */
    static final String REDIS_NAMESPACE_ENV = "WEKNORA_REDIS_NAMESPACE";

    private static final String KEY_PREFIX = "weknora:mcp_oauth_state:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final OAuthStateRedis redis;
    private final Map<String, MemEntry<OAuthState>> mem = new ConcurrentHashMap<>();
    private final Map<String, MemEntry<OAuthAttempt>> attempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService gc;

    /** @param redis 可为 null（Lite 模式：状态留在本进程内存） */
    public OAuthStateStore(OAuthStateRedis redis) {
        this.redis = redis;
        if (redis == null) {
            // 对照 Go：只有内存实现才需要 GC 循环（Redis 靠 TTL 自己过期）
            this.gc = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mcp-oauth-state-gc");
                t.setDaemon(true);
                return t;
            });
            gc.scheduleWithFixedDelay(this::gcOnce, 1, 60, TimeUnit.SECONDS);
        } else {
            this.gc = null;
        }
    }

    private record MemEntry<T>(T value, Instant expiresAt) {
    }

    /** 对照 Go {@code key()}：`weknora:mcp_oauth_state:[<ns>:]<state>`。 */
    String key(String state) {
        String ns = trimToEmpty(System.getenv(REDIS_NAMESPACE_ENV));
        if (!ns.isEmpty()) {
            return KEY_PREFIX + ns + ":" + state;
        }
        return KEY_PREFIX + state;
    }

    /** 对照 Go {@code attemptKey()}：state 键 + {@code ":attempt"}。 */
    String attemptKey(String state) {
        return key(state) + ":attempt";
    }

    /** 对照 Go {@code Put}：写入 state 与 attempt（10 分钟 TTL）。 */
    public void put(String state, OAuthState value) {
        OAuthAttempt attempt = new OAuthAttempt(
                value.tenantId(), OAuthState.Principal.of(value.principalOrNull()),
                value.serviceId(), false);
        if (redis != null) {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put(key(state), writeJson(value));
            entries.put(attemptKey(state), writeJson(attempt));
            redis.setAll(entries, STATE_TTL);
            return;
        }
        Instant expiresAt = Instant.now().plus(STATE_TTL);
        mem.put(state, new MemEntry<>(value, expiresAt));
        attempts.put(state, new MemEntry<>(attempt, expiresAt));
    }

    /**
     * 对照 Go {@code CompleteAttempt}：<b>仅</b>在 code 交换已成功落库 token 后调用。
     *
     * <p>内存分支会把过期时间再顺延一个 TTL（Go 一致），让发起方在回调完成后仍有
     * 足够窗口轮询到结果。</p>
     */
    public void completeAttempt(String state) {
        if (redis != null) {
            String data = redis.get(attemptKey(state));
            if (data == null) {
                throw OAuthStateNotFoundException.attempt();
            }
            OAuthAttempt attempt = readAttempt(data);
            redis.set(attemptKey(state), writeJson(attempt.completedCopy()), STATE_TTL);
            return;
        }
        MemEntry<OAuthAttempt> entry = attempts.get(state);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            attempts.remove(state);
            throw OAuthStateNotFoundException.attempt();
        }
        attempts.put(state, new MemEntry<>(entry.value().completedCopy(),
                Instant.now().plus(STATE_TTL)));
    }

    /** 对照 Go {@code Attempt}：读一次授权流程的状态记录。 */
    public OAuthAttempt attempt(String state) {
        if (redis != null) {
            String data = redis.get(attemptKey(state));
            if (data == null) {
                throw OAuthStateNotFoundException.attempt();
            }
            return readAttempt(data);
        }
        MemEntry<OAuthAttempt> entry = attempts.get(state);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            attempts.remove(state);
            throw OAuthStateNotFoundException.attempt();
        }
        return entry.value();
    }

    /**
     * 对照 Go {@code Take}：取出并删除 state（<b>单次使用</b>）。
     *
     * <p>内存分支刻意"先删再判过期"：即便已过期也要把条目删掉，避免过期条目反复被扫到。
     * Redis 分支的 GETDEL 天然原子，两个并发回调只有一个能拿到值。</p>
     */
    public OAuthState take(String state) {
        if (redis != null) {
            String data = redis.getAndDelete(key(state));
            if (data == null) {
                throw OAuthStateNotFoundException.state();
            }
            return readState(data);
        }
        MemEntry<OAuthState> entry = mem.remove(state);
        if (entry == null) {
            throw OAuthStateNotFoundException.state();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            throw OAuthStateNotFoundException.state();
        }
        return entry.value();
    }

    /** 对照 Go {@code gcLoop}：每分钟清一次过期条目。 */
    void gcOnce() {
        Instant now = Instant.now();
        mem.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        attempts.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }

    /** 测试可见：内存分支当前条目数（Redis 分支恒为 0）。 */
    int memorySize() {
        return mem.size();
    }

    private static OAuthState readState(String data) {
        try {
            return MAPPER.readValue(data, OAuthState.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode oauth state: " + e.getMessage(), e);
        }
    }

    private static OAuthAttempt readAttempt(String data) {
        try {
            return MAPPER.readValue(data, OAuthAttempt.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode oauth attempt: " + e.getMessage(), e);
        }
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode oauth state: " + e.getMessage(), e);
        }
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
