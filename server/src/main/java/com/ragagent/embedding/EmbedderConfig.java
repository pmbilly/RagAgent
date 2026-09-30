package com.ragagent.embedding;

import java.util.Map;


/**
 * embedder 构造配置（对照 Go {@code embedding.Config}，embedder.go L43-61）。
 *
 * <p>可变类而非 record：工厂链里 {@code SetSupportsDimensionOverride} 会就地改内层
 * embedder，Config 本身按值传递。</p>
 */
public final class EmbedderConfig {

    private String source = "";
    private String baseUrl = "";
    private String modelName = "";
    private String apiKey = "";
    private int truncatePromptTokens;
    private int dimensions;
    private boolean supportsDimensionOverride;
    private String modelId = "";
    private String provider = "";
    /** 0 = 回退进程级默认上限（见 limiter.GateN）。 */
    private int maxConcurrency;
    private Map<String, String> extraConfig;
    /** 调远程 API 时附加的自定义 HTTP 请求头（类似 OpenAI Python SDK 的 extra_headers）。 */
    private Map<String, String> customHeaders;
    private String appId = "";
    /** 加密值，工厂函数调用方传入，使用前已解密。 */
    private String appSecret = "";

    public EmbedderConfig() {
    }



    public String getSource() { return source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v == null ? "" : v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { modelName = v == null ? "" : v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public int getTruncatePromptTokens() { return truncatePromptTokens; }
    public void setTruncatePromptTokens(int v) { truncatePromptTokens = v; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int v) { dimensions = v; }
    public boolean isSupportsDimensionOverride() { return supportsDimensionOverride; }
    public void setSupportsDimensionOverride(boolean v) { supportsDimensionOverride = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int v) { maxConcurrency = v; }
    public Map<String, String> getExtraConfig() { return extraConfig; }
    public void setExtraConfig(Map<String, String> v) { extraConfig = v; }
    public Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Map<String, String> v) { customHeaders = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { appId = v == null ? "" : v; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String v) { appSecret = v == null ? "" : v; }
}
