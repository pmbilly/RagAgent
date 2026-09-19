package com.ragagent.event;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 查询事件数据（对照 Go {@code event.AgentQueryData}，internal/event/event_data.go:130-135）。
 */
@JsonPropertyOrder({"session_id", "query", "request_id", "extra"})
public class AgentQueryData {

    @JsonProperty("session_id")
    private String sessionId = "";

    @JsonProperty("query")
    private String query = "";

    /** Go omitempty */
    @JsonProperty("request_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String requestId = "";

    /** Go omitempty */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public AgentQueryData() {
    }

    public AgentQueryData(String sessionId, String query, String requestId,
                          Map<String, Object> extra) {
        this.sessionId = QueryData.orEmpty(sessionId);
        this.query = QueryData.orEmpty(query);
        this.requestId = QueryData.orEmpty(requestId);
        this.extra = extra;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String v) {
        this.query = QueryData.orEmpty(v);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        this.requestId = QueryData.orEmpty(v);
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> v) {
        this.extra = v;
    }
}
