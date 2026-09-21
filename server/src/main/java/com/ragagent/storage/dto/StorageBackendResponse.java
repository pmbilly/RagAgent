package com.ragagent.storage.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 对照 Go {@code types.StorageBackend} 的**响应形态**（NewStorageBackendResponse =
 * 掩码 config 后原样序列化 struct）。键序 = Go struct 声明序：
 * id, tenant_id, name, provider, config, source, status, legacy_alias,
 * created_at, updated_at, deleted_at（全部无 omitempty → 恒输出，deleted_at 恒 null）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StorageBackendResponse {

    @JsonProperty("id") public String id;
    @JsonProperty("tenant_id") public long tenantId;
    @JsonProperty("name") public String name;
    @JsonProperty("provider") public String provider;
    @JsonProperty("config") public JsonNode config;
    @JsonProperty("source") public String source;
    @JsonProperty("status") public String status;
    @JsonProperty("legacy_alias") public boolean legacyAlias;
    @JsonProperty("created_at") @JsonSerialize(using = GoTimeSerializer.Utc.class)
    public OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;
    @JsonProperty("updated_at") @JsonSerialize(using = GoTimeSerializer.Utc.class)
    public OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;
    @JsonProperty("deleted_at")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Go 无 omitempty：null 恒输出
    public OffsetDateTime deletedAt;
}
