package com.ragagent.mcp.dto;

import java.time.OffsetDateTime;


/**
 * 已保存 MCP 目录的**列表卡片视图**。
 *
 * <p>三个字段恒输出（无省略语义）。</p>
 */
public record McpCatalogSummary( int toolCount, boolean stale, OffsetDateTime syncedAt) {
}
