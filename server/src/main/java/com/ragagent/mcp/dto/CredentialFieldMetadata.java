package com.ragagent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 凭据字段的"是否已配置"元数据（对照 Go dto.CredentialFieldMetadata，
 * internal/handler/dto/mcp.go:73-75）。
 *
 * <p><b>只有布尔值，永远没有值本身</b>——前端据此渲染"已配置 / 未配置"徽标。</p>
 */
@JsonPropertyOrder({"configured"})
public record CredentialFieldMetadata(@JsonProperty("configured") boolean configured) {
}
