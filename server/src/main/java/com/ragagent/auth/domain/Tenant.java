package com.ragagent.auth.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * tenants 表实体（对照 Go types/tenant.go Tenant）。
 *
 * GORM 隐式行为清单：
 * - 软删除 → 显式 isNull("deleted_at") 条件（同 User）
 * - 钩子 BeforeCreate：RetrieverEngines.Engines == nil 时置为空数组（等价：列 NOT NULL DEFAULT '[]'，
 *   Java 侧插入时若 null 由 DB 兜底；读取后 normalize 见 TenantService）
 * - RetrieverEngines.Scan 兼容历史裸数组格式 [{...}] 与现行 {"engines":[...]} 包装格式，
 *   响应恒为包装格式 → 读取后在 TenantService 归一化
 * - id 为 SERIAL（迁移 000000 从 10000 起）→ @TableId(type = AUTO)
 * - jsonb 配置列在本阶段统一按 JsonNode 透传（Go 的 *Config 强类型翻译随对应模块按需补强；
 *   读取路径字节级一致：DB 存什么响应什么，Jackson 保持解析时的 key 顺序）
 *
 * JSON 输出不走本实体（API 输出统一经 dto.TenantResponse），无需 @JsonPropertyOrder。
 */
@TableName(value = "tenants", autoResultMap = true)
public class Tenant {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    /** gorm default:'active' */
    private String status;
    /** json 列：包装格式 {"engines":[...]} 或历史裸数组（读取后归一化） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode retrieverEngines;
    private String business;
    /** gorm default:10737418240（10GB） */
    private Long storageQuota;
    /** gorm default:0 */
    private Long storageUsed;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode contextConfig;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode webSearchConfig;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode parserEngineConfig;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode credentials;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode storageEngineConfig;
    private String defaultStorageBackendId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode chatHistoryConfig;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode retrievalConfig;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode memoryConfig;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public JsonNode getRetrieverEngines() { return retrieverEngines; }
    public void setRetrieverEngines(JsonNode v) { retrieverEngines = v; }
    public String getBusiness() { return business; }
    public void setBusiness(String v) { business = v; }
    public Long getStorageQuota() { return storageQuota; }
    public void setStorageQuota(Long v) { storageQuota = v; }
    public Long getStorageUsed() { return storageUsed; }
    public void setStorageUsed(Long v) { storageUsed = v; }
    public JsonNode getContextConfig() { return contextConfig; }
    public void setContextConfig(JsonNode v) { contextConfig = v; }
    public JsonNode getWebSearchConfig() { return webSearchConfig; }
    public void setWebSearchConfig(JsonNode v) { webSearchConfig = v; }
    public JsonNode getParserEngineConfig() { return parserEngineConfig; }
    public void setParserEngineConfig(JsonNode v) { parserEngineConfig = v; }
    public JsonNode getCredentials() { return credentials; }
    public void setCredentials(JsonNode v) { credentials = v; }
    public JsonNode getStorageEngineConfig() { return storageEngineConfig; }
    public void setStorageEngineConfig(JsonNode v) { storageEngineConfig = v; }
    public String getDefaultStorageBackendId() { return defaultStorageBackendId; }
    public void setDefaultStorageBackendId(String v) { defaultStorageBackendId = v; }
    public JsonNode getChatHistoryConfig() { return chatHistoryConfig; }
    public void setChatHistoryConfig(JsonNode v) { chatHistoryConfig = v; }
    public JsonNode getRetrievalConfig() { return retrievalConfig; }
    public void setRetrievalConfig(JsonNode v) { retrievalConfig = v; }
    public JsonNode getMemoryConfig() { return memoryConfig; }
    public void setMemoryConfig(JsonNode v) { memoryConfig = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
