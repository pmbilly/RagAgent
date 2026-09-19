package com.ragagent.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件总线（对照 Go {@code event.EventBus}，internal/event/event.go:107-267，逐方法翻译）。
 *
 * <h2>语义（全部有 /tmp Go 实录钉住，见 EventBusTest）</h2>
 * <ul>
 *   <li><b>同步模式（默认，{@code new EventBus()}）</b>：按注册顺序执行；任一 handler
 *       抛异常 → 立即中断链，抛 {@link EventBusException}
 *       （{@code event handler failed for <type>: <原因>}），后续 handler 不再执行
 *       （实录：h3 未被调用）。无订阅者时静默成功（Go 返回 nil）。</li>
 *   <li><b>ID 自动生成是值语义</b>：发射在 {@link Event#shallowCopy()} 上进行——
 *       handler 看到补出的 UUID，调用方的 Event 对象不被写回（实录：callerStillEmpty=true）；
 *       显式传入的 ID 原样保留。metadata map 跨拷贝共享（Go 结构体拷贝语义）。</li>
 *   <li><b>同步 panic</b>：Go 的同步 Emit 不 recover，panic 冒到调用方。Java 侧把
 *       {@link Error} 视为 Go panic 等价物原样冒出；handler 的 Exception 一律按 Go 的
 *       error 返回值处理（包装并中断）。这是 Go panic/error 二元性在 Java 异常体系下的
 *       最贴近映射，见 {@link EventHandler} 注释。</li>
 *   <li><b>异步模式（{@link #EventBus(boolean)} async=true）</b>：每个 handler 一个
 *       虚拟线程并发执行，发射立即返回；Throwable 一律隔离并记日志（Go：recover 后
 *       Errorf；error 被 {@code _ =} 丢弃——两者在 goroutine 里都不外泄，Java 合并处理）。
 *       跨线程经 {@link TenantContextSnapshot} 显式传值。</li>
 *   <li><b>{@link #emitAndWait}</b>：两模式下都并发执行全部 handler 并等齐（实录：
 *       barrier 证明三个 handler 并发）；单 handler 的 panic 转 error
 *       （{@code event handler panic (type=...): ...}），最终包成
 *       {@code event handler failed for ...}（实录文案逐字）。多个错误时取其一（Go 按
 *       channel 到达序，本身非确定）。</li>
 * </ul>
 *
 * <p>Go 的 {@code sync.RWMutex} → {@link ReentrantReadWriteLock}（§1 技术栈映射）；
 * goroutine → 虚拟线程（{@code Thread.ofVirtual()}）。无 {@code context.Context} 参数
 * （约定 §5）；同步模式 handler 跑在调用线程，TenantContext 天然可见。</p>
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();
    private final Map<String, List<EventHandler>> handlers = new java.util.HashMap<>();
    private final boolean asyncMode;

    /** 对照 Go {@code NewEventBus}：同步模式。 */
    public EventBus() {
        this(false);
    }

    /** 对照 Go {@code NewAsyncEventBus}（async=true）/ {@code NewEventBus}（async=false）。 */
    public EventBus(boolean async) {
        this.asyncMode = async;
    }

    /**
     * 注册 handler；同一事件类型可注册多个，按注册顺序执行
     * （对照 Go {@code On}，event.go:132-137）。
     */
    public void on(String eventType, EventHandler handler) {
        mu.writeLock().lock();
        try {
            handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
        } finally {
            mu.writeLock().unlock();
        }
    }

    /** 移除该事件类型的全部 handler（对照 Go {@code Off}，event.go:140-145）。 */
    public void off(String eventType) {
        mu.writeLock().lock();
        try {
            handlers.remove(eventType);
        } finally {
            mu.writeLock().unlock();
        }
    }

    /**
     * 发布事件（对照 Go {@code Emit}，event.go:150-189）。
     *
     * <p>ID 为空时在浅拷贝上补 UUID；无订阅者静默返回；同步模式顺序执行、失败即断链；
     * 异步模式立即返回。失败抛 {@link EventBusException}（Go：返回 error）。</p>
     */
    public void emit(Event event) {
        // Go 值语义：补 ID 的写入不落回调用方
        Event copy = event == null ? new Event() : event.shallowCopy();
        if (copy.getId().isEmpty()) {
            copy.setId(Event.newUuid());
        }

        mu.readLock().lock();
        List<EventHandler> list;
        try {
            list = handlers.get(copy.getType());
        } finally {
            mu.readLock().unlock();
        }
        if (list == null || list.isEmpty()) {
            return; // 无订阅者：Go 返回 nil
        }
        // 快照一份，避免持锁回调（Go 在 RLock 下取 slice 后即解锁）
        List<EventHandler> snapshot = List.copyOf(list);

        if (asyncMode) {
            // Async mode: fire and forget（Go event.go:165-179）
            TenantContextSnapshot ctx = TenantContextSnapshot.capture();
            for (EventHandler handler : snapshot) {
                Thread.ofVirtual().start(() -> {
                    TenantContextSnapshot saved = TenantContextSnapshot.capture();
                    ctx.replay();
                try {
                    handler.handle(copy);
                } catch (Exception e) {
                    // Go：`_ = h(ctx, event)`——异步模式下 error 被静默丢弃（event.go:175）
                } catch (Throwable t) {
                    // Go：recover → logger.Errorf（panic 路径，event.go:170-174）
                    log.error("event handler panic recovered (type={}): {}", copy.getType(),
                            t.toString(), t);
                } finally {
                    saved.replay(); // 恢复工作线程原有上下文（虚拟线程复用场景）
                }
                });
            }
            return;
        }

        // Sync mode: execute handlers sequentially（Go event.go:182-186）
        for (EventHandler handler : snapshot) {
            try {
                handler.handle(copy);
            } catch (Exception e) {
                throw EventBusException.wrap(copy.getType(), e);
            }
            // Error 及其他非 Exception 的 Throwable 原样冒出——对照 Go 同步路径
            // 不 recover panic 的行为
        }
    }

    /**
     * 发布事件并等待全部 handler 完成（对照 Go {@code EmitAndWait}，event.go:194-239）。
     * 两种模式下 handler 都<b>并发</b>执行（每个一个虚拟线程）。
     */
    public void emitAndWait(Event event) {
        Event copy = event == null ? new Event() : event.shallowCopy();
        if (copy.getId().isEmpty()) {
            copy.setId(Event.newUuid());
        }

        mu.readLock().lock();
        List<EventHandler> list;
        try {
            list = handlers.get(copy.getType());
        } finally {
            mu.readLock().unlock();
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        List<EventHandler> snapshot = List.copyOf(list);

        // Go：sync.WaitGroup + buffered errChan（event.go:208-229）。
        // Go 区分两条失败路径：handler 返回 error（原样入 channel）与 panic
        // （recover 后转 "event handler panic (type=...)" 再入 channel）。Java 侧以
        // Exception ↔ Go error、Error/其他 Throwable ↔ Go panic 对应。
        CountDownLatch done = new CountDownLatch(snapshot.size());
        List<Failure> failures = new CopyOnWriteArrayList<>();
        TenantContextSnapshot ctx = TenantContextSnapshot.capture();

        for (EventHandler handler : snapshot) {
            Thread.ofVirtual().start(() -> {
                TenantContextSnapshot saved = TenantContextSnapshot.capture();
                ctx.replay();
                try {
                    handler.handle(copy);
                } catch (Exception e) {
                    failures.add(new Failure(e, false));
                } catch (Throwable t) {
                    failures.add(new Failure(t, true));
                } finally {
                    saved.replay();
                    done.countDown();
                }
            });
        }

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventBusException("event handler failed for " + copy.getType()
                    + ": interrupted while waiting for handlers", e);
        }

        for (Failure f : failures) {
            if (f.panic()) {
                Throwable t = f.cause();
                String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                throw EventBusException.wrap(copy.getType(),
                        new Throwable("event handler panic (type=" + copy.getType() + "): " + reason, t));
            }
            throw EventBusException.wrap(copy.getType(), f.cause());
        }
    }

    /** EmitAndWait 的一条失败记录（panic 标记区分 Go 的 panic/error 两条路径）。 */
    private record Failure(Throwable cause, boolean panic) {
    }

    /** 是否存在该事件类型的订阅（对照 Go {@code HasHandlers}，event.go:242-248）。 */
    public boolean hasHandlers(String eventType) {
        mu.readLock().lock();
        try {
            List<EventHandler> list = handlers.get(eventType);
            return list != null && !list.isEmpty();
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 该事件类型的 handler 数（对照 Go {@code GetHandlerCount}，event.go:251-259）。 */
    public int getHandlerCount(String eventType) {
        mu.readLock().lock();
        try {
            List<EventHandler> list = handlers.get(eventType);
            return list == null ? 0 : list.size();
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 清空全部 handler（对照 Go {@code Clear}，event.go:262-267）。 */
    public void clear() {
        mu.writeLock().lock();
        try {
            handlers.clear();
        } finally {
            mu.writeLock().unlock();
        }
    }

    /**
     * 对照 Go {@code EventBus.AsEventBusInterface()}（adapter.go:57-59）：
     * 以 {@code types.EventBusInterface} 的形状暴露本总线。见 {@link EventBusAdapter}。
     */
    public EventBusInterface asEventBusInterface() {
        return new EventBusAdapter(this);
    }
}
