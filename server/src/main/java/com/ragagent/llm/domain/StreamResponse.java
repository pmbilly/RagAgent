package com.ragagent.llm.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 流式响应（对照 Go types.StreamResponse，internal/types/chat.go:292-304）。
 *
 * 字段序 = Go struct 声明序（struct 响应按声明序输出，见约定 §9）。
 * id/response_type/content/done 恒输出；其余 omitempty → NON_EMPTY。
 *
 * 线上契约：这是 SSE 事件体的核心结构，`response_type` 取值见 {@link ResponseType}。
 */
@JsonPropertyOrder({
        "id", "response_type", "content", "done", "knowledge_references",
        "session_id", "assistant_message_id", "tool_calls", "data", "usage", "finish_reason"
})
public class StreamResponse {

    @JsonProperty("id")
    private String id = "";
    @JsonProperty("response_type")
    private ResponseType responseType;
    @JsonProperty("content")
    private String content = "";
    @JsonProperty("done")
    private boolean done;
    /**
     * 检索引用（Go: types.References = []*SearchResult）。
     * 类型随检索模块（阶段 5/7）细化——chat 包本身从不设置该字段，
     * 仅需保证序列化时原样透传。
     */
    @JsonProperty("knowledge_references")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Object> knowledgeReferences;
    @JsonProperty("session_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String sessionId;
    @JsonProperty("assistant_message_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String assistantMessageId;
    @JsonProperty("tool_calls")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ToolCall> toolCalls;
    @JsonProperty("data")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> data;
    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private TokenUsage usage;
    @JsonProperty("finish_reason")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String finishReason;

    /** 流在 provider 发出 finish_reason 之前中断（读错误/超时/停滞）时使用。 */
    public static final String FINISH_REASON_INCOMPLETE = "incomplete";

    public StreamResponse() {
    }

    /** 常用构造：类型 + 内容 + 完成标记 */
    public static StreamResponse of(ResponseType type, String content, boolean done) {
        StreamResponse r = new StreamResponse();
        r.responseType = type;
        r.content = content == null ? "" : content;
        r.done = done;
        return r;
    }

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }
    public ResponseType getResponseType() { return responseType; }
    public void setResponseType(ResponseType v) { responseType = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }
    public boolean isDone() { return done; }
    public void setDone(boolean v) { done = v; }
    public List<Object> getKnowledgeReferences() { return knowledgeReferences; }
    public void setKnowledgeReferences(List<Object> v) { knowledgeReferences = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { sessionId = v; }
    public String getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(String v) { assistantMessageId = v; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> v) { toolCalls = v; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> v) { data = v; }
    public TokenUsage getUsage() { return usage; }
    public void setUsage(TokenUsage v) { usage = v; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String v) { finishReason = v; }
}
