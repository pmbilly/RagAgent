package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
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
 */
@TableName(value = "chunks", autoResultMap = true)
public class Chunk {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long seqId;
    private Long tenantId;
    private String knowledgeId;
    private String knowledgeBaseId;
    private String tagId;
    private String content;
    /** 不可变解析原文（无 JSON 输出） */
    private String sourceContent;
    private int contentRevision;
    private String indexStatus = "ready";
    private String lastEditorId;
    private int chunkIndex;
    private boolean isEnabled = true;
    private int flags = 1;
    private int status;
    /** rune 偏移 */
    private int startAt;
    private int endAt;
    private String preChunkId;
    private String nextChunkId;
    private String chunkType = "text";
    private String parentChunkId;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode relationChunks;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode indirectRelationChunks;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode metadata;
    private String contentHash;
    private String imageInfo;
    /** 标题面包屑；索引用 EmbeddingContent()=ContextHeader+"\n\n"+Content */
    private String contextHeader;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
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
