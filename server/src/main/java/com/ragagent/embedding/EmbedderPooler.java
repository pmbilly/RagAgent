package com.ragagent.embedding;

import java.util.List;

/**
 * 批量向量化的并发池化口（对照 Go {@code EmbedderPooler} 接口）。
 *
 * <p>Go：{@code BatchEmbedWithPool(ctx, model, texts)}；池实现把 texts 切成
 * {@code BATCH_EMBED_SIZE} 大小的子批并发调用 {@code model.BatchEmbed}。</p>
 */
public interface EmbedderPooler {

    List<float[]> batchEmbedWithPool(Embedder model, List<String> texts);
}
