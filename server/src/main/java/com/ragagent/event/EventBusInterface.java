package com.ragagent.event;

/**
 * 事件总线的最小接口。
 *
 * <p>接口只保留最小形状（On + Emit），事件与 handler 在本仓只有一种表示。</p>
 *
 * <p>消费方以此接口持有总线，典型如 chatpipeline 的 {@code ChatManage}
 * 与 session 服务的装配。</p>
 */
public interface EventBusInterface {

    /** 注册 handler。 */
    void on(String eventType, EventHandler handler);

    /** 发布事件；失败抛 {@link EventBusException}。 */
    void emit(Event event);
}
