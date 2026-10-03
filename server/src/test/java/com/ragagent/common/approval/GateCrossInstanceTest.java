package com.ragagent.common.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.llm.ResponseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Redis Pub/Sub 跨实例语义（覆盖 {@code Gate} 的 runSubscriber / resolve / resolveCrossInstance）——
 * 真 Redis 不在测试矩阵里，这里用内存 pubsub 覆盖：
 *
 * <ul>
 *   <li>pending 在别的实例上：Resolve 经广播投递，并由持有者回 ack（调用方返回成功）；</li>
 *   <li>租户/用户不匹配：ack 带回准确状态码（handler 据此映射 404/409）；</li>
 *   <li>无人持有：ack 窗口内无回应 → PENDING_NOT_FOUND；</li>
 *   <li>自己发布的报文被 OriginID 过滤（不产生噪音）。</li>
 * </ul>
 */
@Timeout(30)
class GateCrossInstanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Gate gateA;
    private Gate gateB;

    @AfterEach
    void tearDown() {
        if (gateA != null) {
            gateA.close();
        }
        if (gateB != null) {
            gateB.close();
        }
    }

    private static GateOptions options(String instanceId, Duration ackTimeout) {
        return GateOptions.defaults()
                .withInstanceId(instanceId)
                .withAckTimeout(ackTimeout)
                .withTimeout(Duration.ofSeconds(10));
    }

    private static ApprovalException captureResolve(Gate gate, long tenantId, String userId, String pendingId, Decision d) {
        try {
            gate.resolve(tenantId, userId, pendingId, d);
            return null;
        } catch (ApprovalException e) {
            return e;
        }
    }

    /** 等一个标志，避免测试用固定 sleep */
    private static void awaitFlag(AtomicReference<Boolean> flag, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Boolean.TRUE.equals(flag.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /**
     * pending 在 B 实例、Resolve 打到 A 实例：广播 → B 投递 → ack 回 A → A 返回成功。
     */
    @Test
    void resolveFansOutToOwningInstance() throws Exception {
        FakeRedisPubSub pubsub = new FakeRedisPubSub();
        gateB = new Gate(options("B", Duration.ofSeconds(3)), new StubChecker(true), pubsub);
        gateA = new Gate(options("A", Duration.ofSeconds(3)), new StubChecker(true), pubsub);
        pubsub.awaitSubscribers(Gate.pubsubChannel(), 2, Duration.ofSeconds(2));

        RecordingEventBus bus = new RecordingEventBus();
        AtomicReference<ApprovalException> callerResult = new AtomicReference<>();
        AtomicReference<Boolean> callerDone = new AtomicReference<>(false);
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                // 打到“另一个实例”：本地没有该 pending，只能靠广播
                callerResult.set(captureResolve(gateA, 1, "", pendingId, Decision.allowWith("{\"a\":2}")));
                callerDone.set(true);
            });
        });

        Decision d = gateB.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).eventBus(bus).sessionId("s").assistantMessageId("m")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertTrue(d.approved());
        assertNotNull(d.modifiedArgs());
        assertEquals(JSON.readTree("{\"a\":2}"), JSON.readTree(d.modifiedArgs()));
        awaitFlag(callerDone, Duration.ofSeconds(5));
        assertNull(callerResult.get(), "跨实例投递成功时调用方不应报错");
    }

    /** 跨实例的租户/用户不匹配：状态码经 ack 原样回传。 */
    @Test
    void resolveFansOutMismatchStatuses() throws Exception {
        FakeRedisPubSub pubsub = new FakeRedisPubSub();
        gateB = new Gate(options("B", Duration.ofSeconds(3)), new StubChecker(true), pubsub);
        gateA = new Gate(options("A", Duration.ofSeconds(3)), new StubChecker(true), pubsub);
        pubsub.awaitSubscribers(Gate.pubsubChannel(), 2, Duration.ofSeconds(2));

        RecordingEventBus bus = new RecordingEventBus();
        AtomicReference<ApprovalException> tenantErr = new AtomicReference<>();
        AtomicReference<ApprovalException> userErr = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                tenantErr.set(captureResolve(gateA, 999, "", pendingId, Decision.allow()));
                userErr.set(captureResolve(gateA, 1, "mallory", pendingId, Decision.allow()));
                // 由持有实例本地收尾，避免等满超时
                gateB.resolve(1, "alice", pendingId, Decision.deny("done"));
                done.set(true);
            });
        });

        Decision d = gateB.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).userId("alice").eventBus(bus).sessionId("s").assistantMessageId("m")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        awaitFlag(done, Duration.ofSeconds(5));
        assertFalse(d.approved());
        assertNotNull(tenantErr.get());
        assertTrue(tenantErr.get().is(ApprovalException.Kind.TENANT_MISMATCH), String.valueOf(tenantErr.get()));
        assertNotNull(userErr.get());
        assertTrue(userErr.get().is(ApprovalException.Kind.USER_MISMATCH), String.valueOf(userErr.get()));
    }

    /** 没有任何实例持有该 pending：ack 窗口内无回应 → PENDING_NOT_FOUND（而非静默成功）。 */
    @Test
    void resolveCrossInstanceNotFound() {
        FakeRedisPubSub pubsub = new FakeRedisPubSub();
        gateA = new Gate(options("A", Duration.ofMillis(300)), new StubChecker(true), pubsub);

        ApprovalException err = captureResolve(gateA, 1, "", "missing-pending", Decision.allow());
        assertNotNull(err);
        assertTrue(err.is(ApprovalException.Kind.PENDING_NOT_FOUND));
    }

    /** 自己发布的报文必须被 OriginID 过滤：否则会自己投递一个本地必然 miss 的请求。 */
    @Test
    void subscriberIgnoresOwnMessages() throws Exception {
        FakeRedisPubSub pubsub = new FakeRedisPubSub();
        gateA = new Gate(options("A", Duration.ofMillis(300)), new StubChecker(true), pubsub);
        pubsub.awaitSubscribers(Gate.pubsubChannel(), 1, Duration.ofSeconds(2));

        long before = pubsub.published().size();
        captureResolve(gateA, 1, "", "missing-pending", Decision.allow());
        // 只有 resolveCrossInstance 发布的那一条；自己不会因为它再回 ack（OriginID 相等被跳过）
        assertEquals(before + 1, pubsub.published().size());
        assertTrue(pubsub.published().get(0).startsWith(Gate.PUBSUB_CHANNEL_BASE + "|"));
    }

    /**
     * nonce 隔离：同回复频道上，属于别的并发 Resolve 的 ack 必须被忽略
     * （nonce 不匹配的 ack 直接跳过）。
     *
     * <p>做法：抢在持有实例回复之前，往回复频道塞一条 nonce 不匹配、状态为 tenant_mismatch 的假 ack。
     * 若 nonce 过滤失效，调用方就会拿到 TENANT_MISMATCH；正确实现应忽略它并等到真 ack（成功）。</p>
     */
    @Test
    void ackForOtherNonceIsIgnored() throws Exception {
        FakeRedisPubSub pubsub = new FakeRedisPubSub();
        gateB = new Gate(options("B", Duration.ofSeconds(3)), new StubChecker(true), pubsub);
        gateA = new Gate(options("A", Duration.ofSeconds(3)), new StubChecker(true), pubsub);
        pubsub.awaitSubscribers(Gate.pubsubChannel(), 2, Duration.ofSeconds(2));

        RecordingEventBus bus = new RecordingEventBus();
        AtomicReference<ApprovalException> callerResult = new AtomicReference<>();
        AtomicReference<Boolean> callerDone = new AtomicReference<>(false);
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            String replyChannel = Gate.pubsubChannel() + ":reply:" + pendingId;
            // 独立线程：等 A 订阅上回复频道后，抢先塞入一条 nonce 不匹配的假 ack
            // （必须与调用 resolve 的线程分开——等待订阅的动作发生在 resolve 内部）
            Thread.ofVirtual().start(() -> {
                try {
                    pubsub.awaitSubscribers(replyChannel, 1, Duration.ofSeconds(5));
                    pubsub.publish(replyChannel, ApprovalJson.write(
                            ResolveAck.of(pendingId, ResolveAck.STATUS_TENANT_MISMATCH, "X", "foreign-nonce")));
                } catch (RuntimeException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.ofVirtual().start(() -> {
                callerResult.set(captureResolve(gateA, 1, "", pendingId, Decision.allow()));
                callerDone.set(true);
            });
        });

        Decision d = gateB.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).eventBus(bus).sessionId("s").assistantMessageId("m")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        awaitFlag(callerDone, Duration.ofSeconds(5));
        assertTrue(d.approved());
        assertNull(callerResult.get(), "别人 nonce 的 ack 必须被忽略，调用方应等到自己的 ack");
    }
}
