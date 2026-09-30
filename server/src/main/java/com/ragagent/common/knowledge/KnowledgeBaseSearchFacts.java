package com.ragagent.common.knowledge;

/**
 * 知识库的「检索侧事实」——检索引擎编排一次 KB 检索时真正读取的字段
 * （归属租户、KB 类型、嵌入模型、绑定的向量店、两条索引开关）。
 *
 * <p>为什么需要它：{@code retrieval} 的混合检索编排要按 KB 分组扇出、
 * 校验多 KB 的嵌入模型一致性、决定发不发向量/关键词检索——这些判断以前直接读
 * {@code knowledge.domain.KnowledgeBase} 实体，使检索引擎域反向依赖知识域。
 * 改成端口载荷后，实体（含其 jsonb 配置）不再漏出知识域。</p>
 *
 * <p>只带调用方真正读取的字段；需要更多字段时**先改这里**，别把实体漏出去。</p>
 */
public record KnowledgeBaseSearchFacts(
        String id,
        Long tenantId,
        String type,
        String embeddingModelId,
        String vectorStoreId,
        boolean vectorEnabled,
        boolean keywordEnabled) {
}
