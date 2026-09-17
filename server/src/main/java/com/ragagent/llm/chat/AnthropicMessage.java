package com.ragagent.llm.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Anthropic 消息（对照 Go chat.anthropicMessage，
 * internal/models/chat/anthropic.go:44-47）。
 *
 * <p>{@code content} 是 {@code any}：普通对话是字符串；带 tool_use / tool_result 时是
 * {@code List<AnthropicContentBlock>}。两者都恒输出（Go 无 omitempty）。</p>
 */
@JsonPropertyOrder({"role", "content"})
public class AnthropicMessage {

    @JsonProperty("role")
    private String role = "";
    @JsonProperty("content")
    private Object content;

    public AnthropicMessage() {
    }

    public AnthropicMessage(String role, Object content) {
        this.role = role == null ? "" : role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String v) { role = v == null ? "" : v; }
    public Object getContent() { return content; }
    public void setContent(Object v) { content = v; }
}
