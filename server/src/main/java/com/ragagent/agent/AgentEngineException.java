package com.ragagent.agent;

import com.ragagent.agent.domain.AgentState;

/**
 * 引擎不可恢复失败的错误通道（对照 Go 的 {@code error} 返回值，internal/agent/engine.go
 * 的 {@code return nil, err}）。
 *
 * <p>Go 的 {@code (result, error)} 双通道在 Java 折叠为：成功 → 返回
 * {@link AgentState}；失败 → 抛本异常。<b>message 即契约</b>——它是 error 事件的
 * {@code error} 字段与日志原文（如 {@code LLM call failed: ...}），逐字对照 Go 的
 * fmt.Errorf 文案。stub LLM / 工具在测试里也抛本类型。</p>
 *
 * <p>个别路径 Go 在返回 error 的同时携带部分状态（循环头取消时
 * {@code return state, ctx.Err()}，state 里已有抢救出的 final answer）——
 * 这类状态挂在 {@link #getState()}。</p>
 */
public class AgentEngineException extends RuntimeException {

    private final transient AgentState state;

    public AgentEngineException(String message) {
        super(message);
        this.state = null;
    }

    public AgentEngineException(String message, AgentState state) {
        super(message);
        this.state = state;
    }

    /** 取消/失败路径上已抢救出的部分状态（可为 null）。 */
    public AgentState getState() {
        return state;
    }
}
