package com.ragagent.common.embedding;

import java.util.List;

import com.ragagent.common.model.ModelFacts;

/**
 * 文本嵌入的**能力端口**：按"模型事实"（base_url / api_key / 模型名）调用嵌入服务，
 * 返回与输入等长的向量列表。
 *
 * <p>为什么需要它：检索引擎的查询向量预计算原本直连知识域的嵌入客户端
 * （{@code knowledge.client.EmbedderClient}），使检索引擎域反向依赖知识域。
 * 端口化后消费方只认 {@link ModelFacts}（已是最底层共享类型），
 * 提供方（现为 {@code EmbedderClient}）决定用哪套 HTTP 客户端实现。</p>
 */
public interface EmbeddingGateway {

    /**
     * 批量嵌入。
     *
     * @param model 模型事实（调用方已按需解析；不可为 null）
     * @param texts 待嵌入文本
     * @return 与 {@code texts} 等长的向量列表（实现负责错误语义）
     */
    List<float[]> embed(ModelFacts model, List<String> texts);
}
