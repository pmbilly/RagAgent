package com.ragagent.vectorstore.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.vectorstore.domain.VectorStore;

/**
 * 对照 Go {@code types.VectorStoreResponse}（内嵌 VectorStore + source/readonly）。
 * 键序 = Go struct 声明序：id, tenant_id, name, engine_type, connection_config,
 * index_config, created_at, updated_at, deleted_at, source, readonly。
 * connection_config 经 MaskSensitiveFields（非空 password/api_key → "***"）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VectorStoreResponse {

    @JsonProperty("id")
    public String id;
    @JsonProperty("tenant_id")
    public long tenantId;
    @JsonProperty("name")
    public String name;
    @JsonProperty("engine_type")
    public String engineType;
    @JsonProperty("connection_config")
    public ConnectionConfig connectionConfig;
    @JsonProperty("index_config")
    public IndexConfig indexConfig;
    @JsonProperty("created_at")
    public OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;
    @JsonProperty("updated_at")
    public OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;
    @JsonProperty("deleted_at")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Go 无 omitempty：null 恒输出
    public OffsetDateTime deletedAt;
    @JsonProperty("source")
    public String source;
    @JsonProperty("readonly")
    public boolean readOnly;

    /** 对照 NewVectorStoreResponse(store, source, readonly)：掩码后组装 */
    public static VectorStoreResponse of(VectorStore s, String source, boolean readonly) {
        ConnectionConfig conn = s.getConnectionConfig() == null
                ? new ConnectionConfig()
                : s.getConnectionConfig().maskSensitiveFields();
        VectorStoreResponse r = new VectorStoreResponse();
        r.id = s.getId();
        r.tenantId = s.getTenantId() == null ? 0 : s.getTenantId();
        r.name = s.getName() == null ? "" : s.getName();
        r.engineType = s.getEngineType() == null ? "" : s.getEngineType();
        r.connectionConfig = conn;
        r.indexConfig = s.getIndexConfig() == null ? new IndexConfig() : s.getIndexConfig();
        r.createdAt = s.getCreatedAt() == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : s.getCreatedAt();
        r.updatedAt = s.getUpdatedAt() == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : s.getUpdatedAt();
        r.deletedAt = s.getDeletedAt();
        r.source = source;
        r.readOnly = readonly;
        return r;
    }
}
