package com.ragagent.agent.approval;

/**
 * MCPTool 侧使用的审批面（对照 Go approval.MCPApproval，gate.go:102-107）。
 *
 * <p>Go 里该接口存在的主要目的是让工具执行层可 mock；Java 侧同样只暴露这三个方法，
 * {@link Gate} 是唯一产品实现。</p>
 */
public interface McpApproval {

    /** 对照 Go MCPApproval.NeedsApproval（内部已按 fail-close/fail-open 吞掉策略查询错误） */
    boolean needsApproval(Cancellation ctx, long tenantId, String serviceId, String toolName);

    /** 对照 Go MCPApproval.IsEnabled，查询失败时抛运行时异常（Go: error） */
    boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName);

    /** 对照 Go MCPApproval.RequestAndWait */
    Decision requestAndWait(Cancellation ctx, PendingRequest req);
}
