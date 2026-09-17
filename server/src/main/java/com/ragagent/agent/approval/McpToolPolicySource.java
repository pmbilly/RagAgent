package com.ragagent.agent.approval;

import java.util.List;

import com.ragagent.mcp.domain.McpToolApproval;

/**
 * 逐工具策略的**整表读取口**（对照 Go 里 {@link Adapter} 对
 * {@code ListByService(ctx, tenantID, serviceID) ([]*types.MCPToolApproval, error)} 的匿名接口断言，
 * tool_policy.go:95-97）。
 *
 * <p><b>为什么在本包再定义一个窄接口</b>：Go 的 {@code Adapter.Svc} 接受任意实现了
 * IsRequired/IsEnabled 的对象，并在运行时**结构化**断言它是否额外提供 ListByService。
 * Java 没有结构化类型，等价做法就是定义这个只含 {@code listByService} 的窄接口，
 * 由 {@code instanceof} 探测——因此它的实现方（MCP 服务层）只需在原类上
 * {@code implements ... McpToolPolicySource}（或用一个匿名类桥接），
 * 本包**不**直接依赖 {@code com.ragagent.mcp.service} 的具体类，避免并行开发冲突。</p>
 *
 * <p>行类型直接复用 MCP 领域的 {@link McpToolApproval}（对应 Go 的 {@code types.MCPToolApproval}）；
 * 本包只读它的 tenantId / serviceId / toolName / enabled 四个字段。</p>
 */
public interface McpToolPolicySource {

    /** 对照 Go MCPToolApprovalService.ListByService */
    List<McpToolApproval> listByService(long tenantId, String serviceId);
}
