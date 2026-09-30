package com.ragagent.common.knowledge;

/**
 * 知识库「检索侧事实」的**只读端口**（检索引擎编排取 KB 检索配置的最小接口）。
 *
 * <p>由知识域实现（{@code KnowledgeBaseService}），消费方（{@code retrieval} 的混合检索）
 * 注入接口而非 {@code KnowledgeBase} 实体 + {@code KnowledgeBaseService}，
 * 依赖方向因此是"检索域 → 端口 ← 知识域"，不再是双向。</p>
 */
public interface KnowledgeBaseSearchGateway {

    /**
     * 按 id 查未软删的知识库的检索配置；查不到返回 {@code null}。
     *
     * <p>与 {@code getAllTenantById} 同语义：命中后回填默认值（KB 类型、索引策略零值
     * → vector+keyword 默认），保证"读到的策略"与既有检索行为一致。</p>
     */
    KnowledgeBaseSearchFacts findSearchFacts(String knowledgeBaseId);
}
