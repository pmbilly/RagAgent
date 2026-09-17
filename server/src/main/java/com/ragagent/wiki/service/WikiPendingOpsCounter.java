package com.ragagent.wiki.service;

/**
 * 待处理 wiki 摄取任务计数端口（对照 Go {@code TaskPendingOpsRepository.PendingCount}）。
 *
 * <p>Go 的 {@code GetStats}（wiki_page.go L852-859）这样用：</p>
 * <pre>{@code
 * if s.taskPendingRepo != nil {
 *     pendingTasks, _ = s.taskPendingRepo.PendingCount(ctx, wikiTaskType, wikiTaskScope, kbID)
 * }
 * }</pre>
 *
 * <p>即：<b>仓储缺席时 pendingTasks 保持 0</b>，不是报错。
 * {@code task_pending_ops} 表随 wiki ingest 翻译（其归属在
 * wiki_ingest.go / knowledge 模块一侧），故 Java 侧定义这个窄端口：
 * 没有实现 bean 时 {@code getStats} 走 Go 的 nil 分支。</p>
 *
 * <p>Go 的实参：{@code taskType = "wiki:ingest"}（wiki_ingest.go L200）、
 * {@code scope = types.TaskScopeKnowledgeBase}、{@code scopeID = kbID}。</p>
 */
public interface WikiPendingOpsCounter {

    /** 对照 Go {@code wikiTaskType}（wiki_ingest.go L200） */
    String TASK_TYPE_WIKI_INGEST = "wiki:ingest";

    /**
     * 对照 Go {@code PendingCount}：满足 (task_type, scope, scope_id) 的待处理行数。
     * 失败时调用方按 0 处理（Go 用 {@code pendingTasks, _ = ...} 忽略错误）。
     */
    long pendingCount(String taskType, String scope, String scopeId);

    /** 对照 Go {@code types.TaskScopeKnowledgeBase} */
    String SCOPE_KNOWLEDGE_BASE = "knowledge_base";
}
