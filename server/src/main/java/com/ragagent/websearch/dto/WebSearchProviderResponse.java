package com.ragagent.websearch.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.websearch.domain.WebSearchProvider;

/**
 * provider 响应 DTO。api_key **按构造摘除**；
 * credentials 恒输出单键 {@code api_key.configured}（map 恒非空，键恒出现）。
 *
 * <p>proxy_url / extra_config 仅 Admin+（或全量/管理租户设置能力的 API key）可见——
 * 判定 {@code dto.CanViewIntegrationSecrets}；不可见时 proxy_url 置空串、extra_config 置 null。</p>
 */
public class WebSearchProviderResponse {

    public String id;
    public long tenantId;
    public String name;
    public String provider;
    public String description;
    public ParametersDTO parameters;
    public boolean isDefault;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public Map<String, CredentialFieldMetadata> credentials;

    public static WebSearchProviderResponse from(WebSearchProvider e, boolean canViewIntegrationSecrets) {
        WebSearchProviderResponse r = new WebSearchProviderResponse();
        r.id = e.getId();
        r.tenantId = e.getTenantId() == null ? 0L : e.getTenantId();
        r.name = e.getName() == null ? "" : e.getName();
        r.provider = e.getProvider() == null ? "" : e.getProvider();
        r.description = e.getDescription() == null ? "" : e.getDescription();
        var params = e.getParameters();
        var dto = new ParametersDTO();
        dto.engineId = params == null ? "" : params.getEngineId();
        dto.baseUrl = params == null ? "" : params.getBaseUrl();
        dto.proxyUrl = params == null ? "" : params.getProxyUrl();
        dto.extraConfig = params == null ? null : params.getExtraConfig();
        if (!canViewIntegrationSecrets) {
            dto.proxyUrl = "";
            dto.extraConfig = null;
        }
        r.parameters = dto;
        r.isDefault = e.isDefault();
        r.createdAt = e.getCreatedAt();
        r.updatedAt = e.getUpdatedAt();
        // map 恒输出（即使 api_key 为空），键恒在
        Map<String, CredentialFieldMetadata> creds = new LinkedHashMap<>();
        creds.put("apiKey", new CredentialFieldMetadata(params != null && !params.getApiKey().isEmpty()));
        r.credentials = creds;
        return r;
    }

    public static List<WebSearchProviderResponse> listOf(List<WebSearchProvider> es, boolean canViewSecrets) {
        // 空仓库序列化为 []（非 null）
        java.util.ArrayList<WebSearchProviderResponse> out = new java.util.ArrayList<>();
        if (es != null) {
            for (WebSearchProvider e : es) {
                out.add(from(e, canViewSecrets));
            }
        }
        return out;
    }

    /** provider 参数视图：除 api_key 外的全部参数（非秘密） */
        public static class ParametersDTO {
                    public String engineId = "";
                    public String baseUrl = "";
                    public String proxyUrl = "";
                    public Map<String, String> extraConfig;
    }

    /** 凭据字段的对外形态（只暴露 configured，不含值） */
    public static class CredentialFieldMetadata {
        public final boolean configured;

        public CredentialFieldMetadata(boolean configured) {
            this.configured = configured;
        }
    }
}
