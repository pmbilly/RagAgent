package com.ragagent.agentm.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;

/**
 * initialization POST 响应里的 models 序列化（对照 Go json.Marshal(*types.Model)，
 * **不是** NewModelResponse 形态：api_key 留在 parameters 里原样输出、无 credentials 键、
 * managed_by omitempty）。
 */
public final class InitResponses {

    private InitResponses() {}

    public static Map<String, Object> rawModel(Model m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("tenant_id", m.getTenantId() == null ? 0L : m.getTenantId());
        out.put("name", m.getName());
        out.put("display_name", m.getDisplayName());
        out.put("type", m.getType());
        out.put("source", m.getSource());
        out.put("description", m.getDescription());
        out.put("parameters", rawParameters(m.getParameters()));
        out.put("is_default", m.isIsDefault());
        out.put("is_builtin", m.isIsBuiltin());
        if (m.getManagedBy() != null && !m.getManagedBy().isEmpty()) {
            out.put("managed_by", m.getManagedBy());
        }
        out.put("status", m.getStatus());
        out.put("created_at", m.getCreatedAt());
        out.put("updated_at", m.getUpdatedAt());
        out.put("deleted_at", m.getDeletedAt());
        return out;
    }

    private static Map<String, Object> rawParameters(ModelParameters p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base_url", p.getBaseUrl());
        out.put("api_key", p.getApiKey());
        out.put("interface_type", p.getInterfaceType());
        Map<String, Object> emb = new LinkedHashMap<>();
        emb.put("dimension", p.getEmbeddingParameters().getDimension());
        emb.put("truncate_prompt_tokens", p.getEmbeddingParameters().getTruncatePromptTokens());
        emb.put("supports_dimension_override", p.getEmbeddingParameters().isSupportsDimensionOverride());
        out.put("embedding_parameters", emb);
        out.put("parameter_size", p.getParameterSize());
        out.put("provider", p.getProvider());
        out.put("extra_config", p.getExtraConfig());
        out.put("supports_vision", p.isSupportsVision());
        if (p.getCustomHeaders() != null && !p.getCustomHeaders().isEmpty()) {
            out.put("custom_headers", p.getCustomHeaders());
        }
        if (p.getContextWindow() != 0) {
            out.put("context_window", p.getContextWindow());
        }
        if (p.getMaxOutputTokens() != 0) {
            out.put("max_output_tokens", p.getMaxOutputTokens());
        }
        if (p.getMaxConcurrency() != 0) {
            out.put("max_concurrency", p.getMaxConcurrency());
        }
        if (p.getAppId() != null && !p.getAppId().isEmpty()) {
            out.put("app_id", p.getAppId());
        }
        return out;
    }
}
