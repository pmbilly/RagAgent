package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** ImageProcessingConfig：单字段 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbImageProcessingConfig {

    private String modelId = "";

    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
}
