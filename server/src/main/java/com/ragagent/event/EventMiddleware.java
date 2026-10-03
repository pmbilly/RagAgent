package com.ragagent.event;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件中间件。
 *
 * <ul>
 *   <li>{@link #withLogging()}：前置 info、失败 error、成功 debug
 *       （type/session/request 字段）。</li>
 *   <li>{@link #withTiming()}：耗时（毫秒）写进 <b>event.metadata 的共享 map</b>——
 *       调用方持有的 Event 能看到 {@code duration_ms}（浅拷贝共享同一 map）。</li>
 *   <li>{@link #withRecovery()}：panic 转成
 *       {@link PanicError}（{@code panic in event handler: ...}）。</li>
 *   <li>{@link #chain(EventMiddleware...)}：<b>先列的在外层</b>
 *       （执行序 {@code [first-in second-in core second-out first-out]}）。</li>
 *   <li>{@link #applyMiddleware(EventHandler, EventMiddleware...)}：组合后套用。</li>
 * </ul>
 */
@FunctionalInterface
public interface EventMiddleware {

    /** 中间件：包装并返回新的 handler。 */
    EventHandler apply(EventHandler next);

    /** 日志中间件：前置 info、失败 error、成功 debug。 */
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

    /** 计时中间件：耗时写入 metadata。 */
    static EventMiddleware withTiming() {
        Logger log = LoggerFactory.getLogger(EventMiddleware.class);
        return next -> event -> {
            long start = System.nanoTime();
            try {
                next.handle(event);
            } finally {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                log.debug("Event {} took {}", event.getType(), elapsed);
                // 耗时写进 metadata（null 时先建 map）。map 跨浅拷贝共享，调用方可见。
                Map<String, Object> metadata = event.getMetadata();
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                    event.setMetadata(metadata);
                }
                metadata.put("duration_ms", elapsed.toMillis());
            }
        };
    }

    /** 恢复中间件：panic 转 {@link PanicError}。 */
    static EventMiddleware withRecovery() {
        Logger log = LoggerFactory.getLogger(EventMiddleware.class);
        return next -> event -> {
            try {
                next.handle(event);
            } catch (Throwable t) {
                // 记日志并抛 PanicError
                log.error("Event handler panic: type={}, panic={}", event.getType(), t.toString());
                throw new PanicError(t);
            }
        };
    }

    /**
     * 组合中间件：反向 apply，<b>列表中第一个成为最外层</b>。
     */
    static EventMiddleware chain(EventMiddleware... middlewares) {
        return handler -> {
            for (int i = middlewares.length - 1; i >= 0; i--) {
                handler = middlewares[i].apply(handler);
            }
            return handler;
        };
    }

    /** 依次应用中间件。 */
    static EventHandler applyMiddleware(EventHandler handler, EventMiddleware... middlewares) {
        return chain(middlewares).apply(handler);
    }
}
