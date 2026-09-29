package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 危险 MCP 工具即将执行时的“请求批准”事件体
 * （对照 Go {@code event.ToolApprovalRequiredData}，internal/event/event_data.go:253-270）。
 *
 * <p>字段序 = Go struct 声明序；带 omitempty 的字段用 {@code NON_NULL} 表达
 * （缺失与空值对消费方等价，与 Go 逐字一致的是**字段名**这一线上契约）。</p>
 *
 * <p>字段名与类型说明见字段注释；{@code args} 是解析后的 JSON 对象，
 * {@code argsJson} 是原始 JSON 串（Go 同时给两者：前者给 UI 渲染表单，后者给回填）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalRequiredData( String pendingId, long tenantId, String sessionId, String assistantMessageId, String serviceId, String serviceName, String mcpToolName, String registeredToolName, String description, Object args, String argsJson, int timeoutSeconds, long requestedAtUnix, String toolCallId, String requestId) {

    public ToolApprovalRequiredData {
        if (argsJson != null && argsJson.isEmpty()) {
            argsJson = null;   // Go: omitempty
        }
        if (requestId != null && requestId.isEmpty()) {
            requestId = null;  // Go: omitempty
        }
    }
}
