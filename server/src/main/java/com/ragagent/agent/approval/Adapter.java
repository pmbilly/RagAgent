package com.ragagent.agent.approval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import com.ragagent.mcp.domain.McpToolApproval;

/**
 * 把 MCP 工具策略服务适配成 {@link Checker}，使 gate 不必 import 服务层包。
 *
 * <p>{@code svc} 的静态类型是 {@link Checker}（只需 isRequired/isEnabled），
 * 运行时用 {@code instanceof McpToolPolicySource} 探测批量能力。</p>
 *
 * <p><b>接线</b>：MCP 服务实现（{@code com.ragagent.mcp.service} 下的 McpToolApprovalService）
 * 只要也实现 {@link McpToolPolicySource}，或被一个小匿名类桥接，即可传入本类。</p>
 */
public class Adapter implements Checker, BulkEnabledChecker {

    /** 被适配的服务；为 null 时：isRequired → false、isEnabled → true */
    private final Checker svc;

    public Adapter(Checker svc) {
        this.svc = svc;
    }

    /** 服务缺失时视为不需要审批。 */
    @Override
    public boolean isRequired(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        if (svc == null) {
            return false;
        }
        return svc.isRequired(ctx, tenantId, serviceId, toolName);
    }

    /** 服务缺失时视为启用——策略表是可选的。 */
    @Override
    public boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        if (svc == null) {
            return true;
        }
        return svc.isEnabled(ctx, tenantId, serviceId, toolName);
    }

    /**
     * 服务支持 ListByService 时一次批量读策略，否则退化为逐个查。
     */
    @Override
    public Map<String, Boolean> enabledTools(
            Cancellation ctx, long tenantId, String serviceId, List<String> names) {
        if (ctx.isCancelled()) {
            throw new CancellationException("context canceled");
        }
        if (tenantId == 0 || serviceId == null || serviceId.isEmpty()) {
            throw ApprovalException.internal("MCP policy identity is required");
        }
        // svc 为 null 时 instanceof 恒为 false，自然落入退化路径。
        if (!(svc instanceof McpToolPolicySource lister)) {
            // 传 this（Adapter）：svc 为 null 时 isEnabled 恒 true，铺出“默认全启用”
            return ToolPolicy.enabledToolsIndividually(ctx, this, tenantId, serviceId, names);
        }
        List<McpToolApproval> rows = lister.listByService(tenantId, serviceId);
        // 先铺默认全 true，再由策略表覆盖
        Map<String, Boolean> result =
                ToolPolicy.enabledToolsIndividually(ctx, null, tenantId, serviceId, names);
        if (rows == null) {
            return result;
        }
        for (McpToolApproval row : rows) {
            if (row == null || row.getTenantId() == null || row.getTenantId() != tenantId) {
                continue;
            }
            if (!serviceId.equals(row.getServiceId())) {
                continue;
            }
            if (result.containsKey(row.getToolName())) {
                result.put(row.getToolName(), row.isEnabled());
            }
        }
        return result;
    }

    /** 便于测试与接线：取回被适配的服务 */
    public Checker service() {
        return svc;
    }
}
