package com.ragagent.model.service;

import com.ragagent.common.error.BizException;
import com.ragagent.embedding.EmbedderConfig;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.provider.Config;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.rerank.RerankerConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import com.ragagent.retrieval.vlm.VlmClient.VlmConfig;

/**
 * 「模型行 → 各能力运行时配置」的映射（原散在 5 个能力层配置类里的 {@code fromModel} 静态工厂）。
 *
 * <p><b>为什么集中到本域</b>：映射的输入是 {@link Model}（业务实体），输出才是能力层配置。
 * 原先工厂写在配置类上，等于让 embedding/rerank/llm/retrieval 反向 import {@code model.domain}
 * （{@code embedding ⇄ model}、{@code llm ⇄ model}、{@code model ⇄ rerank}、{@code model ⇄ retrieval} 四条环的成因）。
 * 配置类字段本就是普通值，不带实体；映射搬到这里后，依赖方向回到"调用方（本域或上层）→ 能力层配置"。
 * 映射体逐字搬移，行为不变。</p>
 */
public final class ModelRuntimeConfigs {

    private ModelRuntimeConfigs() {
    }

public static EmbedderConfig embedderConfig(Model m, String appId, String appSecret) {
        EmbedderConfig c = new EmbedderConfig();
        if (m == null) {
            return c;
        }
        var p = m.getParameters();
        c.setSource(m.getSource() == null ? "" : m.getSource());
        c.setBaseUrl(p == null ? "" : p.getBaseUrl());
        c.setApiKey(p == null ? "" : p.getApiKey());
        c.setModelId(m.getId() == null ? "" : m.getId());
        c.setModelName(m.getName() == null ? "" : m.getName());
        c.setDimensions(p == null ? 0 : p.getEmbeddingParameters().getDimension());
        c.setSupportsDimensionOverride(p != null && p.getEmbeddingParameters().isSupportsDimensionOverride());
        c.setTruncatePromptTokens(p == null ? 0 : p.getEmbeddingParameters().getTruncatePromptTokens());
        c.setProvider(p == null ? "" : p.getProvider());
        c.setMaxConcurrency(p == null ? 0 : p.getMaxConcurrency());
        c.setExtraConfig(p == null ? null : p.getExtraConfig());
        c.setCustomHeaders(p == null ? null : p.getCustomHeaders());
        c.setAppId(appId);
        c.setAppSecret(appSecret);
        return c;
    }

    public static RerankerConfig rerankerConfig(Model m, String appId, String appSecret) {
        if (m == null) {
            return null;
        }
        RerankerConfig c = new RerankerConfig();
        var p = m.getParameters();
        c.setModelId(m.getId() == null ? "" : m.getId());
        c.setApiKey(p == null ? "" : p.getApiKey());
        c.setBaseUrl(p == null ? "" : p.getBaseUrl());
        c.setModelName(m.getName() == null ? "" : m.getName());
        c.setSource(m.getSource() == null ? "" : m.getSource());
        c.setProvider(p == null ? "" : p.getProvider());
        c.setExtraConfig(p == null ? null : p.getExtraConfig());
        c.setCustomHeaders(p == null ? null : p.getCustomHeaders());
        c.setAppId(appId);
        c.setAppSecret(appSecret);
        return c;
    }

    public static Config providerConfig(Model model) {
        if (model == null) {
            throw BizException.badRequest("model is nil");
        }
        ProviderName providerName = ProviderName.fromValue(model.getParameters().getProvider());
        if (providerName == null) {
            // 对照 Go: if providerName == "" { providerName = DetectProvider(...) }
            providerName = ProviderRegistry.detectProvider(model.getParameters().getBaseUrl());
        }
        return new Config(
                providerName,
                model.getParameters().getBaseUrl(),
                model.getParameters().getApiKey(),
                model.getName(),
                model.getId(),
                null);
    }

    public static ChatConfig chatConfig(Model m, String appId, String appSecret) {
        if (m == null) {
            return null;
        }
        ModelParameters p = m.getParameters();
        ChatConfig c = new ChatConfig();
        c.setModelId(m.getId());
        c.setApiKey(p == null ? null : p.getApiKey());
        c.setBaseUrl(p == null ? null : p.getBaseUrl());
        c.setModelName(m.getName());
        c.setSource(m.getSource());
        c.setProvider(p == null ? null : p.getProvider());
        c.setMaxConcurrency(p == null ? 0 : p.getMaxConcurrency());
        c.setExtraConfig(p == null ? null : p.getExtraConfig());
        c.setCustomHeaders(p == null ? null : p.getCustomHeaders());
        c.setAppId(appId);
        c.setAppSecret(appSecret);
        return c;
    }

    public static VlmConfig vlmConfig(Model m, String appId, String appSecret) {
        if (m == null) {
            return null;
        }
        var p = m.getParameters();
        String ifType = p == null ? "" : p.getInterfaceType();
        if (ifType == null || ifType.isEmpty()) {
            ifType = "local".equals(m.getSource()) ? "ollama" : "openai";
        }
        Map<String, String> extra = p == null ? Map.of()
                : new LinkedHashMap<>(p.getExtraConfig() == null ? Map.of() : p.getExtraConfig());
        return new VlmConfig(m.getSource(), p == null ? "" : p.getBaseUrl(),
                m.getName(), p == null ? "" : p.getApiKey(), m.getId(), ifType,
                p == null ? "" : p.getProvider(), extra, appId == null ? "" : appId,
                appSecret == null ? "" : appSecret);
    }}
