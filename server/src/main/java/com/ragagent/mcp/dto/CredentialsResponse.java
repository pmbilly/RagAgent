package com.ragagent.mcp.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * PUT {@code /{resource}/{id}/credentials} 的共享响应（对照 Go dto.CredentialsResponse，
 * internal/handler/dto/mcp.go:176-182）。
 *
 * <p>按字段名（{@code api_key} / {@code token}）索引；前端据此在不重新拉取整个资源的前提下
 * 更新内存中的元数据。{@code fields} 无 omitempty → 恒输出；
 * map 的键在 Go 里按字母序输出（api_key &lt; token），故用 {@link LinkedHashMap} 固定插入序。</p>
 */
@JsonPropertyOrder({"fields"})
public record CredentialsResponse(
        @JsonProperty("fields") Map<String, CredentialFieldMetadata> fields) {

    /** 对照 Go {@code map[string]dto.CredentialFieldMetadata{"api_key":..., "token":...}} */
    public static CredentialsResponse of(boolean apiKeyConfigured, boolean tokenConfigured) {
        Map<String, CredentialFieldMetadata> fields = new LinkedHashMap<>();
        fields.put("api_key", new CredentialFieldMetadata(apiKeyConfigured));
        fields.put("token", new CredentialFieldMetadata(tokenConfigured));
        return new CredentialsResponse(fields);
    }
}
