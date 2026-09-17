package com.ragagent.model.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * ModelParameters（对照 Go types/model.go ModelParameters），json 列映射。
 *
 * 写库前 api_key/app_secret 加密、读库后宽容解密——由
 * {@link ModelParametersTypeHandler} 承担（对照 Go 的 Value()/Scan() driver 钩子）。
 * ExtraConfig/CustomHeaders 为 map → Jackson 序列化按 key 字母序（与 Go map 序列化一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "base_url", "api_key", "interface_type", "embedding_parameters", "parameter_size",
        "provider", "extra_config", "custom_headers", "supports_vision",
        "context_window", "max_output_tokens", "max_concurrency", "app_id", "app_secret"
})
public class ModelParameters {

    @JsonProperty("base_url")
    private String baseUrl = "";
    @JsonProperty("api_key")
    private String apiKey = "";
    @JsonProperty("interface_type")
    private String interfaceType = "";
    @JsonProperty("embedding_parameters")
    private EmbeddingParameters embeddingParameters = new EmbeddingParameters();
    @JsonProperty("parameter_size")
    private String parameterSize = "";
    @JsonProperty("provider")
    private String provider = "";
    @JsonProperty("extra_config")
    private java.util.Map<String, String> extraConfig;
    @JsonProperty("custom_headers")
    private java.util.Map<String, String> customHeaders;
    @JsonProperty("supports_vision")
    private boolean supportsVision;
    @JsonProperty("context_window")
    private int contextWindow;
    @JsonProperty("max_output_tokens")
    private int maxOutputTokens;
    @JsonProperty("max_concurrency")
    private int maxConcurrency;
    @JsonProperty("app_id")
    private String appId = "";
    @JsonProperty("app_secret")
    private String appSecret = "";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v == null ? "" : v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String v) { interfaceType = v == null ? "" : v; }
    public EmbeddingParameters getEmbeddingParameters() { return embeddingParameters; }
    public void setEmbeddingParameters(EmbeddingParameters v) { embeddingParameters = v == null ? new EmbeddingParameters() : v; }
    public String getParameterSize() { return parameterSize; }
    public void setParameterSize(String v) { parameterSize = v == null ? "" : v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
    public java.util.Map<String, String> getExtraConfig() { return extraConfig; }
    public void setExtraConfig(java.util.Map<String, String> v) { extraConfig = v; }
    public java.util.Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(java.util.Map<String, String> v) { customHeaders = v; }
    public boolean isSupportsVision() { return supportsVision; }
    public void setSupportsVision(boolean v) { supportsVision = v; }
    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int v) { contextWindow = v; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int v) { maxOutputTokens = v; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int v) { maxConcurrency = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { appId = v == null ? "" : v; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String v) { appSecret = v == null ? "" : v; }

    /** 写库拷贝：加密时不能污染内存中的明文（对照 Go value receiver） */
    public ModelParameters copy() {
        ModelParameters cp = new ModelParameters();
        cp.baseUrl = baseUrl;
        cp.apiKey = apiKey;
        cp.interfaceType = interfaceType;
        cp.embeddingParameters = embeddingParameters;
        cp.parameterSize = parameterSize;
        cp.provider = provider;
        cp.extraConfig = extraConfig;
        cp.customHeaders = customHeaders;
        cp.supportsVision = supportsVision;
        cp.contextWindow = contextWindow;
        cp.maxOutputTokens = maxOutputTokens;
        cp.maxConcurrency = maxConcurrency;
        cp.appId = appId;
        cp.appSecret = appSecret;
        return cp;
    }

    /** EmbeddingParameters（Go 值类型，字段恒输出） */
    @JsonPropertyOrder({"dimension", "truncate_prompt_tokens", "supports_dimension_override"})
    public static class EmbeddingParameters {
        @JsonProperty("dimension")
        private int dimension;
        @JsonProperty("truncate_prompt_tokens")
        private int truncatePromptTokens;
        @JsonProperty("supports_dimension_override")
        private boolean supportsDimensionOverride;

        public int getDimension() { return dimension; }
        public void setDimension(int v) { dimension = v; }
        public int getTruncatePromptTokens() { return truncatePromptTokens; }
        public void setTruncatePromptTokens(int v) { truncatePromptTokens = v; }
        public boolean isSupportsDimensionOverride() { return supportsDimensionOverride; }
        public void setSupportsDimensionOverride(boolean v) { supportsDimensionOverride = v; }
    }
}
