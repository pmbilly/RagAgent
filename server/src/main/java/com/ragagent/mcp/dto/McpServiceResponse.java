package com.ragagent.mcp.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpConfigFingerprint;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;

/**
 * MCP 服务的主资源响应。
 *
 * <p><b>为什么单独一个 DTO 包</b>：响应形态刻意与持久化实体分离，使"响应里没有秘密"
 * 成为<b>编译期不变式</b>而不是运行时脱敏步骤。将来有人想把凭据放进响应，
 * 必须显式在本类里加字段——泄漏面因此收敛到一次可评审的 diff。</p>
 *
 * <p><b>剥离规则</b>：</p>
 * <ol>
 *   <li>{@code includeDetail = CanViewIntegrationSecrets(ctx)}（Admin+）；为 false 时
 *       剥离 url / headers / env_vars / stdio_config / advanced_config，
 *       并把 auth_config 的 custom_headers 置空。</li>
 *   <li><b>内置服务额外剥离</b>：url / headers / env_vars / stdio_config / auth_config
 *       且不返回 credentials（内置行跨租户共享，绝不能泄漏"本租户怎么配的上游"）。
 *       注意内置服务这条分支里<b>不动 advanced_config</b>——保留既有行为。</li>
 *   <li>非内置服务返回 credentials 布尔映射（api_key / token 是否已配置）。</li>
 * </ol>
 *
 * <p>字段序＝声明序；usage_instructions 是响应的第一个字段（契约）。</p>
 */
public class McpServiceResponse {

    /** 恒输出（无省略语义） */
    private String usageInstructions = "";
    private String id = "";
    private long tenantId;
    private String name = "";
    private String description = "";
    private boolean enabled;
    private String transportType = "";
    private String url;
    // 多键 map 的输出键序需保持稳定（与既有响应/落库形态一致）

    private Map<String, String> headers;
    private McpAuthConfigResponse authConfig;
    private McpAdvancedConfig advancedConfig;
    private McpStdioConfig stdioConfig;
    // 多键 map 的输出键序需保持稳定（与既有响应/落库形态一致）

    private Map<String, String> envVars;
    /** 布尔字段不带 is 前缀（键名 `builtin`）。 */
    private boolean builtin;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /** 逐字段"是否已配置"；内置服务不返回（它们没有按租户的凭据） */
    // 多键 map 的输出键序需保持稳定（与既有响应/落库形态一致）

    private Map<String, CredentialFieldMetadata> credentials;
    /** 列表卡片用的已保存目录摘要；从未同步过时省略 */
    private McpCatalogSummary catalog;

    /**
     * 实体 → 响应的逐字段拷贝与剥离。
     *
     * @param includeDetail 是否保留细节（等价于 CanViewIntegrationSecrets 的判定结果）
     */
    public static McpServiceResponse from(McpService svc, boolean includeDetail) {
        if (svc == null) {
            return null;
        }
        McpServiceResponse resp = new McpServiceResponse();
        resp.id = nullToEmpty(svc.getId());
        resp.tenantId = svc.getTenantId() == null ? 0L : svc.getTenantId();
        resp.name = nullToEmpty(svc.getName());
        // description 恒为字符串（NULL 读出按 "" 处理，无省略语义）
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
        resp.builtin = svc.isIsBuiltin();
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
            creds.put("apiKey", new CredentialFieldMetadata(
                    svc.getAuthConfig() != null && !nullToEmpty(svc.getAuthConfig().getApiKey()).isEmpty()));
            creds.put("token", new CredentialFieldMetadata(
                    svc.getAuthConfig() != null && !nullToEmpty(svc.getAuthConfig().getToken()).isEmpty()));
            resp.credentials = creds;
        }
        return resp;
    }

    /** 批量转换 */
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
     * 把已持久化的目录计数挂到列表/详情响应上。
     *
     * <p>长度不一致或 summaries 为 null 时整体跳过（防御性）。</p>
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

    // ── getter（Jackson 序列化需要） ─────────────────────────────────────

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
    public boolean isBuiltin() { return builtin; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Map<String, CredentialFieldMetadata> getCredentials() { return credentials; }
    public McpCatalogSummary getCatalog() { return catalog; }
}
