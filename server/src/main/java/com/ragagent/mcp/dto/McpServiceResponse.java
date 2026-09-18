package com.ragagent.mcp.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpConfigFingerprint;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;
import com.ragagent.common.web.GoMapSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * MCP 服务的主资源响应（对照 Go dto.MCPServiceResponse，internal/handler/dto/mcp.go:24-50）。
 *
 * <p><b>为什么单独一个 DTO 包</b>：响应形态刻意与持久化实体分离，使"响应里没有秘密"
 * 成为<b>编译期不变式</b>而不是运行时脱敏步骤。将来有人想把凭据放进响应，
 * 必须显式在本类里加字段——泄漏面因此收敛到一次可评审的 diff。</p>
 *
 * <p><b>剥离规则（逐条对照 Go NewMCPServiceResponse）</b>：</p>
 * <ol>
 *   <li>{@code includeDetail = CanViewIntegrationSecrets(ctx)}（Admin+）；为 false 时
 *       剥离 url / headers / env_vars / stdio_config / advanced_config，
 *       并把 auth_config 的 custom_headers 置空。</li>
 *   <li><b>内置服务额外剥离</b>：url / headers / env_vars / stdio_config / auth_config
 *       且不返回 credentials（内置行跨租户共享，绝不能泄漏"本租户怎么配的上游"）。
 *       注意 Go 在这条分支里<b>不动 advanced_config</b>——本类照抄该行为。</li>
 *   <li>非内置服务返回 credentials 布尔映射（api_key / token 是否已配置）。</li>
 * </ol>
 *
 * <p>字段序 = Go struct 声明序（注意 usage_instructions 在 Go 里是第一个字段）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "usage_instructions", "id", "tenant_id", "name", "description", "enabled",
        "transport_type", "url", "headers", "auth_config", "advanced_config",
        "stdio_config", "env_vars", "is_builtin", "created_at", "updated_at",
        "credentials", "catalog"
})
public class McpServiceResponse {

    /** Go 无 omitempty → 恒输出 */
    @JsonProperty("usage_instructions")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String usageInstructions = "";
    @JsonProperty("id")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String id = "";
    @JsonProperty("tenant_id")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private long tenantId;
    @JsonProperty("name")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String name = "";
    @JsonProperty("description")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String description = "";
    @JsonProperty("enabled")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private boolean enabled;
    @JsonProperty("transport_type")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String transportType = "";
    @JsonProperty("url")
    private String url;
    @JsonProperty("headers")
    // Go 对 map 恒按字节序输出；Jackson 不排——不挂这个，多键 map 会与 Go 分叉
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, String> headers;
    @JsonProperty("auth_config")
    private McpAuthConfigResponse authConfig;
    @JsonProperty("advanced_config")
    private McpAdvancedConfig advancedConfig;
    @JsonProperty("stdio_config")
    private McpStdioConfig stdioConfig;
    @JsonProperty("env_vars")
    // Go 对 map 恒按字节序输出；Jackson 不排——不挂这个，多键 map 会与 Go 分叉
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, String> envVars;
    @JsonProperty("is_builtin")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private boolean isBuiltin;
    @JsonProperty("created_at")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private OffsetDateTime updatedAt;
    /** 逐字段"是否已配置"；内置服务不返回（它们没有按租户的凭据） */
    @JsonProperty("credentials")
    // Go 对 map 恒按字节序输出；Jackson 不排——不挂这个，多键 map 会与 Go 分叉
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, CredentialFieldMetadata> credentials;
    /** 列表卡片用的已保存目录摘要；从未同步过时省略 */
    @JsonProperty("catalog")
    private McpCatalogSummary catalog;

