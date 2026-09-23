package com.ragagent.sandbox.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 会话→沙箱绑定的 Redis 权威存储（对照 Go internal/sandbox/session_binding_redis.go 全文）。
 *
 * <h2>跨语言互操作契约（不可改动，Go 与 Java 读写同一批键）</h2>
 * <ul>
 *   <li>绑定键：{@code weknora:sandbox:session:{<namespace>:<tenantID>:<sessionID>}:binding}
 *       ——hash tag 使同一会话的绑定/turn/create-lock 落在同一 slot（Cluster 事务兼容），
 *       create-lock 沿用历史后缀以让滚动升级里的多副本在同一把锁上串行。</li>
 *   <li>turn 键：{@code ...:turn}（hash：refs/rebuild）；锁键：{@code ...:create-lock}。</li>
 *   <li>绑定值为 {@link SessionSandboxBinding} 的 JSON：
 *       键名与 Go json tag 逐字一致，tenant_id 是数字（Lua 的 cjson 会把整数变 x.0，
 *       所以 Go 的 token patch 是文本 gsub 而非整对象重编码——脚本原文照抄，Java 侧
 *       写入的 JSON 必须让同样的脚本能工作）。</li>
 *   <li>六段 Lua 脚本逐字移植：delete/mark-stale/token-patch/turn 三件。</li>
 * </ul>
 *
 * <h2>生命周期锁</h2>
 * Go 用 redislock.WithRenewableLock（lease 60s / renew 20s，丢锁即取消 lockCtx）。
 * Java 等价实现：SET NX PX + 周期续租 + 释放时按 token 删。差异（备案）：Go 丢锁时
 * 通过取消 context 让锁内操作中止；Java 无法中断任意代码段，锁内代码继续跑完，
 * 但绝不会再续租（锁过期后其他进程可拿锁）——destructive cleanup 的预算由
 * {@code RemoteSessionLifecycle.cleanupSandboxID} 自身控制。
 */
