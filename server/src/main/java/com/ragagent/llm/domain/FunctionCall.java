package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 函数调用详情（对照 Go chat.FunctionCall / types.FunctionCall，形状一致）。
 * 字段序 = Go 声明序。
 */
@JsonPropertyOrder({"name", "arguments"})
public class FunctionCall {

    @JsonProperty("name")
    private String name = "";
    /** JSON 字符串（**不是**对象——Go 侧同样是 string，Ollama 路径另用 map）。 */
    @JsonProperty("arguments")
    private String arguments = "";

    public FunctionCall() {
    }

    public FunctionCall(String name, String arguments) {
        this.name = name == null ? "" : name;
        this.arguments = arguments == null ? "" : arguments;
    }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getArguments() { return arguments; }
    public void setArguments(String v) { arguments = v == null ? "" : v; }
}
