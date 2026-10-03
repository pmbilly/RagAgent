package com.ragagent.llm.ollama;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Ollama 聊天消息（对齐 ollama v0.23.2 API）。
 *
 * <p>JSON 字段序 = 声明序：role / content 恒输出，其余为空时省略（NON_EMPTY）。</p>
 *
 * <p>{@code images} 是<b>原始字节</b>（{@code byte[][]}）：
 * 序列化为 base64 字符串数组（{@code byte[]} 的标准 JSON 编码）。</p>
 */
@JsonPropertyOrder({"role", "content", "thinking", "images", "tool_calls", "tool_name", "tool_call_id"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaMessage {

    @JsonProperty("role")
    private String role = "";
    @JsonProperty("content")
    private String content = "";
    /** 思考内容（仅响应侧有值；请求侧 OllamaChat 从不设置） */
    @JsonProperty("thinking")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String thinking;
    @JsonProperty("images")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<byte[]> images;
    @JsonProperty("tool_calls")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<OllamaToolCall> toolCalls;
    /** role=tool 时的工具名 */
    @JsonProperty("tool_name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String toolName;
    @JsonProperty("tool_call_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String toolCallId;

    public OllamaMessage() {
    }

    public OllamaMessage(String role, String content) {
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
    }

    public String getRole() { return role; }
    public void setRole(String v) { role = v == null ? "" : v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }
    public String getThinking() { return thinking; }
    public void setThinking(String v) { thinking = v; }
    public List<byte[]> getImages() { return images; }
    public void setImages(List<byte[]> v) { images = v; }
    public List<OllamaToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<OllamaToolCall> v) { toolCalls = v; }
    public String getToolName() { return toolName; }
    public void setToolName(String v) { toolName = v; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String v) { toolCallId = v; }
}
