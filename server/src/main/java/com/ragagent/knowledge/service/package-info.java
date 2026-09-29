/**
 * 知识库用例层。按能力的职责地图（队列/客户端/存储/守卫已各自分包）：
 * <ul>
 *   <li><b>处理管线</b>：KnowledgeService（门面与共享工具）+ KnowledgeParseService /
 *       KnowledgeSummaryService / KnowledgeFileService（解析生命周期、摘要、文件与手工内容）；</li>
 *   <li><b>FAQ 子链路</b>：FaqEntryCommandService / FaqEntryQueryService / FaqImportService
 *       （命令、查询、导入）+ FaqChunkCodec / FaqIndexWriter / FaqIndexRows（编解码与向量写路径）；</li>
 *   <li><b>chunk 编辑与生成</b>：ChunkEditService（乐观锁编辑/回滚/软删）+ ChunkReadService +
 *       ChunkQuestionService（LLM 生成问题）+ ChunkVectorIndexer（图抽取与向量索引）；</li>
 *   <li><b>知识库与检索</b>：KnowledgeBaseService / KnowledgeTagService / KnowledgeSearchService /
 *       KnowledgeBatchOpsService / KnowledgeCloneService / KnowledgeMoveService /
 *       KnowledgeFolderService / VectorStoreService；</li>
 *   <li><b>支撑</b>：HousekeepingService（定时清理）+ SpanTracker（处理进度 span 树，
 *       被 knowledge/wiki 两域共用）+ KnowledgeVectorWrites（向量写路由网关）。</li>
 * </ul>
 */
package com.ragagent.knowledge.service;
