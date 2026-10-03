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
 * 事件总线。
 *
 * <h2>语义（行为均有测试钉住，见 EventBusTest）</h2>
 * <ul>
 *   <li><b>同步模式（默认，{@code new EventBus()}）</b>：按注册顺序执行；任一 handler
 *       抛异常 → 立即中断链，抛 {@link EventBusException}
 *       （{@code event handler failed for <type>: <原因>}），后续 handler 不再执行。
 *       无订阅者时静默成功。</li>
 *   <li><b>ID 自动生成是值语义</b>：发射在 {@link Event#shallowCopy()} 上进行——
 *       handler 看到补出的 UUID，调用方的 Event 对象不被写回；
 *       显式传入的 ID 原样保留。metadata map 跨拷贝共享。</li>
 *   <li><b>同步 panic</b>：{@link Error} 原样冒到调用方；handler 的 Exception
 *       一律视为处理失败（包装并中断链）。这是"处理失败 / panic"二元性在 Java
 *       异常体系下的映射，见 {@link EventHandler} 注释。</li>
 *   <li><b>异步模式（{@link #EventBus(boolean)} async=true）</b>：每个 handler 一个
 *       虚拟线程并发执行，发射立即返回；Exception 静默丢弃，其余 Throwable 记日志，
 *       两者都不外泄到调用方。跨线程经 {@link TenantContextSnapshot} 显式传值。</li>
 *   <li><b>{@link #emitAndWait}</b>：两模式下都并发执行全部 handler 并等齐；单 handler
 *       的 panic 包装为 {@code event handler panic (type=...): ...}，最终包成
 *       {@code event handler failed for ...}。多个错误时取其一（完成序，本身非确定）。</li>
 * </ul>
 *
 * <p>并发实现：{@link ReentrantReadWriteLock} 保护订阅表，异步 handler 跑在虚拟线程
 * （{@code Thread.ofVirtual()}）上。无 context 参数；同步模式 handler 跑在调用线程，
 * TenantContext 天然可见。</p>
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();
    private final Map<String, List<EventHandler>> handlers = new java.util.HashMap<>();
    private final boolean asyncMode;

    /** 创建同步模式总线。 */
    public EventBus() {
        this(false);
    }

    /** async=true 为异步模式，false 为同步模式。 */
    public EventBus(boolean async) {
        this.asyncMode = async;
    }

    /**
     * 注册 handler；同一事件类型可注册多个，按注册顺序执行。
     */
    public void on(String eventType, EventHandler handler) {
        mu.writeLock().lock();
        try {
            handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
        } finally {
            mu.writeLock().unlock();
        }
    }

    /** 移除该事件类型的全部 handler。 */
    public void off(String eventType) {
        mu.writeLock().lock();
        try {
            handlers.remove(eventType);
        } finally {
            mu.writeLock().unlock();
        }
    }

    /**
     * 发布事件。
     *
     * <p>ID 为空时在浅拷贝上补 UUID；无订阅者静默返回；同步模式顺序执行、失败即断链；
     * 异步模式立即返回。失败抛 {@link EventBusException}。</p>
     */
    public void emit(Event event) {
        // 补 ID 的写入不落回调用方
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
            return; // 无订阅者：静默成功
        }
        // 快照一份，避免持锁回调
        List<EventHandler> snapshot = List.copyOf(list);

        if (asyncMode) {
            // Async mode: fire and forget
            TenantContextSnapshot ctx = TenantContextSnapshot.capture();
            for (EventHandler handler : snapshot) {
                Thread.ofVirtual().start(() -> {
                    TenantContextSnapshot saved = TenantContextSnapshot.capture();
                    ctx.replay();
                try {
                    handler.handle(copy);
                } catch (Exception e) {
                    // 异步模式下处理失败（Exception）被静默丢弃
                } catch (Throwable t) {
                    // panic 路径：记日志，不外泄
                    log.error("event handler panic recovered (type={}): {}", copy.getType(),
                            t.toString(), t);
                } finally {
                    saved.replay(); // 恢复工作线程原有上下文（虚拟线程复用场景）
                }
                });
            }
            return;
        }

        // Sync mode: execute handlers sequentially
        for (EventHandler handler : snapshot) {
            try {
                handler.handle(copy);
            } catch (Exception e) {
                throw EventBusException.wrap(copy.getType(), e);
            }
            // Error 及其他非 Exception 的 Throwable 原样冒出，不当作处理失败捕获
        }
    }

    /**
     * 发布事件并等待全部 handler 完成。
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

        // 两条失败路径分开记录：Exception 视为处理失败原样保存；Error/其他
        // Throwable 视为 panic，包装为 "event handler panic (type=...)" 后保存。
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

    /** EmitAndWait 的一条失败记录（panic 标记区分处理失败/panic 两条路径）。 */
    private record Failure(Throwable cause, boolean panic) {
    }

    /** 是否存在该事件类型的订阅。 */
    public boolean hasHandlers(String eventType) {
        mu.readLock().lock();
        try {
            List<EventHandler> list = handlers.get(eventType);
            return list != null && !list.isEmpty();
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 该事件类型的 handler 数。 */
    public int getHandlerCount(String eventType) {
        mu.readLock().lock();
        try {
            List<EventHandler> list = handlers.get(eventType);
            return list == null ? 0 : list.size();
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 清空全部 handler。 */
    public void clear() {
        mu.writeLock().lock();
        try {
            handlers.clear();
        } finally {
            mu.writeLock().unlock();
        }
    }

    /**
     * 以最小接口 {@link EventBusInterface} 的形状暴露本总线。见 {@link EventBusAdapter}。
     */
    public EventBusInterface asEventBusInterface() {
        return new EventBusAdapter(this);
    }
}
