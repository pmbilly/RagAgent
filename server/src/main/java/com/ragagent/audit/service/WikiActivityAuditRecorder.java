package com.ragagent.audit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.common.context.TenantContext;
import com.ragagent.wiki.domain.WikiActivityAudit;
import org.springframework.stereotype.Component;

/**
 * {@link WikiActivityAudit} 的生产实现——把 Wiki 的埋点真正落进 audit_logs。
 *
 * <p>Wiki 侧 6 处埋点（{@code WikiPageController.recordManualWikiActivity} 的
 * manual_create / manual_edit / manual_delete / revert / auto-fix 等，
 * 以及 {@code WikiIngestBatchHandler} 的 ingest / retract 批量摘要）此前因为没有实现
 * bean 而退化成 debug 日志；本类补上后它们才会真正落库。</p>
 *
 * <h2>写入口径</h2>
 * <ul>
 *   <li>Action = {@code wiki.content_changed}，ScopeType = {@code knowledge_base}，
 *       ScopeID = TargetID = kbID，TargetType = {@code wiki}，Outcome = {@code success}；</li>
 *   <li>Details = {@code {"count": N, "actions": {...}}}；count 为 0 时<b>不写</b>；</li>
 *   <li>ActorUserID = 上下文用户；仅在非空时才带 ActorRole；</li>
 *   <li>tenantId 传 0 时回落到上下文租户；仍为 0 则不写。</li>
 * </ul>
 *
 * <p><b>记账是尽力而为</b>：绝不能让埋点失败反过来让编辑失败
 * （{@link AuditLogService#logBestEffort}）。</p>
 *
 * <p><b>说明</b>：details 不写 {@code task_id} / {@code trigger} / {@code processing_status}
 * 三个任务上下文键（Wiki ingest 侧无该上下文）；前端按可选字段读。</p>
 */
@Component
public class WikiActivityAuditRecorder implements WikiActivityAudit {

    static final String SCOPE_KNOWLEDGE_BASE = "knowledge_base";
    static final String TARGET_TYPE_WIKI = "wiki";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditLogService auditLogService;

    public WikiActivityAuditRecorder(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void wikiContentChanged(long tenantId, String knowledgeBaseId, Map<String, Integer> actions) {
        int count = 0;
        if (actions != null) {
            for (Integer c : actions.values()) {
                if (c != null) {
                    count += c;
                }
            }
        }
        // 无变化不写
        if (count == 0) {
            return;
        }
        // kbID 为空时不写
        if (knowledgeBaseId == null || knowledgeBaseId.isEmpty()) {
            return;
        }

        long tid = tenantId;
        if (tid == 0) {
            Long ctxTenant = TenantContext.currentTenantId();
            tid = ctxTenant == null ? 0L : ctxTenant;
        }
        if (tid == 0) {
            return;
        }

        String actorId = nullToEmpty(TenantContext.currentUserId());
        String actorRole = actorId.isEmpty() ? "" : nullToEmpty(TenantContext.currentRole());

        AuditLog entry = new AuditLog();
        entry.setTenantId(tid);
        entry.setActorUserId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(AuditAction.WIKI_CONTENT_CHANGED);
        entry.setScopeType(SCOPE_KNOWLEDGE_BASE);
        entry.setScopeId(knowledgeBaseId);
        entry.setTargetType(TARGET_TYPE_WIKI);
        entry.setTargetId(knowledgeBaseId);
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(details(count, actions));

        auditLogService.logBestEffort(entry);
    }

    /**
     * details = {@code {"count": N, "actions": {...}}}。
     *
     * <p>键按<b>字母序</b>输出 → {@code actions} 先于 {@code count}；内层 actions
     * 也按动作名字母序。落 PG jsonb 后还会被规范化成（长度,字节序），读取侧
     * {@code PgJsonTypeHandler} 做同样的规范化。</p>
     */
    private static ObjectNode details(int count, Map<String, Integer> actions) {
        ObjectNode actionsNode = MAPPER.createObjectNode();
        if (actions != null) {
            List<String> keys = new ArrayList<>(actions.keySet());
            keys.removeIf(k -> k == null);
            keys.sort(String::compareTo);
            for (String key : keys) {
                Integer v = actions.get(key);
                actionsNode.put(key, v == null ? 0 : v);
            }
        }
        ObjectNode details = MAPPER.createObjectNode();
        details.set("actions", actionsNode);
        details.put("count", count);
        return details;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
