package com.ragagent.llm.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ollama 工具定义（对齐 ollama v0.23.2 API）。
 *
 * <p><b>schema 原样透传</b>（JsonNode）：不做强类型结构体的有损往返，
 * {@code oneOf} / {@code additionalProperties} / {@code $ref} 等
 * 未建模的 JSON Schema 关键字全部保留——信息只会更完整，不会更少。</p>
 *
 * <p>JSON 字段序 = 声明序：type / function 恒输出，description 空则省略，
 * parameters 恒输出（null 时为 JSON null）。</p>
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

    /** function 子对象。 */
    @JsonPropertyOrder({"name", "description", "parameters"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {

        @JsonProperty("name")
        private String name = "";
        @JsonProperty("description")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String description;
        /**
         * JSON Schema，原样透传（见类注释）。
         * 恒输出，null 时输出 JSON null。
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
