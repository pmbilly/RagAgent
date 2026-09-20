package com.ragagent.rerank;

import java.util.Map;

import com.ragagent.model.domain.Model;

/**
 * reranker 构造配置（对照 Go {@code rerank.RerankerConfig}）。
 */
public final class RerankerConfig {

    private String apiKey = "";
    private String baseUrl = "";
    private String modelName = "";
    private String source = "";
    private String modelId = "";
    /** Provider 标识：openai/aliyun/zhipu/siliconflow/jina/generic 等。 */
    private String provider = "";
    private Map<String, String> extraConfig;
    /** 调远程 API 时附加的自定义 HTTP 请求头。 */
    private Map<String, String> customHeaders;
    private String appId = "";
    /** 加密值，工厂函数调用方传入，使用前已解密（LKEAP/Volcengine 的 SecretKey 槽位）。 */
    private String appSecret = "";

    public RerankerConfig() {
    }

    /**
     * 对照 {@code ConfigFromModel}（reranker.go L98-114）：生产/测试连接路径共享。
     * 返回 null 对照 Go 的 {@code (nil) }（m == nil → nil config）。
     */
    public static RerankerConfig configFromModel(Model m, String appId, String appSecret) {
        if (m == null) {
            return null;
        }
        RerankerConfig c = new RerankerConfig();
        var p = m.getParameters();
        c.modelId = m.getId() == null ? "" : m.getId();
        c.apiKey = p == null ? "" : p.getApiKey();
        c.baseUrl = p == null ? "" : p.getBaseUrl();
        c.modelName = m.getName() == null ? "" : m.getName();
        c.source = m.getSource() == null ? "" : m.getSource();
        c.provider = p == null ? "" : p.getProvider();
        c.extraConfig = p == null ? null : p.getExtraConfig();
        c.customHeaders = p == null ? null : p.getCustomHeaders();
        c.appId = appId;
        c.appSecret = appSecret;
        return c;
    }

    public String getApiKey() { return apiKey == null ? "" : apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public String getBaseUrl() { return baseUrl == null ? "" : baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v == null ? "" : v; }
    public String getModelName() { return modelName == null ? "" : modelName; }
    public void setModelName(String v) { modelName = v == null ? "" : v; }
    public String getSource() { return source == null ? "" : source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getModelId() { return modelId == null ? "" : modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
    public String getProvider() { return provider == null ? "" : provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
    public Map<String, String> getExtraConfig() { return extraConfig; }
    public void setExtraConfig(Map<String, String> v) { extraConfig = v; }
    public Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Map<String, String> v) { customHeaders = v; }
    public String getAppId() { return appId == null ? "" : appId; }
    public void setAppId(String v) { appId = v == null ? "" : v; }
    public String getAppSecret() { return appSecret == null ? "" : appSecret; }
    public void setAppSecret(String v) { appSecret = v == null ? "" : v; }
}
