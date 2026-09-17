package com.ragagent.agent.approval;

/**
 * 事件总线在本包内的最小面（对照 Go {@code *event.EventBus} 的 Emit，
 * internal/event/event.go:150-189）。
 *
 * <p><b>为什么只留 Emit</b>：gate 只发不订（订阅方是 SSE 转发层/前端），
 * 因此本包不去翻译整棵事件总线（那是 agent 引擎/流式模块的活）。
 * 留成 {@code @FunctionalInterface} 的好处是接线时一行 lambda 即可：
 * {@code event -> streamManager.emit(event)}。</p>
 *
 * <p><b>线程语义</b>：Go 的 EventBus 默认**同步**模式（asyncMode=false），
 * Emit 顺序执行 handler 并在任一 handler 失败时返回 error、由 gate 包装上报。
 * Java 侧同一约定：实现应同步执行并在失败时抛运行时异常，
 * {@link Gate} 会包装成 {@link ApprovalException.Kind#INTERNAL}。</p>
 */
@FunctionalInterface
public interface EventBus {

    /** 对照 Go EventBus.Emit；失败抛运行时异常（Go: error 返回值） */
    void emit(Event event);
}
