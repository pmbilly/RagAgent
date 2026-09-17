package com.ragagent.agent.approval;

import java.util.List;
import java.util.Map;

/**
 * 目录枚举时的批量启用状态查询（对照 Go approval.BulkEnabledChecker，tool_policy.go:14-18）。
 *
 * <p>“用一个查询代替 N 个查询”。Go 侧通过类型断言 {@code checker.(BulkEnabledChecker)}
 * 探测是否支持批量；Java 侧用 {@code instanceof}，语义一致：实现方必须**同时**实现
 * {@link EnabledChecker} 与本接口，才会被 {@link ToolPolicy#enabledTools} 走批量分支。</p>
 */
public interface BulkEnabledChecker {

    /**
     * 对照 Go BulkEnabledChecker.EnabledTools：为每个请求的名字返回一个显式决定。
     * 返回值里缺少某个名字时，调用方按“默认启用”处理（见 {@link ToolPolicy}）。
     */
    Map<String, Boolean> enabledTools(Cancellation ctx, long tenantId, String serviceId, List<String> names);
}
