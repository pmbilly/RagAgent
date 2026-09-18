package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 这个人反复从中取材的文档（对照 Go {@code types.MemoryDocView}，
 * internal/types/memory.go:1060-1067）。
 *
 * <p>六个字段都无 omitempty，恒输出。</p>
 */
@JsonPropertyOrder({"id", "knowledge_id", "knowledge_base_id", "title", "hits", "last_used_at"})
public class MemoryDocView {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("knowledge_id")
    private String knowledgeId = "";

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    @JsonProperty("title")
    private String title = "";

    @JsonProperty("hits")
    private int hits;

    @JsonProperty("last_used_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime lastUsedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { title = v == null ? "" : v; }

    public int getHits() { return hits; }
    public void setHits(int v) { hits = v; }

    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime v) {
        lastUsedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /**
     * 对照 Go {@code MemoryDocViewFromAffinity}（internal/types/memory.go L1070-1082）。
     *
     * @return {@code row} 为 null 时回 null（对应 Go 的 {@code if row == nil { return nil }}）
     */
    public static MemoryDocView fromAffinity(MemoryDocAffinity row) {
        if (row == null) {
            return null;
        }
        MemoryDocView view = new MemoryDocView();
        view.setId(row.getId());
        view.setKnowledgeId(row.getKnowledgeId());
        view.setKnowledgeBaseId(row.getKnowledgeBaseId());
        view.setTitle(row.getTitle());
        view.setHits(row.getHits());
        view.setLastUsedAt(row.getLastUsedAt());
        return view;
    }
}
