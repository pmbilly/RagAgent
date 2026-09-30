package com.ragagent.event.payload;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 错误事件数据（对照 Go {@code event.ErrorData}，internal/event/event_data.go:64-71）。
 * agent 引擎唯一 emit 点：engine.go:362（stage="agent_execution"），见包注释 emit 表 #20。
 */
@JsonPropertyOrder({"error", "error_code", "stage", "session_id", "query", "extra"})
public class ErrorData {

    @JsonProperty("error")
    private String error = "";

    /** Go omitempty */
    @JsonProperty("error_code")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String errorCode = "";

    /** 错误发生的阶段（无 omitempty） */
    @JsonProperty("stage")
    private String stage = "";

    @JsonProperty("session_id")
    private String sessionId = "";

    /** Go omitempty */
    @JsonProperty("query")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String query = "";

    /** Go omitempty */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public ErrorData() {
    }

    public ErrorData(String error, String errorCode, String stage, String sessionId,
                     String query, Map<String, Object> extra) {
        this.error = QueryData.orEmpty(error);
        this.errorCode = QueryData.orEmpty(errorCode);
        this.stage = QueryData.orEmpty(stage);
        this.sessionId = QueryData.orEmpty(sessionId);
        this.query = QueryData.orEmpty(query);
        this.extra = extra;
    }

    public String getError() {
        return error;
    }

    public void setError(String v) {
        this.error = QueryData.orEmpty(v);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String v) {
        this.errorCode = QueryData.orEmpty(v);
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String v) {
        this.stage = QueryData.orEmpty(v);
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

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> v) {
        this.extra = v;
    }
}