    /**
     * 对照 Go {@code NewMCPServiceResponse}。
     *
     * @param includeDetail 对照 {@code dto.CanViewIntegrationSecrets(ctx)}
     */
    public static McpServiceResponse from(McpService svc, boolean includeDetail) {
        if (svc == null) {
            return null;
        }
        McpServiceResponse resp = new McpServiceResponse();
        resp.id = nullToEmpty(svc.getId());
        resp.tenantId = svc.getTenantId() == null ? 0L : svc.getTenantId();
        resp.name = nullToEmpty(svc.getName());
        // Go 的非指针 string 列读出 NULL 即 ""；description 无 omitempty → 恒为字符串
        resp.description = nullToEmpty(svc.getDescription());
        resp.usageInstructions = nullToEmpty(svc.getUsageInstructions());
        resp.enabled = svc.isEnabled();
        resp.transportType = nullToEmpty(svc.getTransportType());
        resp.url = svc.getUrl();
        resp.headers = svc.getHeaders() == null || svc.getHeaders().isEmpty()
                ? null : new LinkedHashMap<>(svc.getHeaders());
        resp.advancedConfig = svc.getAdvancedConfig();
        resp.stdioConfig = svc.getStdioConfig();
        resp.envVars = svc.getEnvVars() == null || svc.getEnvVars().isEmpty()
                ? null : new LinkedHashMap<>(svc.getEnvVars());
        resp.isBuiltin = svc.isIsBuiltin();
        resp.createdAt = svc.getCreatedAt();
        resp.updatedAt = svc.getUpdatedAt();

        if (!includeDetail) {
            resp.headers = null;
            resp.envVars = null;
            resp.url = null;
            resp.stdioConfig = null;
            resp.advancedConfig = null;
        }
        resp.authConfig = McpAuthConfigResponse.from(svc.getAuthConfig(), includeDetail);

        if (svc.isIsBuiltin()) {
            // 内置服务跨租户共享——剥离一切可能泄漏"本租户如何配置上游"的字段。
            resp.url = null;
            resp.headers = null;
            resp.envVars = null;
            resp.stdioConfig = null;
            resp.authConfig = null;
        } else {
            Map<String, CredentialFieldMetadata> creds = new LinkedHashMap<>();
            creds.put("api_key", new CredentialFieldMetadata(
                    svc.getAuthConfig() != null && !nullToEmpty(svc.getAuthConfig().getApiKey()).isEmpty()));
            creds.put("token", new CredentialFieldMetadata(
                    svc.getAuthConfig() != null && !nullToEmpty(svc.getAuthConfig().getToken()).isEmpty()));
            resp.credentials = creds;
        }
        return resp;
    }

    /** 对照 Go {@code NewMCPServiceResponses} */
    public static List<McpServiceResponse> listOf(List<McpService> services, boolean includeDetail) {
        List<McpServiceResponse> out = new java.util.ArrayList<>(services == null ? 0 : services.size());
        if (services == null) {
            return out;
        }
        for (McpService s : services) {
            out.add(from(s, includeDetail));
        }
        return out;
    }

    /**
     * 对照 Go {@code AttachMCPCatalogs}：把已持久化的目录计数挂到列表/详情响应上。
     *
     * <p>Go 在长度不一致或 summaries 为 nil 时整体跳过（防御性），本方法照抄。</p>
     */
    public static void attachCatalogs(List<McpServiceResponse> resp, List<McpService> services,
                                      Map<String, McpMetadataSummary> summaries) {
        if (resp == null || services == null || summaries == null
                || resp.size() != services.size()) {
            return;
        }
        for (int i = 0; i < services.size(); i++) {
            McpService service = services.get(i);
            McpServiceResponse r = resp.get(i);
            if (r == null || service == null) {
                continue;
            }
            McpMetadataSummary summary = summaries.get(service.getId());
            if (summary == null) {
                continue;
            }
            r.catalog = new McpCatalogSummary(
                    summary.getToolCount(),
                    !java.util.Objects.equals(summary.getConfigFingerprint(),
                            McpConfigFingerprint.of(service)),
                    summary.getSyncedAt());
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ── getter（Jackson 序列化需要；Go 侧是导出字段，无特殊语义） ──────────

    public String getUsageInstructions() { return usageInstructions; }
    public String getId() { return id; }
    public long getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public String getTransportType() { return transportType; }
    public String getUrl() { return url; }
    public Map<String, String> getHeaders() { return headers; }
    public McpAuthConfigResponse getAuthConfig() { return authConfig; }
    public McpAdvancedConfig getAdvancedConfig() { return advancedConfig; }
    public McpStdioConfig getStdioConfig() { return stdioConfig; }
    public Map<String, String> getEnvVars() { return envVars; }
    public boolean isIsBuiltin() { return isBuiltin; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Map<String, CredentialFieldMetadata> getCredentials() { return credentials; }
    public McpCatalogSummary getCatalog() { return catalog; }
}
