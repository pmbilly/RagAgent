package com.ragagent.event;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 查询相关事件数据（对照 Go {@code event.QueryData}，internal/event/event_data.go:8-14）。
 *
 * <p>字段序 = Go struct 声明序；带 omitempty 的字段 {@code NON_DEFAULT}/{@code NON_EMPTY}
 * （0/空/false/null 省略，对照 Go 的零值省略），不带 omitempty 的恒输出（零值也输出）。</p>
 */
@JsonPropertyOrder({"original_query", "rewritten_query", "session_id", "user_id", "extra"})
public class QueryData {

    @JsonProperty("original_query")
    private String originalQuery = "";

    /** Go omitempty */
    @JsonProperty("rewritten_query")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String rewrittenQuery = "";

    @JsonProperty("session_id")
    private String sessionId = "";

    /** Go omitempty */
    @JsonProperty("user_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String userId = "";

    /** Go omitempty（nil 或空 map 都省略） */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public QueryData() {
    }

    public QueryData(String originalQuery, String rewrittenQuery, String sessionId,
                     String userId, Map<String, Object> extra) {
        this.originalQuery = orEmpty(originalQuery);
        this.rewrittenQuery = orEmpty(rewrittenQuery);
        this.sessionId = orEmpty(sessionId);
        this.userId = orEmpty(userId);
        this.extra = extra;
    }

    static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public void setOriginalQuery(String v) {
        this.originalQuery = orEmpty(v);
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String v) {
        this.rewrittenQuery = orEmpty(v);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = orEmpty(v);
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String v) {
        this.userId = orEmpty(v);
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> v) {
        this.extra = v;
    }
}
