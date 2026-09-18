package com.ragagent.stream;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 基于 Redis List 的 append-only 流管理器（对照 Go {@code RedisStreamManager}，
 * internal/stream/redis_manager.go）。
 *
 * <p>事件用 RPush 追加、LRange 增量读——O(1) 追加 + 按 offset 批量拉取，
 * 正好是 SSE 轮询消费的形状。</p>
 *
 * <h2>键布局</h2>
 * <pre>
 *   {prefix}:{sessionId}:{messageId}          事件列表（RPush / LRange）
 *   {prefix}:{sessionId}:{messageId}:steer    steer 控制面子列表，永不上用户可见流
 *   {prefix}:{sessionId}:live-run             该会话当前生成中的一轮
 * </pre>
 * 注意 prefix 来自 {@code REDIS_PREFIX} 且**不做去尾冒号处理**——dev 的
 * {@code REDIS_PREFIX=stream:} 会让键长成 {@code stream::sess:msg}（双冒号）。
 * 这是 Go 的原样行为，照抄。
 *
 * <h2>三个 Lua 脚本各自解决什么</h2>
 * <ul>
 *   <li>{@code steerAppendUnique}：去重与追加必须原子。一个超时的 POST 会被客户端重试，
 *       而另一个副本可能正在受理同一个客户端 ID。</li>
 *   <li>{@code steerUpdateCAS}：只在该槽位仍持有我们读到的值时改写。
 *       整表重建（DEL + RPUSH）会静默丢掉别的副本在此期间追加的 steer 消息，
 *       所以每次修改要么是对单个下标的 CAS，要么是对精确值的 LREM。</li>
 *   <li>{@code clearLiveRunCAS}：只在该标记仍指向正在拆掉的那一轮时才删，
 *       否则会把后续轮次已经抢到的会话标记误删。</li>
 * </ul>
 *
 * <p>连接由 Spring 管理（{@code StringRedisTemplate}），故没有 Go 的 {@code Close()}。</p>
 */
public class RedisStreamManager implements StreamManager {

    /** {@code UpdateSteerEventData} CAS 重试上限。 */
    static final int STEER_UPDATE_MAX_ATTEMPTS = 3;

    private static final String DEFAULT_PREFIX = "stream:events";

    private final StringRedisTemplate template;
    private final Duration ttl;
    private final String prefix;

    public RedisStreamManager(StringRedisTemplate template, String prefix, Duration ttl) {
        this.template = template;
        this.prefix = (prefix == null || prefix.isEmpty()) ? DEFAULT_PREFIX : prefix;
        this.ttl = (ttl == null || ttl.isZero()) ? Duration.ofHours(24) : ttl;
    }

    // ── 键 ──────────────────────────────────────────────────────────────────

    String buildKey(String sessionId, String messageId) {
        return prefix + ":" + sessionId + ":" + messageId;
    }

    /**
     * steer 子列表的键。单独一个键是为了让控制事件不落进用户可见的 SSE 流，
     * 同时复用同一套 TTL 生命周期。
     */
    String buildSteerKey(String sessionId, String messageId) {
        return prefix + ":" + sessionId + ":" + messageId + ":steer";
    }

    String buildLiveRunKey(String sessionId) {
        return prefix + ":" + sessionId + ":live-run";
    }

    // ── 事件流 ──────────────────────────────────────────────────────────────

    @Override
    public void appendEvent(String sessionId, String messageId, StreamEvent event) {
        String key = buildKey(sessionId, messageId);

        // Cf. Go 的按值传参：调用方的对象不被补上时间戳。
        StreamEvent stored = event.copy();
        if (stored.getTimestamp() == null) {
            stored.setTimestamp(OffsetDateTime.now());
        }
        String eventJson = StreamJson.write(stored);

        try {
            template.opsForList().rightPush(key, eventJson);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to append event to Redis: " + e.getMessage(), e);
        }
        try {
            template.expire(key, ttl);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to set TTL: " + e.getMessage(), e);
        }
        touchLiveRun(sessionId);
    }

