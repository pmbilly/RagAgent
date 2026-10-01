package com.ragagent.model.dto;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;

/**
 * models/{id}/debug 的 chat 分支 rawResponse（流式聚合结果）。
 *
 * <p>{@code content} 恒输出（可能为空串）；{@code streamEvents} 恒为数组
 * （空也是 {@code []}）；其余字段未产出时显式 null。{@code streamEvents}/{@code usage}
 * 的元素是 LLM 域事件载荷（Byte 契约保留面），其内部键名不变。</p>
 */
public class ModelDebugChatResponse {

    private String content = "";

    private String reasoningContent;

    private List<ToolCall> toolCalls;

    private String finishReason;

    private TokenUsage usage;

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
