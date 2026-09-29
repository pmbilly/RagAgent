/**
 * 知识库域：知识的存储、处理管线与检索面。处理主链路 =
 * 三入口（文件上传 / URL / 手工）→ {@link com.ragagent.knowledge.task.KnowledgeProcessWorker}
 * （解析状态机）→ chunker 分块 → 向量化 → finalizing fan-out（摘要 / 生成问题 / wiki / 图谱）
 * → 检索消费方（session / retrieval）。FAQ 是独立子链路：条目命令/查询/导入三服务 +
 * FaqChunkCodec（条目↔chunk 编解码）+ FaqChunkRepository（方言感知读写）。
 */
package com.ragagent.knowledge;
