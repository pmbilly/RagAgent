/**
 * 知识库异步任务：队列与执行。
 * <ul>
 *   <li><b>队列</b>：ChunkExtractTaskQueue / QuestionGenerationTaskQueue（接口）+
 *       InProcess* 两个进程内实现（当前唯一实现，后续可换持久化队列）；</li>
 *   <li><b>执行</b>：KnowledgeProcessWorker（解析主链路 worker）+
 *       KnowledgeTaskExecutor（子任务调度）+ KnowledgeTaskIds（任务 id 规则）；</li>
 *   <li><b>进度</b>：KnowledgeTaskProgressStore / FaqImportTaskStore（进程内进度与结果暂存）。</li>
 * </ul>
 */
package com.ragagent.knowledge.task;
