package com.ragagent.llm.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Anthropic 工具选择（对照 Go chat.anthropicToolChoice，
 * internal/models/chat/anthropic_tools.go:17-21）。
 *
 * <p>取值映射（对照 Go anthropicToolOptions）：</p>
 * <ul>
 *   <li>"" / "auto" → {@code auto}</li>
 *   <li>"required" → {@code any}（Anthropic 的"必须调用某个工具"）</li>
 *   <li>"none" → {@code none}</li>
 *   <li>其它 → {@code tool} + name（指定工具名）</li>
 * </ul>
 *
 * <p>{@code disable_parallel_tool_use} 是 {@code *bool}：nil 省略，false 也照发
 * （正好与 OpenAI 的 parallel_tool_calls 语义相反，见 Go anthropicToolOptions 的注释）。</p>
 */
@JsonPropertyOrder({"type", "name", "disable_parallel_tool_use"})
public class AnthropicToolChoice {

    @JsonProperty("type")
    private String type = "";
    @JsonProperty("name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String name;
    @JsonProperty("disable_parallel_tool_use")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean disableParallelToolUse;

    public AnthropicToolChoice() {
    }

    public AnthropicToolChoice(String type) {
        this.type = type == null ? "" : type;
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public Boolean getDisableParallelToolUse() { return disableParallelToolUse; }
    public void setDisableParallelToolUse(Boolean v) { disableParallelToolUse = v; }
}
