/**
 * 知识库服务层。按能力分组的职责地图：
 * <ul>
 *   <li><b>处理管线</b>：KnowledgeService（门面/共享工具）+ KnowledgeProcessWorker（虚拟线程
 *       解析主链路）+ KnowledgeSummaryService / KnowledgeFileService / KnowledgeParseService
 *       （摘要、文件与手工内容、解析生命周期）+ SpanTracker（进度 span 树，best-effort）；</li>
 *   <li><b>FAQ 子链路</b>：FaqEntryCommandService / FaqEntryQueryService / FaqImportService
 *       （命令、查询、导入）+ FaqGuard（域校验与写计划）+ FaqChunkCodec / FaqIndexWriter
 *       （编解码与向量写路径）+ FaqImportTaskStore（进程内导入进度）；</li>
 *   <li><b>chunk 编辑</b>：ChunkEditService（乐观锁编辑/回滚/软删）+ ChunkQuestionService
 *       （LLM 生成问题）+ ChunkAccessGuard（chunk 写守卫）+ ChunkVectorIndexer；</li>
 *   <li><b>支撑</b>：KnowledgeBaseService / KnowledgeSearchService / HousekeepingService
 *       （定时清理）/ TenantFileStorage（租户隔离存储）/ DocReaderClient（文档解析）。</li>
 * </ul>
 */
package com.ragagent.knowledge.service;