public class SessionSandboxBindingRedis implements SessionSandboxBindingStore,
        SessionSandboxBindingStore.TenantBindingScanner,
        SessionSandboxBindingStore.SessionTurnLeaseStore {

    static final Duration REDIS_LIFECYCLE_LOCK_LEASE = Duration.ofSeconds(60);
    static final Duration REDIS_LIFECYCLE_LOCK_RENEW_INTERVAL = Duration.ofSeconds(20);

    /** 对照 sessionTurnLeaseTTL：进程崩溃时泄漏 turn 的上限。 */
    static final Duration SESSION_TURN_LEASE_TTL = Duration.ofMinutes(30);

    /** 对照 redisBindingScanCount：绑定是小键，一个工作区通常一批扫完。 */
    static final int REDIS_BINDING_SCAN_COUNT = 200;

    // ---- Lua 脚本（与 Go 逐字一致） --------------------------------------

    private static final String DELETE_BINDING_IF_MATCH_LUA = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local value = cjson.decode(raw)
            local provider = value['provider']
            if provider == ARGV[1] and value['sandbox_id'] == ARGV[2] then
            	return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private static final String MARK_BINDING_STALE_IF_MATCH_LUA = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local value = cjson.decode(raw)
            if value['provider'] ~= ARGV[1] or value['sandbox_id'] ~= ARGV[2] then
            	return 0
            end
            redis.call('SET', KEYS[1], ARGV[3])
            return 1
            """;

    /**
     * 只 patch traffic_access_token，使并发的 stale 标记不被整体覆盖。ARGV[3] 是新
     * token。字段在存储的 JSON 文本上重写而非 cjson.encode 整个对象：Redis 的 cjson
     * 把整数变 x.0，encoding/json 随后拒收 uint64（tenant_id）。
     */
    private static final String REPLACE_TRAFFIC_TOKEN_IF_MATCH_LUA = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local value = cjson.decode(raw)
            if value['provider'] ~= ARGV[1] or value['sandbox_id'] ~= ARGV[2] then
            	return 0
            end
            if value['traffic_access_token'] == ARGV[3] then
            	return 0
            end
            local encoded = string.gsub(cjson.encode(ARGV[3]), '%%', '%%%%')
            local updated, n = string.gsub(raw, '"traffic_access_token"%s*:%s*".-"', '"traffic_access_token":'..encoded, 1)
            if n == 0 then
            	updated, n = string.gsub(raw, '}(%s*)$', ',"traffic_access_token":'..encoded..'}%1', 1)
            	if n == 0 then
            		return 0
            	end
            end
            redis.call('SET', KEYS[1], updated)
            return 1
            """;

    private static final String BEGIN_TURN_LUA = """
            local refs = redis.call('HINCRBY', KEYS[1], 'refs', 1)
            if refs == 1 then
            	redis.call('HSET', KEYS[1], 'rebuild', '1')
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return refs
            """;

    private static final String END_TURN_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local refs = redis.call('HINCRBY', KEYS[1], 'refs', -1)
            if refs <= 0 then
            	redis.call('DEL', KEYS[1])
            	return 0
            end
            return refs
            """;

    private static final String CONSUME_TURN_REBUILD_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], 'rebuild', '0')
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """;

    private static final String RELEASE_LOCK_IF_TOKEN_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0";

    private static final String EXTEND_LOCK_IF_TOKEN_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0";

    private static final RedisScript<Long> DELETE_BINDING_IF_MATCH =
            new DefaultRedisScript<>(DELETE_BINDING_IF_MATCH_LUA, Long.class);
    private static final RedisScript<Long> MARK_BINDING_STALE_IF_MATCH =
            new DefaultRedisScript<>(MARK_BINDING_STALE_IF_MATCH_LUA, Long.class);
    private static final RedisScript<Long> REPLACE_TRAFFIC_TOKEN_IF_MATCH =
            new DefaultRedisScript<>(REPLACE_TRAFFIC_TOKEN_IF_MATCH_LUA, Long.class);
    private static final RedisScript<Long> BEGIN_TURN =
            new DefaultRedisScript<>(BEGIN_TURN_LUA, Long.class);
    private static final RedisScript<Long> END_TURN =
            new DefaultRedisScript<>(END_TURN_LUA, Long.class);
    private static final RedisScript<Long> CONSUME_TURN_REBUILD =
            new DefaultRedisScript<>(CONSUME_TURN_REBUILD_LUA, Long.class);
    private static final RedisScript<Long> RELEASE_LOCK_IF_TOKEN =
            new DefaultRedisScript<>(RELEASE_LOCK_IF_TOKEN_LUA, Long.class);
    private static final RedisScript<Long> EXTEND_LOCK_IF_TOKEN =
            new DefaultRedisScript<>(EXTEND_LOCK_IF_TOKEN_LUA, Long.class);

    /** 绑定 JSON 编解码器：键名由 Jackson 注解钉死；宽容未知键（Go json.Unmarshal 默认忽略）。 */
    static final ObjectMapper BINDING_JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 续租线程：所有实例共享一个 daemon 线程（Spring 不管理本类生命周期）。 */
    private static final ScheduledExecutorService LOCK_RENEWER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                private final AtomicLong seq = new AtomicLong();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "sandbox-binding-lock-renewer-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final StringRedisTemplate template;
    private final String namespace;
    private final Duration lockLease;
    private final Duration lockRenewInterval;

    /** 对照 NewRedisSessionSandboxBindingStore：fail-closed（client/namespace 缺失即拒）。 */
    public SessionSandboxBindingRedis(StringRedisTemplate template, String namespace) {
        if (template == null) {
            throw new IllegalArgumentException("sandbox binding Redis client is required");
        }
        String ns = namespace == null ? "" : namespace.strip();
        validateRedisNamespace(ns);
        this.template = template;
        this.namespace = ns;
        this.lockLease = REDIS_LIFECYCLE_LOCK_LEASE;
        this.lockRenewInterval = REDIS_LIFECYCLE_LOCK_RENEW_INTERVAL;
    }

    // ── SessionSandboxBindingStore ──────────────────────────────────────

    @Override
    public SessionSandboxBinding get(SessionSandboxKey key) {
        key.validate();
        String raw = template.opsForValue().get(bindingKey(key));
        if (raw == null) {
            return null;
        }
        SessionSandboxBinding binding;
        try {
            binding = BINDING_JSON.readValue(raw, SessionSandboxBinding.class);
        } catch (Exception e) {
            throw new IllegalStateException("decode sandbox binding: " + e.getMessage(), e);
        }
        try {
            binding.validate(key);
        } catch (RuntimeException e) {
            throw new IllegalStateException("validate sandbox binding: " + e.getMessage(), e);
        }
        return binding;
    }

    @Override
    public boolean create(SessionSandboxKey key, SessionSandboxBinding binding) {
        binding.validate(key);
        String raw;
        try {
            raw = BINDING_JSON.writeValueAsString(binding);
        } catch (Exception e) {
            throw new IllegalStateException("encode sandbox binding: " + e.getMessage(), e);
        }
        Boolean created = template.opsForValue().setIfAbsent(bindingKey(key), raw);
        if (created == null) {
            throw new IllegalStateException("create sandbox binding: redis returned null");
        }
        return created;
    }

    @Override
    public boolean deleteIfMatch(SessionSandboxKey key, String provider, String sandboxId) {
        SessionSandboxBindingStore.validateBindingMatch(key, provider, sandboxId);
        Long deleted = template.execute(DELETE_BINDING_IF_MATCH,
                List.of(bindingKey(key)), provider, sandboxId);
        if (deleted == null) {
            throw new IllegalStateException("delete sandbox binding: redis returned null");
        }
        return deleted != 0;
    }

    @Override
    public boolean replaceTrafficTokenIfMatch(SessionSandboxKey key,
            SessionSandboxBinding expected, String token) {
        SessionSandboxBindingStore.validateBindingMatch(key, expected.provider, expected.sandboxId);
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long wrote = template.execute(REPLACE_TRAFFIC_TOKEN_IF_MATCH,
                List.of(bindingKey(key)), expected.provider, expected.sandboxId, token);
        if (wrote == null) {
            throw new IllegalStateException("replace sandbox inbound token: redis returned null");
        }
        return wrote != 0;
    }

    @Override
    public <T> T withLifecycleLock(SessionSandboxKey key, Duration lockWaitTimeout,
            LifecycleAction<T> action) {
        key.validate();
        if (action == null) {
            throw new IllegalArgumentException("sandbox lifecycle lock callback is required");
        }
        String lockKey = lockKey(key);
        String token = UUID.randomUUID().toString();
        long deadlineNanos = lockWaitTimeout == null || lockWaitTimeout.isZero()
                || lockWaitTimeout.isNegative()
                ? Long.MAX_VALUE
                : System.nanoTime() + lockWaitTimeout.toNanos();
        long pollMillis = Math.max(50, lockRenewInterval.toMillis() / 4);

        while (true) {
            Boolean acquired = template.opsForValue().setIfAbsent(lockKey, token, lockLease);
            if (Boolean.TRUE.equals(acquired)) {
                break;
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw SandboxException.internal(
                        "sandbox lifecycle lock wait exceeded "
                                + (lockWaitTimeout == null ? "unbounded" : lockWaitTimeout));
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw SandboxException.internal(
                        "interrupted while waiting for sandbox lifecycle lock", e);
            }
        }

        ScheduledFuture<?> renewal = LOCK_RENEWER.scheduleAtFixedRate(() -> {
            try {
                template.execute(EXTEND_LOCK_IF_TOKEN, List.of(lockKey),
                        token, String.valueOf(lockLease.toMillis()));
                // 续租失败（丢锁）时 Go 会取消 lockCtx 中止锁内代码；Java 无法中断
                // 进行中的回调——锁到期后其他进程可获锁，这里不再续租即可。
            } catch (RuntimeException ignored) {
                // 续租抖动留给下一次；lease 还有余量
            }
        }, lockRenewInterval.toMillis(), lockRenewInterval.toMillis(), TimeUnit.MILLISECONDS);

        try {
            return action.run();
        } finally {
            renewal.cancel(false);
            template.execute(RELEASE_LOCK_IF_TOKEN, List.of(lockKey), token);
        }
    }

    @Override
    public int invalidateByConfig(long tenantId, String configId) {
        return SessionSandboxBindingStore.invalidateBindingsByConfig(this, tenantId, configId, 2);
    }

    // ── TenantBindingScanner ─────────────────────────────────────────────

    @Override
    public List<SessionSandboxKey> listTenantBindingKeys(long tenantId) {
        String prefix = "weknora:sandbox:session:{" + namespace + ":" + tenantId + ":";
        String suffix = "}:binding";
        String pattern = escapeRedisGlob(prefix) + "*" + suffix;

        List<SessionSandboxKey> keys = new ArrayList<>();
        template.execute((RedisCallback<Object>) connection -> {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(REDIS_BINDING_SCAN_COUNT)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String raw = new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8);
                    String sessionId = raw;
                    if (sessionId.startsWith(prefix)) {
                        sessionId = sessionId.substring(prefix.length());
                    }
                    if (sessionId.endsWith(suffix)) {
                        sessionId = sessionId.substring(0, sessionId.length() - suffix.length());
                    }
                    SessionSandboxKey key = new SessionSandboxKey(tenantId, sessionId);
                    try {
                        key.validate();
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    keys.add(key);
                }
            }
            return null;
        });
        return keys;
    }

    @Override
    public boolean markBindingStale(SessionSandboxKey key, SessionSandboxBinding expected,
            java.time.Instant staleAt) {
        SessionSandboxBindingStore.validateBindingMatch(key, expected.provider, expected.sandboxId);
        SessionSandboxBinding marked = expected.copy();
        marked.staleAt = staleAt;
        String payload;
        try {
            payload = BINDING_JSON.writeValueAsString(marked);
        } catch (Exception e) {
            throw new IllegalStateException("encode stale sandbox binding: " + e.getMessage(), e);
        }
        Long wrote = template.execute(MARK_BINDING_STALE_IF_MATCH,
                List.of(bindingKey(key)), expected.provider, expected.sandboxId, payload);
        if (wrote == null) {
            throw new IllegalStateException("mark sandbox binding stale: redis returned null");
        }
        return wrote != 0;
    }

    /**
     * 对照 escapeRedisGlob：引用 SCAN MATCH 的通配字符。namespace 是运维提供的，
     * 只筛查了花括号与控制字符，含 "*" 的 namespace 否则会把 pattern 放大到
     * 目标工作区之外。
     */
    static String escapeRedisGlob(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char r = literal.charAt(i);
            if (r == '\\' || r == '*' || r == '?' || r == '[' || r == ']' || r == '^') {
                out.append('\\');
            }
            out.append(r);
        }
        return out.toString();
    }

    // ── SessionTurnLeaseStore ────────────────────────────────────────────

    @Override
    public void beginTurn(SessionSandboxKey key) {
        key.validate();
        long ttlMs = SESSION_TURN_LEASE_TTL.toMillis();
        template.execute(BEGIN_TURN, List.of(turnKey(key)), String.valueOf(ttlMs));
    }

    @Override
    public void endTurn(SessionSandboxKey key) {
        key.validate();
        template.execute(END_TURN, List.of(turnKey(key)));
    }

    @Override
    public SessionSandboxBindingStore.TurnLeaseState turnState(SessionSandboxKey key) {
        key.validate();
        Map<Object, Object> values = template.opsForHash().entries(turnKey(key));
        if (values.isEmpty()) {
            return new SessionSandboxBindingStore.TurnLeaseState(false, false);
        }
        template.expire(turnKey(key), SESSION_TURN_LEASE_TTL);
        int refs = 0;
        Object rawRefs = values.get("refs");
        if (rawRefs != null) {
            try {
                refs = Integer.parseInt(rawRefs.toString());
            } catch (NumberFormatException ignored) {
                // Go 的 Atoi 失败按 0 处理
            }
        }
        if (refs <= 0) {
            return new SessionSandboxBindingStore.TurnLeaseState(false, false);
        }
        Object rebuild = values.get("rebuild");
        return new SessionSandboxBindingStore.TurnLeaseState(true, "1".equals(rebuild));
    }

    @Override
    public void consumeTurnRebuild(SessionSandboxKey key) {
        key.validate();
        template.execute(CONSUME_TURN_REBUILD, List.of(turnKey(key)),
                String.valueOf(SESSION_TURN_LEASE_TTL.toMillis()));
    }

    // ── 键构造（跨语言契约） ─────────────────────────────────────────────

    String turnKey(SessionSandboxKey key) {
        return "weknora:sandbox:session:{" + hashTag(key) + "}:turn";
    }

    String bindingKey(SessionSandboxKey key) {
        return "weknora:sandbox:session:{" + hashTag(key) + "}:binding";
    }

    String lockKey(SessionSandboxKey key) {
        // 保留多节点 Cube 时代的历史后缀，让滚动升级在同一把锁上串行
        return "weknora:sandbox:session:{" + hashTag(key) + "}:create-lock";
    }

    String hashTag(SessionSandboxKey key) {
        return namespace + ":" + key.tenantId() + ":" + key.sessionId();
    }

    /** 对照 validateRedisNamespace。 */
    static void validateRedisNamespace(String namespace) {
        if (namespace.indexOf('{') >= 0 || namespace.indexOf('}') >= 0) {
            throw new IllegalArgumentException("WEKNORA_REDIS_NAMESPACE must not contain braces");
        }
        for (int i = 0; i < namespace.length(); i++) {
            if (Character.isISOControl(namespace.charAt(i))) {
                throw new IllegalArgumentException(
                        "WEKNORA_REDIS_NAMESPACE must not contain control characters");
            }
        }
    }
}
