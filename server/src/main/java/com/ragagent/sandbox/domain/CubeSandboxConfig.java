package com.ragagent.sandbox.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code types.CubeSandboxConfig}（internal/types/tenant.go L689-704）。
 *
 * <p>寻址一个 CubeSandbox 部署。api_url/proxy_url/sandbox_domain/template_id 均必填；
 * api_key 可选（常见的单节点部署不启用认证）。落 jsonb + 出响应体双用，键序 = Go 声明序。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CubeSandboxConfig {

    @JsonProperty("api_url")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String apiUrl = "";

    @JsonProperty("proxy_url")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String proxyUrl = "";

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

    /** 0 = 内建默认 30s */
    @JsonProperty("http_timeout_sec")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int httpTimeoutSec;

    @JsonProperty("cube_sandbox_ttl_seconds")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int cubeSandboxTtlSeconds;

    /** Cube 模板 nameserver IP；空 = 用 Cubelet 默认 */
    @JsonProperty("dns_servers")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> dnsServers;

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String v) { apiUrl = v == null ? "" : v; }
    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String v) { proxyUrl = v == null ? "" : v; }
    public String getSandboxDomain() { return sandboxDomain; }
    public void setSandboxDomain(String v) { sandboxDomain = v == null ? "" : v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String v) { templateId = v == null ? "" : v; }
    public int getHttpTimeoutSec() { return httpTimeoutSec; }
    public void setHttpTimeoutSec(int v) { httpTimeoutSec = v; }
    public int getCubeSandboxTtlSeconds() { return cubeSandboxTtlSeconds; }
    public void setCubeSandboxTtlSeconds(int v) { cubeSandboxTtlSeconds = v; }
    public List<String> getDnsServers() { return dnsServers; }
    public void setDnsServers(List<String> v) { dnsServers = v; }

    public CubeSandboxConfig copy() {
        CubeSandboxConfig c = new CubeSandboxConfig();
        c.apiUrl = apiUrl;
        c.proxyUrl = proxyUrl;
        c.sandboxDomain = sandboxDomain;
        c.apiKey = apiKey;
        c.templateId = templateId;
        c.httpTimeoutSec = httpTimeoutSec;
        c.cubeSandboxTtlSeconds = cubeSandboxTtlSeconds;
        c.dnsServers = dnsServers == null ? null : new java.util.ArrayList<>(dnsServers);
        return c;
    }
}
