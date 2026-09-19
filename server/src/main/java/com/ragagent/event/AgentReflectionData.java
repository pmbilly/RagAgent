package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 反思数据（对照 Go {@code event.AgentReflectionData}，internal/event/event_data.go:233-238）。
 *
 * <p>四字段全无 omitempty：零值恒输出
 * {@code {"tool_call_id":"","content":"","iteration":0,"done":false}}。</p>
 */
@JsonPropertyOrder({"tool_call_id", "content", "iteration", "done"})
public class AgentReflectionData {

    /** 工具调用 ID（追踪用） */
    @JsonProperty("tool_call_id")
    private String toolCallId = "";

    @JsonProperty("content")
    private String content = "";

    @JsonProperty("iteration")
    private int iteration;

    /** 流式是否完成 */
    @JsonProperty("done")
    private boolean done;

    public AgentReflectionData() {
    }

    public AgentReflectionData(String toolCallId, String content, int iteration, boolean done) {
        this.toolCallId = QueryData.orEmpty(toolCallId);
        this.content = QueryData.orEmpty(content);
        this.iteration = iteration;
        this.done = done;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String v) {
        this.toolCallId = QueryData.orEmpty(v);
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = QueryData.orEmpty(v);
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean v) {
        this.done = v;
    }
}
