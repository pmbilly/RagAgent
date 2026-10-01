package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 追问建议的归因（对照 Go {@code types.SuggestionAttribution}，
 * internal/types/message_suggestion.go L28-31）。
 *
 * <p>挂在点击建议之后的那条用户消息上，让分析能区分"用户点了建议"与"用户自己打了同样的问题"。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuggestionAttribution {

    private String suggestionSetId = "";

    private String questionId = "";

    public SuggestionAttribution() {
    }

    public String getSuggestionSetId() {
        return suggestionSetId;
    }

    public void setSuggestionSetId(String v) {
        this.suggestionSetId = v == null ? "" : v;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String v) {
        this.questionId = v == null ? "" : v;
    }
}
