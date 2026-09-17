package com.ragagent.model.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * ModelParametersDTO（对照 Go dto.ModelParametersDTO）：
 * 除 api_key/app_secret 外的全部参数字段。ExtraConfig/CustomHeaders 为 map → 字母序。
 * 数值 0 / 空串按 Go 非指针语义恒输出（omitempty 字段除外）。
 */
@JsonPropertyOrder({
        "base_url", "interface_type", "embedding_parameters", "parameter_size", "provider",
        "extra_config", "custom_headers", "supports_vision",
        "context_window", "max_output_tokens", "max_concurrency", "app_id"
})
public record ModelParametersDTO(
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("interface_type") String interfaceType,
        @JsonProperty("embedding_parameters") EmbeddingParametersDTO embeddingParameters,
        @JsonProperty("parameter_size") String parameterSize,
        @JsonProperty("provider") String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("extra_config") Map<String, String> extraConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("custom_headers") Map<String, String> customHeaders,
        @JsonProperty("supports_vision") boolean supportsVision,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("context_window") Integer contextWindow,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("max_output_tokens") Integer maxOutputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("max_concurrency") Integer maxConcurrency,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("app_id") String appId) {

    /** 非 Admin+：剥离 base_url/extra_config/custom_headers（对照 CanViewIntegrationSecrets=false） */
    ModelParametersDTO withSecretsStripped() {
        return new ModelParametersDTO("", interfaceType, embeddingParameters,
                parameterSize, provider, null, null, supportsVision,
                contextWindow, maxOutputTokens, maxConcurrency, appId);
    }

    /** 内置模型对非系统管理员：额外剥离 app_id 并清空 base_url */
    ModelParametersDTO withBuiltinStripped() {
        return new ModelParametersDTO("", interfaceType, embeddingParameters,
                parameterSize, provider, null, null, supportsVision,
                contextWindow, maxOutputTokens, maxConcurrency, null);
    }

    @JsonPropertyOrder({"dimension", "truncate_prompt_tokens", "supports_dimension_override"})
    public record EmbeddingParametersDTO(
            @JsonProperty("dimension") int dimension,
            @JsonProperty("truncate_prompt_tokens") int truncatePromptTokens,
            @JsonProperty("supports_dimension_override") boolean supportsDimensionOverride) {
    }
}
