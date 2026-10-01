package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 渲染给终端用户的一条**可归因**的追问建议（对照 Go {@code types.SuggestionItem}，
 * internal/types/message_suggestion.go L34-40）。
 *
 * <p>所谓"可归因"：每条建议有稳定的 ID，点击后会把
 * {@link SuggestionAttribution} 挂到下一条用户消息上，分析才能区分
 * "用户点了建议"与"用户自己打了一模一样的问题"。</p>
 *
 * <p><b>与 LLM 解析面的边界</b>（§14.9l S3 入场风险 1）：模型回复里的建议**不经 Jackson**
 * 反序列化到这个类——`MessageSuggestionPipeline.parseGeneratedSuggestions` 只逐字段读
 * {@code path("text")} / {@code path("category")} 后手工 new 出来。所以键名换 camelCase
 * 不影响提示词那条路（模型若回 {@code knowledge_base_ids} 本来也没人读）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuggestionItem {

    private String id = "";

    private String text = "";

    private String category;

    private String source = "";

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
