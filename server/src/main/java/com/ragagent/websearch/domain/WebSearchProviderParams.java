package com.ragagent.websearch.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 对照 Go {@code types.WebSearchProviderParameters}（internal/types/web_search_provider.go）。
 *
 * <p>api_key 落库加密（AES-GCM，enc:v1: 前缀）由 {@link WebSearchParamsTypeHandler}
 * 在 Value/Scan 语义的位置处理；响应 DTO（{@code dto.WebSearchProviderResponse}）
 * 按构造摘除 api_key——密钥从不出现在响应里（Go 的 dto 层不变式）。</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：Go 的 json.Unmarshal 默认
 * 忽略未知键；裸 SQL / 未来演进写入的 parameters 可能带本类不认识的键——
 * 没有它会整行读不出来（§9「波 2 FAQ 补充」教训，本批 config 列逐个挂）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSearchProviderParams {

    /** API key（落库加密；响应永不回显） */
        private String apiKey = "";

    /** Google CSE engine id */
        private String engineId = "";

    /** 自托管搜索引擎地址（SearXNG） */
        private String baseUrl = "";

    /** 出站代理（仅隧道官方 API） */
        private String proxyUrl = "";

    /** provider 特有的非秘密扩展配置 */
        private Map<String, String> extraConfig;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public String getEngineId() { return engineId; }
    public void setEngineId(String v) { engineId = v == null ? "" : v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v == null ? "" : v; }
    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String v) { proxyUrl = v == null ? "" : v; }
    public Map<String, String> getExtraConfig() { return extraConfig; }
    public void setExtraConfig(Map<String, String> v) { extraConfig = v; }
}
