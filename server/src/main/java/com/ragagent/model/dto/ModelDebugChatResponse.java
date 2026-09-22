package com.ragagent.model.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;

/**
 * models/{id}/debug 的 chat 分支 raw_response（对照 Go
 * {@code handler.modelDebugChatStreamResponse}，model.go:304-311）。
 *
 * <p>字段序 = Go struct 声明序。{@code stream_events} 无 omitempty 恒输出
 * （空也是 {@code []}）；{@code content} 恒输出；其余 omitempty。</p>
 */
@JsonPropertyOrder({"content", "reasoning_content", "tool_calls", "finish_reason", "usage", "stream_events"})
public class ModelDebugChatResponse {

    @JsonProperty("content")
    private String content = "";

    @JsonProperty("reasoning_content")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String reasoningContent;

    @JsonProperty("tool_calls")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ToolCall> toolCalls;

    @JsonProperty("finish_reason")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String finishReason;

    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TokenUsage usage;

    @JsonProperty("stream_events")
    private List<StreamResponse> streamEvents = new ArrayList<>();

    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }
    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String v) { reasoningContent = v; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> v) { toolCalls = v; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String v) { finishReason = v; }
    public TokenUsage getUsage() { return usage; }
    public void setUsage(TokenUsage v) { usage = v; }
    public List<StreamResponse> getStreamEvents() { return streamEvents; }
    public void setStreamEvents(List<StreamResponse> v) { streamEvents = v == null ? new ArrayList<>() : v; }
}
