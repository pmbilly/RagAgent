/**
 * 嵌入向量 provider 客户端（库式域，无 HTTP 面）：{@link com.ragagent.embedding.EmbedderFactory} 选择器、
 * EmbeddingHttp/GoJson 传输与编码、池化与失败降级。只做"文本 → 向量"，不含业务语义。
 *
 * <p>各家 provider 实现与公共骨架 {@code BaseEmbedder} 在 {@link com.ragagent.embedding.provider} 子包。</p>
  */
package com.ragagent.embedding;
