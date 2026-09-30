package com.ragagent.common.knowledge;

import java.util.List;

/**
 * 文档（knowledge 行）的**只读端口**（检索结果装配取文档元数据的最小接口）。
 *
 * <p>由知识域实现（{@code KnowledgeService}），消费方（{@code retrieval} 的混合检索）
 * 注入接口而非 {@code Knowledge} 实体。</p>
 */
public interface KnowledgeDocumentGateway {

    /**
     * 批量取文档元数据（含共享访问范围，与包内 {@code getKnowledgeBatchWithSharedAccess}
     * 同语义）；缺失 id 跳过，查不到返回空列表。
     */
    List<KnowledgeDocumentFacts> findAccessibleDocuments(long tenantId, List<String> knowledgeIds);
}
