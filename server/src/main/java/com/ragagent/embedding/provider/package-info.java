/**
 * 嵌入 provider 的**具体实现**（各家 HTTP 调用与响应解析）：OpenAI / AzureOpenAI / Gemini / Jina /
 * Nvidia / Ollama / Aliyun / Volcengine / Zhipu / WeknoraCloud。公共骨架
 * {@link com.ragagent.embedding.provider.BaseEmbedder}（重试取首个非空批、维度覆盖、自定义头）也在这里。
 *
 * <p>框架面（{@link com.ragagent.embedding.Embedder} 接口、{@code EmbedderFactory} 选择器、
 * {@code EmbeddingHttp}/{@code ProviderJson} 传输与编码、池化）留在 {@code com.ragagent.embedding} 根包。</p>
 */
package com.ragagent.embedding.provider;
