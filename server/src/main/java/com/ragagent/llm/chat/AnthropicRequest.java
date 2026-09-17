package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Anthropic Messages 请求体（对照 Go chat.anthropicRequest，
 * internal/models/chat/anthropic.go:49-59）。
 *
 * <p><b>字段序 = Go 声明序</b>：model, max_tokens, stream, system, messages,
 * temperature, top_p, tools, tool_choice。</p>
 *
 * <p>omitempty 语义逐条对齐：</p>
 * <ul>
 *   <li>{@code stream}：bool + omitempty，false 时省略（Java 用 NON_DEFAULT）；</li>
 *   <li>{@code system}：{@code any} + omitempty ⇒ <b>只有 nil 才省略</b>。空串是"非 nil 接口"，
 *       会照发 {@code "system":""}（见 {@link AnthropicChat#buildRequest}）；</li>
 *   <li>{@code temperature} / {@code top_p}：指针，nil 省略 → NON_NULL。</li>
 * </ul>
 */
@JsonPropertyOrder({
        "model", "max_tokens", "stream", "system", "messages", "temperature", "top_p", "tools", "tool_choice"
})
public class AnthropicRequest {

    @JsonProperty("model")
    private String model = "";
    @JsonProperty("max_tokens")
    private int maxTokens;
    @JsonProperty("stream")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean stream;
    /** 顶层 system：字符串，或 {@code List<AnthropicContentBlock>}（要打断点时） */
    @JsonProperty("system")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object system;
    @JsonProperty("messages")
    private List<AnthropicMessage> messages = new ArrayList<>();
    @JsonProperty("temperature")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double temperature;
    @JsonProperty("top_p")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double topP;
    @JsonProperty("tools")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<AnthropicTool> tools;
    @JsonProperty("tool_choice")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AnthropicToolChoice toolChoice;

    public String getModel() { return model; }
    public void setModel(String v) { model = v == null ? "" : v; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int v) { maxTokens = v; }
    public boolean isStream() { return stream; }
    public void setStream(boolean v) { stream = v; }
    public Object getSystem() { return system; }
    public void setSystem(Object v) { system = v; }
    public List<AnthropicMessage> getMessages() { return messages; }
    public void setMessages(List<AnthropicMessage> v) { messages = v == null ? new ArrayList<>() : v; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double v) { temperature = v; }
    public Double getTopP() { return topP; }
    public void setTopP(Double v) { topP = v; }
    public List<AnthropicTool> getTools() { return tools; }
    public void setTools(List<AnthropicTool> v) { tools = v; }
    public AnthropicToolChoice getToolChoice() { return toolChoice; }
    public void setToolChoice(AnthropicToolChoice v) { toolChoice = v; }

    /** 对照 Go 的 {@code req.Tools = append(req.Tools, ...)}。 */
    public void addTool(AnthropicTool tool) {
        if (tools == null) {
            tools = new ArrayList<>();
        }
        tools.add(tool);
    }
}
