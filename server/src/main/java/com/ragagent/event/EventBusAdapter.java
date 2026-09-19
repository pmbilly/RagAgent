package com.ragagent.event;

/**
 * {@link EventBus} 到 {@link EventBusInterface} 的适配
 * （对照 Go {@code event.EventBusAdapter}，internal/event/adapter.go 全文）。
 *
 * <p>Go 的适配器做 {@code types.Event ↔ event.Event} 的逐字段拷贝——那份重复存在的唯一
 * 理由是打破 import cycle（adapter.go 注释原话）。Java 无循环依赖约束、只有一种
 * {@link Event}，故转换是恒等：本类保留 Go 的形状（让 chat_manage / approval 风格的
 * 消费方以接口持有总线），构造器与 {@link EventBus#asEventBusInterface()} 对照
 * {@code NewEventBusAdapter} / {@code AsEventBusInterface}。</p>
 */
public final class EventBusAdapter implements EventBusInterface {

    private final EventBus bus;

    /** 对照 Go {@code NewEventBusAdapter(bus) types.EventBusInterface}。 */
    public EventBusAdapter(EventBus bus) {
        this.bus = bus;
    }

    /** 对照 Go {@code EventBusAdapter.On}（types.EventType → event.EventType 的转换在 Java 中恒等）。 */
    @Override
    public void on(String eventType, EventHandler handler) {
        bus.on(eventType, handler);
    }

    /** 对照 Go {@code EventBusAdapter.Emit}（types.Event → event.Event 的转换在 Java 中恒等）。 */
    @Override
    public void emit(Event event) {
        bus.emit(event);
    }
}
