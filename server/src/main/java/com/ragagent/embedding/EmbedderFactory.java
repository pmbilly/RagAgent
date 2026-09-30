package com.ragagent.embedding;

import java.util.Locale;
import java.util.Map;

import com.ragagent.embedding.provider.AliyunEmbedder;
import com.ragagent.embedding.provider.AzureOpenAiEmbedder;
import com.ragagent.embedding.provider.BaseEmbedder;
import com.ragagent.embedding.provider.GeminiEmbedder;
import com.ragagent.embedding.provider.JinaEmbedder;
import com.ragagent.embedding.provider.NvidiaEmbedder;
import com.ragagent.embedding.provider.OllamaEmbedder;
import com.ragagent.embedding.provider.OpenAiEmbedder;
import com.ragagent.embedding.provider.VolcengineEmbedder;
import com.ragagent.embedding.provider.WeknoraCloudEmbedder;
import com.ragagent.embedding.provider.ZhipuEmbedder;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;

/**
 * embedder 工厂（对照 Go {@code internal/models/embedding/embedder.go} 的
 * {@code NewEmbedder/newEmbedder} 全文）。
 *
 * <p>装饰顺序（注释逐条对照 Go）：最内层是真实 embedder →
 * {@code wrapEmbeddingConcurrency}（子批往返逐个过闸）→ debug/langfuse 装饰器
 * <b>未翻译</b>（§9 阶段 4.0 差异 1/2：langfuse 与 LLM debug 日志未实现，等价于
 * Go 未启用路径）。</p>
 */
public final class EmbedderFactory {

    private EmbedderFactory() {
    }

    /**
     * 对照 {@code NewEmbedder}。
     *
     * @param governor 进程级并发闸门（Go 的 limiter 包级 governor 的 Java 装配形态）
     */
    public static Embedder newEmbedder(EmbedderConfig config, EmbedderPooler pooler,
                                       OllamaService ollamaService, ConcurrencyGovernor governor) {
        Embedder e = newEmbedderInner(config, pooler, ollamaService);
        BaseEmbedder.applyDimensionOverride(e, config.isSupportsDimensionOverride());
        e = wrapEmbeddingConcurrency(e, config.getMaxConcurrency(), governor);
        return e;
    }

