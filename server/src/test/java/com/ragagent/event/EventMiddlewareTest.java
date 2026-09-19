package com.ragagent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 中间件行为断言（对照 Go middleware.go；执行序与文案有 /tmp Go 实录钉住）。
 */
class EventMiddlewareTest {

    @Test
    void chainAppliesFirstListedAsOutermost() throws Exception {
        // Go 实录：chainOrder => [first-in second-in core second-out first-out]
        List<String> order = new ArrayList<>();
        EventMiddleware first = next -> event -> {
            order.add("first-in");
            try {
                next.handle(event);
            } finally {
                order.add("first-out");
            }
        };
        EventMiddleware second = next -> event -> {
            order.add("second-in");
            try {
                next.handle(event);
            } finally {
                order.add("second-out");
            }
        };
        EventHandler h = EventMiddleware.applyMiddleware(
                event -> order.add("core"), first, second);
        h.handle(new Event("", "evt", "", null, null, ""));
        assertEquals(List.of("first-in", "second-in", "core", "second-out", "first-out"), order);
    }

    @Test
    void withRecoveryConvertsPanicToPanicError() {
        // Go 实录：panicError => "panic in event handler: recovered-panic"
        EventHandler h = EventMiddleware.withRecovery().apply(event -> {
            throw new IllegalStateException("recovered-panic");
        });
        PanicError err = assertThrows(PanicError.class,
                () -> h.handle(new Event("", "evt", "", null, null, "")));
        assertEquals("panic in event handler: recovered-panic", err.getMessage());
    }

    @Test
    void withRecoveryPassesSuccessThrough() throws Exception {
        List<String> calls = new ArrayList<>();
        EventHandler h = EventMiddleware.withRecovery().apply(event -> calls.add("ok"));
        h.handle(new Event("", "evt", "", null, null, ""));
        assertEquals(List.of("ok"), calls);
    }

    @Test
    void withTimingWritesDurationIntoSharedMetadata() throws Exception {
        // Go 实录：timingSharedMetadata => callerSees=5——metadata map 跨值拷贝共享
        EventHandler h = EventMiddleware.withTiming().apply(event -> {
        });
        Event event = new Event("", "evt", "", null, new java.util.LinkedHashMap<>(), "");
        h.handle(event);
        Object ms = event.getMetadata().get("duration_ms");
        assertInstanceOf(Long.class, ms);
        assertTrue((Long) ms >= 0);
    }

    @Test
    void withTimingCreatesMetadataWhenAbsent() throws Exception {
        // Go：event.Metadata == nil 时先 make 再写入
        EventHandler h = EventMiddleware.withTiming().apply(event -> {
        });
        Event event = new Event("", "evt", "", null, null, "");
        h.handle(event);
        assertTrue(event.getMetadata() != null && event.getMetadata().containsKey("duration_ms"));
    }

    @Test
    void withTimingPreservesHandlerException() {
        EventHandler h = EventMiddleware.withTiming().apply(event -> {
            throw new IllegalStateException("boom");
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> h.handle(new Event("", "evt", "", null, null, "")));
        assertEquals("boom", ex.getMessage());
    }

    @Test
    void withLoggingPassesResultThrough() throws Exception {
        // Go 的 WithLogging 返回原 error / 原成功——Java 侧异常透传（日志内容不作为契约断言）
        List<String> calls = new ArrayList<>();
        EventHandler ok = EventMiddleware.withLogging().apply(event -> calls.add("ok"));
        ok.handle(new Event("", "evt", "s-1", null, null, "r-1"));
        assertEquals(List.of("ok"), calls);

        EventHandler failing = EventMiddleware.withLogging().apply(event -> {
            throw new IllegalStateException("handler err");
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> failing.handle(new Event("", "evt", "s-1", null, null, "r-1")));
        assertEquals("handler err", ex.getMessage());
    }

    @Test
    void chainEqualsApplyMiddleware() throws Exception {
        // Go：ApplyMiddleware(handler, mws...) = Chain(mws...)(handler)
        List<String> order = new ArrayList<>();
        EventMiddleware mw = next -> event -> {
            order.add("mw");
            next.handle(event);
        };
        EventHandler core = event -> order.add("core");
        EventMiddleware.chain(mw).apply(core).handle(new Event("", "evt", "", null, null, ""));
        EventMiddleware.applyMiddleware(core, mw).handle(new Event("", "evt", "", null, null, ""));
        assertEquals(List.of("mw", "core", "mw", "core"), order);
    }

    @Test
    void recoveryErrorInsideEmitIsWrappedLikeGo() {
        // Go 组合：WithRecovery 返回的 PanicError 经 Emit 包装 =>
        // "event handler failed for evt: panic in event handler: kaboom"
        EventBus bus = new EventBus();
        bus.on("evt", EventMiddleware.withRecovery().apply(event -> {
            throw new IllegalArgumentException("kaboom");
        }));
        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.emit(new Event("", "evt", "", null, null, "")));
        assertEquals("event handler failed for evt: panic in event handler: kaboom",
                ex.getMessage());
        assertInstanceOf(PanicError.class, ex.getCause());
    }
}
