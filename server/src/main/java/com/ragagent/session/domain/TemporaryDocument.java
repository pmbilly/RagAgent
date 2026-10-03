package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * 会话附件（临时文档）。
 *
 * <p>序列化要点：HTTP 响应键名＝Java 字段名（camelCase），
 * 无条件键（可空字段显式 null）；{@code resource_ref} / {@code content} / {@code chunks}
 * / {@code processing_options} / {@code deleted_at} 是 {@code @JsonIgnore}（不出现在响应里）。
 * 三列 Java 构造的 jsonb 同样走字段名：{@code chunks} 元素是
 * {@code TemporaryDocumentPromptResolver.DocumentChunk} 的字段名、{@code image_refs}
 * 元素是 {@code originalRef/url/mimeType}、{@code processing_options} 见
 * {@code TemporaryDocumentService.CreateOptions.toJson}。</p>
 *
 * <p>时间列在真库是 TIMESTAMP WITHOUT TIME ZONE（naive）：读路径按 JVM 默认时区
 * 补偏移后序列化（由 GoTimeSerializer 家族统一处理）。</p>
 */
// autoResultMap = true：jsonb/时间列的 typeHandler 在 MP 生成的 insert/update SQL 里
// 生效的前提（缺了会按 String 直写，真 PG 上报 jsonb 类型错——A/B 实测）
@TableName(value = "temporary_documents", autoResultMap = true)
public class TemporaryDocument {

    public static final String STATUS_UPLOADED = "uploaded";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    @TableId(type = IdType.INPUT)  // Go BeforeCreate：uuid.NewString() 带连字符
    private String id;

    private Long tenantId;

    private String sessionId;

    /** 不进响应。 */
    @JsonIgnore
    private String resourceRef;

    private String fileName;

    private String fileType;

    private String mimeType;

    private Long fileSize;

    private String status;

    /** 不进响应。 */
    @JsonIgnore
    private String content;

    /** 不进响应。 */
    @TableField(value = "chunks", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonIgnore
    private String chunks;

    /** image_refs（jsonb）：@JsonRawValue 对齐 Go types.JSON.MarshalJSON 的原样输出。 */
    @TableField(value = "image_refs", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonRawValue
    private String imageRefs;

    /** metadata（jsonb）：同上，raw 输出。 */
    @TableField(value = "metadata", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonRawValue
    private String metadata;

    /** 不进响应。 */
    @TableField(value = "processing_options", typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    @JsonIgnore
    private String processingOptions;

    private Integer tokenCount;

    private Integer chunkCount;

    /** omitempty：空串省略。 */
    private String errorMessage;


    @TableField(value = "expires_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    private OffsetDateTime expiresAt;

    /** omitempty：nil 省略。 */
    @TableField(value = "started_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    private OffsetDateTime startedAt;

    /** omitempty：nil 省略。 */
    @TableField(value = "ready_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    private OffsetDateTime readyAt;


    @TableField(value = "created_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    private OffsetDateTime createdAt;


    @TableField(value = "updated_at", typeHandler = com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler.class)
    private OffsetDateTime updatedAt;

    /** 软删除时间，不进响应。 */
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
