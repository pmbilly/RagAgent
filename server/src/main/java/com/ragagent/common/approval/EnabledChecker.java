package com.ragagent.common.approval;

/**
 * 目录批量校验用的单工具查询面。
 *
 * <p>因 {@link ToolPolicy} 的公开方法签名以本接口为参数类型，
 * Java 不允许“公开方法暴露非公开类型”，故本接口一并设为 public。</p>
 */
public interface EnabledChecker {

    boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName);
}
