package com.ragagent.tracing.langfuse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.embedding.Embedder;

/**
 * embedding 客户端的 langfuse 装饰器：
 * Embed/BatchEmbed 各发一条 generation；用量按「码点数/4 + 1」估算
 * （provider 不返回 usage，Langfuse 成本报表需要非零 input tokens）。
 * 由 {@code ModelRuntimeFactory.getEmbeddingModel} 装配。
 */
public final class LangfuseEmbedder implements Embedder {

    private final Embedder inner;

    LangfuseEmbedder(Embedder inner) {
        this.inner = inner;
    }

    /** 未启用/空客户端原样返回。 */
    public static Embedder wrap(Embedder embedder) {
        if (embedder == null || !LangfuseManager.get().enabled()) {
            return embedder;
        }
        return new LangfuseEmbedder(embedder);
    }

    @Override
    public float[] embed(String text) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.embed(text);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model_id", inner.getModelID());
        metadata.put("dimensions", inner.getDimensions());
        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "embedding.embed", inner.getModelName(), text, metadata, null));

        float[] result = null;
        String err = null;
        try {
            result = inner.embed(text);
            return result;
        } catch (RuntimeException e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            Object output = null;
            if (result != null && result.length > 0) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("dimensions", result.length);
                out.put("vector_preview", java.util.Arrays.copyOf(result, Math.min(3, result.length)));
                output = out;
            }
            gen.finish(output, LangfusePayloads.approxEmbeddingUsage(List.of(text)), err);
        }
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.batchEmbed(texts);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", texts.size());
        // 不发送全量文本（Langfuse 会截断，但网络成本真实）：只留短预览
        input.put("preview", LangfusePayloads.previewTexts(texts, 5));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model_id", inner.getModelID());
        metadata.put("dimensions", inner.getDimensions());
        metadata.put("batch_size", texts.size());
        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "embedding.batch_embed", inner.getModelName(), input, metadata, null));

        List<float[]> result = null;
        String err = null;
        try {
            result = inner.batchEmbed(texts);
            return result;
        } catch (RuntimeException e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            Object output = null;
            if (result != null && !result.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", result.size());
                out.put("dimensions", result.get(0).length);
                output = out;
            }
            gen.finish(output, LangfusePayloads.approxEmbeddingUsage(texts), err);
        }
    }

    @Override
    public String getModelName() {
        return inner.getModelName();
    }

    @Override
    public int getDimensions() {
        return inner.getDimensions();
    }

    @Override
    public String getModelID() {
        return inner.getModelID();
    }
}
