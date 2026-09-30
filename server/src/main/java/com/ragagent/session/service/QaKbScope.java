package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * {@code SessionQaResolution} 的**KB 范围簇**（§14.9c 刀 9）：从 agent 配置推导可用知识库集合、
 * KB 与 agent 约束的匹配判定、检索租户推导与调用方读权限判定。
 *
 * <p>依赖两个：{@code SessionKnowledgeQaService service}（宿主）与 {@link QaModelSelection}
 * （依赖只有宿主 service）。门面对每个搬走成员留一行
 * 薄委托，宿主与同族调用点零改动。</p>
 */
final class QaKbScope {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaKbScope.class);

    private final SessionKnowledgeQaService service;
    QaKbScope(SessionKnowledgeQaService service) {
        this.service = service;
    }

    public List<String> resolveKnowledgeBasesFromAgent(



            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId) {



        if (agentCfg == null) {



            return new ArrayList<>();



        }



        String mode = agentCfg.path("kb_selection_mode").asText("");



        switch (mode) {



            case "all" -> {



                // 能力过滤（DeriveKBFilterForAgent）：取 tool 能力面判定，非 wiki/rerank 工具



                // 只要求 vector/keyword。



                List<KnowledgeBase> allKbs = service.knowledgeBaseService.listKnowledgeBases(null);



                List<String> kbIds = new ArrayList<>();



                Set<String> kbIdSet = new LinkedHashSet<>();



                int ownSkipped = 0;



                for (KnowledgeBase kb : allKbs) {



                    if (kbSatisfiesAgentRequirements(kb, agentCfg)) {



                        kbIds.add(kb.getId());



                        kbIdSet.add(kb.getId());



                    } else {



                        ownSkipped++;



                    }



                }







                if (ownSkipped > 0) {



                    log.info("KBSelectionMode=all: tool-capability filter removed {} own KBs", ownSkipped);



                }



                log.info("KBSelectionMode=all: loaded {} knowledge bases (own)", kbIds.size());



                return kbIds;



            }



            case "selected" -> {



                List<String> configured = SessionKnowledgeQaService.stringListOf(agentCfg.get("knowledge_bases"));



                log.info("KBSelectionMode=selected: using {} configured knowledge bases", configured.size());



                return configured;



            }



            case "none" -> {



                log.info("KBSelectionMode=none: no knowledge bases configured");



                return new ArrayList<>();



            }



            default -> {



                List<String> configured = SessionKnowledgeQaService.stringListOf(agentCfg.get("knowledge_bases"));



                if (!configured.isEmpty()) {



                    log.info("KBSelectionMode not set: using {} configured knowledge bases", configured.size());



                }



                return configured;



            }



        }



    }
    static boolean kbSatisfiesAgentRequirements(KnowledgeBase kb, ObjectNode agentCfg) {



        if (kb == null) {



            return false;



        }



        var st = kb.getIndexingStrategy();



        return st.isVectorEnabled() || st.isKeywordEnabled() || st.isWikiEnabled();



    }
    public long resolveRetrievalTenantId(QaSupport.QaRequest req) {



        long retrievalTenantId = req.session.getTenantId();



        if (req.agentRow != null && req.agentRow.getTenantId() != null && req.agentRow.getTenantId() != 0) {



            retrievalTenantId = req.agentRow.getTenantId();



            log.info("Using agent tenant {} for retrieval scope", retrievalTenantId);



        } else {



            Long ctxTenant = TenantContext.currentTenantId();



            if (ctxTenant != null && ctxTenant != 0) {



                retrievalTenantId = ctxTenant;



            }



        }



        return retrievalTenantId;



    }
    boolean callerCanReadKb(String kbId, long ownerTenantId, long retrievalTenantId) {



        // ① API-key 作用域（对照 Go Check 的第二步 AuthorizeTenantAPIKeyKnowledgeBases，



        //    tenant_api_key.go:376-385）——**拒绝**路径：KB 受限的 Key 指向白名单外 ⇒ 不可读。



        //    等价物（TenantAPIKeyScope）早已存在，此前未在会话/QA 模块接线（该文件自述的"需决策点"）。



        com.ragagent.auth.apikey.domain.TenantAPIKeyScope scope =



                com.ragagent.auth.apikey.domain.APIKeyScopeContext.current();



        if (scope != null && scope.isKnowledgeBaseRestricted()



                && !scope.allowsKnowledgeBases(java.util.List.of(kbId))) {



            return false;



        }



        // ② 租户判定（空间分享裁撤：跨租户共享授权链已退役，仅本租户可读）。



        return SessionKnowledgeQaService.kbReadableByCaller(retrievalTenantId, ownerTenantId, () -> false);



    }
}
