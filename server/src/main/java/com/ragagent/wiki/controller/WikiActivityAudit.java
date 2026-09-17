package com.ragagent.wiki.controller;

import java.util.Map;

/**
 * 知识库活动流的 wiki 变更投影接缝（对照 Go
 * {@code internal/application/service/kb_activity.go} 的
 * {@code RecordWikiContentActivity} → {@code recordKBActivity}）。
 *
 * <p>Go 的 {@code WikiPageHandler} 通过 {@code auditService}（{@code interfaces.AuditLogService}）
 * 把人工页面变更写成一条 {@code wiki_content_changed} 审计事件：</p>
 * <pre>{@code
 * RecordWikiContentActivity(ctx, h.auditService, page.TenantID,
 *     page.KnowledgeBaseID, map[string]int{action: 1})
 * }</pre>
 * <p>净效果是一条 {@code AuditLog}：Action={@code wiki_content_changed}、Scope={@code knowledgebase}、
 * Target={@code wiki}/{@code kbID}、Outcome={@code success}、
 * Details={@code {"count":N,"actions":{...}}}（只在 count &gt; 0 时写）。</p>
 *
 * <p><b>为什么是接缝而不是直接调用</b>：Java 侧审计模块（{@code com.ragagent.audit.*}）
 * 尚未翻译，本阶段没有 {@code AuditLogService} 可注入。为了让 handler 的埋点位置与 Go
 * <b>一一对应</b>、且不把 wiki 模块耦合到未来的审计实现上，这里定义端口，由后续阶段
 * （或主会话）提供实现 bean。没有实现 bean 时 {@code ObjectProvider.getIfAvailable()}
 * 返回 null，行为退化为一条 debug 日志——等价于 Go 侧 {@code auditService} 为 nil 的情形
 * （{@code recordKBActivity} 内部 {@code audit.Log} 是尽力而为、绝不影响编辑本身）。</p>
 */
public interface WikiActivityAudit {

    /**
     * 对照 Go {@code recordKBActivity(ctx, audit, tenantID, kbID,
     * AuditActionWikiContentChanged, "wiki", kbID, AuditOutcomeSuccess,
     * {"count": sum(actions), "actions": actions})}。
     *
     * @param tenantId        页面所属工作空间
     * @param knowledgeBaseId 页面所属知识库（同时是审计的 ScopeID 与 TargetID）
     * @param actions         动作名 → 次数（handler 恒传单键 {@code {action: 1}}）
     */
    void wikiContentChanged(long tenantId, String knowledgeBaseId, Map<String, Integer> actions);
}
