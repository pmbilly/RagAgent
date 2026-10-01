package com.ragagent.vectorstore.dto;

import java.time.OffsetDateTime;

import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.vectorstore.domain.VectorStore;

/**
 * 向量库的响应形态（内嵌 VectorStore + source/readonly）。键名即 Java 字段名
 * （camelCase），键恒输出（deletedAt 恒 null），时间 ISO-8601 带时区；
 * connectionConfig 经掩码（非空 password/apiKey → "***"）。
 */
public class VectorStoreResponse {

    public String id;
    public long tenantId;
    public String name;
    public String engineType;
    public ConnectionConfig connectionConfig;
    public IndexConfig indexConfig;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public OffsetDateTime deletedAt;
    public String source;
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
        r.createdAt = s.getCreatedAt();
        r.updatedAt = s.getUpdatedAt();
        r.deletedAt = s.getDeletedAt();
        r.source = source;
        r.readOnly = readonly;
        return r;
    }
}
