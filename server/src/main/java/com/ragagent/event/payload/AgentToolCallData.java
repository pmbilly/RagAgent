package com.ragagent.event.payload;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 工具调用通知数据（对照 Go {@code event.AgentToolCallData}，internal/event/event_data.go:162-168）。
 * emit 点：think.go:348（pending）/ think.go:361（progress）/ act.go:477（hint），见包注释 emit 表 #3/#4/#19。
 *
 * <p>实录锚点：{@code arguments} 带 omitempty——nil 与空 map 都省略（emptyArgs 实录）；
 * {@code hint} 带 omitempty（人可读提示，如 {@code web_search("query")}）。</p>
 */
@JsonPropertyOrder({"tool_call_id", "tool_name", "arguments", "iteration", "hint"})
public class AgentToolCallData {

    /** 工具调用 ID（追踪用） */
    @JsonProperty("tool_call_id")
    private String toolCallId = "";

    @JsonProperty("tool_name")
    private String toolName = "";

    /** Go omitempty */
    @JsonProperty("arguments")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> arguments;

    @JsonProperty("iteration")
    private int iteration;

    /** 人可读的工具提示，如 {@code web_search("query")}；Go omitempty */
    @JsonProperty("hint")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String hint = "";

    public AgentToolCallData() {
    }

    public AgentToolCallData(String toolCallId, String toolName, Map<String, Object> arguments,
                             int iteration, String hint) {
        this.toolCallId = QueryData.orEmpty(toolCallId);
        this.toolName = QueryData.orEmpty(toolName);
        this.arguments = arguments;
        this.iteration = iteration;
        this.hint = QueryData.orEmpty(hint);
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

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> v) {
        this.arguments = v;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String v) {
        this.hint = QueryData.orEmpty(v);
    }
}
