package com.ragagent.mcp.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 已保存 MCP 目录的**列表卡片视图**（对照 Go dto.MCPCatalogSummary，
 * internal/handler/dto/mcp.go:52-57）。
 *
 * <p>三个字段在 Go 里都无 omitempty → 恒输出。</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"tool_count", "stale", "synced_at"})
public record McpCatalogSummary(
        @JsonProperty("tool_count") int toolCount,
        @JsonProperty("stale") boolean stale,
        @JsonProperty("synced_at") OffsetDateTime syncedAt) {
}
