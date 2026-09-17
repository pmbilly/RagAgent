package com.ragagent.model.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * ModelResponse（对照 Go handler/dto/model.go）。
 * 字段序 = Go struct 序。秘密字段（api_key/app_secret）在构造上就不存在；
 * 内置模型对非系统管理员剥离 base_url/extra_config/custom_headers/app_id。
 * credentials 为 map → 序列化按 key 字母序（api_key < app_secret）。
 */
@JsonPropertyOrder({
        "id", "tenant_id", "name", "display_name", "type", "source", "description",
        "parameters", "is_default", "is_builtin", "status", "created_at", "updated_at", "credentials"
})
public record ModelResponse(
        @JsonProperty("id") String id,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("name") String name,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("type") String type,
        @JsonProperty("source") String source,
        @JsonProperty("description") String description,
        @JsonProperty("parameters") ModelParametersDTO parameters,
        @JsonProperty("is_default") boolean isDefault,
        @JsonProperty("is_builtin") boolean isBuiltin,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("credentials") Map<String, CredentialFieldMetadata> credentials) {

    /**
     * 对照 NewModelResponse。
     * @param canViewIntegrationSecrets Admin+ 可见完整参数
     * @param canManageBuiltin 系统管理员可管内置模型
     */
    public static ModelResponse from(com.ragagent.model.domain.Model m,
                                     boolean canViewIntegrationSecrets,
                                     boolean canManageBuiltin) {
        com.ragagent.model.domain.ModelParameters p = m.getParameters();
        // Go omitempty：int 0 / 空串省略
        Integer contextWindow = p.getContextWindow() == 0 ? null : p.getContextWindow();
        Integer maxOutputTokens = p.getMaxOutputTokens() == 0 ? null : p.getMaxOutputTokens();
        Integer maxConcurrency = p.getMaxConcurrency() == 0 ? null : p.getMaxConcurrency();
        String appId = p.getAppId().isEmpty() ? null : p.getAppId();
        ModelParametersDTO params = new ModelParametersDTO(
                p.getBaseUrl(), p.getInterfaceType(),
                new ModelParametersDTO.EmbeddingParametersDTO(
                        p.getEmbeddingParameters().getDimension(),
                        p.getEmbeddingParameters().getTruncatePromptTokens(),
                        p.getEmbeddingParameters().isSupportsDimensionOverride()),
                p.getParameterSize(), p.getProvider(), p.getExtraConfig(), p.getCustomHeaders(),
                p.isSupportsVision(), contextWindow, maxOutputTokens, maxConcurrency, appId);

        if (!canViewIntegrationSecrets && !canManageBuiltin) {
            params = params.withSecretsStripped();
        }
        if (m.isIsBuiltin() && !canManageBuiltin) {
            params = params.withBuiltinStripped();
        }

        Map<String, CredentialFieldMetadata> creds = null;
        if (!m.isIsBuiltin() || canManageBuiltin) {
            // Go map 序列化按 key 字母序：api_key < app_secret
            creds = new java.util.LinkedHashMap<>();
            creds.put("api_key", new CredentialFieldMetadata(!p.getApiKey().isEmpty()));
            creds.put("app_secret", new CredentialFieldMetadata(!p.getAppSecret().isEmpty()));
        }
        return new ModelResponse(
                m.getId(), m.getTenantId() == null ? 0 : m.getTenantId(),
                m.getName(), m.getDisplayName(), m.getType(), m.getSource(), m.getDescription(),
                params, m.isIsDefault(), m.isIsBuiltin(), m.getStatus(),
                m.getCreatedAt(), m.getUpdatedAt(), creds);
    }
}
