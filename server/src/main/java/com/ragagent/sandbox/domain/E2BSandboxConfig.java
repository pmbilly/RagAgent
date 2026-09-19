package com.ragagent.sandbox.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code types.E2BSandboxConfig}（internal/types/tenant.go L706-732）。
 *
 * <p>寻址一个 E2B 协议控制面：E2B Cloud、自托管 E2B Infrastructure 或任何 E2B 兼容实现。
 * api_key/template_id 必填；api_url/sandbox_domain 可选（go-e2b 在为空时自行解析两者）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class E2BSandboxConfig {

    @JsonProperty("api_url")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String apiUrl = "";

    @JsonProperty("sandbox_domain")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String sandboxDomain = "";

    /** 🔒 落库加密（Value/Scan 钩子），响应打码为 "***" */
    @JsonProperty("api_key")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String apiKey = "";

    @JsonProperty("template_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String templateId = "";

    /** envd 前的数据面网关；空 = 走公共 DNS + TLS 解析（E2B Cloud 形态） */
    @JsonProperty("proxy_url")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String proxyUrl = "";

    /** 0 = 内建默认 30s */
    @JsonProperty("http_timeout_sec")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int httpTimeoutSec;

    @JsonProperty("e2b_sandbox_ttl_seconds")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int e2bSandboxTtlSeconds;

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String v) { apiUrl = v == null ? "" : v; }
    public String getSandboxDomain() { return sandboxDomain; }
    public void setSandboxDomain(String v) { sandboxDomain = v == null ? "" : v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String v) { templateId = v == null ? "" : v; }
    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String v) { proxyUrl = v == null ? "" : v; }
    public int getHttpTimeoutSec() { return httpTimeoutSec; }
    public void setHttpTimeoutSec(int v) { httpTimeoutSec = v; }
    public int getE2bSandboxTtlSeconds() { return e2bSandboxTtlSeconds; }
    public void setE2bSandboxTtlSeconds(int v) { e2bSandboxTtlSeconds = v; }

    public E2BSandboxConfig copy() {
        E2BSandboxConfig c = new E2BSandboxConfig();
        c.apiUrl = apiUrl;
        c.sandboxDomain = sandboxDomain;
        c.apiKey = apiKey;
        c.templateId = templateId;
        c.proxyUrl = proxyUrl;
        c.httpTimeoutSec = httpTimeoutSec;
        c.e2bSandboxTtlSeconds = e2bSandboxTtlSeconds;
        return c;
    }
}
