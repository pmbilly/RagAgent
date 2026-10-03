package com.ragagent.mcp.dto;


/**
 * 凭据字段的"是否已配置"元数据。
 *
 * <p><b>只有布尔值，永远没有值本身</b>——前端据此渲染"已配置 / 未配置"徽标。</p>
 */
public record CredentialFieldMetadata(boolean configured) {
}
