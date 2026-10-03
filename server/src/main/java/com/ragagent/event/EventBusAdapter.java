package com.ragagent.event;

/**
 * {@link EventBus} 到 {@link EventBusInterface} 的适配。
 *
 * <p>本类的存在意义是让消费方（chat_manage / approval 风格）以接口持有总线；
 * 事件在 Java 中只有一种表示，转发是恒等的。通常经
 * {@link EventBus#asEventBusInterface()} 获取实例。</p>
 */
public final class EventBusAdapter implements EventBusInterface {

    private final EventBus bus;

    /** 包装给定总线。 */
    public EventBusAdapter(EventBus bus) {
        this.bus = bus;
    }

    /** 直接转发（事件类型只有一种表示，无需转换）。 */
    @Override
    public void on(String eventType, EventHandler handler) {
        bus.on(eventType, handler);
    }

    /** 直接转发（事件只有一种表示，无需转换）。 */
    @Override
    public void emit(Event event) {
        bus.emit(event);
    }
}
