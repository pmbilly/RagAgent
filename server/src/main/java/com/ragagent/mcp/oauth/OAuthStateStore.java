package com.ragagent.mcp.oauth;

import java.time.Duration;
import com.ragagent.common.deployment.AppEnvLookup;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 进行中的 OAuth state 存储。
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

    /** 从"发出 authorize-url"到"收到回调"的时限。 */
    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    /** 环境变量：多套部署共享同一 Redis 时用命名空间隔开。 */
    static final String REDIS_NAMESPACE_ENV = "WEKNORA_REDIS_NAMESPACE";

    private static final String KEY_PREFIX = "weknora:mcp_oauth_state:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 旧 blob 的键名 → 新键名（仅用于 {@link #migrateLegacyKeys}，窗口过后连同该方法删除）。 */
    private static final Map<String, String> LEGACY_KEY_RENAMES = Map.of(
            "tenant_id", "tenantId",
            "user_id", "userId",
            "service_id", "serviceId",
            "code_verifier", "codeVerifier",
            "client_id", "clientId",
            "redirect_uri", "redirectUri",
            "frontend_redirect", "frontendRedirect");

    private final OAuthStateRedis redis;
    private final Map<String, MemEntry<OAuthState>> mem = new ConcurrentHashMap<>();
    private final Map<String, MemEntry<OAuthAttempt>> attempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService gc;

    /** @param redis 可为 null（Lite 模式：状态留在本进程内存） */
    public OAuthStateStore(OAuthStateRedis redis) {
        this.redis = redis;
        if (redis == null) {
            // 只有内存实现才需要 GC 循环（Redis 靠 TTL 自己过期）
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

    /** 键形：`weknora:mcp_oauth_state:[<ns>:]<state>`。 */
    String key(String state) {
        String ns = trimToEmpty(AppEnvLookup.get(REDIS_NAMESPACE_ENV));
        if (!ns.isEmpty()) {
            return KEY_PREFIX + ns + ":" + state;
        }
        return KEY_PREFIX + state;
    }

    /** attempt 键：state 键 + {@code ":attempt"}。 */
    String attemptKey(String state) {
        return key(state) + ":attempt";
    }

    /** 写入 state 与 attempt（10 分钟 TTL）。 */
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
     * <b>仅</b>在 code 交换已成功落库 token 后调用。
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

    /** 读一次授权流程的状态记录。 */
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
     * 取出并删除 state（<b>单次使用</b>）。
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

    /** 每分钟清一次过期条目。 */
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
            return MAPPER.treeToValue(migrateLegacyKeys(MAPPER.readTree(data)), OAuthState.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode oauth state: " + e.getMessage(), e);
        }
    }

    private static OAuthAttempt readAttempt(String data) {
        try {
            return MAPPER.treeToValue(migrateLegacyKeys(MAPPER.readTree(data)), OAuthAttempt.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode oauth attempt: " + e.getMessage(), e);
        }
    }

    /**
     * 旧下划线键名 blob 的键名映射：**部署窗口内的兼容读**。
     *
     * <p>{@link OAuthState}/{@link OAuthAttempt} 的键名已改为组件名，这两个记录
     * 只活在同一份 Redis/内存 JSON 里（{@link #STATE_TTL} = 10 分钟）。若不兼容读，滚动发布
     * 期间"已完成 authorize-url、还没点回调"的用户会拿到一份 tenantId=0/serviceId="" 的
     * 空壳（`ignoreUnknown` 会静默吞掉旧键），回调只能报 authorization_failed。
     * 这里按已知旧键改名后再反序列化，窗口过后可整体删除（删除条件：一次 STATE_TTL 的
     * 部署窗口）。</p>
     */
    private static JsonNode migrateLegacyKeys(JsonNode node) {
        if (!node.isObject()) {
            return node;
        }
        ObjectNode obj = (ObjectNode) node;
        boolean legacy = LEGACY_KEY_RENAMES.keySet().stream().anyMatch(obj::has);
        if (!legacy) {
            return node;
        }
        ObjectNode migrated = MAPPER.createObjectNode();
        obj.fields().forEachRemaining(f -> migrated.set(
                LEGACY_KEY_RENAMES.getOrDefault(f.getKey(), f.getKey()), f.getValue()));
        return migrated;
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
