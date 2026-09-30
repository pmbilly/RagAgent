/**
 * 重排 provider 的**具体实现**：OpenAI / Jina / Nvidia / Aliyun / Volcengine / Zhipu / Lkeap /
 * WeknoraCloud 各家 HTTP 调用与响应解析。
 *
 * <p>框架面（{@link com.ragagent.rerank.Reranker} 接口、{@code RerankerFactory} 选择器、
 * {@code RerankHttp}/{@code GoJson}/{@code RankResult}）留在 {@code com.ragagent.rerank} 根包。</p>
 */
package com.ragagent.rerank.provider;
