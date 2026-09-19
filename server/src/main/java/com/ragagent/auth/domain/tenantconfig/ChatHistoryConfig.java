package com.ragagent.auth.domain.tenantconfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对照 Go {@code types.ChatHistoryConfig}（internal/types/chat_history_config.go L17-26）。
 * 三个字段都无 omitempty，恒输出。knowledge_base_id 由后端自动管理（隐藏 KB），
 * 客户端 PUT 携带的值会被丢弃（handler 重建对象）。
 */
@JsonPropertyOrder({"enabled", "embedding_model_id", "knowledge_base_id"})
public class ChatHistoryConfig {

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("embedding_model_id")
    private String embeddingModelId = "";

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }
}
