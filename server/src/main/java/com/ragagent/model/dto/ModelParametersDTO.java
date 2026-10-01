package com.ragagent.model.dto;

import java.util.Map;

/**
 * 模型参数响应投影（除 apiKey/appSecret 外的全部参数字段；JSON 字段名即 Java 字段名）。
 *
 * <p>可空字段显式输出 null（未设置的 contextWindow/maxOutputTokens/maxConcurrency/appId、
 * 无额外配置时的 extraConfig/customHeaders）。</p>
 */
public record ModelParametersDTO(
        String baseUrl,
        String interfaceType,
        EmbeddingParametersDTO embeddingParameters,
        String parameterSize,
        String provider,
        Map<String, String> extraConfig,
        Map<String, String> customHeaders,
        boolean supportsVision,
        Integer contextWindow,
        Integer maxOutputTokens,
        Integer maxConcurrency,
        String appId) {

    /** 非 Admin+：剥离 baseUrl/extraConfig/customHeaders */
    ModelParametersDTO withSecretsStripped() {
        return new ModelParametersDTO("", interfaceType, embeddingParameters,
                parameterSize, provider, null, null, supportsVision,
                contextWindow, maxOutputTokens, maxConcurrency, appId);
    }

    /** 内置模型对非系统管理员：额外剥离 appId 并清空 baseUrl */
    ModelParametersDTO withBuiltinStripped() {
        return new ModelParametersDTO("", interfaceType, embeddingParameters,
                parameterSize, provider, null, null, supportsVision,
                contextWindow, maxOutputTokens, maxConcurrency, null);
    }

    /** 向量维度参数（请求/响应共用）。 */
    public record EmbeddingParametersDTO(
            int dimension,
            int truncatePromptTokens,
            boolean supportsDimensionOverride) {
    }
}
