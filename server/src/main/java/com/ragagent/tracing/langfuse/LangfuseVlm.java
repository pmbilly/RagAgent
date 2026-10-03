package com.ragagent.tracing.langfuse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VLM 调用的 langfuse 装饰器：
 * 每次 Predict 发一条 {@code vlm.predict} generation——**不上传图片字节**
 * （Langfuse 追踪面向文本；metadata 只含 image_count / image_bytes_total），
 * 输入为文本 prompt，用量按 prompt 与输出的码点数估算（VLM 不返回 usage）。
 *
 * <p>Java 侧 VLM 面是静态函数族（{@code VlmClient.predict}，无对象接口），
 * 故装饰器包一个函数而非实例；wrap 语义与 chat/embedding/rerank 一致：
 * 未启用时原样返回（零成本）。</p>
 */
public final class LangfuseVlm {

    /** 待装饰的调用（{@code (images, prompt)} → 文本结果）。 */
    @FunctionalInterface
    public interface PredictFn {
        String predict(byte[][] images, String prompt) throws Exception;
    }

    private LangfuseVlm() {
    }

    /** 未启用（或空实现）原样返回。 */
    public static PredictFn wrap(PredictFn inner, String modelName, String modelId) {
        if (inner == null || !LangfuseManager.get().enabled()) {
            return inner;
        }
        return (images, prompt) -> {
            LangfuseManager manager = LangfuseManager.get();
            if (!manager.enabled()) {
                return inner.predict(images, prompt);
            }
            int imageCount = images == null ? 0 : images.length;
            int totalImageSize = 0;
            if (images != null) {
                for (byte[] b : images) {
                    // null 段计 0
                    totalImageSize += b == null ? 0 : b.length;
                }
            }

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", prompt);
            input.put("image_count", imageCount);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("model_id", modelId);
            metadata.put("image_count", imageCount);
            metadata.put("image_bytes_total", totalImageSize);

            Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                    "vlm.predict", modelName, input, metadata, null));

            String result = null;
            String err = null;
            try {
                result = inner.predict(images, prompt);
                return result;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                throw e;
            } finally {
                // 出错时 result 以空串参与收尾（generation 仍记录）
                gen.finish(result == null ? "" : result,
                        LangfusePayloads.approxVlmUsage(prompt, result), err);
            }
        };
    }
}
