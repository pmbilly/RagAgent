package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.ragagent.common.web.GoDoubleSerializer;

/**
 * 搜索结果里的合并 Q&amp;A 对。
 *
 * <p><b>响应体形态</b>：{@code POST /messages/search} 的 data.items 元素就是本类型，
 * 键名＝Java 字段名、键序＝声明序。</p>
 *
 * <p><b>score 必须挂 {@link GoDoubleSerializer}</b>（逐字段，勿全局注册）：
 * 分值序列化用最短表示，输出 {@code 1} 而不是 {@code 1.0}；关键词路径的分值是
 * {@code (n-i)/n}，单个结果时正好是 {@code 1}，裸 double 会写成 {@code 1.0}。</p>
 */
public class MessageSearchGroupItem {

    private String requestId = "";

    private String sessionId = "";

    private String sessionTitle = "";

    private String queryContent = "";

    private String answerContent = "";

    private double score;

    private String matchType = "";

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
