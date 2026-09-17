package com.ragagent.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.model.domain.ModelParameters;

/** 创建模型请求（对照 Go CreateModelRequest）：name/type/source/parameters 必填 */
public record CreateModelRequest(
        @JsonProperty("name") String name,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("type") String type,
        @JsonProperty("source") String source,
        @JsonProperty("description") String description,
        @JsonProperty("parameters") ModelParameters parameters) {
}
