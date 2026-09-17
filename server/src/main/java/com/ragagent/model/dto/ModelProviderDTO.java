package com.ragagent.model.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * ModelProviderDTO（对照 Go handler/model.go ModelProviderDTO）。
 * defaultUrls 为 map → 字母序；modelTypes 数组顺序 = 注册表声明顺序。
 */
@JsonPropertyOrder({"value", "label", "description", "defaultUrls", "modelTypes"})
public record ModelProviderDTO(
        @JsonProperty("value") String value,
        @JsonProperty("label") String label,
        @JsonProperty("description") String description,
        @JsonProperty("defaultUrls") Map<String, String> defaultUrls,
        @JsonProperty("modelTypes") List<String> modelTypes) {
}
