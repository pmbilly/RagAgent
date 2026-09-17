package com.ragagent.agent.approval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import com.ragagent.mcp.domain.McpToolApproval;

/**
 * 把 MCP 工具策略服务适配成 {@link Checker}（对照 Go approval.Adapter，gate.go:685-709 +
 * tool_policy.go:79-118），使 gate 不必 import 服务层包。
 *
 * <p>Go 的 {@code Adapter.Svc} 是一个匿名接口（只要 IsRequired/IsEnabled），
 * 并在 {@code EnabledTools} 里对它做**结构化断言**看是否还提供 ListByService。
 * Java 侧对应：{@code svc} 静态类型是 {@link Checker}，
 * 运行时用 {@code instanceof McpToolPolicySource} 探测批量能力。</p>
 *
 * <p><b>接线</b>：MCP 服务实现（{@code com.ragagent.mcp.service} 下的 McpToolApprovalService）
 * 只要也实现 {@link McpToolPolicySource}，或被一个小匿名类桥接，即可传入本类。</p>
 */
public class Adapter implements Checker, BulkEnabledChecker {

    /** 对照 Go {@code Adapter.Svc}；为 null 时：IsRequired → false、IsEnabled → true */
    private final Checker svc;

    public Adapter(Checker svc) {
        this.svc = svc;
    }

    /** 对照 Go Adapter.IsRequired（Svc 为 nil 时 false） */
    @Override
    public boolean isRequired(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        if (svc == null) {
            return false;
        }
        return svc.isRequired(ctx, tenantId, serviceId, toolName);
    }

    /** 对照 Go Adapter.IsEnabled（Svc 为 nil 时 true——策略表是可选的） */
    @Override
    public boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        if (svc == null) {
            return true;
        }
        return svc.isEnabled(ctx, tenantId, serviceId, toolName);
    }

    /**
     * 对照 Go {@code (*Adapter).EnabledTools}（tool_policy.go:79-118）：
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
        // 对照 Go: lister, ok := a.Svc.(interface{ ListByService(...) }); if !ok { 逐个查 }
        // Svc 为 null 时 instanceof 恒为 false，与 Go 的 a.Svc == nil 分支汇合到同一退化路径。
        if (!(svc instanceof McpToolPolicySource lister)) {
            // Go 这里传的是 a（Adapter）本身，Svc 为 nil 时其 IsEnabled 恒 true
            return ToolPolicy.enabledToolsIndividually(ctx, this, tenantId, serviceId, names);
        }
        List<McpToolApproval> rows = lister.listByService(tenantId, serviceId);
        // 对照 Go：先用 nil checker 铺默认全 true，再由策略表覆盖
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
