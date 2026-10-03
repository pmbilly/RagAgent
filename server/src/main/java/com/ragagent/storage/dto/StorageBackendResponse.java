package com.ragagent.storage.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 存储后端的响应形态（config 掩码后输出）。键名即 Java 字段名（camelCase）；
 * 键恒输出，{@code deletedAt} 恒 null（可空字段显式输出 null），时间 ISO-8601 带时区。
 */
public class StorageBackendResponse {

    public String id;
    public long tenantId;
    public String name;
    public String provider;
    public JsonNode config;
    public String source;
    public String status;
    public boolean legacyAlias;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public OffsetDateTime deletedAt;
}
