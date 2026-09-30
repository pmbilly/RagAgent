/**
 * 重排 provider 客户端（库式域，无 HTTP 面）：RerankerFactory 选择器、RerankHttp/GoJson 传输与
 * RankResult 形态，只做"候选 → 重排分"。
 *
 * <p>各家 provider 实现在 {@link com.ragagent.rerank.provider} 子包。</p>
  */
package com.ragagent.rerank;
