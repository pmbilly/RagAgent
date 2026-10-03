package com.ragagent.event;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局事件总线：单例 + 可替换 + 静态门面。
 *
 * <p><b>注意</b>：首次 {@link #getGlobalEventBus()} 会无条件创建并赋新实例，
 * 因此在此之前通过 {@link #setGlobalEventBus(EventBus)} 设置的总线会被覆盖。</p>
 */
public final class GlobalEventBus {

    private static final AtomicBoolean ONCE = new AtomicBoolean(false);
    private static volatile EventBus globalEventBus;

    /** 取全局单例（首次调用创建；见类注释的覆盖行为）。 */
    public static EventBus getGlobalEventBus() {
        if (ONCE.compareAndSet(false, true)) {
            globalEventBus = new EventBus();
        }
        return globalEventBus;
    }

    /** 替换全局总线：测试 / 自定义装配用。 */
    public static void setGlobalEventBus(EventBus bus) {
        globalEventBus = bus;
    }

    /** 全局注册 handler。 */
    public static void on(String eventType, EventHandler handler) {
        getGlobalEventBus().on(eventType, handler);
    }

    /** 全局移除该事件类型的全部 handler。 */
    public static void off(String eventType) {
        getGlobalEventBus().off(eventType);
    }

    /** 全局发布事件。 */
    public static void emit(Event event) {
        getGlobalEventBus().emit(event);
    }

    /** 全局发布事件并等待全部 handler 完成。 */
    public static void emitAndWait(Event event) {
        getGlobalEventBus().emitAndWait(event);
    }

    /** 是否存在该事件类型的全局订阅。 */
    public static boolean hasHandlers(String eventType) {
        return getGlobalEventBus().hasHandlers(eventType);
    }

    /** 清空全局全部 handler。 */
    public static void clear() {
        getGlobalEventBus().clear();
    }

    private GlobalEventBus() {
    }
}
