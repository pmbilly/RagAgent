package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * chunks 表实体（对照 Go types/chunk.go Chunk L113-178）。
 *
 * GORM 隐式行为清单：
 * - 软删除 → 显式 isNull("deleted_at")
 * - CreateChunks 用 Select("*") 显式插入（绕过 GORM default）——is_enabled/flags/status
 *   等零值由写路径显式赋值
 * - start_at/end_at 以 **rune（Unicode code point）** 计，不是 byte（分块器保证）
 * - relation_chunks/indirect_relation_chunks/metadata 为 json 列
 *
 * <p><b>JSON 是契约</b>（chunk 模块起本实体直接作响应体）：字段序 = Go struct
 * 声明序；{@code source_content} 与 {@code context_header} 是 {@code json:"-"}；
 * 三个 json 列对照 Go types.JSON.MarshalJSON——空/NULL 输出字面量 {@code null}；
 * 其余字段全部无 omitempty → 恒输出（含 deleted_at 的 null、is_enabled 的 false）。
 * {@code EmbeddingContent()} 在 Go 是方法（不序列化），Java 侧等价逻辑在
 * 索引同步处，实体上无此方法即无此坑。</p>
 */
@TableName(value = "chunks", autoResultMap = true)
@JsonPropertyOrder({
        "id", "seq_id", "tenant_id", "knowledge_id", "knowledge_base_id", "tag_id",
        "content", "content_revision", "index_status", "last_editor_id", "chunk_index",
        "is_enabled", "flags", "status", "start_at", "end_at",
        "pre_chunk_id", "next_chunk_id", "chunk_type", "parent_chunk_id",
        "relation_chunks", "indirect_relation_chunks", "metadata",
        "content_hash", "image_info", "created_at", "updated_at", "deleted_at",
})
public class Chunk {

    @TableId(type = IdType.INPUT)
    @JsonProperty("id")
    private String id;
    @JsonProperty("seq_id")
    private Long seqId;
    @JsonProperty("tenant_id")
    private Long tenantId;
    @JsonProperty("knowledge_id")
    private String knowledgeId;
    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId;
    @JsonProperty("tag_id")
    private String tagId;
    @JsonProperty("content")
    private String content;
    /** 不可变解析原文（json:"-"，无 JSON 输出） */
    @JsonIgnore
    private String sourceContent;
    @JsonProperty("content_revision")
    private int contentRevision;
    @JsonProperty("index_status")
    private String indexStatus = "ready";
    @JsonProperty("last_editor_id")
    private String lastEditorId;
    @JsonProperty("chunk_index")
    private int chunkIndex;
    @JsonProperty("is_enabled")
    private boolean isEnabled = true;
    @JsonProperty("flags")
    private int flags = 1;
    @JsonProperty("status")
    private int status;
    /** rune 偏移 */
    @JsonProperty("start_at")
    private int startAt;
    @JsonProperty("end_at")
    private int endAt;
    @JsonProperty("pre_chunk_id")
    private String preChunkId;
    @JsonProperty("next_chunk_id")
    private String nextChunkId;
    @JsonProperty("chunk_type")
    private String chunkType = "text";
    @JsonProperty("parent_chunk_id")
    private String parentChunkId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("relation_chunks")
    private JsonNode relationChunks;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("indirect_relation_chunks")
    private JsonNode indirectRelationChunks;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("metadata")
    private JsonNode metadata;
    @JsonProperty("content_hash")
    private String contentHash;
    @JsonProperty("image_info")
    private String imageInfo;
    /** 标题面包屑（json:"-"）；索引用 ContextHeader+"\n\n"+Content */
    @JsonIgnore
    private String contextHeader;
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;
    /** gorm.DeletedAt：活的行 Go 输出 null（Java null 字段不经序列化器，同为 null） */
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getSeqId() { return seqId; }
    public void setSeqId(Long v) { seqId = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public String getTagId() { return tagId; }
    public void setTagId(String v) { tagId = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String v) { sourceContent = v; }
    public int getContentRevision() { return contentRevision; }
    public void setContentRevision(int v) { contentRevision = v; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String v) { indexStatus = v; }
    public String getLastEditorId() { return lastEditorId; }
    public void setLastEditorId(String v) { lastEditorId = v; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int v) { chunkIndex = v; }
    @JsonProperty("is_enabled")
    public boolean isIsEnabled() { return isEnabled; }
    public void setIsEnabled(boolean v) { isEnabled = v; }
    public int getFlags() { return flags; }
    public void setFlags(int v) { flags = v; }
    public int getStatus() { return status; }
    public void setStatus(int v) { status = v; }
    public int getStartAt() { return startAt; }
    public void setStartAt(int v) { startAt = v; }
    public int getEndAt() { return endAt; }
    public void setEndAt(int v) { endAt = v; }
    public String getPreChunkId() { return preChunkId; }
    public void setPreChunkId(String v) { preChunkId = v; }
    public String getNextChunkId() { return nextChunkId; }
    public void setNextChunkId(String v) { nextChunkId = v; }
    public String getChunkType() { return chunkType; }
    public void setChunkType(String v) { chunkType = v == null ? "text" : v; }
    public String getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(String v) { parentChunkId = v; }
    public JsonNode getRelationChunks() { return relationChunks; }
    public void setRelationChunks(JsonNode v) { relationChunks = v; }
    public JsonNode getIndirectRelationChunks() { return indirectRelationChunks; }
    public void setIndirectRelationChunks(JsonNode v) { indirectRelationChunks = v; }
    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode v) { metadata = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v; }
    public String getImageInfo() { return imageInfo; }
    public void setImageInfo(String v) { imageInfo = v; }
    public String getContextHeader() { return contextHeader; }
    public void setContextHeader(String v) { contextHeader = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
