package com.ragagent.event;

/**
 * 事件处理器（对照 Go {@code event.EventHandler}，internal/event/event.go:105）。
 *
 * <p>Go 签名 {@code func(ctx context.Context, event Event) error}；按项目约定 §5，
 * context.Context 不作参数层层传（认证会话信息走 TenantContext ThreadLocal，
 * 跨虚拟线程由 EventBus 显式快照传值），error 返回值改抛异常（§1 技术栈映射）。</p>
 *
 * <p><b>异常即 Go 的 error</b>：同步 Emit 收到异常会中断 handler 链并包成
 * {@link EventBusException}（{@code event handler failed for <type>: ...}）；
 * 这与 Go 的 error 返回路径逐行对应。Go 的 panic 与 Java 异常没有一一对应——
 * 同步模式下异常都走 error 路径（包装并中断），异步 / EmitAndWait 模式下
 * Throwable 按 panic 处理（隔离 / 转error），见 {@link EventBus} 的类注释。</p>
 */
@FunctionalInterface
public interface EventHandler {

    /**
     * 处理事件。
     *
     * @throws Exception 处理失败（对照 Go 返回 error）
     */
    void handle(Event event) throws Exception;
}
