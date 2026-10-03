package com.ragagent.llm.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic 工具定义。
 *
 * <p><b>三个字段都没有 omitempty</b>：description 为空串照发，input_schema 为 null 时输出
 * {@code "input_schema":null}。</p>
 *
 * <p>与 OpenAI 路径的关键差异：Anthropic 的 schema 字段名是 {@code input_schema}（不是
 * {@code parameters}），且 <b>schema 原样透传</b>——{@code $defs} / {@code $ref} / {@code oneOf}
 * / {@code additionalProperties} 全部保留。</p>
 */
@JsonPropertyOrder({"name", "description", "input_schema"})
public class AnthropicTool {

    @JsonProperty("name")
    private String name = "";
    @JsonProperty("description")
    private String description = "";
    @JsonProperty("input_schema")
    private JsonNode inputSchema;

    public AnthropicTool() {
    }

    public AnthropicTool(String name, String description, JsonNode inputSchema) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
    }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }
    public JsonNode getInputSchema() { return inputSchema; }
    public void setInputSchema(JsonNode v) { inputSchema = v; }
}
