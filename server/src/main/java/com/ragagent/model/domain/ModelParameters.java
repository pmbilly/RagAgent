package com.ragagent.model.domain;

/**
 * ModelParameters（models.parameters jsonb 列的落库形态）。
 *
 * <p>写库前 apiKey/appSecret 加密、读库后宽容解密——由
 * {@link ModelParametersTypeHandler} 承担。键名 = Java 字段名（camelCase）；
 * extraConfig/customHeaders 为 map，序列化按键字母序；可空字段显式 null。</p>
 */
public class ModelParameters {

    private String baseUrl = "";
    private String apiKey = "";
    private String interfaceType = "";
    private EmbeddingParameters embeddingParameters = new EmbeddingParameters();
    private String parameterSize = "";
    private String provider = "";
    private java.util.Map<String, String> extraConfig;
    private java.util.Map<String, String> customHeaders;
    private boolean supportsVision;
    private int contextWindow;
    private int maxOutputTokens;
    private int maxConcurrency;
    private String appId = "";
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

    /** 向量维度参数（字段恒输出）。 */
    public static class EmbeddingParameters {
        private int dimension;
        private int truncatePromptTokens;
        private boolean supportsDimensionOverride;

        public int getDimension() { return dimension; }
        public void setDimension(int v) { dimension = v; }
        public int getTruncatePromptTokens() { return truncatePromptTokens; }
        public void setTruncatePromptTokens(int v) { truncatePromptTokens = v; }
        public boolean isSupportsDimensionOverride() { return supportsDimensionOverride; }
        public void setSupportsDimensionOverride(boolean v) { supportsDimensionOverride = v; }
    }
}
