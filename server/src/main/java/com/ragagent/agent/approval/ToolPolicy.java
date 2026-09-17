package com.ragagent.agent.approval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 目录级启用状态查询（对照 Go approval.tool_policy.go 的自由函数 {@code EnabledTools} /
 * {@code enabledToolsIndividually}）。
 *
 * <p><b>缺行 = 启用</b>：{@code enabledToolsIndividually} 先给每个名字填默认 true，
 * 批量路径再用策略表覆盖——这与 Go 的“策略表只存覆盖值”一致。</p>
 */
public final class ToolPolicy {

    private ToolPolicy() {
    }

    /**
     * 对照 Go {@code EnabledTools(ctx, checker, tenantID, serviceID, names)}：
     * 支持批量就一次查完，否则退化为逐个查（兼容只实现单工具契约的 gate）。
     */
    public static Map<String, Boolean> enabledTools(
            Cancellation ctx, EnabledChecker checker, long tenantId, String serviceId, List<String> names) {
        // 对照 Go: if err := ctx.Err(); err != nil { return nil, err } → context.Canceled
        if (ctx.isCancelled()) {
            throw new CancellationException("context canceled");
        }
        if (tenantId == 0 || serviceId == null || serviceId.isEmpty()) {
            throw ApprovalException.internal("MCP policy identity is required");
        }
        // 对照 Go 的类型断言 checker.(BulkEnabledChecker)
        if (checker instanceof BulkEnabledChecker bulk) {
            return bulk.enabledTools(ctx, tenantId, serviceId, names);
        }
        return enabledToolsIndividually(ctx, checker, tenantId, serviceId, names);
    }

    /**
     * 对照 Go {@code enabledToolsIndividually}：逐个查、每个名字前检查 ctx 取消。
     * checker 为 null 时全部默认启用。
     */
    static Map<String, Boolean> enabledToolsIndividually(
            Cancellation ctx, EnabledChecker checker, long tenantId, String serviceId, List<String> names) {
        Map<String, Boolean> result = new HashMap<>();
        if (names == null) {
            return result;
        }
        for (String name : names) {
            if (ctx.isCancelled()) {
                throw new CancellationException("context canceled");
            }
            boolean enabled = true;
            if (checker != null) {
                enabled = checker.isEnabled(ctx, tenantId, serviceId, name);
            }
            result.put(name, enabled);
        }
        return result;
    }
}
