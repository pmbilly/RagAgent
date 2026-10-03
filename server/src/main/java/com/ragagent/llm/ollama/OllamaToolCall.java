package com.ragagent.llm.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Ollama 工具调用（对齐 ollama v0.23.2 API）。
 *
 * <p><b>Ollama 的工具调用没有语义化 ID</b>：它只有一个 {@code index}（整数）。
 * 本模块入站时用 {@code index} 转成字符串当 ID 用（见 OllamaChat.toolCallTo），
 * 出站时再把 ID 解析回整数（toolCallFrom）。</p>
 *
 * <p>{@code arguments} 是<b>对象</b>（不是 OpenAI 那样的 JSON 字符串），
 * 且恒输出——空值时给 {@code {}}（字段永不为 null）。</p>
 */
@JsonPropertyOrder({"id", "function"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaToolCall {

    @JsonProperty("id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String id;
    @JsonProperty("function")
    private Function function = new Function();

    public OllamaToolCall() {
    }

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Function getFunction() { return function; }
    public void setFunction(Function v) { function = v == null ? new Function() : v; }

    /** function 子对象（index / name / arguments 三者都恒输出）。 */
    @JsonPropertyOrder({"index", "name", "arguments"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {

        @JsonProperty("index")
        private int index;
        @JsonProperty("name")
        private String name = "";
        /** 参数对象，恒非 null：空即 {@code {}}。 */
        @JsonProperty("arguments")
        private ObjectNode arguments = JsonNodeFactory.instance.objectNode();

        public int getIndex() { return index; }
        public void setIndex(int v) { index = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v == null ? "" : v; }
        public ObjectNode getArguments() { return arguments; }
        public void setArguments(ObjectNode v) { arguments = v == null ? JsonNodeFactory.instance.objectNode() : v; }
    }
}
