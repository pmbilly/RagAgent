package com.ragagent.common.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 危险 MCP 工具即将执行时的“请求批准”事件体。
 *
 * <p>JSON 键 = record 组件名（Java 字段名）；可空字段用 {@code NON_NULL} 表达
 * （缺失与空值对消费方等价）。</p>
 *
 * <p>{@code args} 是解析后的 JSON 对象（给 UI 渲染表单），
 * {@code argsJson} 是原始 JSON 串（给回填）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalRequiredData( String pendingId, long tenantId, String sessionId, String assistantMessageId, String serviceId, String serviceName, String mcpToolName, String registeredToolName, String description, Object args, String argsJson, int timeoutSeconds, long requestedAtUnix, String toolCallId, String requestId) {

    public ToolApprovalRequiredData {
        if (argsJson != null && argsJson.isEmpty()) {
            argsJson = null;   // 空串归一为 null，配合 NON_NULL 不序列化该字段
        }
        if (requestId != null && requestId.isEmpty()) {
            requestId = null;  // 空串归一为 null，配合 NON_NULL 不序列化该字段
        }
    }
}
