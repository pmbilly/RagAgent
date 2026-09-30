package com.ragagent.common.model;

/**
 * 模型的「最小事实集」——跨域只读端口的载荷（调用侧真正需要的几个字段）。
 *
 * <p>为什么需要它：retrieval 的查询嵌入要 (baseUrl, apiKey, name) 造 HTTP 请求、还要
 * (name|baseUrl) 做"同一嵌入模型"的身份键；但它不该持有 {@code model.domain.Model} 实体
 * （{@code model ⇄ retrieval} 环的成因）。只带调用方真正读取的字段；要更多字段时**先改这里**。</p>
 *
 * <p>空值统一为 {@code ""}（与调用侧原有的 {@code == null ? "" : ...} 归一一致）。</p>
 */
public record ModelFacts(String modelId, String name, String baseUrl, String apiKey) {
}
