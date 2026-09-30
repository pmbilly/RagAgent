package com.ragagent.llm.domain;

import java.util.Map;

/**
 * 聊天实例配置（对照 Go chat.ChatConfig，internal/models/chat/chat.go:127-142）。
 *
 * 构造入口统一走 {@link #fromModel}（对照 Go ConfigFromModel）：
 * 生产路径（service 层按 DB 模型配置拉起实例）与测试路径（handler 层按前端表单
 * 临时拉起实例）必须走完全相同的字段映射，避免重复样板。
 */
public class ChatConfig {

    /** "local"（Ollama）或 "remote" */
    private String source;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private String modelId;
    /** provider 名，空则从 baseUrl 探测 */
    private String provider;
    /** 该模型后台调用的并发上限；0 = 用进程级默认（见 limiter.GateN） */
    private int maxConcurrency;
    /** api_version / remote_model_name / thinking_control 等 */
    private Map<String, String> extraConfig;
    /** 附加自定义 HTTP 头（类似 OpenAI Python SDK 的 extra_headers） */
    private Map<String, String> customHeaders;
    private String appId;
    /** 加密值：由工厂函数调用方传入，在 WeKnoraCloud 实现使用前已解密 */
    private String appSecret;

    public ChatConfig() {
    }



    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { modelName = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int v) { maxConcurrency = v; }
    public Map<String, String> getExtraConfig() { return extraConfig; }
    public void setExtraConfig(Map<String, String> v) { extraConfig = v; }
    public Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Map<String, String> v) { customHeaders = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { appId = v; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String v) { appSecret = v; }

    /** extraConfig 取值，缺省空串（对照 Go map 读取的零值语义）。 */
    public String extra(String key) {
        if (extraConfig == null) {
            return "";
        }
        String v = extraConfig.get(key);
        return v == null ? "" : v;
    }
}
