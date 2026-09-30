package com.ragagent.common.approval;

/**
 * 事件总线在本包内的最小面。
 *
 * <p><b>为什么只留 Emit</b>：gate 只发不订（订阅方是 SSE 转发层/前端），
 * 整棵事件总线属于 agent 引擎/流式模块，本包不重复建设。
 * 留成 {@code @FunctionalInterface} 的好处是接线时一行 lambda 即可：
 * {@code event -> streamManager.emit(event)}。</p>
 *
 * <p><b>线程语义</b>：实现应**同步**执行——顺序调用 handler，
 * 任一 handler 失败即抛运行时异常，
 * {@link Gate} 会包装成 {@link ApprovalException.Kind#INTERNAL}。</p>
 */
@FunctionalInterface
public interface EventBus {

    /** 发布事件；实现失败时抛运行时异常。 */
    void emit(Event event);
}
