package com.ragagent.agent.approval;

/**
 * 目录批量校验用的单工具查询面（对照 Go approval.enabledChecker，tool_policy.go:10-12）。
 *
 * <p>Go 里该接口是 unexported 的，但 {@code EnabledTools} 是 exported 函数——
 * Java 不支持“公开方法 + 非公开参数类型”，故提升为 public（语义不变）。</p>
 */
public interface EnabledChecker {

    /** 对照 Go enabledChecker.IsEnabled */
    boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName);
}
