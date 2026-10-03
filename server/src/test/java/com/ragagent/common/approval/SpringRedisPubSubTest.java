package com.ragagent.common.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.ragagent.common.llm.ResponseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * {@link SpringRedisPubSub} 的真 Redis 测试（对照 go-redis 的 Subscribe/Publish 语义）。
 *
 * <p>仅在本地能连上**且能通过认证**的 Redis（默认 localhost:16379，与
 * server/src/test/resources 的 spring.data.redis 一致）时运行，否则整体跳过——
 * 仓库里没有 embedded redis，其余跨实例场景由 {@link FakeRedisPubSub} 覆盖
 * （见 {@link GateCrossInstanceTest}）。</p>
 *
 * <p>若开发用 Redis 开了密码（docker-compose 的 {@code --requirepass ${REDIS_PASSWORD}}），
 * 跑测试时带上同名环境变量即可：{@code REDIS_PASSWORD=... ./gradlew test}；
 * 未设置且 Redis 要求认证时本类自动跳过（不把口令写进仓库）。
 * 注意：Java 侧 application.yml 目前也没配 spring.data.redis.password，
 * 接线 Redis 时同样需要补上。</p>
 *
 * <p>覆盖两点：适配器自身的 订阅→确认→收消息 / 发布 往返，
 * 以及两个 Gate 真实经 Redis 完成“广播投递 + ack 回执”的全链路。</p>
 */
@Timeout(30)
@EnabledIf("redisAvailable")
class SpringRedisPubSubTest {

    private static final String HOST = "localhost";
    private static final int PORT = 16379;
    private static final String PASSWORD_ENV = "REDIS_PASSWORD";

    private LettuceConnectionFactory factory;
    private Gate gateA;
    private Gate gateB;

    private static String redisPassword() {
        String p = System.getenv(PASSWORD_ENV);
        return p == null ? "" : p.trim();
    }

    /**
     * 条件方法：先用裸 socket 做一次 “AUTH（若配了密码）+ PING”，
     * 只有拿到 +PONG 才认为这套跨实例测试可跑（端口开着但要求认证的情况也算不可用）。
     */
    static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            socket.setSoTimeout(500);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String password = redisPassword();
            if (!password.isEmpty()) {
                send(out, "AUTH " + password);
                String authReply = in.readLine();
                if (authReply == null || !authReply.startsWith("+OK")) {
                    return false;
                }
            }
            send(out, "PING");
            String pong = in.readLine();
            return pong != null && pong.startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
        String password = redisPassword();
        if (!password.isEmpty()) {
            config.setPassword(RedisPassword.of(password));
        }
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        if (gateA != null) {
            gateA.close();
        }
        if (gateB != null) {
            gateB.close();
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void publishSubscribeRoundTrip() {
        SpringRedisPubSub pubsub = new SpringRedisPubSub(factory);
        // 用专属频道，避免打扰真实部署（gate 的频道由 pubsubChannel() 固定，见下一个用例）
        String channel = Gate.PUBSUB_CHANNEL_BASE + ":junit-" + UUID.randomUUID();

        try (RedisPubSub.Subscription sub = pubsub.subscribe(channel)) {
            assertTrue(sub.awaitSubscribed(Duration.ofSeconds(5)), "订阅必须被 Redis 确认");
            assertEquals(1L, pubsub.publish(channel, "{\"hello\":1}"));
            assertEquals("{\"hello\":1}", sub.receiveMessage(Duration.ofSeconds(5)));
            // 超时返回 null
            assertNull(sub.receiveMessage(Duration.ofMillis(200)));
        }
    }

    /** 两个 Gate 经真 Redis 完成跨实例 Resolve：A 广播、B 投递、B 回 ack、A 返回成功。 */
    @Test
    void crossInstanceResolveOverRealRedis() throws Exception {
        SpringRedisPubSub pubsub = new SpringRedisPubSub(factory);
        GateOptions options = GateOptions.defaults()
                .withTimeout(Duration.ofSeconds(10))
                .withAckTimeout(Duration.ofSeconds(5));
        gateB = new Gate(options.withInstanceId("java-test-B"), new StubChecker(true), pubsub);
        gateA = new Gate(options.withInstanceId("java-test-A"), new StubChecker(true), pubsub);
        // 订阅是异步建立的：给订阅线程一点时间，避免发布早于订阅（Redis pubsub 不重放）
        Thread.sleep(800);

        RecordingEventBus bus = new RecordingEventBus();
        AtomicReference<ApprovalException> callerResult = new AtomicReference<>();
        AtomicReference<Boolean> callerDone = new AtomicReference<>(false);
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                callerResult.set(resolveQuietly(gateA, pendingId));
                callerDone.set(true);
            });
        });

        Decision d = gateB.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).eventBus(bus).sessionId("s").assistantMessageId("m")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertTrue(d.approved(), "跨实例投递的决策必须到达 B 的等待者");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Boolean.TRUE.equals(callerDone.get()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Boolean.TRUE.equals(callerDone.get()), "A 侧的 Resolve 必须在 ack 窗口内返回");
        assertNull(callerResult.get(), "A 侧必须收到 ok ack 而返回成功");
    }

    private static ApprovalException resolveQuietly(Gate gate, String pendingId) {
        try {
            gate.resolve(1, "", pendingId, Decision.allowWith("{\"a\":2}"));
            return null;
        } catch (ApprovalException e) {
            return e;
        }
    }
}
