package com.ragagent.event;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件中间件（对照 Go {@code event} 包的 middleware.go 全文）。
 *
 * <ul>
 *   <li>{@link #withLogging()} ↔ {@code WithLogging()}：前置 info、失败 error、成功 debug，
 *       文案逐字对齐（type/session/request 字段）。</li>
 *   <li>{@link #withTiming()} ↔ {@code WithTiming()}：耗时（毫秒）写进 <b>event.metadata 的
 *       共享 map</b>——Go 实录确认调用方持有的 Event 能看到 {@code duration_ms}
 *       （结构体拷贝但 map 同引用）。</li>
 *   <li>{@link #withRecovery()} ↔ {@code WithRecovery()}：panic 转成
 *       {@link PanicError}（{@code panic in event handler: ...}）。Java 侧以
 *       {@code try/catch Throwable} 对应 Go 的 {@code defer/recover}。</li>
 *   <li>{@link #chain(EventMiddleware...)} ↔ {@code Chain}：<b>先列的在外层</b>
 *       （Go 反向 apply，实录执行序 {@code [first-in second-in core second-out first-out]}）。</li>
 *   <li>{@link #applyMiddleware(EventHandler, EventMiddleware...)} ↔ {@code ApplyMiddleware}。</li>
 * </ul>
 */
@FunctionalInterface
public interface EventMiddleware {

    /** 对照 Go {@code type Middleware func(EventHandler) EventHandler}（middleware.go:12）。 */
    EventHandler apply(EventHandler next);

    /** 对照 Go {@code WithLogging()}（middleware.go:15-32）。 */
    static EventMiddleware withLogging() {
        Logger log = LoggerFactory.getLogger(EventMiddleware.class);
        return next -> event -> {
            log.info("Event triggered: type={}, session={}, request={}",
                    event.getType(), event.getSessionId(), event.getRequestId());
            try {
                next.handle(event);
            } catch (Exception e) {
                log.error("Event handler error: type={}, error={}", event.getType(), e.toString());
                throw e;
            }
            log.debug("Event handled successfully: type={}", event.getType());
        };
    }

    /** 对照 Go {@code WithTiming()}（middleware.go:35-53）。 */
    static EventMiddleware withTiming() {
        Logger log = LoggerFactory.getLogger(EventMiddleware.class);
        return next -> event -> {
            long start = System.nanoTime();
            try {
                next.handle(event);
            } finally {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                log.debug("Event {} took {}", event.getType(), elapsed);
                // Go：耗时写进 metadata（nil 时先建 map）。map 跨值拷贝共享，调用方可见。
                Map<String, Object> metadata = event.getMetadata();
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                    event.setMetadata(metadata);
                }
                metadata.put("duration_ms", elapsed.toMillis());
            }
        };
    }

    /** 对照 Go {@code WithRecovery()}（middleware.go:56-69）。 */
    static EventMiddleware withRecovery() {
        Logger log = LoggerFactory.getLogger(EventMiddleware.class);
        return next -> event -> {
            try {
                next.handle(event);
            } catch (Throwable t) {
                // Go：defer recover → logger.Errorf + 返回 PanicError
                log.error("Event handler panic: type={}, panic={}", event.getType(), t.toString());
                throw new PanicError(t);
            }
        };
    }

    /**
     * 组合中间件（对照 Go {@code Chain}，middleware.go:81-89）：
     * 反向 apply，<b>列表中第一个成为最外层</b>。
     */
    static EventMiddleware chain(EventMiddleware... middlewares) {
        return handler -> {
            for (int i = middlewares.length - 1; i >= 0; i--) {
                handler = middlewares[i].apply(handler);
            }
            return handler;
        };
    }

    /** 对照 Go {@code ApplyMiddleware}（middleware.go:92-94）。 */
    static EventHandler applyMiddleware(EventHandler handler, EventMiddleware... middlewares) {
        return chain(middlewares).apply(handler);
    }
}
