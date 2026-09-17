package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义（对照 Go chat.Tool）。字段序 = Go 声明序，两者均恒输出。
 */
@JsonPropertyOrder({"type", "function"})
public class ChatTool {

    /** 恒为 "function" */
    @JsonProperty("type")
    private String type = "function";
    @JsonProperty("function")
    private FunctionDef function = new FunctionDef();

    public ChatTool() {
    }

    public ChatTool(String name, String description, JsonNode parameters) {
        this.function = new FunctionDef(name, description, parameters);
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "function" : v; }
    public FunctionDef getFunction() { return function; }
    public void setFunction(FunctionDef v) { function = v == null ? new FunctionDef() : v; }
}
