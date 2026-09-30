package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 知识库的 VLM（图像描述）配置：开关、模型、描述语言与自定义指令等。
 *
 * <p>字段一律输出（含空串与 null）。
 */
public class KnowledgeBaseVlmConfig {

    private boolean enabled;
    private String modelId = "";
    private String descriptionLanguage;
    private String customInstructions;
    private String modelName = "";
    private String baseUrl = "";
    private String apiKey = "";
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

    /** 新版本 Enabled&&ModelID!=""，老版本 ModelName&&BaseURL。
     *  否则写入 jsonb 后回读触发 UnrecognizedPropertyException） */
    @JsonIgnore
    public boolean isMultimodalEnabled() {
        return (enabled && !modelId.isEmpty()) || (!modelName.isEmpty() && !baseUrl.isEmpty());
    }
}
