package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 步骤事件数据（对照 Go {@code event.AgentStepData}，internal/event/event_data.go:111-116）。
 *
 * <p>实录锚点：{@code tool_calls} 与 {@code duration_ms} 均无 omitempty——
 * 零值输出 {@code {"iteration":0,"thought":"","tool_calls":null,"duration_ms":0}}。</p>
 */
@JsonPropertyOrder({"iteration", "thought", "tool_calls", "duration_ms"})
public class AgentStepData {

    @JsonProperty("iteration")
    private int iteration;

    @JsonProperty("thought")
    private String thought = "";

    /** Go {@code []types.ToolCall}；无 omitempty：null 恒输出 */
    @JsonProperty("tool_calls")
    private Object toolCalls;

    /** 无 omitempty：0 恒输出 */
    @JsonProperty("duration_ms")
    private long durationMs;

    public AgentStepData() {
    }

    public AgentStepData(int iteration, String thought, Object toolCalls, long durationMs) {
        this.iteration = iteration;
        this.thought = QueryData.orEmpty(thought);
        this.toolCalls = toolCalls;
        this.durationMs = durationMs;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }

    public String getThought() {
        return thought;
    }

    public void setThought(String v) {
        this.thought = QueryData.orEmpty(v);
    }

    public Object getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(Object v) {
        this.toolCalls = v;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long v) {
        this.durationMs = v;
    }
}
