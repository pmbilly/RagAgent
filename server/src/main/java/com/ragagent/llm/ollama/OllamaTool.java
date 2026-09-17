package com.ragagent.llm.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ollama 工具定义（对照 ollamaapi.Tool / ToolFunction，
 * ollama@v0.23.2/api/types.go:300-320 与 500-505）。
 *
 * <p><b>⚠️ 与 Go 的一处差异（已在任务报告里登记，需要主会话决策）</b>：</p>
 * <p>Go 把调用方的 schema 反序列化进强类型结构体 {@code ollamaapi.ToolFunctionParameters}，
 * 于是<b>未被建模的 JSON Schema 关键字会被静默丢弃</b>——实测会丢
 * {@code oneOf} / {@code additionalProperties}，以及 properties 里的 {@code $ref}
 * （{@code ToolProperty} 没有 $ref 字段），只保留 type / $defs / items / required /
 * properties（且 property 内部同样只剩它建模的那几个键）。
 * Java 侧没有该 SDK，直接<b>原样透传</b> schema（JsonNode）——信息只会更完整，不会更少。</p>
 *
 * <p>字段序 = Go 声明序：type / function 恒输出，description 空则省略，
 * parameters 恒输出（nil 时为 JSON null）。</p>
 */
@JsonPropertyOrder({"type", "items", "function"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaTool {

    @JsonProperty("type")
    private String type = "function";
    @JsonProperty("items")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode items;
    @JsonProperty("function")
    private Function function = new Function();

    public OllamaTool() {
    }

    public OllamaTool(String type, Function function) {
        this.type = type == null ? "function" : type;
        this.function = function == null ? new Function() : function;
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "function" : v; }
    public JsonNode getItems() { return items; }
    public void setItems(JsonNode v) { items = v; }
    public Function getFunction() { return function; }
    public void setFunction(Function v) { function = v == null ? new Function() : v; }

    /** 对照 ollamaapi.ToolFunction。 */
    @JsonPropertyOrder({"name", "description", "parameters"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {

        @JsonProperty("name")
        private String name = "";
        @JsonProperty("description")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String description;
        /**
         * JSON Schema，原样透传（见类注释里与 Go 的差异说明）。
         * Go 的 {@code Parameters} 无 omitempty → 恒输出，null 时输出 JSON null。
         */
        @JsonProperty("parameters")
        private JsonNode parameters;

        public Function() {
        }

        public Function(String name, String description, JsonNode parameters) {
            this.name = name == null ? "" : name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public void setName(String v) { name = v == null ? "" : v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public JsonNode getParameters() { return parameters; }
        public void setParameters(JsonNode v) { parameters = v; }
    }
}
