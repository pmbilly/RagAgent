package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * VLMConfig（对照 Go types/knowledgebase.go L563）。
 * enabled/model_id/model_name/base_url/api_key/interface_type 无 omitempty 恒输出；
 * description_language/custom_instructions 带 omitempty。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "enabled", "model_id", "description_language", "custom_instructions",
        "model_name", "base_url", "api_key", "interface_type"
})
public class KbVlmConfig {

    @JsonProperty("enabled")
    private boolean enabled;
    @JsonProperty("model_id")
    private String modelId = "";
    @JsonProperty("description_language")
    private String descriptionLanguage;
    @JsonProperty("custom_instructions")
    private String customInstructions;
    @JsonProperty("model_name")
    private String modelName = "";
    @JsonProperty("base_url")
    private String baseUrl = "";
    @JsonProperty("api_key")
    private String apiKey = "";
    @JsonProperty("interface_type")
    private String interfaceType = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
    public String getDescriptionLanguage() { return descriptionLanguage; }
    public void setDescriptionLanguage(String v) { descriptionLanguage = v; }
    public String getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(String v) { customInstructions = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { modelName = v == null ? "" : v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v == null ? "" : v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String v) { interfaceType = v == null ? "" : v; }

    /** 对照 IsEnabled：新版本 Enabled&&ModelID!=""，老版本 ModelName&&BaseURL。
     *  @JsonIgnore：Go 方法非字段，不参与 JSON（序列化/反序列化都要排除，
     *  否则写入 jsonb 后回读触发 UnrecognizedPropertyException） */
    @JsonIgnore
    public boolean isMultimodalEnabled() {
        return (enabled && !modelId.isEmpty()) || (!modelName.isEmpty() && !baseUrl.isEmpty());
    }
}
