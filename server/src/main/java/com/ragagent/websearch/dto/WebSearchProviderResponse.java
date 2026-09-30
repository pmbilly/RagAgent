package com.ragagent.websearch.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.websearch.domain.WebSearchProvider;

/**
 * 对照 Go {@code dto.WebSearchProviderResponse} / {@code WebSearchProviderParametersDTO}
 * （internal/handler/dto/web_search_provider.go）。api_key **按构造摘除**；
 * credentials 恒输出单键 {@code api_key.configured}（map 非 nil，omitempty 不触发）。
 *
 * <p>proxy_url / extra_config 仅 Admin+（或全量/管理租户设置能力的 API key）可见——
 * 对照 {@code CanViewIntegrationSecrets}；不可见时 proxy_url 置空串、extra_config 置 nil。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSearchProviderResponse {

    @JsonProperty("id")
    public String id;
    @JsonProperty("tenant_id")
    public long tenantId;
    @JsonProperty("name")
    public String name;
    @JsonProperty("provider")
    public String provider;
    @JsonProperty("description")
    public String description;
    @JsonProperty("parameters")
    public ParametersDTO parameters;
    @JsonProperty("is_default")
    public boolean isDefault;
    @JsonProperty("created_at")
    public OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;
    @JsonProperty("updated_at")
    public OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;
    @JsonProperty("credentials")
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
        r.createdAt = e.getCreatedAt() == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : e.getCreatedAt();
        r.updatedAt = e.getUpdatedAt() == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : e.getUpdatedAt();
        // Go 恒构造 map（即使 api_key 为空）→ omitempty 不触发，键恒在
        Map<String, CredentialFieldMetadata> creds = new LinkedHashMap<>();
        creds.put("api_key", new CredentialFieldMetadata(params != null && !params.getApiKey().isEmpty()));
        r.credentials = creds;
        return r;
    }

    public static List<WebSearchProviderResponse> listOf(List<WebSearchProvider> es, boolean canViewSecrets) {
        // Go NewWebSearchProviderResponses：make(...) → 空仓库序列化为 []（非 null）
        java.util.ArrayList<WebSearchProviderResponse> out = new java.util.ArrayList<>();
        if (es != null) {
            for (WebSearchProvider e : es) {
                out.add(from(e, canViewSecrets));
            }
        }
        return out;
    }

    /** 对照 WebSearchProviderParametersDTO：除 api_key 外的全部参数（非秘密） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParametersDTO {
        @JsonProperty("engine_id")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        public String engineId = "";
        @JsonProperty("base_url")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        public String baseUrl = "";
        @JsonProperty("proxy_url")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        public String proxyUrl = "";
        @JsonProperty("extra_config")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Map<String, String> extraConfig;
    }

    /** 对照 dto.CredentialFieldMetadata */
    public static class CredentialFieldMetadata {
        @JsonProperty("configured")
        public final boolean configured;

        public CredentialFieldMetadata(boolean configured) {
            this.configured = configured;
        }
    }
}
