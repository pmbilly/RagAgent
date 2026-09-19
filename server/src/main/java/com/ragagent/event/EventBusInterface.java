package com.ragagent.event;

/**
 * 事件总线的最小接口（对照 Go {@code types.EventBusInterface}，internal/types/event_bus.go:24-30）。
 *
 * <p>Go 里 {@code internal/types} 另有一份 {@code types.Event}/{@code types.EventHandler}
 * 副本，专为打破 types ↔ event 的 import cycle；本包只有一种 {@link Event}，
 * 故转换退化为恒等，接口只保留形状（On + Emit）。</p>
 *
 * <p>消费方：Go 的 {@code types.ChatManage.EventBus}（chat_pipeline 路径）与
 * {@code agent/approval} 的 {@code req.EventBus} 都以此接口持有总线——波 4.6 / 波 5
 * 翻译对应 Java 代码时按此形状接线。</p>
 */
public interface EventBusInterface {

    /** 注册 handler（对照 Go {@code On(eventType types.EventType, handler types.EventHandler)}）。 */
    void on(String eventType, EventHandler handler);

    /** 发布事件（对照 Go {@code Emit(ctx, evt)}；失败抛 {@link EventBusException}）。 */
    void emit(Event event);
}
