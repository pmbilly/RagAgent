package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * {@code SessionQaResolution} 的**agent 覆盖簇**（§11.37 第 2 步）：把 custom agent 的配置
 * （system_prompt / context / 采样与检索参数 / 各能力开关）覆盖到 {@code ChatManage} 上，
 * 以及从 agentRow + 配置读出提示词（{@code resolveCustomAgentPrompts}）。
 *
 * <p>公共嵌套类型 {@code SessionQaResolution.Prompts} 留门面（类型不能委托）；已迁协作者按字段转发；
 * 门面对每个搬走成员留一行薄委托。</p>
 */
final class QaSearchTargets {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaSearchTargets.class);

    private final SessionKnowledgeQaService service;
    private final QaKbScope kbScope;

    QaSearchTargets(SessionKnowledgeQaService service, QaKbScope kbScope) {
        this.service = service;
        this.kbScope = kbScope;
    }

    public List<SessionKnowledgeQaService.SearchTargetView> buildSearchTargets(long tenantId, List<String> knowledgeBaseIds,



            List<String> knowledgeIds, List<QaSupport.TagScope> tagScopes) {



        List<SessionKnowledgeQaService.SearchTargetView> targets = new ArrayList<>();



        Map<String, List<String>> tagIdsByKb = SessionKnowledgeQaService.mergeTagScopesByKb(tagScopes);







        Map<String, Long> kbTenantMap = new LinkedHashMap<>();



        Set<String> fullKbSet = new LinkedHashSet<>();







        List<String> kbIdsToFetch = new ArrayList<>(knowledgeBaseIds);



        kbIdsToFetch.addAll(tagIdsByKb.keySet());



        kbIdsToFetch = SessionKnowledgeQaService.uniqueNonEmptyStrings(kbIdsToFetch);







        Map<String, KnowledgeBase> kbById = new LinkedHashMap<>();



        if (!kbIdsToFetch.isEmpty()) {



            List<KnowledgeBase> kbs = new ArrayList<>();



            for (String id : kbIdsToFetch) {



                KnowledgeBase kb = service.knowledgeBaseService.getAllTenantById(id);



                if (kb != null) {



                    kbs.add(kb);



                }



            }



            for (KnowledgeBase kb : kbs) {



                if (kb != null) {



                    kbById.put(kb.getId(), kb);



                }



            }



        }



        // resolveKBTenant（对照 Go `resolveKBTenant` + `access.KBPermissions.Check`，



        // context.go:79-94，required=OrgRoleViewer）：



        //   ① KB 行缺失 ⇒ 租户回落 caller（**保留**该 target；未知 KB 在检索插件内报 1003，



        //      A/B 场景 kse-unknown-kb 依赖这一形态）；



        //   ② KB 行存在但**调用方无权读** ⇒ 记 0 ⇒ 调用方 continue ⇒ **该 KB 不进检索范围**



        //      （Go：permissions.Check 不过即 `continue` 丢弃）；



        //   ③ 否则归 KB 自己的租户。



        // ⚠️ 旧实现按"KB 行存在即归其租户"处理（注释假称"跨租户由上层可见性拒绝"），



        // 实测与 Go 分歧且**用户可见**：拿外租户 KB 检索时 Go `search_targets=0` 降级作答，



        // 本仓却真去搜它 ⇒ 命中失效 store 绑定时 2200 硬错中止（W5γ5.15，09 §7.6）。



        java.util.function.Function<String, Long> resolveKbTenant = kbId -> {



            Long cached = kbTenantMap.get(kbId);



            if (cached != null && cached != 0) {



                return cached;



            }



            KnowledgeBase kb = kbById.get(kbId);



            if (kb == null) {



                kbTenantMap.put(kbId, tenantId);



                return tenantId;



            }



            if (!kbScope.callerCanReadKb(kbId, kb.getTenantId(), tenantId)) {



                kbTenantMap.put(kbId, 0L);



                return 0L;



            }



            kbTenantMap.put(kbId, kb.getTenantId());



            return kb.getTenantId();



        };







        for (String kbId : knowledgeBaseIds) {



            fullKbSet.add(kbId);



            long kbTenant = resolveKbTenant.apply(kbId);



            if (kbTenant == 0) {



                continue;



            }



            if (tagIdsByKb.get(kbId) != null && !tagIdsByKb.get(kbId).isEmpty()) {



                continue;



            }



            SessionKnowledgeQaService.SearchTargetView t = new SessionKnowledgeQaService.SearchTargetView();



            t.type = "knowledge_base";



            t.knowledgeBaseId = kbId;



            t.tenantId = kbTenant;



            targets.add(t);



        }







        Map<String, List<String>> kbToKnowledgeIds = new LinkedHashMap<>();



        if (!knowledgeIds.isEmpty()) {



            List<Knowledge> knowledgeList;



            try {



                knowledgeList = service.knowledgeService.getKnowledgeBatchWithSharedAccess(tenantId, knowledgeIds);



            } catch (RuntimeException e) {



                log.warn("Failed to get knowledge batch for search targets: {}", e.toString());



                return targets; // Return what we have, don't fail



            }



            for (Knowledge k : knowledgeList) {



                if (k == null || k.getKnowledgeBaseId() == null || k.getKnowledgeBaseId().isEmpty()) {



                    continue;



                }



                if (!kbTenantMap.containsKey(k.getKnowledgeBaseId()) || kbTenantMap.get(k.getKnowledgeBaseId()) == 0) {



                    kbTenantMap.put(k.getKnowledgeBaseId(), k.getTenantId());



                }



                if (fullKbSet.contains(k.getKnowledgeBaseId())



                        && (tagIdsByKb.get(k.getKnowledgeBaseId()) == null



                                || tagIdsByKb.get(k.getKnowledgeBaseId()).isEmpty())) {



                    continue;



                }



                kbToKnowledgeIds.computeIfAbsent(k.getKnowledgeBaseId(), x -> new ArrayList<>()).add(k.getId());



            }



            for (Map.Entry<String, List<String>> e : kbToKnowledgeIds.entrySet()) {



                String kbId = e.getKey();



                if (tagIdsByKb.get(kbId) != null && !tagIdsByKb.get(kbId).isEmpty()) {



                    continue;



                }



                Long kbTenantBoxed = kbTenantMap.get(kbId);



                long kbTenant = kbTenantBoxed == null || kbTenantBoxed == 0 ? tenantId : kbTenantBoxed;



                SessionKnowledgeQaService.SearchTargetView t = new SessionKnowledgeQaService.SearchTargetView();



                t.type = "knowledge";



                t.knowledgeBaseId = kbId;



                t.tenantId = kbTenant;



                t.knowledgeIds = e.getValue();



                t.disableRecallThresholds = true;



                targets.add(t);



            }



        }







        for (Map.Entry<String, List<String>> e : tagIdsByKb.entrySet()) {



            String kbId = e.getKey();



            List<String> tagIds = e.getValue();



            if (kbId.isEmpty() || tagIds.isEmpty()) {



                continue;



            }



            long kbTenant = resolveKbTenant.apply(kbId);



            if (kbTenant == 0) {



                continue;



            }



            KnowledgeBase kb = kbById.get(kbId);



            List<String> explicitKnowledgeIds = SessionKnowledgeQaService.uniqueNonEmptyStrings(



                    kbToKnowledgeIds.getOrDefault(kbId, new ArrayList<>()));







            boolean useDocumentTagResolution = kb == null || !"faq".equals(kb.getType());



            if (kb == null) {



                log.warn("Knowledge base metadata missing for tag scope, kb_id={}, using document tag resolution", kbId);



            }



            if (useDocumentTagResolution) {



                List<String> tagKnowledgeIds;



                tagKnowledgeIds = service.listKnowledgeIdsByTagIds(kbTenant, kbId, tagIds);



                if (!explicitKnowledgeIds.isEmpty()) {



                    tagKnowledgeIds = SessionKnowledgeQaService.intersectStrings(tagKnowledgeIds, explicitKnowledgeIds);



                }



                tagKnowledgeIds = SessionKnowledgeQaService.uniqueNonEmptyStrings(tagKnowledgeIds);



                if (tagKnowledgeIds.isEmpty()) {



                    continue;



                }



                SessionKnowledgeQaService.SearchTargetView t = new SessionKnowledgeQaService.SearchTargetView();



                t.type = "knowledge";



                t.knowledgeBaseId = kbId;



                t.tenantId = kbTenant;



                t.knowledgeIds = tagKnowledgeIds;



                t.scopeTagIds = new ArrayList<>(tagIds);



                t.disableRecallThresholds = true;



                targets.add(t);



                continue;



            }







            SessionKnowledgeQaService.SearchTargetView t = new SessionKnowledgeQaService.SearchTargetView();



            t.type = "knowledge_base";



            t.knowledgeBaseId = kbId;



            t.tenantId = kbTenant;



            t.tagIds = new ArrayList<>(tagIds);



            t.scopeTagIds = new ArrayList<>(tagIds);



            t.disableRecallThresholds = true;



            if (!explicitKnowledgeIds.isEmpty()) {



                t.type = "knowledge";



                t.knowledgeIds = explicitKnowledgeIds;



                t.disableRecallThresholds = true;



            }



            targets.add(t);



        }







        log.info("Built {} search targets: {} full KB, {} partial/tag KB, kbTenantMap={}",



                targets.size(), knowledgeBaseIds.size(), targets.size() - knowledgeBaseIds.size(), kbTenantMap);



        return targets;



    }
}
