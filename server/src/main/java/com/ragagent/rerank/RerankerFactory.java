package com.ragagent.rerank;

import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;

/**
 * reranker 工厂（对照 Go {@code rerank/reranker.go} 的
 * {@code NewReranker/newReranker} 全文）。
 *
 * <p>provider 字段缺省时用 {@code DetectProvider(baseURL)} 路由；customHeaders 在
 * 工厂层统一注入（对照 customHeaderSetter 类型断言）。debug/langfuse 装饰器未翻译
 * （§9 阶段 4.0 差异 1/2，等价于 Go 未启用路径）。</p>
 */
public final class RerankerFactory {

    private RerankerFactory() {
    }

    /** 对照 {@code NewReranker}。 */
    public static Reranker newReranker(RerankerConfig config) {
        Reranker r = newRerankerInner(config);
        if (r instanceof OpenAiReranker o) {
            o.setCustomHeaders(config.getCustomHeaders());
        } else if (r instanceof AliyunReranker a) {
            a.setCustomHeaders(config.getCustomHeaders());
        } else if (r instanceof ZhipuReranker z) {
            z.setCustomHeaders(config.getCustomHeaders());
        } else if (r instanceof JinaReranker j) {
            j.setCustomHeaders(config.getCustomHeaders());
        } else if (r instanceof NvidiaReranker n) {
            n.setCustomHeaders(config.getCustomHeaders());
        }
        return r;
    }

    /** 对照 {@code newReranker}：按 provider 路由。 */
    static Reranker newRerankerInner(RerankerConfig config) {
        ProviderName providerName = ProviderName.fromValue(config.getProvider());
        if (providerName == null) {
            providerName = ProviderRegistry.detectProvider(config.getBaseUrl());
        }
        String name = providerName == null ? "" : providerName.value();
        return switch (name) {
            case "aliyun" -> new AliyunReranker(config);
            case "zhipu" -> new ZhipuReranker(config);
            case "jina" -> new JinaReranker(config);
            case "nvidia" -> new NvidiaReranker(config);
            case "weknoracloud" -> new WeknoraCloudReranker(config);
            case "lkeap" -> new LkeapReranker(config);
            case "volcengine" -> new VolcengineReranker(config);
            default -> new OpenAiReranker(config);
        };
    }
}
