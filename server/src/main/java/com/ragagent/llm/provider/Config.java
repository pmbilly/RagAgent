package com.ragagent.llm.provider;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.common.error.BizException;
import com.ragagent.model.domain.Model;

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

    /**
     * 对照 Go NewConfigFromModel（provider.go）：
     * provider 为空则用 BaseURL 探测；Go 返回 error("model is nil") → Java 抛 BizException。
     *
     * ⚠️ 差异点：Go 对"非空但未知"的 provider 字符串原样保留，Java 枚举承载不了未知值，
     * fromValue 返回 null 时此处会改用 DetectProvider 探测（对已知厂商名行为完全一致）。
     */
    public static Config fromModel(Model model) {
        if (model == null) {
            throw BizException.badRequest("model is nil");
        }
        ProviderName providerName = ProviderName.fromValue(model.getParameters().getProvider());
        if (providerName == null) {
            // 对照 Go: if providerName == "" { providerName = DetectProvider(...) }
            providerName = ProviderRegistry.detectProvider(model.getParameters().getBaseUrl());
        }
        return new Config(
                providerName,
                model.getParameters().getBaseUrl(),
                model.getParameters().getApiKey(),
                model.getName(),
                model.getId(),
                null);
    }
}
