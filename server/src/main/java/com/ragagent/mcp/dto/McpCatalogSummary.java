package com.ragagent.mcp.dto;

import java.time.OffsetDateTime;


/**
 * 已保存 MCP 目录的**列表卡片视图**（对照 Go dto.MCPCatalogSummary，
 * internal/handler/dto/mcp.go:52-57）。
 *
 * <p>三个字段在 Go 里都无 omitempty → 恒输出。</p>
 */
public record McpCatalogSummary( int toolCount, boolean stale, OffsetDateTime syncedAt) {
}
