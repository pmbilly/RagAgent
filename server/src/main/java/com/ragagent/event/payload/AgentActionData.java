package com.ragagent.event.payload;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 工具执行事件数据（对照 Go {@code event.AgentActionData}，internal/event/event_data.go:119-127）。
 * emit 点：act.go:359（{@code <toolCallID>-tool-exec}），见包注释 emit 表 #18。
 *
 * <p>实录锚点：{@code tool_input} 无 omitempty——nil map 输出 {@code "tool_input":null}；
 * {@code error} 带 omitempty——成功路径整键省略。</p>
 */
@JsonPropertyOrder({"iteration", "tool_name", "tool_input", "tool_output", "success",
        "error", "duration_ms"})
public class AgentActionData {

    @JsonProperty("iteration")
    private int iteration;

    @JsonProperty("tool_name")
    private String toolName = "";

    /** 无 omitempty：null（Go nil map）恒输出 */
    @JsonProperty("tool_input")
    private Map<String, Object> toolInput;

    @JsonProperty("tool_output")
    private String toolOutput = "";

    /** 无 omitempty：false 恒输出 */
    @JsonProperty("success")
    private boolean success;

    /** Go omitempty */
    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String error = "";

    /** 无 omitempty：0 恒输出 */
    @JsonProperty("duration_ms")
    private long durationMs;

    public AgentActionData() {
    }

    public AgentActionData(int iteration, String toolName, Map<String, Object> toolInput,
                           String toolOutput, boolean success, String error, long durationMs) {
        this.iteration = iteration;
        this.toolName = QueryData.orEmpty(toolName);
        this.toolInput = toolInput;
        this.toolOutput = QueryData.orEmpty(toolOutput);
        this.success = success;
        this.error = QueryData.orEmpty(error);
        this.durationMs = durationMs;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String v) {
        this.toolName = QueryData.orEmpty(v);
    }

    public Map<String, Object> getToolInput() {
        return toolInput;
    }

    public void setToolInput(Map<String, Object> v) {
        this.toolInput = v;
    }

    public String getToolOutput() {
        return toolOutput;
    }

    public void setToolOutput(String v) {
        this.toolOutput = QueryData.orEmpty(v);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean v) {
        this.success = v;
    }

    public String getError() {
        return error;
    }

    public void setError(String v) {
        this.error = QueryData.orEmpty(v);
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long v) {
        this.durationMs = v;
    }
}
