package com.ragagent.model.dto;

import java.util.Map;

import com.ragagent.model.domain.ModelParameters;

/**
 * 模型参数请求体（创建/更新共用；JSON 字段名即 Java 字段名）。
 *
 * <p>与响应投影 {@link ModelParametersDTO} 的差别：含 {@code apiKey}/{@code appSecret}
 * 两个密钥字段——创建时可携带；更新时经 PUT 正文携带的密钥会被忽略（WARN），
 * 凭证只走 {@code /models/{id}/credentials} 子资源。</p>
 */
public record ModelParametersRequest(
        String baseUrl,
        String apiKey,
        String interfaceType,
        ModelParametersDTO.EmbeddingParametersDTO embeddingParameters,
        String parameterSize,
        String provider,
        Map<String, String> extraConfig,
        Map<String, String> customHeaders,
        boolean supportsVision,
        Integer contextWindow,
        Integer maxOutputTokens,
        Integer maxConcurrency,
        String appId,
        String appSecret) {

    /** 转落库实体（setter 归一 null → 零值/空串，与历史"直接绑定实体"行为一致）。 */
    public ModelParameters toDomain() {
        ModelParameters p = new ModelParameters();
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setInterfaceType(interfaceType);
        if (embeddingParameters != null) {
            ModelParameters.EmbeddingParameters ep = new ModelParameters.EmbeddingParameters();
            ep.setDimension(embeddingParameters.dimension());
            ep.setTruncatePromptTokens(embeddingParameters.truncatePromptTokens());
            ep.setSupportsDimensionOverride(embeddingParameters.supportsDimensionOverride());
            p.setEmbeddingParameters(ep);
        }
        p.setParameterSize(parameterSize);
        p.setProvider(provider);
        p.setExtraConfig(extraConfig);
        p.setCustomHeaders(customHeaders);
        p.setSupportsVision(supportsVision);
        p.setContextWindow(contextWindow == null ? 0 : contextWindow);
        p.setMaxOutputTokens(maxOutputTokens == null ? 0 : maxOutputTokens);
        p.setMaxConcurrency(maxConcurrency == null ? 0 : maxConcurrency);
        p.setAppId(appId);
        p.setAppSecret(appSecret);
        return p;
    }
}
