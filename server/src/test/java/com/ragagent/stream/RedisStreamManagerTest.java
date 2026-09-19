package com.ragagent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ragagent.llm.domain.ResponseType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 流管理器的语义（对照 Go {@code internal/stream/redis_manager.go} 与
 * {@code redis_live_run_test.go}）。
 *
 * <p>跑在真 redis-server 上（{@link EmbeddedRedis}，对应 Go 测试的 miniredis）——
 * 这里一半的行为在 Lua 脚本与真实 TTL 里，假实现替不掉。机器上没有
 * {@code redis-server} 时整类跳过。</p>
 *
 * <p>TTL 相关的用例用"观察 TTL 被推回去"代替 Go 的 {@code mini.FastForward}：
 * 真 Redis 不会快进，但把到期时间读出来一样能证明续期发生了，还更省时间。</p>
 */
class RedisStreamManagerTest {

    private static final String PREFIX = "test";

    private static EmbeddedRedis redis;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startRedis() {
        redis = EmbeddedRedis.tryStart();
        Assumptions.assumeTrue(redis != null,
                "本机没有 redis-server（PATH 上找不到，也未设 REDIS_TEST_ADDR）——跳过 Redis 流管理器用例");
        template = redis.template();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void resetKeys() {
        redis.flushAll();
    }

    private RedisStreamManager manager(Duration ttl) {
        return new RedisStreamManager(template, PREFIX, ttl);
    }

    private Long ttlSeconds(String key) {
        return template.getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * 跑一段"续期动作"，断言它把 live-run 的 TTL 推了回去。
     *
     * <p>用「前后对比」而不是「大于某个绝对值」：TTL 按毫秒存、按秒**向下取整**读，
     * 刚写进去 5s 也可能读到 4（实现无误，断言太紧会假红——这里已经踩过一次）。</p>
     *
     * <p>⚠️ 读数用毫秒精度（PTTL），且调用方用**长 TTL**（≥2min）建 manager：
     * 全量慢跑时秒级取整可能让 renew 前后落在同一秒（after==before 假红）、
     * 短 TTL 键可能撑不到断言就过期（读数 -2 假红）——波 3 sandbox 批验收时
     * 两种形态都实测到了（§5 陷阱 9 的第三变种）。</p>
     */
    private void assertRefreshesLiveRunTtl(String liveRunKey, Runnable action) {
        Long before = template.getExpire(liveRunKey, TimeUnit.MILLISECONDS);
        action.run();
        Long after = template.getExpire(liveRunKey, TimeUnit.MILLISECONDS);
        assertTrue(after > before,
                "live-run 的 TTL 必须被推回去（前 " + before + "ms → 后 " + after + "ms）");
    }

    // ── 键布局 ──────────────────────────────────────────────────────────────

    @Test
    void keyLayoutMatchesGoIncludingTheDoubleColonFromEnvPrefix() {
        // Go 是 fmt.Sprintf("%s:%s:%s", prefix, sessionID, messageID) —— prefix 不做去尾冒号处理。
        // dev .env 的 REDIS_PREFIX=stream: 因此拼出 stream::sess:msg，照抄不改。
        RedisStreamManager m = new RedisStreamManager(template, "stream:", Duration.ofHours(1));
        assertEquals("stream::sess-1:msg-1", m.buildKey("sess-1", "msg-1"));
        assertEquals("stream::sess-1:msg-1:steer", m.buildSteerKey("sess-1", "msg-1"));
        assertEquals("stream::sess-1:live-run", m.buildLiveRunKey("sess-1"));

        // 空 prefix 回落 "stream:events"（对照 NewRedisStreamManager 的默认值）
        RedisStreamManager def = new RedisStreamManager(template, "", Duration.ofHours(1));
        assertEquals("stream:events:sess-1:msg-1", def.buildKey("sess-1", "msg-1"));

        // TTL 归零回落 24h
        RedisStreamManager noTtl = new RedisStreamManager(template, "", Duration.ZERO);
        noTtl.setLiveRun("s", "a", "r");
        // TTL 是按毫秒存、按秒读且向下取整的，刚写完就可能读到 86399
        assertTrue(ttlSeconds(noTtl.buildLiveRunKey("s")) >= 24 * 3600 - 2,
                "TTL 归零应回落到 24h");
    }

    // ── live run ────────────────────────────────────────────────────────────

    @Test
    void getLiveRunOnAMissingKeyIsEmpty() {
        assertFalse(manager(Duration.ofHours(1)).getLiveRun("sess-missing").isPresent());
    }

    @Test
    void getLiveRunOnACorruptMarkerIsAnError() {
        // 对照 Go TestGetLiveRunCorruptJSONIsError：损坏的标记**不能**折叠成"没有 live run"，
        // 否则 /steer 会答 new_run，客户端在仍在生成的轮次上叠第二个 AgentQA。
        String key = manager(Duration.ofHours(1)).buildLiveRunKey("sess-1");
        template.opsForValue().set(key, "not-json");

        assertThrows(StreamStoreException.class, () -> manager(Duration.ofHours(1)).getLiveRun("sess-1"));
    }

    @Test
    void setLiveRunRejectsADifferentAssistantButClaimOverwrites() {
        // 对照 Go TestSetLiveRunRejectsADifferentAssistant / TestClaimLiveRunOverwritesTheMarker
        RedisStreamManager m = manager(Duration.ofHours(1));

        m.setLiveRun("sess-1", "assist-1", "req-1");
        assertThrows(LiveRunExistsException.class, () -> m.setLiveRun("sess-1", "assist-2", "req-2"));

        LiveRun run = m.getLiveRun("sess-1");
        assertEquals("assist-1", run.assistantMessageId());
        assertEquals("req-1", run.requestId());

        m.claimLiveRun("sess-1", "assist-2", "req-2");
        LiveRun claimed = m.getLiveRun("sess-1");
        assertEquals("assist-2", claimed.assistantMessageId());
        assertEquals("req-2", claimed.requestId());
    }

    @Test
    void setLiveRunIsIdempotentForTheSameAssistant() {
        RedisStreamManager m = manager(Duration.ofHours(1));
        m.setLiveRun("sess-1", "assist-1", "req-1");
        m.setLiveRun("sess-1", "assist-1", "req-1");
        assertEquals("assist-1", m.getLiveRun("sess-1").assistantMessageId());
    }

    @Test
    void clearLiveRunDropsTheMarkerOnlyWhenItStillNamesThatRun() {
        // CAS：后续轮次可能已经把标记抢过去了，此时拆旧轮次不能连带删掉新标记
        RedisStreamManager m = manager(Duration.ofHours(1));
        m.claimLiveRun("sess-1", "assist-2", "req-2");

        m.clearLiveRun("sess-1", "assist-1");
        assertEquals("assist-2", m.getLiveRun("sess-1").assistantMessageId(),
                "指向别的轮次时不该删");

        m.clearLiveRun("sess-1", "assist-2");
        assertFalse(m.getLiveRun("sess-1").isPresent());

        // 空 ID 是 no-op（Go 直接 return nil）
        m.setLiveRun("sess-1", "assist-3", "req-3");
        m.clearLiveRun("sess-1", "");
        assertEquals("assist-3", m.getLiveRun("sess-1").assistantMessageId());
    }

    // ── live-run TTL 续期（对照 Go 的三个 FastForward 用例） ─────────────────

    @Test
    void appendEventRefreshesTheLiveRunTtl() {
        // 2min TTL：全量慢跑下键必须撑到断言（5s 时实测过期 → -2 假红）
        RedisStreamManager m = manager(Duration.ofMinutes(2));
        m.setLiveRun("sess-1", "assist-1", "req-1");
        sleep(2000);

        assertRefreshesLiveRunTtl(m.buildLiveRunKey("sess-1"),
                () -> m.appendEvent("sess-1", "assist-1",
                        new StreamEvent("e1", ResponseType.ANSWER, "chunk", false)));
    }

    @Test
    void getEventsRefreshesTheLiveRunTtlWhileWaiting() {
        RedisStreamManager m = manager(Duration.ofMinutes(2));
        m.setLiveRun("sess-1", "assist-1", "req-1");
        sleep(2000);

        // 空读也要续期：模型思考期间 SSE 轮询循环正是走这条路径
        assertRefreshesLiveRunTtl(m.buildLiveRunKey("sess-1"),
                () -> m.getEvents("sess-1", "assist-1", 0));
    }

    @Test
    void appendSteerEventsRefreshesTheLiveRunTtl() {
        RedisStreamManager m = manager(Duration.ofMinutes(2));
        m.setLiveRun("sess-1", "assist-1", "req-1");
        sleep(2000);

        assertRefreshesLiveRunTtl(m.buildLiveRunKey("sess-1"),
                () -> m.appendSteerEvents("sess-1", "assist-1",
                        List.of(new StreamEvent("s1", ResponseType.STEER, "queued", true))));
    }

    // ── 事件流 ──────────────────────────────────────────────────────────────

    @Test
    void eventsRoundTripThroughRedisByteForByte() {
        RedisStreamManager m = manager(Duration.ofHours(1));
        m.appendEvent("s", "m", new StreamEvent("e1", ResponseType.ANSWER, "hi <b>&</b>", false));

        // 直接读原文，整条字节串与 Go 实测的 json.Marshal 输出比对（只掩掉随本机时区变的
        // timestamp）。这条断言就是"跨语言互操作"的全部依据：Go 读自己写出的字节是天经地义，
        // 而 Java 写出的字节与它逐字节相同。
        String raw = template.opsForList().index(m.buildKey("s", "m"), 0);
        String expected = "{\"id\":\"e1\",\"type\":\"answer\","
                + "\"content\":\"hi \\u003cb\\u003e\\u0026\\u003c/b\\u003e\","
                + "\"done\":false,\"timestamp\":\"<TS>\"}";
        assertEquals(expected, raw.replaceFirst("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<TS>\""),
                "落到 Redis 的字节必须与 Go 逐字节一致");

        StreamBatch batch = m.getEvents("s", "m", 0);
        assertEquals(1, batch.events().size());
        assertEquals("hi <b>&</b>", batch.events().get(0).getContent());
        assertEquals(1, batch.nextOffset());
    }

    @Test
    void getEventsSkipsUndecodableRowsButStillAdvancesTheOffset() {
        // Go 对解码失败的行 `continue`，但 nextOffset 用的是 Redis 的原始条数——
        // 否则每次轮询都会把同一条坏数据再拉一遍
        RedisStreamManager m = manager(Duration.ofHours(1));
        String key = m.buildKey("s", "m");
        template.opsForList().rightPush(key, "{ not json");
        m.appendEvent("s", "m", new StreamEvent("e2", ResponseType.ANSWER, "ok", false));

        StreamBatch batch = m.getEvents("s", "m", 0);
        assertEquals(1, batch.events().size(), "坏行被跳过");
        assertEquals("e2", batch.events().get(0).getId());
        assertEquals(2, batch.nextOffset(), "offset 按原始条数推进");
    }

    // ── steer 控制面 ────────────────────────────────────────────────────────

    @Test
    void appendSteerEventsDeduplicatesClientIds() throws Exception {
        // 对照 Go TestAppendSteerEventsDeduplicatesClientIDs（redis 分支）
        RedisStreamManager m = manager(Duration.ofHours(1));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    StreamEvent evt = new StreamEvent("same-client-id", ResponseType.STEER,
                            "do this next", false);
                    evt.setData(Map.of("delivery", "after"));
                    m.appendSteerEvents("session", "run", List.of(evt));
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        List<StreamEvent> events = m.getSteerEvents("session", "run", 0).events();
        assertEquals(1, events.size(), "Lua 里的去重必须原子生效");

        assertTrue(m.updateSteerEventData("session", "run", "same-client-id", Map.of("consumed", true)));
        m.appendSteerEvents("session", "run",
                List.of(new StreamEvent("same-client-id", ResponseType.STEER, "do this next", false)));

        List<StreamEvent> after = m.getSteerEvents("session", "run", 0).events();
        assertEquals(1, after.size());
        assertEquals(Boolean.TRUE, after.get(0).getData().get("consumed"));
    }

    @Test
    void updateSteerEventDataMergesKeysAndPersistsThem() {
        RedisStreamManager m = manager(Duration.ofHours(1));
        StreamEvent evt = new StreamEvent("s1", ResponseType.STEER, "queued", true);
        evt.setData(Map.of("delivery", "after"));
        m.appendSteerEvents("session", "run", List.of(evt));

        assertTrue(m.updateSteerEventData("session", "run", "s1", Map.of("consumed", true)));

        StreamEvent stored = m.getSteerEvents("session", "run", 0).events().get(0);
        assertEquals(Boolean.TRUE, stored.getData().get("consumed"));
        assertEquals("after", stored.getData().get("delivery"), "原有键不能被覆盖掉");

        assertFalse(m.updateSteerEventData("session", "run", "nope", Map.of("consumed", true)));
    }

    @Test
    void deleteSteerEventRemovesItUnlessItWasConsumed() {
        RedisStreamManager m = manager(Duration.ofHours(1));
        m.appendSteerEvents("session", "run",
                List.of(new StreamEvent("s1", ResponseType.STEER, "queued", true)));

        assertTrue(m.deleteSteerEvent("session", "run", "s1"));
        assertTrue(m.getSteerEvents("session", "run", 0).events().isEmpty());
        assertFalse(m.deleteSteerEvent("session", "run", "s1"), "删过一次后就没有了");

        m.appendSteerEvents("session", "run",
                List.of(new StreamEvent("s2", ResponseType.STEER, "queued", true)));
        m.updateSteerEventData("session", "run", "s2", Map.of("consumed", true));

        assertFalse(m.deleteSteerEvent("session", "run", "s2"),
                "已并入运行轮次的消息不能被浮层撤回");
        assertEquals(1, m.getSteerEvents("session", "run", 0).events().size());
    }

    @Test
    void appendSteerEventsWithAnEmptyListIsANoOp() {
        RedisStreamManager m = manager(Duration.ofHours(1));
        m.appendSteerEvents("session", "run", List.of());
        assertFalse(Boolean.TRUE.equals(template.hasKey(m.buildSteerKey("session", "run"))),
                "空列表提前返回，不该建出键");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
