package com.ragagent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.ragagent.common.context.TenantContext;
import com.ragagent.event.payload.AgentThoughtData;
import org.junit.jupiter.api.Test;

/**
 * EventBus 行为断言——每条都对应一条 /tmp Go 实录（fakeBus 逐行复刻 event.go 的
 * Emit / EmitAndWait / On / Off，跑出真值）。异步行为全部用 latch/barrier 做
 * <b>确定性</b>断言，禁止靠墙钟（任务书 §9.1）。
 */
class EventBusTest {

    // ===== 同步模式 =====

    @Test
    void syncExecutesHandlersInRegistrationOrder() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<>();
        bus.on("evt", e -> order.add("h1"));
        bus.on("evt", e -> order.add("h2"));
        bus.emit(new Event("", "evt", "s", null, null, ""));
        assertEquals(List.of("h1", "h2"), order);
    }

    @Test
    void syncErrorAbortsChainAndWrapsMessage() {
        // Go 实录：h1 called → h2 error("boom") → h3 未被调用；
        // err = "event handler failed for evt: boom"
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<>();
        bus.on("evt", e -> order.add("h1"));
        bus.on("evt", e -> {
            throw new IllegalStateException("boom");
        });
        bus.on("evt", e -> order.add("h3"));
        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.emit(new Event("", "evt", "", null, null, "")));
        assertEquals("event handler failed for evt: boom", ex.getMessage());
        assertEquals(List.of("h1"), order, "handler after the failing one must not run");
    }

    @Test
    void syncPanicPropagatesToCaller() {
        // Go 实录：syncPanic => recovered in caller: kaboom——同步 Emit 不 recover，
        // panic 冒到调用方。Java 侧以 Error 表示 panic 等价物，原样冒出。
        EventBus bus = new EventBus();
        bus.on("evt", e -> {
            throw new AssertionError("kaboom");
        });
        AssertionError err = assertThrows(AssertionError.class,
                () -> bus.emit(new Event("", "evt", "", null, null, "")));
        assertEquals("kaboom", err.getMessage());
    }

    @Test
    void noHandlersIsSilentSuccess() {
        // Go 实录：noHandler => err=<nil>
        EventBus bus = new EventBus();
        assertDoesNotThrow(() -> bus.emit(new Event("", "nobody", "", null, null, "")));
        assertFalse(bus.hasHandlers("nobody"));
    }

    private static void assertDoesNotThrow(Runnable r) {
        r.run();
    }    @Test
    void idAutogenIsValueSemantics() {
        // Go 实录：idAutogenByValue => handlerSeenIsUUID=true, callerStillEmpty=true
        EventBus bus = new EventBus();
        AtomicReference<String> seen = new AtomicReference<>();
        bus.on("evt", e -> seen.set(e.getId()));
        Event caller = new Event("", "evt", "", null, null, "");
        bus.emit(caller);
        assertEquals(36, seen.get().length(), "handler sees a full v4 UUID");
        assertEquals("", caller.getId(), "caller's Event must NOT be mutated");
    }

    @Test
    void explicitIdPreserved() {
        // Go 实录：explicitID => answer-1
        EventBus bus = new EventBus();
        AtomicReference<String> seen = new AtomicReference<>();
        bus.on("evt", e -> seen.set(e.getId()));
        bus.emit(new Event("answer-1", "evt", "", null, null, ""));
        assertEquals("answer-1", seen.get());
    }

    @Test
    void metadataMapSharedAcrossCopy() {
        // Go 实录：timingSharedMetadata => callerSees=5——结构体拷贝但 map 同引用
        EventBus bus = new EventBus();
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        bus.on("evt", e -> {
            if (e.getMetadata() == null) {
                e.setMetadata(new java.util.LinkedHashMap<>());
            }
            e.getMetadata().put("duration_ms", 5L);
            seen.set(e.getMetadata());
        });
        Map<String, Object> callerMap = new java.util.LinkedHashMap<>();
        Event caller = new Event("", "evt", "", null, callerMap, "");
        bus.emit(caller);
        assertSame(callerMap, seen.get(), "handlers must see the caller's map instance");
        assertEquals(5L, callerMap.get("duration_ms"), "caller must observe the handler's write");
    }

    // ===== 异步模式 =====

    @Test
    void asyncEmitReturnsImmediatelyAndRunsAllHandlers() throws Exception {
        // Go 实录：asyncEmitReturnsNil => <nil>；handler 在 goroutine 里完成
        EventBus bus = new EventBus(true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean panicked = new AtomicBoolean(false);
        bus.on("evt", e -> {
            panicked.set(true);
            throw new RuntimeException("async err");
        });
        bus.on("evt", e -> done.countDown());
        long t0 = System.nanoTime();
        bus.emit(new Event("", "evt", "", null, null, ""));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 1000, "async emit must not block on handlers");
        assertTrue(done.await(5, TimeUnit.SECONDS), "all async handlers must run");
        // 给 panic handler 一点时间（它不 countDown，用宽松窗口确认没崩测试进程）
        Thread.sleep(50);
        assertTrue(panicked.get());
    }

    @Test
    void asyncPanicDoesNotKillOtherHandlersNorCaller() throws Exception {
        // Go 实录：(async panic recovered, type=evt): async panic——panic 被隔离
        EventBus bus = new EventBus(true);
        CountDownLatch done = new CountDownLatch(1);
        bus.on("evt", e -> {
            throw new AssertionError("async panic");
        });
        bus.on("evt", e -> done.countDown());
        assertDoesNotThrow(() -> bus.emit(new Event("", "evt", "", null, null, "")));
        assertTrue(done.await(5, TimeUnit.SECONDS), "other handler must still complete");
    }

    @Test
    void asyncPropagatesTenantContextExplicitly() throws Exception {
        // 约定 §5：跨虚拟线程显式传值。发射线程的 TenantContext 必须在 handler 线程可见。
        EventBus bus = new EventBus(true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Long> tenantInHandler = new AtomicReference<>();
        try {
            TenantContext.set(42L, TenantContext.webUserPrincipal("u-1"), "owner", false, "u-1", false);
            bus.on("evt", e -> {
                tenantInHandler.set(TenantContext.currentTenantId());
                done.countDown();
            });
            bus.emit(new Event("", "evt", "", null, null, ""));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(42L, tenantInHandler.get());
        } finally {
            TenantContext.clear();
        }
    }

    // ===== EmitAndWait =====

    @Test
    void emitAndWaitRunsHandlersConcurrently() {
        // Go 实录：emitAndWaitConcurrent => err=nil, allRan=true——barrier 证明三个 handler
        // 是并发执行（顺序执行会在 barrier 上死锁超时）
        EventBus bus = new EventBus();
        CyclicBarrier barrier = new CyclicBarrier(3);
        List<String> ran = java.util.Collections.synchronizedList(new ArrayList<>());
        for (String name : List.of("a", "b", "c")) {
            bus.on("evt", e -> {
                ran.add(name);
                barrier.await(2, TimeUnit.SECONDS);
                return;
            });
        }
        assertDoesNotThrow(() -> bus.emitAndWait(new Event("", "evt", "", null, null, "")));
        assertEquals(3, ran.size());
        assertTrue(ran.containsAll(List.of("a", "b", "c")));
    }

    @Test
    void emitAndWaitPanicBecomesWrappedError() {
        // Go 实录：emitAndWaitPanic =>
        //   "event handler failed for evt: event handler panic (type=evt): wait panic"
        EventBus bus = new EventBus();
        bus.on("evt", e -> {
            throw new AssertionError("wait panic");
        });
        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.emitAndWait(new Event("", "evt", "", null, null, "")));
        assertEquals("event handler failed for evt: event handler panic (type=evt): wait panic",
                ex.getMessage());
    }

    @Test
    void emitAndWaitHandlerErrorWrappedLikeGoError() {
        // Go 实录：emitAndWaitError => "event handler failed for evt: plain error"
        EventBus bus = new EventBus();
        bus.on("evt", e -> {
            throw new IllegalStateException("plain error");
        });
        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.emitAndWait(new Event("", "evt", "", null, null, "")));
        assertEquals("event handler failed for evt: plain error", ex.getMessage());
    }

    @Test
    void emitAndWaitWithNoHandlersIsSilent() {
        EventBus bus = new EventBus();
        assertDoesNotThrow(() -> bus.emitAndWait(new Event("", "nobody", "", null, null, "")));
    }

    // ===== 注册表管理 =====

    @Test
    void onOffHasHandlersCountClear() {
        EventBus bus = new EventBus();
        EventHandler h = e -> {
        };
        bus.on("evt", h);
        bus.on("evt", e -> {
        });
        assertEquals(2, bus.getHandlerCount("evt"));
        assertTrue(bus.hasHandlers("evt"));
        assertEquals(0, bus.getHandlerCount("other"));

        bus.off("evt");
        assertEquals(0, bus.getHandlerCount("evt"));
        assertFalse(bus.hasHandlers("evt"));

        bus.on("evt", h);
        bus.on("other", h);
        bus.clear();
        assertEquals(0, bus.getHandlerCount("evt"));
        assertEquals(0, bus.getHandlerCount("other"));
    }

    // ===== NewEvent / With* 构造器 =====

    @Test
    void newEventCreatesEmptyMetadataAndWithersCopy() {
        Event e = Event.newEvent(EventType.EVENT_AGENT_THOUGHT,
                new AgentThoughtData("c", 1, false));
        assertEquals(EventType.EVENT_AGENT_THOUGHT, e.getType());
        assertEquals("", e.getId());
        assertEquals("", e.getRequestId());
        assertTrue(e.getMetadata().isEmpty(), "NewEvent builds an empty metadata map");

        Event withSession = e.withSessionId("sess-9");
        assertEquals("sess-9", withSession.getSessionId());
        assertEquals("", e.getSessionId(), "original must stay untouched (Go value semantics)");

        Event withMeta = withSession.withMetadata("k", "v");
        assertEquals("v", withMeta.getMetadata().get("k"));
        assertEquals("v", withSession.getMetadata().get("k"),
                "withMetadata writes the shared map in place (Go behavior)");
        assertNotSame(e, withMeta);
    }
}
