package com.ragagent.common.knowledge;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 文档（knowledge 行）的「检索结果装配事实」——把检索命中拼成
 * {@link com.ragagent.common.retrieval.SearchResult} 时需要的元数据。
 *
 * <p>为什么不直接传 {@code knowledge.domain.Knowledge} 实体：检索引擎域不该依赖知识域的
 * 表实体（实体同时是 DB 行与 API 视图）。这里只保留结果装配实际写入的字段。</p>
 *
 * <p>只带调用方真正读取的字段；需要更多字段时**先改这里**，别把实体漏出去。</p>
 */
public record KnowledgeDocumentFacts(
        String id,
        String title,
        JsonNode metadata,
        String fileName,
        String source,
        String channel,
        String description,
        String knowledgeBaseId) {
}
