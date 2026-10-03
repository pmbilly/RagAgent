package com.ragagent.event;

/**
 * 事件处理器。
 *
 * <p>context 不作参数层层传：认证会话信息走 TenantContext ThreadLocal，
 * 跨虚拟线程由 EventBus 显式快照传值；处理失败改抛异常而非返回错误值。</p>
 *
 * <p><b>异常即处理失败信号</b>：同步 Emit 收到异常会中断 handler 链并包成
 * {@link EventBusException}（{@code event handler failed for <type>: ...}）；
 * 异步 / EmitAndWait 模式下 Error 及其他 Throwable 按 panic 处理
 * （隔离 / 转包装），见 {@link EventBus} 的类注释。</p>
 */
@FunctionalInterface
public interface EventHandler {

    /**
     * 处理事件。
     *
     * @throws Exception 处理失败
     */
    void handle(Event event) throws Exception;
}
