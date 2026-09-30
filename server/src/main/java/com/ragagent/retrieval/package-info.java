/**
 * 检索引擎域（库式域，**无 HTTP 面**）：多引擎仓储（{@code engine/}：OpenSearch/Doris/Milvus/Qdrant/Tencent…）、
 * 混合检索（{@link com.ragagent.retrieval.HybridSearchService}）与图谱/VLM 辅助。
 * 消费方：{@code chatpipeline}（19 文件）、{@code knowledge}（10）、{@code session}（7）；对外契约由消费域承担。
  */
package com.ragagent.retrieval;