    /** 对照 {@code newEmbedder}：按 source → provider 路由。 */
    static Embedder newEmbedderInner(EmbedderConfig config, EmbedderPooler pooler,
                                     OllamaService ollamaService) {
        String source = config.getSource() == null ? "" : config.getSource().toLowerCase(Locale.ROOT);
        switch (source) {
            case "local" -> {
                return new OllamaEmbedder(config.getBaseUrl(), config.getModelName(),
                        config.getTruncatePromptTokens(), config.getDimensions(),
                        config.getModelId(), pooler, ollamaService);
            }
            case "remote" -> {
                String providerValue = config.getProvider() == null ? "" : config.getProvider();
                ProviderName providerName = ProviderName.fromValue(providerValue);
                if (providerName == null) {
                    providerName = ProviderRegistry.detectProvider(config.getBaseUrl());
                }
                Map<String, String> headers = config.getCustomHeaders();
                Embedder e = switch (providerName == null ? "" : providerName.value()) {
                    // 多模态模型（tongyi-embedding-vision-*/multimodal-embedding-*）走
                    // DashScope 专用 API；text-embedding-v1/v2/v3/v4 走 OpenAI 兼容接口
                    case "aliyun" -> newAliyun(config, pooler);
                    // Volcengine Ark 多模态 embedding API
                    case "volcengine" -> {
                        VolcengineEmbedder ve = new VolcengineEmbedder(config.getApiKey(),
                                config.getBaseUrl(), config.getModelName(),
                                config.getTruncatePromptTokens(), config.getDimensions(),
                                config.getModelId(), pooler);
                        ve.setCustomHeaders(headers);
                        yield ve;
                    }
                    case "jina" -> {
                        JinaEmbedder je = new JinaEmbedder(config.getApiKey(), config.getBaseUrl(),
                                config.getModelName(), config.getTruncatePromptTokens(),
                                config.getDimensions(), config.getModelId(), pooler);
                        je.setCustomHeaders(headers);
                        yield je;
                    }
                    case "azure_openai" -> {
                        String apiVersion = "2024-10-21";
                        if (config.getExtraConfig() != null) {
                            String v = config.getExtraConfig().get("api_version");
                            if (v != null) {
                                apiVersion = v;
                            }
                        }
                        AzureOpenAiEmbedder ae = new AzureOpenAiEmbedder(config.getApiKey(),
                                config.getBaseUrl(), config.getModelName(),
                                config.getTruncatePromptTokens(), config.getDimensions(),
                                config.getModelId(), apiVersion, pooler);
                        ae.setCustomHeaders(headers);
                        yield ae;
                    }
                    case "nvidia" -> {
                        NvidiaEmbedder ne = new NvidiaEmbedder(config.getApiKey(),
                                config.getBaseUrl(), config.getModelName(),
                                config.getDimensions(), config.getModelId(), pooler);
                        ne.setCustomHeaders(headers);
                        yield ne;
                    }
                    case "gemini" -> {
                        GeminiEmbedder ge = new GeminiEmbedder(config.getApiKey(),
                                config.getBaseUrl(), config.getModelName(),
                                config.getTruncatePromptTokens(), config.getDimensions(),
                                config.getModelId(), pooler);
                        ge.setCustomHeaders(headers);
                        yield ge;
                    }
                    case "zhipu" -> {
                        ZhipuEmbedder ze = new ZhipuEmbedder(config.getApiKey(), config.getBaseUrl(),
                                config.getModelName(), config.getTruncatePromptTokens(),
                                config.getDimensions(), config.getModelId(), pooler);
                        ze.setCustomHeaders(headers);
                        yield ze;
                    }
                    case "weknoracloud" -> new WeknoraCloudEmbedder(config);
                    // 其它 provider 一律 OpenAI 兼容
                    default -> {
                        OpenAiEmbedder oe = new OpenAiEmbedder(config.getApiKey(),
                                config.getBaseUrl(), config.getModelName(),
                                config.getTruncatePromptTokens(), config.getDimensions(),
                                config.getModelId(), pooler);
                        oe.setCustomHeaders(headers);
                        yield oe;
                    }
                };
                return e;
            }
            default -> throw new EmbeddingHttp.EmbeddingException(
                    "unsupported embedder source: " + config.getSource());
        }
    }

    /** Go aliyun 分支：多模态检测 + compatible-mode URL 修正。 */
    private static Embedder newAliyun(EmbedderConfig config, EmbedderPooler pooler) {
        String nameLower = config.getModelName() == null ? "" : config.getModelName().toLowerCase(Locale.ROOT);
        boolean isMultimodalModel = nameLower.contains("vision") || nameLower.contains("multimodal");
        if (isMultimodalModel) {
            String baseUrl = config.getBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "https://dashscope.aliyuncs.com";
            } else if (baseUrl.contains("/compatible-mode/")) {
                baseUrl = baseUrl.replaceFirst("(?s)/compatible-mode/v1", "");
                baseUrl = baseUrl.replaceFirst("(?s)/compatible-mode", "");
            }
            AliyunEmbedder ae = new AliyunEmbedder(config.getApiKey(), baseUrl,
                    config.getModelName(), config.getTruncatePromptTokens(),
                    config.getDimensions(), config.getModelId(), pooler);
            ae.setCustomHeaders(config.getCustomHeaders());
            return ae;
        }
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty() || !baseUrl.contains("/compatible-mode/")) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        OpenAiEmbedder oe = new OpenAiEmbedder(config.getApiKey(), baseUrl,
                config.getModelName(), config.getTruncatePromptTokens(),
                config.getDimensions(), config.getModelId(), pooler);
        oe.setCustomHeaders(config.getCustomHeaders());
        return oe;
    }

    /** Go default 分支的 URL/构造逻辑已内联进 switch（含 custom headers 注入）。 */
    static Embedder wrapEmbeddingConcurrency(Embedder e, int limit, ConcurrencyGovernor governor) {
        if (e == null) {
            return e;
        }
        return new ConcurrencyEmbedder(e, limit,
                governor != null ? governor : new ConcurrencyGovernor());
    }
}
