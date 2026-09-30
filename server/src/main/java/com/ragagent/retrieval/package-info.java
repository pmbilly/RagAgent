/**
 * 检索引擎域（库式域，**无 HTTP 面**）：多引擎仓储（{@code engine/}：OpenSearch/Doris/Milvus/Qdrant/Tencent…，
 * 含 postgres 引擎的 embeddings 读写两侧）、混合检索（{@link com.ragagent.retrieval.HybridSearchService}）
 * 与图谱/VLM 辅助。
 * 消费方：{@code chatpipeline}（19 文件）、{@code knowledge}（10）、{@code session}（7）；对外契约由消费域承担。
 *
 * <p>本域**不依赖任何业务域**：知识库/文档/chunk 的读取与查询嵌入一律经
 * {@code common.knowledge} / {@code common.embedding} 的端口（由知识域实现）。</p>
 */
package com.ragagent.retrieval;
