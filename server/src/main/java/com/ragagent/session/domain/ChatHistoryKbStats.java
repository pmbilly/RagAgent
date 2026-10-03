package com.ragagent.session.domain;


/**
 * 聊天历史知识库统计。
 *
 * <p>键序＝声明序、键名＝Java 字段名。六个键**全部恒输出**（§1.6）：空串写 {@code ""}、0/false 照写；
 * {@code enabled}、{@code indexed_message_count}、{@code has_indexed_messages} 恒输出——
 * 未配置时响应就是 {@code {"enabled":false,"indexed_message_count":0,"has_indexed_messages":false}}。</p>
 */
public class ChatHistoryKbStats {

    private boolean enabled;

    private String embeddingModelId = "";

    private String knowledgeBaseId = "";

    private String knowledgeBaseName = "";

    private long indexedMessageCount;

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
