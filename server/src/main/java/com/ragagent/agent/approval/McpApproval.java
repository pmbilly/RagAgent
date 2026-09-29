package com.ragagent.agent.approval;

/**
 * MCPTool 侧使用的审批面。
 *
 * <p>接口只暴露这三个方法，让工具执行层可 mock；{@link Gate} 是唯一产品实现。</p>
 */
public interface McpApproval {

    /** 策略查询错误已在内部按 fail-close/fail-open 吞掉。 */
    boolean needsApproval(Cancellation ctx, long tenantId, String serviceId, String toolName);

    /** 查询失败时抛运行时异常。 */
    boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName);

    Decision requestAndWait(Cancellation ctx, PendingRequest req);
}
