package com.ragagent.auth.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.common.web.PgJsonTypeHandler;
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
 * JSON 输出契约（波 2 扫尾批 3 起，POST /tenants 直接序列化本实体，
 * 对照 Go types.Tenant 的 json tag 与字段声明序）：
 * id, name, description, status, retriever_engines, business, storage_quota,
 * storage_used, context_config, web_search_config, parser_engine_config,
 * credentials, storage_engine_config, default_storage_backend_id（omitempty）,
 * chat_history_config, retrieval_config, memory_config, created_at, updated_at,
 * deleted_at；api_principal_config json:"-" 恒不输出。其余 null 恒输出显式 null。
 *
 * <p>备案（2026-09-28 评审）：DB 列 {@code conversation_config}（000001 建列，
 * COMMENT "Global Conversation configuration for this tenant"）Java 全仓零读写
 * ——Go 侧同列亦未见 struct 映射（疑似遗留死列），与 sessions 的同类未映射列
 * （Session.java 35-38 备案）同款处理：不映射，仅在此记录。</p>
 * 列表/详情等其余 API 输出仍统一经 dto.TenantResponse。
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

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode retrieverEngines;

    private String business;
    /** gorm default:10737418240（10GB） */

    private Long storageQuota;
    /** gorm default:0 */

    private Long storageUsed;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode contextConfig;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode webSearchConfig;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode parserEngineConfig;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode credentials;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode storageEngineConfig;
    /** Go 指针 + omitempty：null 时整键省略 */
    private String defaultStorageBackendId;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode chatHistoryConfig;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode retrievalConfig;

    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode memoryConfig;
    /** jsonb（迁移 000064）：API principal 配置；加密语义见 APIPrincipalConfigTypeHandler；
     *  Go json:"-" —— 任何响应都不输出（@JsonIgnore 同时挡住反序列化，与 Go 一致） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @TableField(typeHandler = APIPrincipalConfigTypeHandler.class)
    private APIPrincipalConfig apiPrincipalConfig;

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
    public APIPrincipalConfig getApiPrincipalConfig() { return apiPrincipalConfig; }
    public void setApiPrincipalConfig(APIPrincipalConfig v) { apiPrincipalConfig = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
