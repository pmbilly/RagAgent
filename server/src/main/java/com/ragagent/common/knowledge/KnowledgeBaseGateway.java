package com.ragagent.common.knowledge;

/**
 * 知识库的**只读端口**（跨域读取知识库归属事实的最小接口）。
 *
 * <p>由知识域实现（{@code KnowledgeBaseService}），消费方（audit / auth）注入接口而非
 * {@code KnowledgeBaseMapper}，依赖方向因此是"消费域 → 端口 ← 知识域"，不再互相成环。</p>
 */
public interface KnowledgeBaseGateway {

    /**
     * 按 id 查未软删的知识库，返回归属事实；查不到返回 {@code null}。
     *
     * <p>刻意**不按租户过滤**：调用方需要区分「查不到」（404/400）与「存在但属于别的空间」（403）。</p>
     */
    KnowledgeBaseFacts findFacts(String knowledgeBaseId);
}
