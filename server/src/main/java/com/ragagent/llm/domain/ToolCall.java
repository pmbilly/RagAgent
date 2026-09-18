package com.ragagent.llm.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoMapSerializer;

/**
 * 工具调用（同时对照 Go chat.ToolCall 与 types.LLMToolCall——两者 JSON 形状相同，
 * Java 侧合并为一个类型，不影响线上契约）。
 *
 * 字段序 = Go 声明序。provider_metadata 带 omitempty。
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
     * 以免把厂商字段教给核心 agent 代码。Go 类型为 map[string]json.RawMessage。
     */
    @JsonProperty("provider_metadata")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    // Go 对 map 恒按字节序输出；Jackson 不排——不挂这个，多键 map 会与 Go 分叉
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, JsonNode> providerMetadata;

    /**
     * 以下为**请求内**观测状态（对照 types.LLMToolCall 的 ModelArguments 等）。
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

    /** 工具名（Go 侧到处用 tc.Function.Name 的快捷方式） */
    @JsonIgnore
    public String getToolName() {
        return function == null ? "" : function.getName();
    }
}
