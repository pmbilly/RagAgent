package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * 会话附件（临时文档），对照 Go types.TemporaryDocument（types/temporary_document.go L24-48）。
 *
 * <p>序列化对齐要点：resource_ref / content / chunks / processing_options / deleted_at
 * 在 Go 是 {@code json:"-"}（响应里没有这些键）；image_refs / metadata 的
 * {@code omitempty} 对 JSON 类型是"len==0 时省略"——但 BeforeCreate 会把空值填成
 * {@code []} / {@code {}}，所以正常上传后的响应里**恒有**这两个键。</p>
 *
 * <p>时间列在真库是 TIMESTAMP WITHOUT TIME ZONE（naive）：GORM 读回的是本地时区
 * 时间，序列化带 +08:00——Java 侧用 {@link LocalDateTime}（naive）承载，由
 * GoTimeSerializer 按 JVM 默认时区补偏移（与 GoTimeConvert 的既有对齐方式一致）。</p>
 */
// autoResultMap = true：jsonb/时间列的 typeHandler 在 MP 生成的 insert/update SQL 里
// 生效的前提（缺了会按 String 直写，真 PG 上报 jsonb 类型错——A/B 实测）
@TableName(value = "temporary_documents", autoResultMap = true)
@JsonPropertyOrder({"id", "tenant_id", "session_id", "file_name", "file_type", "mime_type",
        "file_size", "status", "image_refs", "metadata", "token_count", "chunk_count",
        "error_message", "expires_at", "started_at", "ready_at", "created_at", "updated_at"})
public class TemporaryDocument {

    public static final String STATUS_UPLOADED = "uploaded";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    @TableId(type = IdType.INPUT)  // Go BeforeCreate：uuid.NewString() 带连字符
    private String id;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("session_id")
    private String sessionId;

    /** Go json:"-"——不进响应。 */
    @JsonIgnore
    private String resourceRef;

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("file_type")
    private String fileType;

    @JsonProperty("mime_type")
    private String mimeType;

    @JsonProperty("file_size")
    private Long fileSize;

    @JsonProperty("status")
    private String status;

    /** Go json:"-"——不进响应。 */
    @JsonIgnore
    private String content;

    /** Go json:"-"——不进响应。 */
    @TableField(value = "chunks", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonIgnore
    private String chunks;

    /** image_refs（jsonb）：@JsonRawValue 对齐 Go types.JSON.MarshalJSON 的原样输出。 */
    @TableField(value = "image_refs", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonRawValue
    @JsonProperty("image_refs")
    private String imageRefs;

    /** metadata（jsonb）：同上，raw 输出。 */
    @TableField(value = "metadata", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonRawValue
    @JsonProperty("metadata")
    private String metadata;

    /** Go json:"-"——不进响应。 */
    @TableField(value = "processing_options", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonIgnore
    private String processingOptions;

    @JsonProperty("token_count")
    private Integer tokenCount;

    @JsonProperty("chunk_count")
    private Integer chunkCount;

    /** omitempty：空串省略。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("error_message")
    private String errorMessage;


    @TableField(value = "expires_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    @JsonProperty("expires_at")
    private OffsetDateTime expiresAt;

    /** omitempty：nil 省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @TableField(value = "started_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    @JsonProperty("started_at")
    private OffsetDateTime startedAt;

    /** omitempty：nil 省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @TableField(value = "ready_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    @JsonProperty("ready_at")
    private OffsetDateTime readyAt;


    @TableField(value = "created_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;


    @TableField(value = "updated_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /** Go gorm.DeletedAt json:"-"——不进响应。 */
    @JsonIgnore
    private OffsetDateTime deletedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getResourceRef() {
        return resourceRef;
    }

    public void setResourceRef(String resourceRef) {
        this.resourceRef = resourceRef;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getChunks() {
        return chunks;
    }

    public void setChunks(String chunks) {
        this.chunks = chunks;
    }

    public String getImageRefs() {
        return imageRefs;
    }

    public void setImageRefs(String imageRefs) {
        this.imageRefs = imageRefs;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getProcessingOptions() {
        return processingOptions;
    }

    public void setProcessingOptions(String processingOptions) {
        this.processingOptions = processingOptions;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(OffsetDateTime readyAt) {
        this.readyAt = readyAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
