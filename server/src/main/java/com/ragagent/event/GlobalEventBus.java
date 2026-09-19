package com.ragagent.event;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局事件总线（对照 Go {@code internal/event/global.go} 全文：sync.Once 单例 +
 * 可替换 + 包级函数门面）。
 *
 * <p><b>刻意保留的 Go quirk</b>：{@code SetGlobalEventBus} 在首次 {@code GetGlobalEventBus}
 * 之前调用会被 {@code once.Do} 覆盖（Go 的 once 在第一次 Get 时才触发、无条件覆盖全局变量）。
 * /tmp 实录：{@code globalSetThenGet => overwritten=true}。Java 复刻同一行为
 * （{@link #getGlobalEventBus()} 首次调用时无条件赋新实例），照抄而非"顺手修好"。</p>
 */
public final class GlobalEventBus {

    private static final AtomicBoolean ONCE = new AtomicBoolean(false);
    private static volatile EventBus globalEventBus;

    /** 对照 Go {@code GetGlobalEventBus}：单例（含上述 once 覆盖 quirk）。 */
    public static EventBus getGlobalEventBus() {
        if (ONCE.compareAndSet(false, true)) {
            globalEventBus = new EventBus();
        }
        return globalEventBus;
    }

    /** 对照 Go {@code SetGlobalEventBus}：测试 / 自定义装配用。 */
    public static void setGlobalEventBus(EventBus bus) {
        globalEventBus = bus;
    }

    /** 对照 Go 包级 {@code On}。 */
    public static void on(String eventType, EventHandler handler) {
        getGlobalEventBus().on(eventType, handler);
    }

    /** 对照 Go 包级 {@code Off}。 */
    public static void off(String eventType) {
        getGlobalEventBus().off(eventType);
    }

    /** 对照 Go 包级 {@code Emit}。 */
    public static void emit(Event event) {
        getGlobalEventBus().emit(event);
    }

    /** 对照 Go 包级 {@code EmitAndWait}。 */
    public static void emitAndWait(Event event) {
        getGlobalEventBus().emitAndWait(event);
    }

    /** 对照 Go 包级 {@code HasHandlers}。 */
    public static boolean hasHandlers(String eventType) {
        return getGlobalEventBus().hasHandlers(eventType);
    }

    /** 对照 Go 包级 {@code Clear}。 */
    public static void clear() {
        getGlobalEventBus().clear();
    }

    private GlobalEventBus() {
    }
}
