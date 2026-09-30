package com.ragagent.mcp.service;

import java.util.List;

import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.common.approval.Checker;

/**
 * 逐工具策略的**整表读取口**。
 *
 * <p><b>为什么在本包再定义一个窄接口</b>：Java 没有结构化类型，
 * 无法在运行时探测一个 {@link Checker} 是否额外提供 {@code listByService}，
 * 等价做法就是定义这个只含 {@code listByService} 的窄接口，
 * 由 {@code instanceof} 探测——因此它的实现方（MCP 服务层）只需在原类上
 * {@code implements ... McpToolPolicySource}（或用一个匿名类桥接），
 * 本包**不**直接依赖 {@code com.ragagent.mcp.service} 的具体类，避免并行开发冲突。</p>
 *
 * <p>行类型直接复用 MCP 领域的 {@link McpToolApproval}；
 * 本包只读它的 tenantId / serviceId / toolName / enabled 四个字段。</p>
 */
public interface McpToolPolicySource {

    /** 按服务整表读取工具策略。 */
    List<McpToolApproval> listByService(long tenantId, String serviceId);
}
