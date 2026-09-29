package com.ragagent.agent.tools;

import com.ragagent.event.EventBus;

/**
 * Agent 工具执行时挂载的每次调用元数据。
 *
 * <p><b>依赖注入形态</b>：registry 的 {@code executeTool(...)} 显式收一个
 * {@link ToolExecContext}，包进 {@link ToolRequest} 交给工具。工具要读就取
 * {@code request.execMeta()}（无值时 null）。</p>
 *
 * <p>审批等待真正需要的是"不带默认工具超时的取消源"，表达为
 * {@link #approvalCancellation}；null 时回落到外层取消源。
 * {@code execTimeoutMillis} 为 0 表示"回落到 60s"。</p>
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

    /** 工具不携带元数据时的空上下文。 */
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