    @Override
    public StreamBatch getEvents(String sessionId, String messageId, int fromOffset) {
        String key = buildKey(sessionId, messageId);

        List<String> results;
        try {
            // LRange 是闭区间，fromOffset → -1 即"剩下的全部"
            results = template.opsForList().range(key, fromOffset, -1);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to get events from Redis: " + e.getMessage(), e);
        }
        if (results == null) {
            results = List.of();
        }

        // 没有新事件时**仍要**刷新 live-run：模型思考期间 SSE 轮询循环正是走这条路径，
        // 而长轮次恰恰可能活过 SetLiveRun 那一次性的 TTL。
        if (results.isEmpty()) {
            touchLiveRun(sessionId);
            return StreamBatch.empty(fromOffset);
        }

        List<StreamEvent> events = new ArrayList<>(results.size());
        for (String raw : results) {
            StreamEvent event = tryReadEvent(raw);
            if (event != null) {
                events.add(event);
            }
        }

        // 算的是 Redis 原始条数：解码失败被跳过的事件也计入 offset，
        // 否则每次轮询都会重新拉到同一条坏数据。
        int nextOffset = fromOffset + results.size();
        touchLiveRun(sessionId);
        return new StreamBatch(events, nextOffset);
    }

    // ── steer 控制面 ────────────────────────────────────────────────────────

    private static final String STEER_APPEND_UNIQUE_LUA = """
            local seen = {}
            for _, raw in ipairs(redis.call('LRANGE', KEYS[1], 0, -1)) do
              local ok, event = pcall(cjson.decode, raw)
              if ok and event.id then seen[event.id] = true end
            end
            for i = 2, #ARGV do
              local event = cjson.decode(ARGV[i])
              if not event.id or event.id == '' or not seen[event.id] then
                redis.call('RPUSH', KEYS[1], ARGV[i])
                if event.id then seen[event.id] = true end
              end
            end
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            return 1
            """;

    private static final RedisScript<Long> STEER_APPEND_UNIQUE =
            new DefaultRedisScript<>(STEER_APPEND_UNIQUE_LUA, Long.class);

    @Override
    public void appendSteerEvents(String sessionId, String messageId, List<StreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        String key = buildSteerKey(sessionId, messageId);

        List<Object> args = new ArrayList<>(events.size() + 1);
        args.add(Long.toString(ttl.toSeconds()));
        for (StreamEvent event : events) {
            // Go 就地改 events[i]（切片底层数组共享，调用方看得见）
            if (event.getTimestamp() == null) {
                event.setTimestamp(OffsetDateTime.now());
            }
            args.add(StreamJson.write(event));
        }

        try {
            template.execute(STEER_APPEND_UNIQUE, Collections.singletonList(key), args.toArray());
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to append steer events to Redis: " + e.getMessage(), e);
        }
        // 刷新 live-run 标记：这轮还在收控制指令，说明它并没有空闲。
        try {
            template.expire(buildLiveRunKey(sessionId), ttl);
        } catch (DataAccessException ignored) {
            // Go: `_ = r.client.Expire(...)` —— 有意吞掉
        }
    }

    @Override
    public StreamBatch getSteerEvents(String sessionId, String messageId, int fromOffset) {
        String key = buildSteerKey(sessionId, messageId);

        List<String> results;
        try {
            results = template.opsForList().range(key, fromOffset, -1);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to get steer events from Redis: " + e.getMessage(), e);
        }
        if (results == null) {
            results = List.of();
        }
        if (results.isEmpty()) {
            touchLiveRun(sessionId);
            return StreamBatch.empty(fromOffset);
        }

        List<StreamEvent> events = new ArrayList<>(results.size());
        for (String raw : results) {
            StreamEvent event = tryReadEvent(raw);
            if (event != null) {
                events.add(event);
            }
        }

        touchLiveRun(sessionId);
        return new StreamBatch(events, fromOffset + results.size());
    }

