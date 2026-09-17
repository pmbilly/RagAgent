package com.ragagent.llm.ollama;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ollama {@code POST /api/chat} 请求体（对照 ollamaapi.ChatRequest，
 * ollama@v0.23.2/api/types.go:120-146）。
 *
 * <p>字段序 = Go 声明序（KeepAlive 本模块不设置，故略）。omitempty 语义：</p>
 * <ul>
 *   <li>{@code messages} / {@code options} 恒输出（Go 无 omitempty）；{@code options} 是
 *       "非 nil map"，所以哪怕只有 temperature 也会发 {@code {"temperature":0}}；</li>
 *   <li>{@code stream}：{@code *bool}，nil 省略 → NON_NULL；</li>
 *   <li>{@code format} / {@code tools} / {@code think}：空则省略。</li>
 * </ul>
 *
 * <p><b>{@code think}</b> 是 bool（或 "high"/"medium"/"low" 字符串）——Java 用 Object 承载。
 * 它是 Ollama 的"是否思考"开关，不是 OpenAI 的 reasoning_effort。</p>
 */
@JsonPropertyOrder({"model", "messages", "stream", "format", "tools", "options", "think"})
public class OllamaChatRequest {

    @JsonProperty("model")
    private String model = "";
    @JsonProperty("messages")
    private List<OllamaMessage> messages = new ArrayList<>();
    @JsonProperty("stream")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean stream;
    /** 响应格式约束，直接透传（对应 ChatOptions.format） */
    @JsonProperty("format")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode format;
    @JsonProperty("tools")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<OllamaTool> tools;
    /** 模型参数（temperature / top_p / num_predict 等），恒输出 */
    @JsonProperty("options")
    private Map<String, Object> options = new LinkedHashMap<>();
    /** 思考开关：Boolean（或 "high"/"medium"/"low" 字符串） */
    @JsonProperty("think")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object think;

    public String getModel() { return model; }
    public void setModel(String v) { model = v == null ? "" : v; }
    public List<OllamaMessage> getMessages() { return messages; }
    public void setMessages(List<OllamaMessage> v) { messages = v == null ? new ArrayList<>() : v; }
    public Boolean getStream() { return stream; }
    public void setStream(Boolean v) { stream = v; }
    public JsonNode getFormat() { return format; }
    public void setFormat(JsonNode v) { format = v; }
    public List<OllamaTool> getTools() { return tools; }
    public void setTools(List<OllamaTool> v) { tools = v; }
    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> v) { options = v == null ? new LinkedHashMap<>() : v; }
    public Object getThink() { return think; }
    public void setThink(Object v) { think = v; }

    /** 对照 Go {@code chatReq.Options["x"] = v}。 */
    public void putOption(String key, Object value) {
        if (options == null) {
            options = new LinkedHashMap<>();
        }
        options.put(key, value);
    }
}
