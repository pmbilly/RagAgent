package com.ragagent.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.model.domain.ModelParameters;

/**
 * 更新模型请求（对照 Go UpdateModelRequest）。
 * displayName 为指针语义：null = 不修改；name 空串 = 不修改；
 * type/source/description/parameters 无条件覆盖（Go 行为，golden 已锁定空值覆盖语义）。
 */
public record UpdateModelRequest(
        @JsonProperty("name") String name,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("parameters") ModelParameters parameters,
        @JsonProperty("source") String source,
        @JsonProperty("type") String type) {
}
