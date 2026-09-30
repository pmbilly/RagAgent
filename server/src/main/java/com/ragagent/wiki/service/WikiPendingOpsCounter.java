package com.ragagent.wiki.service;

/**
 * 待处理 wiki 摄取任务计数端口。
 *
 * <p><b>仓储缺席时计数保持 0</b>，不是报错——{@code task_pending_ops} 表归
 * task 队列模块所有，这里定义窄端口让统计面解耦：
 * 没有实现 bean 时 {@code getStats} 按 0 处理。</p>
 *
 * <p>实参约定：{@code taskType = "wiki:ingest"}、
 * {@code scope = "knowledge_base"}、{@code scopeID = kbID}。</p>
 */
public interface WikiPendingOpsCounter {

    /** wiki ingest 的 task_type 戳记 */
    String TASK_TYPE_WIKI_INGEST = "wiki:ingest";

    /**
     * 满足 (task_type, scope, scope_id) 的待处理行数。
     * 失败时调用方按 0 处理。
     */
    long pendingCount(String taskType, String scope, String scopeId);

    /** KB 作用域值 */
    String SCOPE_KNOWLEDGE_BASE = "knowledge_base";
}
