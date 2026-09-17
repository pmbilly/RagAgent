package com.ragagent.agent.approval;

/**
 * 逐工具的 MCP 策略查询口（对照 Go approval.Checker，gate.go:70-74）。
 *
 * <p>Go 里由 {@code *MCPToolApprovalService} 经 {@link Adapter} 适配进来；
 * 注册与执行两个路径都会问它“这个工具需要人工批准吗 / 还启用着吗”。</p>
 *
 * <p>Go 的 {@code (bool, error)} 在 Java 里是“返回值 + 运行时异常”：
 * 抛异常即等价于 Go 返回 error（{@link Gate#needsApproval} 会按 fail-close/fail-open 处理，
 * {@link Gate#isEnabled} 直接向上抛）。</p>
 *
 * <p>{@code IsEnabled} 由父接口 {@link EnabledChecker} 提供——Go 的 {@code Checker} 在结构上
 * 也包含它（目录批量校验 {@link ToolPolicy} 只要求这一个方法），
 * Java 用继承表达同一关系，免得同一个签名要在两个不相干接口里各写一遍。</p>
 */
public interface Checker extends EnabledChecker {

    /** 对照 Go Checker.IsRequired */
    boolean isRequired(Cancellation ctx, long tenantId, String serviceId, String toolName);
}
