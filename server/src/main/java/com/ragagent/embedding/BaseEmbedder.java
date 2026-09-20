package com.ragagent.embedding;

import java.util.List;
import java.util.Map;

/**
 * 各 HTTP embedder 的公共骨架（对照 Go 各 embedder 结构体的公共字段与方法）：
 * {@code Embed} 的三次重试取首个非空批、{@code supportsDimensionsParam} 的
 * 「显式覆盖 + 正数维度」双条件、getter 三件套。
 */
abstract class BaseEmbedder implements Embedder {

    String modelName;
    String apiKey = "";
    int truncatePromptTokens;
    int dimensions;
    String modelId;
    Map<String, String> customHeaders;
    boolean supportsDimensionOverride;
    final EmbedderPooler pooler;

    BaseEmbedder(String modelName, int truncatePromptTokens, int dimensions,
                 String modelId, EmbedderPooler pooler) {
        this.modelName = modelName;
        this.truncatePromptTokens = truncatePromptTokens;
        this.dimensions = dimensions;
        this.modelId = modelId;
        this.pooler = pooler;
    }

    /** 对照 Go {@code Embed}：三次尝试取首个非空批（空批继续循环）。 */
    @Override
    public float[] embed(String text) {
        for (int i = 0; i < 3; i++) {
            List<float[]> embeddings = batchEmbed(List.of(text));
            if (!embeddings.isEmpty()) {
                return embeddings.get(0);
            }
        }
        throw new EmbeddingHttp.EmbeddingException("no embedding returned");
    }

    /** 对照 Go {@code supportsDimensionsParam}：需要显式覆盖且维度为正。 */
    boolean supportsDimensionsParam() {
        return supportsDimensionOverride && dimensions > 0;
    }

    void setCustomHeaders(Map<String, String> headers) {
        this.customHeaders = headers;
    }

    void setSupportsDimensionOverride(boolean supported) {
        this.supportsDimensionOverride = supported;
    }

    void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    /** Go 的工厂在 NewEmbedder 上对支持该能力的实现统一调 SetSupportsDimensionOverride。 */
    static void applyDimensionOverride(Embedder e, boolean supported) {
        if (e instanceof BaseEmbedder be) {
            be.setSupportsDimensionOverride(supported);
        }
    }

    /** Go 的工厂对 HTTP embedder 统一注入自定义头。 */
    static void applyCustomHeaders(Embedder e, Map<String, String> headers) {
        if (e instanceof BaseEmbedder be) {
            be.setCustomHeaders(headers);
        }
    }

    // ── 包内共享的小工具 ──────────────────────────────────────────────

    /** Go：body 超 1000 字节截断加 "... (truncated)"。 */
    static String truncateBody(String body) {
        if (body != null && body.length() > 1000) {
            return body.substring(0, 1000) + "... (truncated)";
        }
        return body == null ? "" : body;
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public String getModelID() {
        return modelId;
    }
}
