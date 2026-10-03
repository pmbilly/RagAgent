package com.ragagent.mcp.dto;


/**
 * 更新单个 MCP 工具策略的请求体。
 *
 * <p>两个字段都是包装类型：必须能区分"未提供"（保持原值）
 * 与"显式 false"。两个都为 null 时 handler 返回 400。</p>
 */
public record McpToolApprovalPolicyRequest(
        Boolean requireApproval,
        Boolean enabled) {
}
