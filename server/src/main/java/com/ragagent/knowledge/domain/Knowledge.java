package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * knowledges 表实体。
 * 仓储行为契约：
 * - 软删除 → 显式 isNull("deleted_at")
 * - 钩子 BeforeCreate：id UUID + custom_metadata 补 '{}'（Java 由 service 显式赋值，语义等价）
 * - metadata jsonb：文件型文档为内部摄取状态（NULL 常见）；手工知识为 ManualKnowledgeMetadata
 * - tags 为 无列映射标签 关联（阶段 3 不回填 → 恒 null，golden 钉住 "tags":null）
 */
@TableName(value = "knowledges", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "knowledge_base_id", "tags", "type", "title", "description",
        "source", "channel", "parse_status", "pending_subtasks_count", "summary_status",
        "enable_status", "embedding_model_id", "file_name", "folder_path", "file_type",
        "file_size", "file_hash", "file_path", "storage_size", "metadata", "custom_metadata",
        "last_faq_import_result", "created_at", "updated_at", "processed_at", "error_message",
        "deleted_at", "knowledge_base_name"
})
public class Knowledge {

    public static final String PARSE_PENDING = "pending";
    public static final String PARSE_PROCESSING = "processing";
    public static final String PARSE_FINALIZING = "finalizing";
    public static final String PARSE_COMPLETED = "completed";
    public static final String PARSE_FAILED = "failed";
    public static final String PARSE_DELETING = "deleting";
    public static final String PARSE_CANCELLED = "cancelled";

    @TableId(type = IdType.INPUT)
    @JsonProperty("id")
    private String id;
    @JsonProperty("tenant_id")
    private Long tenantId;
    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId;
    /** 无列映射标签 关联回填；阶段 3 不回填 → null */
    @TableField(exist = false)
    @JsonProperty("tags")
    private List<JsonNode> tags;
    @JsonProperty("type")
    private String type;
    @JsonProperty("title")
    private String title;
    @JsonProperty("description")
    private String description = "";
    @JsonProperty("source")
    private String source = "";
    @JsonProperty("channel")
    private String channel = "web";
    @JsonProperty("parse_status")
    private String parseStatus = PARSE_PENDING;
    @JsonProperty("pending_subtasks_count")
    private int pendingSubtasksCount;
    @JsonProperty("summary_status")
    private String summaryStatus = "none";
    @JsonProperty("enable_status")
    private String enableStatus = "enabled";
    @JsonProperty("embedding_model_id")
    private String embeddingModelId = "";
    @JsonProperty("file_name")
    private String fileName;
    @JsonProperty("folder_path")
    private String folderPath = "";
    @JsonProperty("file_type")
    private String fileType;
    @JsonProperty("file_size")
    private Long fileSize;
    @JsonProperty("file_hash")
    private String fileHash;
    @JsonProperty("file_path")
    private String filePath;
    @JsonProperty("storage_size")
    private long storageSize;
    /** 恒输出：NULL → "metadata":null */
    @JsonProperty("metadata")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode metadata;
    @JsonProperty("custom_metadata")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode customMetadata;
    /** 恒输出：NULL → null */
    @JsonProperty("last_faq_import_result")
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode lastFaqImportResult;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;
    @JsonProperty("processed_at")
    private OffsetDateTime processedAt;
    @JsonProperty("error_message")
    private String errorMessage = "";
    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;
    /** 无列映射标签 查询回填（跨 KB 列表场景；恒输出，默认 ""） */
    @TableField(exist = false)
    @JsonProperty("knowledge_base_name")
    private String knowledgeBaseName = "";

    @TableField(exist = false)
    @JsonIgnore
    private boolean descriptionSpecified;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public List<JsonNode> getTags() { return tags; }
    public void setTags(List<JsonNode> v) { tags = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }
    public boolean isDescriptionSpecified() { return descriptionSpecified; }
    public void setDescriptionSpecified(boolean v) { descriptionSpecified = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { channel = v == null || v.isEmpty() ? "web" : v; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String v) { parseStatus = v; }
    public int getPendingSubtasksCount() { return pendingSubtasksCount; }
    public void setPendingSubtasksCount(int v) { pendingSubtasksCount = v; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String v) { summaryStatus = v; }
    public String getEnableStatus() { return enableStatus; }
    public void setEnableStatus(String v) { enableStatus = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { fileName = v; }
    public String getFolderPath() { return folderPath; }
    public void setFolderPath(String v) { folderPath = v == null ? "" : v; }
    public String getFileType() { return fileType; }
    public void setFileType(String v) { fileType = v; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long v) { fileSize = v; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String v) { fileHash = v; }
    public String getFilePath() { return filePath == null ? "" : filePath; }
    public void setFilePath(String v) { filePath = v; }
    public long getStorageSize() { return storageSize; }
    public void setStorageSize(long v) { storageSize = v; }
    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode v) { metadata = v; }
    public JsonNode getCustomMetadata() { return customMetadata; }
    public void setCustomMetadata(JsonNode v) { customMetadata = v; }
    public JsonNode getLastFaqImportResult() { return lastFaqImportResult; }
    public void setLastFaqImportResult(JsonNode v) { lastFaqImportResult = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(OffsetDateTime v) { processedAt = v; }
    public String getErrorMessage() { return errorMessage == null ? "" : errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v == null ? "" : v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
    public String getKnowledgeBaseName() { return knowledgeBaseName == null ? "" : knowledgeBaseName; }
    public void setKnowledgeBaseName(String v) { knowledgeBaseName = v == null ? "" : v; }

    /** deleting/cancelled 即中止 */
    @JsonIgnore
    public boolean isAborted() {
        return PARSE_DELETING.equals(parseStatus) || PARSE_CANCELLED.equals(parseStatus);
    }
}