    private static final String STEER_UPDATE_CAS_LUA = """
            if redis.call('LINDEX', KEYS[1], ARGV[1]) ~= ARGV[2] then
              return 0
            end
            redis.call('LSET', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """;

    private static final RedisScript<Long> STEER_UPDATE_CAS =
            new DefaultRedisScript<>(STEER_UPDATE_CAS_LUA, Long.class);

    @Override
    public boolean updateSteerEventData(String sessionId, String messageId, String eventId,
            Map<String, Object> data) {
        String key = buildSteerKey(sessionId, messageId);

        for (int attempt = 0; attempt < STEER_UPDATE_MAX_ATTEMPTS; attempt++) {
            List<String> results;
            try {
                results = template.opsForList().range(key, 0, -1);
            } catch (DataAccessException e) {
                throw new StreamStoreException("failed to read steer events from Redis: " + e.getMessage(), e);
            }
            if (results == null) {
                return false;
            }

            int index = -1;
            String current = null;
            StreamEvent event = null;
            for (int i = 0; i < results.size(); i++) {
                StreamEvent candidate = tryReadEvent(results.get(i));
                if (candidate == null || !eventId.equals(candidate.getId())) {
                    continue;
                }
                index = i;
                current = results.get(i);
                event = candidate;
                break;
            }
            if (index < 0) {
                return false;
            }

            event.mergeData(data);
            String payload = StreamJson.write(event);

            Long updated;
            try {
                updated = template.execute(STEER_UPDATE_CAS, Collections.singletonList(key),
                        Integer.toString(index), current, payload);
            } catch (DataAccessException e) {
                throw new StreamStoreException("failed to update steer event in Redis: " + e.getMessage(), e);
            }
            if (updated != null && updated == 1L) {
                try {
                    template.expire(key, ttl);
                } catch (DataAccessException ignored) {
                    // Go 同样是 `_ = r.client.Expire(...)`
                }
                return true;
            }
            // 有人改写或删掉了那个槽位；重读再试。
        }
        throw new StreamStoreException("steer event " + eventId + " changed concurrently, giving up after "
                + STEER_UPDATE_MAX_ATTEMPTS + " attempts");
    }

    @Override
    public boolean deleteSteerEvent(String sessionId, String messageId, String eventId) {
        String key = buildSteerKey(sessionId, messageId);
        for (int attempt = 0; attempt < STEER_UPDATE_MAX_ATTEMPTS; attempt++) {
            List<String> results;
            try {
                results = template.opsForList().range(key, 0, -1);
            } catch (DataAccessException e) {
                throw new StreamStoreException("failed to read steer events from Redis: " + e.getMessage(), e);
            }
            if (results == null) {
                return false;
            }

            String current = null;
            StreamEvent event = null;
            for (String raw : results) {
                StreamEvent candidate = tryReadEvent(raw);
                if (candidate == null || !eventId.equals(candidate.getId())) {
                    continue;
                }
                current = raw;
                event = candidate;
                break;
            }
            if (event == null) {
                return false;
            }
            Map<String, Object> d = event.getData();
            if (d != null && Boolean.TRUE.equals(d.get("consumed"))) {
                return false;
            }

            Long removed;
            try {
                removed = template.opsForList().remove(key, 1, current);
            } catch (DataAccessException e) {
                throw new StreamStoreException("failed to delete steer event from Redis: " + e.getMessage(), e);
            }
            if (removed != null && removed > 0) {
                return true;
            }
        }
        return false;
    }

    // ── live run ────────────────────────────────────────────────────────────

    private String marshalLiveRun(String assistantMessageId, String requestId) {
        return StreamJson.write(new LiveRunPayload(assistantMessageId, requestId));
    }

