package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.knowledge.domain.Knowledge;

/**
 * {@code SessionQaResolution} 的**mention/tag 收敛簇**（§14.9c 刀 10）：把请求里 @ 提及的知识库/知识范围与
 * tag 范围收敛到 agent 允许的范围内（不满足即裁剪/丢弃）。
 *
 * <p>公共类型 {@code SessionQaResolution.MentionScope} 留在门面（类型不能委托），簇内按类名引用；
 * 已迁协作者（`modelSelection`/`kbScope`）按字段转发；门面对每个搬走成员留一行薄委托。</p>
 */
final class QaMentionTagScope {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaMentionTagScope.class);

    private final SessionKnowledgeQaService service;
    private final QaKbScope kbScope;

    QaMentionTagScope(SessionKnowledgeQaService service, QaKbScope kbScope) {
        this.service = service;
        this.kbScope = kbScope;
    }

    public SessionKnowledgeQaService.KnowledgeResolution resolveKnowledgeBases(QaSupport.QaRequest req) {
        List<String> kbIds = new ArrayList<>(req.knowledgeBaseIds);
        List<String> knowledgeIds = new ArrayList<>(req.knowledgeIds);
        List<String> requestedKbIds = new ArrayList<>(req.knowledgeBaseIds);
        boolean hasExplicitMention = !kbIds.isEmpty() || !knowledgeIds.isEmpty() || !req.tagScopes.isEmpty();
        if (hasExplicitMention) {
            log.info("Using request-specified targets: kbs={}, docs={}", kbIds, knowledgeIds);
            // 共享 agent（agent 属于另一租户）：@mention 必须收敛到 agent 的允许范围，
            // 防止调用方注入范围外的 KB/知识 id（对照 Go L38-43）。
            // ⚠️ Long 一律 equals（约定 §5 第 6 条：装箱比较，租户 10002 超出缓存区间恒不等）
            if (req.agentRow != null && req.session != null
                    && !java.util.Objects.equals(req.agentRow.getTenantId(),
                            req.session.getTenantId())) {
                SessionQaResolution.MentionScope scope = restrictMentionsToAgentScope(req.agentRow, req.agentConfig,
                        req.session.getTenantId(), kbIds, knowledgeIds);
                kbIds = scope.kbIds();
                knowledgeIds = scope.knowledgeIds();
                req.tagScopes = restrictTagScopesToAgentScope(req.agentRow, req.agentConfig,
                        req.session.getTenantId(), req.tagScopes);
            }
        } else if (req.agentConfig != null
                && req.agentConfig.path("retrieveKbOnlyWhenMentioned").asBoolean(false)) {
            kbIds = new ArrayList<>();
            knowledgeIds = new ArrayList<>();
            log.info("RetrieveKBOnlyWhenMentioned is enabled and no @ mention found, "
                    + "KB retrieval disabled for this request");
        } else if (req.agentConfig != null) {
            kbIds = kbScope.resolveKnowledgeBasesFromAgent(req.agentRow, req.agentConfig,
                    req.session.getTenantId());
        }
        // API-Key KB 白名单（Go AuthorizeTenantAPIKeyKnowledgeTargets + Filter*）。
        // 拒绝形态是 BizException（波 1 通道）。
        com.ragagent.auth.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeTargets(requestedKbIds, req.knowledgeIds);
        kbIds = com.ragagent.auth.apikey.domain.TenantAPIKeyScope.filterKnowledgeBases(requestedKbIds, kbIds);
        return new SessionKnowledgeQaService.KnowledgeResolution(kbIds, knowledgeIds);
    }
    public SessionQaResolution.MentionScope restrictMentionsToAgentScope(
            com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode agentCfg,
            long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {
        List<String> allowed = kbScope.resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);
        if (allowed.isEmpty()) {
            log.warn("Shared agent has no allowed KBs, blocking all @mentions");
            return new SessionQaResolution.MentionScope(new ArrayList<>(), new ArrayList<>());
        }
        Set<String> allowedSet = new HashSet<>(allowed);
        List<String> filteredKbs = new ArrayList<>();
        for (String id : kbIds) {
            if (allowedSet.contains(id)) {
                filteredKbs.add(id);
            } else {
                log.warn("Blocking @mentioned KB {}: not in shared agent's allowed scope", id);
            }
        }
        List<String> filteredKnowledge = knowledgeIds;
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            List<Knowledge> rows;
            try {
                rows = service.knowledgeService.getKnowledgeBatch(agent.getTenantId(), knowledgeIds);
            } catch (RuntimeException e) {
                log.warn("Failed to validate knowledge IDs against agent scope: {}, blocking all",
                        e.toString());
                rows = null;
            }
            filteredKnowledge = new ArrayList<>();
            if (rows != null) {
                for (Knowledge k : rows) {
                    if (k != null && allowedSet.contains(k.getKnowledgeBaseId())) {
                        filteredKnowledge.add(k.getId());
                    } else if (k != null) {
                        log.warn("Blocking @mentioned knowledge {} (KB {}): not in shared agent's allowed scope",
                                k.getId(), k.getKnowledgeBaseId());
                    }
                }
            }
        }
        return new SessionQaResolution.MentionScope(filteredKbs, filteredKnowledge);
    }
    public List<QaSupport.TagScope> restrictTagScopesToAgentScope(
            com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode agentCfg,
            long sessionTenantId, List<QaSupport.TagScope> tagScopes) {
        if (tagScopes == null || tagScopes.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> allowed = kbScope.resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);
        Set<String> allowedSet = new HashSet<>(allowed);
        List<QaSupport.TagScope> filtered = new ArrayList<>();
        for (QaSupport.TagScope scope : tagScopes) {
            if (allowedSet.contains(scope.knowledgeBaseId)) {
                filtered.add(scope);
            } else {
                log.warn("Blocking @mentioned tag scope for KB {}: not in shared agent's allowed scope",
                        scope.knowledgeBaseId);
            }
        }
        return filtered;
    }
}
