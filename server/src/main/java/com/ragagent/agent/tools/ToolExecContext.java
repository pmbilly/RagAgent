package com.ragagent.agent.tools;

import com.ragagent.event.EventBus;

/**
 * Agent 工具执行时挂载的每次调用元数据（对照 Go {@code exec_context.go} 的
 * {@code ToolExecContext}，internal/agent/tools/exec_context.go）。
 *
 * <p><b>依赖注入形态（Java 侧设计，报告已备案）</b>：Go 把它塞进 {@code context.Context}
 * （{@code WithToolExecContext/ToolExecFromContext}），Java 无 ctx，改为
 * <b>显式传参</b>——registry 的 {@code executeTool(...)} 收一个
 * {@link ToolExecContext}，包进 {@link ToolRequest} 交给工具。工具要读就取
 * {@code request.execMeta()}，等价于 {@code ToolExecFromContext(ctx)}（无值时 null）。</p>
 *
 * <p>{@code ApprovalCtx}（等待人工审批用的父 ctx，issue #1173）在 Java 里表达为
 * {@link #approvalCancellation}——审批等待真正需要的是"不带默认工具超时的取消源"，
 * 一个 {@link ToolCancellation} 正好承载；null 时回落到外层取消源（同 Go 的 nil 分支）。
 * {@code ExecTimeout}（time.Duration）→ {@code execTimeoutMillis}，0 表示"回落到 60s"。</p>
 */
public record ToolExecContext(
        String sessionId,
        String assistantMessageId,
        String requestId,
        String toolCallId,
        String userId,
        EventBus eventBus,
        ToolCancellation approvalCancellation,
        long execTimeoutMillis) {

    /** 工具不携带元数据时的空上下文（等价 Go 的"ctx 里没有 ToolExecContext"）。 */
    public static ToolExecContext empty() {
        return new ToolExecContext("", "", "", "", "", null, null, 0);
    }

    public ToolExecContext {
        sessionId = sessionId == null ? "" : sessionId;
        assistantMessageId = assistantMessageId == null ? "" : assistantMessageId;
        requestId = requestId == null ? "" : requestId;
        toolCallId = toolCallId == null ? "" : toolCallId;
        userId = userId == null ? "" : userId;
    }
}
