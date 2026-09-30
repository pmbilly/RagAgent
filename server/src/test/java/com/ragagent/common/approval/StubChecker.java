package com.ragagent.common.approval;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.mcp.service.McpToolPolicySource;

/**
 * 对照 Go approval 包的 {@code stubChecker}（gate_test.go:14-33）。
 *
 * <p>Go 的字段：{@code required / err / enabled *bool / enabledErr}。Java 用可空包装表达
 * {@code enabled == nil → true} 的默认语义。</p>
 */
class StubChecker implements Checker {

    boolean required;
    RuntimeException requiredError;
    /** null 视同 Go 的 enabled == nil → 返回 true */
    Boolean enabled;
    RuntimeException enabledError;

    int requiredCalls;
    int enabledCalls;

    StubChecker() {
    }

    StubChecker(boolean required) {
        this.required = required;
    }

    static StubChecker enabled(Boolean value) {
        StubChecker c = new StubChecker();
        c.enabled = value;
        return c;
    }

    @Override
    public boolean isRequired(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        requiredCalls++;
        if (requiredError != null) {
            throw requiredError;
        }
        return required;
    }

    @Override
    public boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        enabledCalls++;
        if (enabledError != null) {
            throw enabledError;
        }
        return enabled == null || enabled;
    }

    /**
     * 对照 Go tool_policy_test.go 的 {@code batchPolicyService}：
     * 在 stubChecker 之上实现 ListByService（Go 用结构体嵌入，Java 用继承）。
     */
    static class BatchPolicyService extends StubChecker implements McpToolPolicySource {

        List<McpToolApproval> rows = new ArrayList<>();
        RuntimeException listError;
        /** 对照 Go 的读取计数：批量路径必须只读一次 */
        int reads;

        @Override
        public List<McpToolApproval> listByService(long tenantId, String serviceId) {
            reads++;
            if (listError != null) {
                throw listError;
            }
            return rows;
        }
    }

    static McpToolApproval row(long tenantId, String serviceId, String toolName, boolean enabled) {
        McpToolApproval row = new McpToolApproval();
        row.setTenantId(tenantId);
        row.setServiceId(serviceId);
        row.setToolName(toolName);
        row.setEnabled(enabled);
        return row;
    }
}
