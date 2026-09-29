package com.ragagent.agentm.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;

/**
 * initialization POST 响应里的 models 序列化（自有契约,键 = camelCase）：
 * apiKey 留在 parameters 里原样输出、无 credentials 键、
 * managedBy 为空时整键省略。
 */
public final class InitResponses {

    private InitResponses() {}

    public static Map<String, Object> rawModel(Model m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("tenantId", m.getTenantId() == null ? 0L : m.getTenantId());
        out.put("name", m.getName());
        out.put("displayName", m.getDisplayName());
        out.put("type", m.getType());
        out.put("source", m.getSource());
        out.put("description", m.getDescription());
        out.put("parameters", rawParameters(m.getParameters()));
        out.put("default", m.isIsDefault());
        out.put("builtin", m.isIsBuiltin());
        if (m.getManagedBy() != null && !m.getManagedBy().isEmpty()) {
            out.put("managedBy", m.getManagedBy());
        }
        out.put("status", m.getStatus());
        out.put("createdAt", m.getCreatedAt());
        out.put("updatedAt", m.getUpdatedAt());
        out.put("deletedAt", m.getDeletedAt());
        return out;
    }

    private static Map<String, Object> rawParameters(ModelParameters p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", p.getBaseUrl());
        out.put("apiKey", p.getApiKey());
        out.put("interfaceType", p.getInterfaceType());
        Map<String, Object> emb = new LinkedHashMap<>();
        emb.put("dimension", p.getEmbeddingParameters().getDimension());
        emb.put("truncatePromptTokens", p.getEmbeddingParameters().getTruncatePromptTokens());
        emb.put("supportsDimensionOverride", p.getEmbeddingParameters().isSupportsDimensionOverride());
        out.put("embeddingParameters", emb);
        out.put("parameterSize", p.getParameterSize());
        out.put("provider", p.getProvider());
        out.put("extraConfig", p.getExtraConfig());
        out.put("supportsVision", p.isSupportsVision());
        if (p.getCustomHeaders() != null && !p.getCustomHeaders().isEmpty()) {
            out.put("customHeaders", p.getCustomHeaders());
        }
        if (p.getContextWindow() != 0) {
            out.put("contextWindow", p.getContextWindow());
        }
        if (p.getMaxOutputTokens() != 0) {
            out.put("maxOutputTokens", p.getMaxOutputTokens());
        }
        if (p.getMaxConcurrency() != 0) {
            out.put("maxConcurrency", p.getMaxConcurrency());
        }
        if (p.getAppId() != null && !p.getAppId().isEmpty()) {
            out.put("appId", p.getAppId());
        }
        return out;
    }
}
