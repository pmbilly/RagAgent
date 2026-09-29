package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * chunk_revisions 表实体。
 * <p><b>JSON 是契约</b>：handler 直接序列化本实体（{@code ListChunkRevisions} 的
 * 恒输出（is_enabled 的 false 也输出）。</p>
 * <p>仓储行为契约（约定 §3）：</p>
 * <ol>
 *   <li><b>钩子/自动时间戳</b>：无 BeforeCreate；{@code created_at} 由 service 显式赋值
 *   <li><b>唯一约束</b>：{@code (chunk_id, revision)} 唯一索引（迁移 000078 的
 *       以 SQL 为准）。</li>
 *   <li><b>软删除</b>：无 DeletedAt，物理行。</li>
 *   <li><b>零值语义</b>：字符串列 NOT NULL DEFAULT ''，Java 侧由写入方显式赋值
 *       （editor_id 可为 ""）。</li>
 * </ol>
 * <p><b>字段名不取 {@code isEnabled}</b>（约定 §7.5：is 前缀字段会让 Jackson 多吐一个键），
 * 按既有 Session.pinned 模式命名为 {@code enabled} + 列名 {@code is_enabled}。</p>
 */
@TableName("chunk_revisions")
@JsonPropertyOrder({
        "id", "tenant_id", "knowledge_base_id", "knowledge_id", "chunk_id",
        "revision", "content", "is_enabled", "editor_id", "edit_source",
        "edited_at", "created_at",
})
public class ChunkRevision {

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId;

    @JsonProperty("knowledge_id")
    private String knowledgeId;

    @JsonProperty("chunk_id")
    private String chunkId;

    @JsonProperty("revision")
    private int revision;

    @JsonProperty("content")
    private String content;

    @TableField("is_enabled")
    @JsonProperty("is_enabled")
    private boolean enabled;

    @JsonProperty("editor_id")
    private String editorId;

    @JsonProperty("edit_source")
    private String editSource;

    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    @JsonProperty("edited_at")
    private OffsetDateTime editedAt;

    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String v) { chunkId = v; }
    public int getRevision() { return revision; }
    public void setRevision(int v) { revision = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getEditorId() { return editorId; }
    public void setEditorId(String v) { editorId = v; }
    public String getEditSource() { return editSource; }
    public void setEditSource(String v) { editSource = v; }
    public OffsetDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(OffsetDateTime v) { editedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
}
