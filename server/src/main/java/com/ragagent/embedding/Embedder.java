package com.ragagent.embedding;

import java.util.List;

/**
 * 文本向量化客户端（对照 Go {@code internal/models/embedding/embedder.go} 的
 * {@code Embedder} 接口）。
 *
 * <p>Go 的 {@code Embed(ctx, text)} / {@code BatchEmbed(ctx, texts)} → Java 去掉
 * ctx（约定 §1：TenantContext + 显式传参；embedding 调用不携带租户语义）。</p>
 */
public interface Embedder {

    /** 对照 {@code Embed}：单文本转向量。空结果时实现应报 {@code no embedding returned}。 */
    float[] embed(String text);

    /** 对照 {@code BatchEmbed}：批量转向量，返回顺序与输入一致（由各 provider 保证）。 */
    List<float[]> batchEmbed(List<String> texts);

    String getModelName();

    int getDimensions();

    String getModelID();
}
