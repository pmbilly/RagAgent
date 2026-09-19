package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** ImageProcessingConfig（对照 Go types/knowledgebase.go L523）：单字段 */
@JsonPropertyOrder({"model_id"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbImageProcessingConfig {

    @JsonProperty("model_id")
    private String modelId = "";

    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
}
