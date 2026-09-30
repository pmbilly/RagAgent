package com.ragagent.common.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.llm.ResponseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.ragagent.mcp.service.Adapter;

/**
 * 对照 Go internal/agent/approval/gate_test.go 的逐用例翻译
 * （stubChecker / TestGate_* / TestAdapter_IsEnabled）。
 *
 * <p>Go 的 goroutine 在 Java 侧用虚拟线程；Go 的 ctx 用 {@link Cancellation}；
 * Go 的 {@code require.*} 用 JUnit 断言。</p>
 */
@Timeout(20)
class GateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对照 Go {@code NewGate(&config.Config{Agent: &config.AgentConfig{ToolApprovalTimeoutSeconds: N}}, ...)} */
    private static GateOptions options(int timeoutSeconds) {
        return GateOptions.fromConfig(timeoutSeconds, true);
    }

    private static List<ResponseType> types(RecordingEventBus bus) {
        return bus.emitted().stream().map(Event::type).toList();
    }

    private static void assertJsonEq(String expected, String actual) {
        try {
            assertEquals(JSON.readTree(expected), JSON.readTree(actual));
        } catch (Exception e) {
            throw new AssertionError("invalid json: " + actual, e);
        }
    }

    /** 对照 Go 测试里 “在 Required 事件回调里起 goroutine 调 Resolve” 的写法 */
    private static void resolveOnRequired(RecordingEventBus bus, Gate gate, long tenantId, String userId, Decision d) {
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> gate.resolve(tenantId, userId, pendingId, d));
        });
    }

    private static ApprovalException captureResolve(Gate gate, long tenantId, String userId, String pendingId, Decision d) {
        try {
            gate.resolve(tenantId, userId, pendingId, d);
            return null;
        } catch (ApprovalException e) {
            return e;
        }
    }

    /**
     * 对照 Go TestGate_RequestAndWait_Approve：
     * Required 事件里 Resolve（带替换参数）→ 决策为批准且带 ModifiedArgs。
     */
    @Test
    void requestAndWaitApprove() {
        RecordingEventBus bus = new RecordingEventBus();
        Gate gate = new Gate(options(2), new StubChecker(true), null);

        AtomicReference<String> seenPendingId = new AtomicReference<>();
        AtomicReference<Integer> seenTimeoutSeconds = new AtomicReference<>();
        AtomicReference<String> seenArgsJson = new AtomicReference<>();
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            ToolApprovalRequiredData data = (ToolApprovalRequiredData) evt.data();
            assertFalse(data.pendingId().isEmpty());
            seenPendingId.set(data.pendingId());
            seenTimeoutSeconds.set(data.timeoutSeconds());
            seenArgsJson.set(data.argsJson());
            Thread.ofVirtual().start(() -> gate.resolve(1, "", data.pendingId(),
                    Decision.allowWith("{\"a\":2}")));
        });

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1)
                .sessionId("s1")
                .assistantMessageId("m1")
                .eventBus(bus)
                .serviceId("svc")
                .serviceName("svcname")
                .mcpToolName("danger_tool")
                .registeredToolName("mcp_svcname_danger_tool")
                .description("desc")
                .args("{\"a\":1}")
                .toolCallId("tc1")
                .build());

        assertTrue(d.approved());
        assertJsonEq("{\"a\":2}", d.modifiedArgs());
        assertFalse(d.timedOut());
        assertFalse(d.contextCanceled());
        assertNotNull(seenPendingId.get());
        assertEquals(2, seenTimeoutSeconds.get());
        assertEquals("{\"a\":1}", seenArgsJson.get());
        // 事件序列 required → resolved；resolved 事件体回带用户决策
        assertEquals(List.of(ResponseType.TOOL_APPROVAL_REQUIRED, ResponseType.TOOL_APPROVAL_RESOLVED),
                types(bus));
        ToolApprovalResolvedData resolved =
                (ToolApprovalResolvedData) bus.emitted().get(1).data();
        assertEquals(seenPendingId.get(), resolved.pendingId());
        assertTrue(resolved.approved());
    }

    /** 对照 Go TestGate_RequestAndWait_Timeout：无人 Resolve → 超时且不批准。 */
    @Test
    void requestAndWaitTimeout() {
        Gate gate = new Gate(options(1).withTimeout(Duration.ofMillis(200)), new StubChecker(true), null);
        RecordingEventBus bus = new RecordingEventBus();

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).sessionId("s1").assistantMessageId("m1").eventBus(bus)
                .serviceId("svc").serviceName("svcname").mcpToolName("t")
                .registeredToolName("mcp_svcname_t").args("{}").build());

        assertFalse(d.approved());
        assertTrue(d.timedOut());
        assertEquals("approval timeout", d.reason());
        // 超时同样会补发 resolved 事件（UI 要回放）
        assertEquals(List.of(ResponseType.TOOL_APPROVAL_REQUIRED, ResponseType.TOOL_APPROVAL_RESOLVED),
                types(bus));
    }

    /** 补充用例（Go 未覆盖）：ctx 取消 → ContextCanceled 决策，且取消后 Resolve 不再生效。 */
    @Test
    void requestAndWaitCancelled() {
        Gate gate = new Gate(options(30), new StubChecker(true), null);
        RecordingEventBus bus = new RecordingEventBus();
        TestCancellation cancellation = new TestCancellation();

        AtomicReference<String> pendingId = new AtomicReference<>();
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            pendingId.set(((ToolApprovalRequiredData) evt.data()).pendingId());
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cancellation.cancel();
            });
        });

        Decision d = gate.requestAndWait(cancellation, PendingRequest.builder()
                .tenantId(1).sessionId("s1").assistantMessageId("m1").eventBus(bus)
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertFalse(d.approved());
        assertTrue(d.contextCanceled());
        assertEquals("request canceled", d.reason());
        // 取消已投递 → 再 Resolve 必须报“已解决”（条目在 requestAndWait 返回前仍在表里）
        ApprovalException err = captureResolve(gate, 1, "", pendingId.get(), Decision.allow());
        assertTrue(err == null || err.is(ApprovalException.Kind.ALREADY_RESOLVED)
                || err.is(ApprovalException.Kind.PENDING_NOT_FOUND), String.valueOf(err));
    }

    /** 对照 Go TestGate_NeedsApproval_NoChecker */
    @Test
    void needsApprovalNoChecker() {
        Gate gate = new Gate((GateOptions) null, null, null);
        assertFalse(gate.needsApproval(Cancellation.none(), 1, "x", "y"));
    }

    /** 补充用例（Go 未覆盖）：策略查询异常时默认 fail-close（要求审批）。 */
    @Test
    void needsApprovalFailCloseOnCheckerError() {
        StubChecker checker = new StubChecker();
        checker.requiredError = new IllegalStateException("db down");
        Gate gate = new Gate(GateOptions.defaults().withFailClose(true), checker, null);
        assertTrue(gate.needsApproval(Cancellation.none(), 1, "svc", "tool"));
    }

    /** 补充用例（Go 未覆盖）：WEKNORA_AGENT_TOOL_APPROVAL_FAIL_OPEN=true 的 fail-open 行为。 */
    @Test
    void needsApprovalFailOpenOnCheckerError() {
        StubChecker checker = new StubChecker();
        checker.requiredError = new IllegalStateException("db down");
        Gate gate = new Gate(GateOptions.defaults().withFailClose(false), checker, null);
        assertFalse(gate.needsApproval(Cancellation.none(), 1, "svc", "tool"));
    }

    /** 身份缺失（tenant 0 / serviceId 空 / toolName 空）不进入审批门。 */
    @Test
    void needsApprovalFalseOnMissingIdentity() {
        Gate gate = new Gate(GateOptions.defaults(), new StubChecker(true), null);
        assertFalse(gate.needsApproval(Cancellation.none(), 0, "svc", "tool"));
        assertFalse(gate.needsApproval(Cancellation.none(), 1, "", "tool"));
        assertFalse(gate.needsApproval(Cancellation.none(), 1, "svc", ""));
    }

    /** 对照 Go TestGate_Resolve_NotFound */
    @Test
    void resolveNotFound() {
        Gate gate = new Gate(options(1), new StubChecker(true), null);
        ApprovalException err = captureResolve(gate, 1, "", "no-such-id", Decision.allow());
        assertNotNull(err);
        assertTrue(err.is(ApprovalException.Kind.PENDING_NOT_FOUND));
    }

    /** 对照 Go TestGate_Resolve_TenantMismatch：租户不符被拒，随后合法租户可正常决议。 */
    @Test
    void resolveTenantMismatch() {
        RecordingEventBus bus = new RecordingEventBus();
        Gate gate = new Gate(options(2), new StubChecker(true), null);

        AtomicReference<ApprovalException> mismatch = new AtomicReference<>();
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                mismatch.set(captureResolve(gate, 999, "", pendingId, Decision.allow()));
                gate.resolve(1, "", pendingId, Decision.deny("no"));
            });
        });

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).eventBus(bus).sessionId("s1").assistantMessageId("m1")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertFalse(d.approved());
        assertNotNull(mismatch.get());
        assertTrue(mismatch.get().is(ApprovalException.Kind.TENANT_MISMATCH));
    }

    /** 对照 Go TestGate_Resolve_UserMismatch：非会话属主不能决议。 */
    @Test
    void resolveUserMismatch() {
        RecordingEventBus bus = new RecordingEventBus();
        Gate gate = new Gate(options(2), new StubChecker(true), null);

        AtomicReference<ApprovalException> mismatch = new AtomicReference<>();
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                mismatch.set(captureResolve(gate, 1, "bob", pendingId, Decision.allow()));
                gate.resolve(1, "alice", pendingId, Decision.allow());
            });
        });

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).userId("alice").eventBus(bus)
                .sessionId("s1").assistantMessageId("m1")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertTrue(d.approved());
        assertNotNull(mismatch.get());
        assertTrue(mismatch.get().is(ApprovalException.Kind.USER_MISMATCH));
    }

    /**
     * 对照 Go TestGate_Resolve_EmptyUserIDRejectedWhenWaiterHasUser：
     * 空 userID 不再短路放行（fail-close），否则同租户的他人可冒名批准。
     */
    @Test
    void resolveEmptyUserIdRejectedWhenWaiterHasUser() {
        RecordingEventBus bus = new RecordingEventBus();
        Gate gate = new Gate(options(2), new StubChecker(true), null);

        AtomicReference<ApprovalException> mismatch = new AtomicReference<>();
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                mismatch.set(captureResolve(gate, 1, "", pendingId, Decision.allow()));
                gate.resolve(1, "alice", pendingId, Decision.deny("no"));
            });
        });

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).userId("alice").eventBus(bus)
                .sessionId("s1").assistantMessageId("m1")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertFalse(d.approved());
        assertNotNull(mismatch.get());
        assertTrue(mismatch.get().is(ApprovalException.Kind.USER_MISMATCH));
    }

    /** 对照 Go TestGate_Resolve_AlreadyResolvedAfterTimeout：超时返回后条目已删，再 Resolve 即 NotFound。 */
    @Test
    void resolveAlreadyResolvedAfterTimeout() throws Exception {
        Gate gate = new Gate(options(1).withTimeout(Duration.ofMillis(200)), new StubChecker(true), null);
        RecordingEventBus bus = new RecordingEventBus();

        AtomicReference<String> pendingId = new AtomicReference<>();
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> pendingId.set(((ToolApprovalRequiredData) evt.data()).pendingId()));

        AtomicReference<ApprovalException> lateResolve = new AtomicReference<>();
        Thread resolver = Thread.ofVirtual().start(() -> {
            try {
                // 等超时触发且 requestAndWait 的 finally 已删表（200ms 超时 + 余量）
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lateResolve.set(captureResolve(gate, 1, "", pendingId.get(), Decision.allow()));
        });

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).eventBus(bus).sessionId("s")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertTrue(d.timedOut());
        resolver.join();
        assertNotNull(lateResolve.get());
        assertTrue(lateResolve.get().is(ApprovalException.Kind.PENDING_NOT_FOUND));
    }

    /**
     * 对照 Go TestGate_Resolve_RaceWinsAlreadyResolved：
     * 同一 pending 的两次 Resolve，第一次成功；第二次必须是
     * 已解决或（条目已被删）不存在。
     */
    @Test
    void resolveRaceWinsAlreadyResolved() throws Exception {
        Gate gate = new Gate(options(30), new StubChecker(true), null);
        RecordingEventBus bus = new RecordingEventBus();

        AtomicReference<ApprovalException> first = new AtomicReference<>();
        AtomicReference<ApprovalException> second = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        bus.on(ResponseType.TOOL_APPROVAL_REQUIRED, evt -> {
            String pendingId = ((ToolApprovalRequiredData) evt.data()).pendingId();
            Thread.ofVirtual().start(() -> {
                first.set(captureResolve(gate, 1, "", pendingId, Decision.allow()));
                second.set(captureResolve(gate, 1, "", pendingId, Decision.deny("")));
                done.set(true);
            });
        });

        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).eventBus(bus).sessionId("s")
                .serviceId("svc").mcpToolName("t").args("{}").build());

        assertTrue(d.approved());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!done.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNull(first.get(), "first resolve must succeed");
        assertNotNull(second.get(), "second resolve must fail");
        assertTrue(second.get().is(ApprovalException.Kind.ALREADY_RESOLVED)
                        || second.get().is(ApprovalException.Kind.PENDING_NOT_FOUND),
                "unexpected error: " + second.get());
    }

    /** 对照 Go TestGate_IsEnabled_NoCheckerKeepsToolsOn */
    @Test
    void isEnabledNoCheckerKeepsToolsOn() {
        Gate gate = new Gate((GateOptions) null, null, null);
        assertTrue(gate.isEnabled(Cancellation.none(), 1, "svc", "tool"));
    }

    /** 对照 Go TestGate_IsEnabled_MissingTenantFailClosed */
    @Test
    void isEnabledMissingTenantFailClosed() {
        Gate gate = new Gate((GateOptions) null, new StubChecker(), null);
        assertFalse(gate.isEnabled(Cancellation.none(), 0, "svc", "tool"));
    }

    /** 对照 Go TestGate_IsEnabled_HonorsChecker */
    @Test
    void isEnabledHonorsChecker() {
        Gate gate = new Gate((GateOptions) null, StubChecker.enabled(false), null);
        assertFalse(gate.isEnabled(Cancellation.none(), 1, "svc", "tool"));
    }

    /** 对照 Go TestGate_IsEnabled_CheckerErrorPropagates */
    @Test
    void isEnabledCheckerErrorPropagates() {
        StubChecker checker = new StubChecker();
        checker.enabledError = new IllegalStateException("deadline exceeded");
        Gate gate = new Gate((GateOptions) null, checker, null);
        assertThrows(IllegalStateException.class, () -> gate.isEnabled(Cancellation.none(), 1, "svc", "tool"));
    }

    /** 对照 Go TestAdapter_IsEnabled */
    @Test
    void adapterIsEnabled() {
        Adapter adapter = new Adapter(StubChecker.enabled(false));
        assertFalse(adapter.isEnabled(Cancellation.none(), 1, "svc", "tool"));

        Adapter empty = new Adapter(null);
        assertTrue(empty.isEnabled(Cancellation.none(), 1, "svc", "tool"));
        assertFalse(empty.isRequired(Cancellation.none(), 1, "svc", "tool"));
    }

    /** 补充用例：checker 为 null 时 requestAndWait 直接放行（Go: g.checker == nil → Approved）。 */
    @Test
    void requestAndWaitWithoutCheckerApprovesImmediately() {
        Gate gate = new Gate((GateOptions) null, null, null);
        Decision d = gate.requestAndWait(Cancellation.none(), PendingRequest.builder()
                .tenantId(1).sessionId("s").serviceId("svc").mcpToolName("t").build());
        assertTrue(d.approved());
    }

    /** 补充用例：EventBus 缺失是内部错误（Go: "tool approval: EventBus is nil"）。 */
    @Test
    void requestAndWaitWithoutEventBusFails() {
        Gate gate = new Gate(options(1), new StubChecker(true), null);
        ApprovalException err = assertThrows(ApprovalException.class, () -> gate.requestAndWait(
                Cancellation.none(),
                PendingRequest.builder().tenantId(1).sessionId("s").serviceId("svc").mcpToolName("t").build()));
        assertTrue(err.is(ApprovalException.Kind.INTERNAL));
        assertTrue(err.getMessage().contains("EventBus is nil"));
    }

    /** 补充用例：emit 失败必须上抛（Go 包装为 "emit tool approval required: ..."）。 */
    @Test
    void requestAndWaitEmitFailurePropagates() {
        Gate gate = new Gate(options(1), new StubChecker(true), null);
        EventBus failing = event -> {
            throw new IllegalStateException("sse closed");
        };
        ApprovalException err = assertThrows(ApprovalException.class, () -> gate.requestAndWait(
                Cancellation.none(),
                PendingRequest.builder().tenantId(1).sessionId("s").eventBus(failing)
                        .serviceId("svc").mcpToolName("t").build()));
        assertTrue(err.is(ApprovalException.Kind.INTERNAL));
    }

    /** 对照 Go RequestOAuthAndWait：授权成功 → Approved=true（工具调用应被重试）。 */
    @Test
    void requestOAuthAndWaitAuthorized() {
        RecordingEventBus bus = new RecordingEventBus();
        Gate gate = new Gate(options(30), new StubChecker(false), null);

        AtomicReference<McpOauthRequiredData> required = new AtomicReference<>();
        bus.on(ResponseType.MCP_OAUTH_REQUIRED, evt -> {
            McpOauthRequiredData data = (McpOauthRequiredData) evt.data();
            required.set(data);
            Thread.ofVirtual().start(() -> gate.resolve(1, "alice", data.pendingId(), Decision.allow()));
        });

        Decision d = gate.requestOAuthAndWait(Cancellation.none(), OAuthPendingRequest.builder()
                .tenantId(1).userId("alice").sessionId("s").assistantMessageId("m")
                .eventBus(bus).serviceId("svc").serviceName("svcname").mcpToolName("t")
                .waitTimeout(Duration.ofSeconds(5)).build());

        assertTrue(d.approved());
        assertNotNull(required.get());
        // WaitTimeout 覆盖后再转成秒（至少 1）
        assertEquals(5, required.get().timeoutSeconds());
        assertEquals(List.of(ResponseType.MCP_OAUTH_REQUIRED, ResponseType.MCP_OAUTH_RESOLVED), types(bus));
        McpOauthResolvedData resolved = (McpOauthResolvedData) bus.emitted().get(1).data();
        assertTrue(resolved.authorized());
        assertEquals("svc", resolved.serviceId());
    }

    /** 对照 Go RequestOAuthAndWait：无人授权 → 授权超时。 */
    @Test
    void requestOAuthAndWaitTimeout() {
        RecordingEventBus bus = new RecordingEventBus();
        Gate gate = new Gate(options(30), new StubChecker(false), null);

        Decision d = gate.requestOAuthAndWait(Cancellation.none(), OAuthPendingRequest.builder()
                .tenantId(1).userId("alice").sessionId("s").eventBus(bus)
                .serviceId("svc").mcpToolName("t")
                .waitTimeout(Duration.ofMillis(200)).build());

        assertFalse(d.approved());
        assertTrue(d.timedOut());
        assertEquals("authorization timeout", d.reason());
    }

    /** 补充用例：未配 Redis 时，不存在的 pending 直接 NotFound（单实例/Lite 行为）。 */
    @Test
    void resolveWithoutRedisIsSingleInstance() {
        Gate gate = new Gate(options(1), new StubChecker(true), null);
        ApprovalException err = captureResolve(gate, 1, "", "missing", Decision.allow());
        assertTrue(err != null && err.is(ApprovalException.Kind.PENDING_NOT_FOUND));
    }
}
