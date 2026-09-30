package com.ragagent.wiki.domain;

import java.util.Map;

/**
 * 知识库活动流的 wiki 变更投影接缝。
 *
 * <p>HTTP 层把人工页面变更写成一条 {@code wiki_content_changed} 审计事件。
 * 净效果是一条 {@code AuditLog}：Action={@code wiki_content_changed}、Scope={@code knowledgebase}、
 * Target={@code wiki}/{@code kbID}、Outcome={@code success}、
 * Details={@code {"count":N,"actions":{...}}}（只在 count &gt; 0 时写）。</p>
 *
 * <p><b>为什么是接缝而不是直接调用</b>：定义端口是为了让埋点位置与原实现
 * <b>一一对应</b>、且不把 wiki 模块反向耦合到审计实现上。没有实现 bean 时
 * {@code ObjectProvider.getIfAvailable()} 返回 null，行为退化为一条 debug 日志——
 * 等价于审计服务缺位的情形（埋点本身尽力而为、绝不影响编辑本身）。</p>
 *
 * <p><b>实现已就位</b>：{@code com.ragagent.audit.service.WikiActivityAuditRecorder}
 * （随审计模块翻译一起交付）实现了本接口，@Component 自动装配，因此
 * WikiPageController 的 6 处人工埋点与 WikiIngestBatchHandler 的批量摘要
 * 现在都<b>真正落库</b>为 {@code wiki.content_changed} 审计行。</p>
 */
public interface WikiActivityAudit {

    /**
     * 写入一条 {@code wiki_content_changed} 审计事件
     * （Details 为 {@code {"count": sum(actions), "actions": actions}}）。
     *
     * @param tenantId        页面所属工作空间
     * @param knowledgeBaseId 页面所属知识库（同时是审计的 ScopeID 与 TargetID）
     * @param actions         动作名 → 次数（调用方恒传单键 {@code {action: 1}}）
     */
    void wikiContentChanged(long tenantId, String knowledgeBaseId, Map<String, Integer> actions);
}
