package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 聊天历史知识库统计（对照 Go {@code types.ChatHistoryKBStats}，types/message.go L560-574）。
 *
 * <p>键序按 Go struct 声明序。三个 id/名字字段带 {@code omitempty}（空串省略）；
 * {@code enabled}、{@code indexed_message_count}、{@code has_indexed_messages} 恒输出——
 * 未配置时响应就是 {@code {"enabled":false,"indexed_message_count":0,"has_indexed_messages":false}}。</p>
 */
@JsonPropertyOrder({
        "enabled", "embedding_model_id", "knowledge_base_id",
        "knowledge_base_name", "indexed_message_count", "has_indexed_messages"
})
public class ChatHistoryKbStats {

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("embedding_model_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String embeddingModelId = "";

    @JsonProperty("knowledge_base_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String knowledgeBaseId = "";

    @JsonProperty("knowledge_base_name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String knowledgeBaseName = "";

    @JsonProperty("indexed_message_count")
    private long indexedMessageCount;

    @JsonProperty("has_indexed_messages")
    private boolean hasIndexedMessages;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String v) {
        this.embeddingModelId = v == null ? "" : v;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String v) {
        this.knowledgeBaseId = v == null ? "" : v;
    }

    public String getKnowledgeBaseName() {
        return knowledgeBaseName;
    }

    public void setKnowledgeBaseName(String v) {
        this.knowledgeBaseName = v == null ? "" : v;
    }

    public long getIndexedMessageCount() {
        return indexedMessageCount;
    }

    public void setIndexedMessageCount(long v) {
        this.indexedMessageCount = v;
    }

    public boolean isHasIndexedMessages() {
        return hasIndexedMessages;
    }

    public void setHasIndexedMessages(boolean v) {
        this.hasIndexedMessages = v;
    }
}
