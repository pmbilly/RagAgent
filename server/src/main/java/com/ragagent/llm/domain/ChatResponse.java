package com.ragagent.llm.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 非流式聊天响应（对照 Go types.ChatResponse，internal/types/chat.go:187-208）。
 *
 * 字段序 = Go struct 声明序；usage 无 omitempty 恒输出（对象/零值），
 * 其余 omitempty → NON_EMPTY。
 */
@JsonPropertyOrder({"content", "reasoning_content", "tool_calls", "finish_reason", "usage"})
public class ChatResponse {

    @JsonProperty("content")
    private String content = "";
    /**
     * 支持思考链的模型（DeepSeek thinking / MiMo / vLLM reasoning 等）本轮输出的推理内容。
     * 需要在后续多轮请求中**原样回传**给那些严格校验的供应商。
     */
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
    private TokenUsage usage = new TokenUsage();

    /**
     * 用户可见的答案文本本轮是否已经实时流出到最终答案区（即模型用纯文本作答）。
     * 为 true 时自然停止分支只能补一个 Done 标记，不得重发整段答案——否则答案会
     * 渲染两次并在流末尾"跳动"。瞬时状态，永不持久化。
     */
    @JsonIgnore
    private boolean answerStreamed;
    /** AnswerStreamed 为 true 时，实况答案块所在的 EventBus 事件 ID。 */
    @JsonIgnore
    private String answerEventId;

    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }
    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String v) { reasoningContent = v; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> v) { toolCalls = v; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String v) { finishReason = v; }
    public TokenUsage getUsage() { return usage; }
    public void setUsage(TokenUsage v) { usage = v == null ? new TokenUsage() : v; }
    public boolean isAnswerStreamed() { return answerStreamed; }
    public void setAnswerStreamed(boolean v) { answerStreamed = v; }
    public String getAnswerEventId() { return answerEventId; }
    public void setAnswerEventId(String v) { answerEventId = v; }
}
