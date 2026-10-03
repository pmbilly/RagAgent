package com.ragagent.common.approval;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.mcp.service.McpToolPolicySource;

/**
 * 可编程的 {@link Checker} 测试替身：各判定方法可注入返回值或异常。
 *
 * <p>字段 {@code required / requiredError / enabled / enabledError}，其中
 * {@code enabled == null} 视同放行（返回 true）的默认语义。</p>
 */
class StubChecker implements Checker {

    boolean required;
    RuntimeException requiredError;
    /** null 视同未配置 → 返回 true */
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
     * 在 stubChecker 之上实现 ListByService 的批量策略源（Java 用继承组合）。
     */
    static class BatchPolicyService extends StubChecker implements McpToolPolicySource {

        List<McpToolApproval> rows = new ArrayList<>();
        RuntimeException listError;
        /** 读取计数：批量路径必须只读一次 */
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
