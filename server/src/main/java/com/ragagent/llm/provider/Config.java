package com.ragagent.llm.provider;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 服务商校验配置，JSON 键逐字段固定：
 * provider / base_url / api_key / model_name / model_id / extra（为空省略）。
 *
 * provider 为枚举：未知值 → null（见 {@link ProviderName#fromValue}），
 * 调用方需处理 null。
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
        // 缺省归一为空串
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        modelName = modelName == null ? "" : modelName;
        modelId = modelId == null ? "" : modelId;
    }


}
