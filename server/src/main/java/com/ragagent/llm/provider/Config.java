package com.ragagent.llm.provider;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go provider.Config（provider.go），json tag 逐字段对齐：
 * provider / base_url / api_key / model_name / model_id / extra(omitempty)。
 *
 * Go 的 Provider 字段类型是 ProviderName（string 别名，可承载未知厂商名）；
 * Java 用枚举，未知值 → null（见 {@link ProviderName#fromValue} 的差异说明），
 * 调用方按 Go 的 default 分支处理 null。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Config(
        @JsonProperty("provider") ProviderName provider,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("model_id") String modelId,
        @JsonProperty("extra") Map<String, Object> extra) {

    public Config {
        // Go 非指针 string 零值 = ""（约定 §9）
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        modelName = modelName == null ? "" : modelName;
        modelId = modelId == null ? "" : modelId;
    }


}
