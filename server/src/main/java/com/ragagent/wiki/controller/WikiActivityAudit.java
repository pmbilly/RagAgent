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
 * <p><b>为什么是接缝而不是直接调用</b>：定义端口是为了让 handler 的埋点位置与 Go
 * <b>一一对应</b>、且不把 wiki 模块反向耦合到审计实现上。没有实现 bean 时
 * {@code ObjectProvider.getIfAvailable()} 返回 null，行为退化为一条 debug 日志——
 * 等价于 Go 侧 {@code auditService} 为 nil 的情形（{@code recordKBActivity} 内部
 * {@code audit.Log} 是尽力而为、绝不影响编辑本身）。</p>
 *
 * <p><b>实现已就位</b>：{@code com.ragagent.audit.service.WikiActivityAuditRecorder}
 * （随审计模块翻译一起交付）实现了本接口，@Component 自动装配，因此
 * WikiPageController 的 6 处人工埋点与 WikiIngestBatchHandler 的批量摘要
 * 现在都<b>真正落库</b>为 {@code wiki.content_changed} 审计行。</p>
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
