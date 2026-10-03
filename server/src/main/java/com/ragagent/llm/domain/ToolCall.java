package com.ragagent.llm.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具调用。
 *
 * JSON 字段序 = 声明序。provider_metadata 为空时省略。
 */
@JsonPropertyOrder({"id", "type", "function", "provider_metadata"})
public class ToolCall {

    @JsonProperty("id")
    private String id = "";
    /** "function" */
    @JsonProperty("type")
    private String type = "function";
    @JsonProperty("function")
    private FunctionCall function = new FunctionCall();
    /**
     * 厂商特有状态（如 Gemini 的 extra_content），必须随 assistant 工具调用原样往返，
     * 以免把厂商字段教给核心 agent 代码。序列化时 map 按键字母序输出，
     * 保持与既有线格式一致。
     */
    @JsonProperty("provider_metadata")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, JsonNode> providerMetadata;

    /**
     * 以下为**请求内**观测状态（不参与线上 JSON）。
     * ModelArguments 保留模型发出的原始 JSON，而 Function.Arguments 在工具执行前
     * 会被解码成持久的应用标识。这些字段**绝不可**回传 provider 或持久化进聊天历史。
     */
    @JsonIgnore
    private String modelArguments;
    @JsonIgnore
    private String argumentResolution;
    @JsonIgnore
    private java.util.List<String> unresolvedHandles;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }
    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "function" : v; }
    public FunctionCall getFunction() { return function; }
    public void setFunction(FunctionCall v) { function = v == null ? new FunctionCall() : v; }
    public Map<String, JsonNode> getProviderMetadata() { return providerMetadata; }
    public void setProviderMetadata(Map<String, JsonNode> v) { providerMetadata = v; }
    public String getModelArguments() { return modelArguments; }
    public void setModelArguments(String v) { modelArguments = v; }
    public String getArgumentResolution() { return argumentResolution; }
    public void setArgumentResolution(String v) { argumentResolution = v; }
    public java.util.List<String> getUnresolvedHandles() { return unresolvedHandles; }
    public void setUnresolvedHandles(java.util.List<String> v) { unresolvedHandles = v; }

    /** 工具名 */
    @JsonIgnore
    public String getToolName() {
        return function == null ? "" : function.getName();
    }
}
