package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** ASRConfig：三字段恒输出 */
@JsonPropertyOrder({"enabled", "model_id", "language"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbAsrConfig {

    @JsonProperty("enabled")
    private boolean enabled;
    @JsonProperty("model_id")
    private String modelId = "";
    @JsonProperty("language")
    private String language = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v == null ? "" : v; }
}