    @Override
    public void setLiveRun(String sessionId, String assistantMessageId, String requestId) {
        String key = buildLiveRunKey(sessionId);
        String payload = marshalLiveRun(assistantMessageId, requestId);

        Boolean ok;
        try {
            ok = template.opsForValue().setIfAbsent(key, payload, ttl);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to set live run marker in Redis: " + e.getMessage(), e);
        }
        if (Boolean.TRUE.equals(ok)) {
            return;
        }

        String current = getLiveRun(sessionId).assistantMessageId();
        if (current != null && current.equals(assistantMessageId)) {
            return;
        }
        if (current == null || current.isEmpty()) {
            // SETNX 失败但读回来是空的：多半是标记刚好过期，再抢一次。
            try {
                ok = template.opsForValue().setIfAbsent(key, payload, ttl);
            } catch (DataAccessException e) {
                throw new StreamStoreException("failed to set live run marker in Redis: " + e.getMessage(), e);
            }
            if (Boolean.TRUE.equals(ok)) {
                return;
            }
        }
        throw new LiveRunExistsException();
    }

    @Override
    public void claimLiveRun(String sessionId, String assistantMessageId, String requestId) {
        String payload = marshalLiveRun(assistantMessageId, requestId);
        try {
            template.opsForValue().set(buildLiveRunKey(sessionId), payload, ttl);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to claim live run marker in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public LiveRun getLiveRun(String sessionId) {
        String raw;
        try {
            raw = template.opsForValue().get(buildLiveRunKey(sessionId));
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to read live run marker from Redis: " + e.getMessage(), e);
        }
        if (raw == null) {
            return LiveRun.NONE;
        }
        LiveRunPayload payload;
        try {
            payload = StreamJson.read(raw, LiveRunPayload.class);
        } catch (StreamStoreException e) {
            // 不能把损坏的标记折叠成"没有 live run"：调用方会把空值当成
            // new_run，从而在仍在生成的轮次之上再起一个 AgentQA。
            throw new StreamStoreException("failed to decode live run marker: " + e.getMessage(), e);
        }
        touchLiveRun(sessionId);
        return new LiveRun(payload.assistantMessageId(), payload.requestId());
    }

    private static final String CLEAR_LIVE_RUN_CAS_LUA = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return 0
            end
            if string.find(raw, ARGV[1], 1, true) == nil then
              return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    private static final RedisScript<Long> CLEAR_LIVE_RUN_CAS =
            new DefaultRedisScript<>(CLEAR_LIVE_RUN_CAS_LUA, Long.class);

    @Override
    public void clearLiveRun(String sessionId, String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isEmpty()) {
            return;
        }
        // 匹配序列化后的字段而不是在 Lua 里解码：标记由上面的 setLiveRun 写入，
        // 编码方式是我们自己可以依赖的。needle 走同一个 mapper，别手工拼引号。
        String needle = "\"assistant_message_id\":" + StreamJson.writeString(assistantMessageId);
        try {
            template.execute(CLEAR_LIVE_RUN_CAS, Collections.singletonList(buildLiveRunKey(sessionId)), needle);
        } catch (DataAccessException e) {
            throw new StreamStoreException("failed to clear live run marker in Redis: " + e.getMessage(), e);
        }
    }

    /**
     * 延长 live-run 标记的 TTL，避免比 StreamManager TTL 更长的轮次看起来"空闲"。
     * 键不存在时 EXPIRE 是空操作。错误有意吞掉（对照 Go）。
     */
    void touchLiveRun(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            template.expire(buildLiveRunKey(sessionId), ttl);
        } catch (DataAccessException ignored) {
            // Go: `_ = r.client.Expire(...).Err()`
        }
    }

    /** 读单个事件；解码失败返回 null（Go 侧是 continue，跳过坏数据继续）。 */
    private static StreamEvent tryReadEvent(String raw) {
        try {
            return StreamJson.read(raw, StreamEvent.class);
        } catch (StreamStoreException e) {
            return null;
        }
    }

    /** 供测试与调试：当前 prefix（对照 Go 的 buildKey 前缀，含 REDIS_PREFIX 原样值）。 */
    String prefix() {
        return prefix;
    }
}
