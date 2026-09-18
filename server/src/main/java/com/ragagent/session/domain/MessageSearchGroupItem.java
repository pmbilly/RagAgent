package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoDoubleSerializer;

/**
 * 搜索结果里的合并 Q&amp;A 对（对照 Go {@code types.MessageSearchGroupItem}，
 * types/message.go L531-550）。
 *
 * <p><b>响应体形态</b>：{@code POST /messages/search} 的 data.items 元素就是本类型，
 * 所以键序按 Go struct 声明序（不是字母序，§9 的键序规则）。</p>
 *
 * <p><b>score 必须挂 {@link GoDoubleSerializer}</b>（逐字段，勿全局注册——§9.2）：
 * Go 的 float64 最短表示输出 {@code 1} 而不是 {@code 1.0}；关键词路径的分值是
 * {@code (n-i)/n}，单个结果时正好是 {@code 1}，Java 的裸 double 会写成 {@code 1.0}。</p>
 */
@JsonPropertyOrder({
        "request_id", "session_id", "session_title", "query_content",
        "answer_content", "score", "match_type", "created_at"
})
public class MessageSearchGroupItem {

    @JsonProperty("request_id")
    private String requestId = "";

    @JsonProperty("session_id")
    private String sessionId = "";

    @JsonProperty("session_title")
    private String sessionTitle = "";

    @JsonProperty("query_content")
    private String queryContent = "";

    @JsonProperty("answer_content")
    private String answerContent = "";

    @JsonProperty("score")
    @JsonSerialize(using = GoDoubleSerializer.class)
    private double score;

    @JsonProperty("match_type")
    private String matchType = "";

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        this.requestId = v == null ? "" : v;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = v == null ? "" : v;
    }

    public String getSessionTitle() {
        return sessionTitle;
    }

    public void setSessionTitle(String v) {
        this.sessionTitle = v == null ? "" : v;
    }

    public String getQueryContent() {
        return queryContent;
    }

    public void setQueryContent(String v) {
        this.queryContent = v == null ? "" : v;
    }

    public String getAnswerContent() {
        return answerContent;
    }

    public void setAnswerContent(String v) {
        this.answerContent = v == null ? "" : v;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double v) {
        this.score = v;
    }

    public String getMatchType() {
        return matchType;
    }

    public void setMatchType(String v) {
        this.matchType = v == null ? "" : v;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }
}
