package com.ragagent.agent.approval;

/**
 * 逐工具的 MCP 策略查询口。
 *
 * <p>实现方通常经 {@link Adapter} 适配进来；
 * 注册与执行两个路径都会问它“这个工具需要人工批准吗 / 还启用着吗”。</p>
 *
 * <p>查询失败用异常表达（{@link Gate#needsApproval} 会按 fail-close/fail-open 处理，
 * {@link Gate#isEnabled} 直接向上抛）。</p>
 *
 * <p>{@code isEnabled} 由父接口 {@link EnabledChecker} 提供——
 * 目录批量校验 {@link ToolPolicy} 只要求这一个方法，
 * 用继承表达这一关系，免得同一个签名要在两个不相干接口里各写一遍。</p>
 */
public interface Checker extends EnabledChecker {

    boolean isRequired(Cancellation ctx, long tenantId, String serviceId, String toolName);
}
