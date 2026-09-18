package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 渲染给终端用户的一条**可归因**的追问建议（对照 Go {@code types.SuggestionItem}，
 * internal/types/message_suggestion.go L34-40）。
 *
 * <p>所谓"可归因"：每条建议有稳定的 ID，点击后会把
 * {@link SuggestionAttribution} 挂到下一条用户消息上，分析才能区分
 * "用户点了建议"与"用户自己打了一模一样的问题"。</p>
 */
@JsonPropertyOrder({"id", "text", "category", "source", "knowledge_base_ids"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuggestionItem {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("text")
    private String text = "";

    @JsonProperty("category")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String category;

    @JsonProperty("source")
    private String source = "";

    @JsonProperty("knowledge_base_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> knowledgeBaseIds;

    public SuggestionItem() {
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v == null ? "" : v;
    }

    public String getText() {
        return text;
    }

    public void setText(String v) {
        this.text = v == null ? "" : v;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String v) {
        this.category = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String v) {
        this.source = v == null ? "" : v;
    }

    public List<String> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<String> v) {
        this.knowledgeBaseIds = v;
    }
}
