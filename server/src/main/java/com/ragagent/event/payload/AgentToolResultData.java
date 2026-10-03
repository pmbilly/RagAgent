package com.ragagent.event.payload;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 工具执行结果数据。
 * emit 点：ActPhase（{@code <toolCallID>-tool-result}），见包注释 emit 表 #17。
 *
 * <p>零值输出
 * {@code {"tool_call_id":"","tool_name":"","output":"","success":false,"iteration":0}}；
 * {@code error}/{@code duration_ms}/{@code data} 空则省略。</p>
 */
@JsonPropertyOrder({"tool_call_id", "tool_name", "output", "error", "success",
        "duration_ms", "iteration", "data"})
public class AgentToolResultData {

    /** 工具调用 ID（追踪用） */
    @JsonProperty("tool_call_id")
    private String toolCallId = "";

    @JsonProperty("tool_name")
    private String toolName = "";

    @JsonProperty("output")
    private String output = "";

    /** 空串省略 */
    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String error = "";

    /** false 恒输出 */
    @JsonProperty("success")
    private boolean success;

    /** 0 省略 */
    @JsonProperty("duration_ms")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long durationMs;

    @JsonProperty("iteration")
    private int iteration;

    /** 工具结果的结构化数据（display_type、格式化结果等）；null 或空省略 */
    @JsonProperty("data")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> data;

    public AgentToolResultData() {
    }

    public AgentToolResultData(String toolCallId, String toolName, String output, String error,
                               boolean success, long durationMs, int iteration,
                               Map<String, Object> data) {
        this.toolCallId = QueryData.orEmpty(toolCallId);
        this.toolName = QueryData.orEmpty(toolName);
        this.output = QueryData.orEmpty(output);
        this.error = QueryData.orEmpty(error);
        this.success = success;
        this.durationMs = durationMs;
        this.iteration = iteration;
        this.data = data;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String v) {
        this.toolCallId = QueryData.orEmpty(v);
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String v) {
        this.toolName = QueryData.orEmpty(v);
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String v) {
        this.output = QueryData.orEmpty(v);
    }

    public String getError() {
        return error;
    }

    public void setError(String v) {
        this.error = QueryData.orEmpty(v);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean v) {
        this.success = v;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long v) {
        this.durationMs = v;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> v) {
        this.data = v;
    }
}
