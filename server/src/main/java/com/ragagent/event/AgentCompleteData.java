package com.ragagent.event;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 完成事件数据（对照 Go {@code event.AgentCompleteData}，internal/event/event_data.go:138-149）。
 * emit 点：finalize.go:161（{@code generateEventID("complete")}），见包注释 emit 表 #16。
 *
 * <p>实录锚点：{@code knowledge_refs}/{@code agent_steps}/{@code usage} 带 omitempty——
 * 空列表也省略（{@code emptyRefs} 实录）；{@code total_duration_ms} 无 omitempty 恒输出。</p>
 */
@JsonPropertyOrder({"session_id", "total_steps", "final_answer", "knowledge_refs",
        "agent_steps", "usage", "total_duration_ms", "message_id", "request_id", "extra"})
public class AgentCompleteData {

    @JsonProperty("session_id")
    private String sessionId = "";

    @JsonProperty("total_steps")
    private int totalSteps;

    @JsonProperty("final_answer")
    private String finalAnswer = "";

    /** Go {@code []*types.SearchResult}；Go omitempty（nil 或空都省略，实录锚点） */
    @JsonProperty("knowledge_refs")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Object> knowledgeRefs;

    /** Go {@code []types.AgentStep}；Go omitempty */
    @JsonProperty("agent_steps")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Object agentSteps;

    /** Go {@code *types.TokenUsage}；Go omitempty */
    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Object usage;

    /** 无 omitempty：0 恒输出 */
    @JsonProperty("total_duration_ms")
    private long totalDurationMs;

    /** Assistant message ID；Go omitempty */
    @JsonProperty("message_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String messageId = "";

    /** Go omitempty */
    @JsonProperty("request_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String requestId = "";

    /** Go omitempty */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public AgentCompleteData() {
    }

    public AgentCompleteData(String sessionId, int totalSteps, String finalAnswer,
                             List<Object> knowledgeRefs, Object agentSteps, Object usage,
                             long totalDurationMs, String messageId, String requestId,
                             Map<String, Object> extra) {
        this.sessionId = QueryData.orEmpty(sessionId);
        this.totalSteps = totalSteps;
        this.finalAnswer = QueryData.orEmpty(finalAnswer);
        this.knowledgeRefs = knowledgeRefs;
        this.agentSteps = agentSteps;
        this.usage = usage;
        this.totalDurationMs = totalDurationMs;
        this.messageId = QueryData.orEmpty(messageId);
        this.requestId = QueryData.orEmpty(requestId);
        this.extra = extra;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(int v) {
        this.totalSteps = v;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String v) {
        this.finalAnswer = QueryData.orEmpty(v);
    }

    public List<Object> getKnowledgeRefs() {
        return knowledgeRefs;
    }

    public void setKnowledgeRefs(List<Object> v) {
        this.knowledgeRefs = v;
    }

    public Object getAgentSteps() {
        return agentSteps;
    }

    public void setAgentSteps(Object v) {
        this.agentSteps = v;
    }

    public Object getUsage() {
        return usage;
    }

    public void setUsage(Object v) {
        this.usage = v;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long v) {
        this.totalDurationMs = v;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String v) {
        this.messageId = QueryData.orEmpty(v);
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
