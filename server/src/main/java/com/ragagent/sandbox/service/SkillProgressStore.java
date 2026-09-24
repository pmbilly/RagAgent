package com.ragagent.sandbox.service;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 对照 Go {@code TenantSkillService} 的进度三件套（tenant_skill_progress.go 全文 +
 * tenant_skill_admin.go 的 SubscribeProgress）：
 * publishProgress / LastProgress / SubscribeProgress。
 *
 * <p>Redis 键 {@code weknora-skill-install:<tenantId>:<configId>:<skillId>} 与 Go
 * <b>完全同名</b>——两个实现读写同一批键（阶段 5 教训：字节级对齐不是洁癖）。
 * TTL 30 分钟：让迟到的 SSE 订阅者还能渲染最后一次值，然后放它过期。</p>
 *
 * <p>没有 Redis（或连接失败）时一切皆 no-op / 空：UI 回落数据库状态 alone，
 * 安装本身照常工作，这正是重点（Go 注释原文）。Go 用 {@code s.redis == nil} 判定；
 * Java 侧模板 bean 恒在，所以以一次 {@code PING} 探测等价 nil——探测失败即按
 * 无 Redis 处理（Go 的 redis 宕机路径：Warnf + 空结果，语义相同）。</p>
 *
 * <p><b>实时订阅（2026-09-24 接线）</b>：subscribe 订阅进度广播（channel = 进度 key，
 * 与 publish 的 convertAndSend 同源），消息泵进有界队列；无 Redis 时仍回落 nil 通道，
 * SSE 端点按 Go 的 {@code events == nil} 分支回落 durable 状态。</p>
 */
@Component
public class SkillProgressStore {

    private static final Logger log = LoggerFactory.getLogger(SkillProgressStore.class);

    /** 对照 {@code skillProgressTTL}。 */
    static final Duration SKILL_PROGRESS_TTL = Duration.ofMinutes(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;

    /** 一次性的可达性判定（Go 的 nil 判定等价物）；失败后进程内恒按无 Redis 处理。 */
    private volatile boolean redisUnavailable;

    public SkillProgressStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis.getIfAvailable();
    }

    /** 对照 {@code skillProgressKey}。 */
    public static String skillProgressKey(long tenantId, String configId, String skillId) {
        return "weknora-skill-install:" + Long.toUnsignedString(tenantId) + ":" + configId
                + ":" + skillId;
    }

    private boolean redisUp() {
        if (redis == null || redisUnavailable) {
            return false;
        }
        try {
            redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
                connection.ping();
                return null;
            });
            return true;
        } catch (RuntimeException e) {
            log.info("[skill] redis unavailable for skill progress: {}", e.getMessage());
            redisUnavailable = true;
            return false;
        }
    }

    /** 对照 {@code publishProgress}：存最新值并广播。没有 Redis 时两者都是 no-op。 */
    public void publish(long tenantId, String configId, String skillId, SkillProgress p) {
        if (!redisUp()) {
            return;
        }
        String payload;
        try {
            payload = MAPPER.writeValueAsString(p);
        } catch (Exception e) {
            return;
        }
        String key = skillProgressKey(tenantId, configId, skillId);
        try {
            redis.opsForValue().set(key, payload, SKILL_PROGRESS_TTL);
        } catch (RuntimeException e) {
            log.warn("[skill] store progress {} failed: {}", key, e.getMessage());
        }
        try {
            redis.convertAndSend(key, payload);
        } catch (RuntimeException e) {
            log.warn("[skill] publish progress {} failed: {}", key, e.getMessage());
        }
    }

    /** 对照 {@code LastProgress}：让新的 SSE 连接立即有画面，而不是等下一个 tick。 */
    public LastProgress last(long tenantId, String configId, String skillId) {
        if (!redisUp()) {
            return LastProgress.ABSENT;
        }
        String raw;
        try {
            raw = redis.opsForValue().get(skillProgressKey(tenantId, configId, skillId));
        } catch (RuntimeException e) {
            return LastProgress.ABSENT;
        }
        if (raw == null) {
            return LastProgress.ABSENT;
        }
        try {
            var node = MAPPER.readTree(raw);
            SkillProgress p = new SkillProgress(
                    node.path("percent").asInt(0),
                    node.path("stage").asText(""),
                    node.hasNonNull("log") ? node.get("log").asText("") : "",
                    node.hasNonNull("status") ? node.get("status").asText("") : "");
            return new LastProgress(p, true);
        } catch (Exception e) {
            return LastProgress.ABSENT;
        }
    }

    /**
     * 对照 {@code SubscribeProgress}：订阅进度广播（channel = 进度 key，与
     * {@link #publish} 的 {@code convertAndSend} 同源），把消息泵进有界队列。
     * 没有可用的 Redis → {@code events == null} + no-op closer（调用方回落 durable
     * 状态，与 Go 的 {@code s.redis == nil} 分支一致）。
     *
     * <p>Spring Data Redis 的 {@code connection.subscribe} 是阻塞调用（内部收循环），
     * 放在独立虚拟线程上——与 Go 的"每订阅一个 goroutine 泵消息进 channel"同形；
     * closer 关闭连接即结束订阅与线程。</p>
     */
    public Subscription subscribe(long tenantId, String configId, String skillId) {
        if (!redisUp() || redis.getConnectionFactory() == null) {
            return new Subscription(null, () -> {
            });
        }
        String channel = skillProgressKey(tenantId, configId, skillId);
        java.util.concurrent.BlockingQueue<SkillProgress> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        org.springframework.data.redis.connection.RedisConnection connection;
        try {
            connection = redis.getConnectionFactory().getConnection();
        } catch (RuntimeException e) {
            log.warn("[skill] subscribe {} failed: {}", channel, e.getMessage());
            return new Subscription(null, () -> {
            });
        }
        byte[] channelBytes = channel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Thread.ofVirtual().name("skill-progress-subscriber").start(() -> {
            try {
                connection.subscribe((message, pattern) -> {
                    try {
                        var node = MAPPER.readTree(new String(message.getBody(),
                                java.nio.charset.StandardCharsets.UTF_8));
                        queue.offer(new SkillProgress(
                                node.path("percent").asInt(0),
                                node.path("stage").asText(""),
                                node.hasNonNull("log") ? node.get("log").asText("") : "",
                                node.hasNonNull("status") ? node.get("status").asText("") : ""));
                    } catch (Exception ignored) {
                        // 单条消息解析失败不打断订阅（对照 Go 的松散解析）
                    }
                }, channelBytes);
            } catch (RuntimeException e) {
                log.info("[skill] progress subscription ended for {}: {}", channel, e.getMessage());
            }
        });
        return new Subscription(queue, () -> {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // 关闭失败无补救动作
            }
        });
    }

    /** 对照 Go 的 {@code (<-chan SkillProgress, func(), error)} 三元组的前两个。 */
    public record Subscription(BlockingQueue<SkillProgress> events, Runnable release) {
    }

    /** 对照 {@code (SkillProgress, bool)}。 */
    public record LastProgress(SkillProgress progress, boolean present) {
        public static final LastProgress ABSENT = new LastProgress(SkillProgress.empty(), false);
    }
}
