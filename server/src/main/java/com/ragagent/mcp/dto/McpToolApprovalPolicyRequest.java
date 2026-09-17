package com.ragagent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 更新单个 MCP 工具策略的请求体（对照 Go handler 的
 * {@code setMCPToolApprovalBody}，mcp_service.go:586-589）。
 *
 * <p>两个字段都是包装类型（Go 里是 {@code *bool}）：必须能区分"未提供"（保持原值）
 * 与"显式 false"。两个都为 null 时 handler 返回 400。</p>
 */
public record McpToolApprovalPolicyRequest(
        @JsonProperty("require_approval") Boolean requireApproval,
        @JsonProperty("enabled") Boolean enabled) {
}
