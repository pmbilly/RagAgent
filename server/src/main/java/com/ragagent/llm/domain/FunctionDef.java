package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 函数定义。JSON 字段序 = 声明序，三者均恒输出。
 */
@JsonPropertyOrder({"name", "description", "parameters"})
public class FunctionDef {

    @JsonProperty("name")
    private String name = "";
    @JsonProperty("description")
    private String description = "";
    /** JSON Schema（JsonNode 自由构造，原样透传） */
    @JsonProperty("parameters")
    private JsonNode parameters;

    public FunctionDef() {
    }

    public FunctionDef(String name, String description, JsonNode parameters) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.parameters = parameters;
    }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }
    public JsonNode getParameters() { return parameters; }
    public void setParameters(JsonNode v) { parameters = v; }
}
