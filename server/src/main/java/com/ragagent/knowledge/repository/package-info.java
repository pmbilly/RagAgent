/**
 * 仓储门面层：把 mapper 的原子操作组合成领域可用的读写契约，并集中承载
 * 软删三面孔、全字段 UPDATE、乐观锁冲突与 PG/H2 方言分支。
 * <ul>
 *   <li>{@code ChunkRepository} — 文档 chunk 行的读写契约（配 {@code ChunkTxTemplate}
 *       在事务里执行复合写）；</li>
 *   <li>{@code FaqChunkRepository} — FAQ 条目面（重复问检测的 jsonb 方言、flags 位运算批量更新）；</li>
 *   <li>{@code KnowledgeTagRepository} / {@code KnowledgeSpanRepository} — 标签关系与处理进度 span。</li>
 * </ul>
 */
package com.ragagent.knowledge.repository;
