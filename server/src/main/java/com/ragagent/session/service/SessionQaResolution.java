package com.ragagent.session.service;







import java.util.ArrayList;






import java.util.LinkedHashMap;



import java.util.LinkedHashSet;



import java.util.List;



import java.util.Map;



import java.util.Set;



import org.slf4j.Logger;



import org.slf4j.LoggerFactory;






import com.fasterxml.jackson.databind.JsonNode;



import com.fasterxml.jackson.databind.ObjectMapper;



import com.fasterxml.jackson.databind.node.ObjectNode;






import com.ragagent.chatpipeline.ChatManage;

























































import com.ragagent.knowledge.domain.Knowledge;



import com.ragagent.knowledge.domain.KnowledgeBase;
























import com.ragagent.model.domain.Model;












import com.ragagent.session.domain.Session;










/**



 * 知识库/模型/租户解析协作者:mention 与 tag 范围收敛到 agent 授权面、chat 模型选择、



 * 检索租户判定、搜索目标视图构建与自定义 agent 提示词解析。



 *



 * <p>持有 {@link SessionKnowledgeQaService} 回引以访问其依赖与共享 helpers;本类不得独立实例化。</p>



 */



final class SessionQaResolution {







    private static final Logger log = LoggerFactory.getLogger(SessionQaResolution.class);



    private static final ObjectMapper JSON = new ObjectMapper();







    private final SessionKnowledgeQaService service;

    /** agent 覆盖簇（§11.37 第 2 步）。 */
    private final QaChatManageOverrides chatOverrides;

    void applyAgentOverridesToChatManage(QaSupport.QaRequest req, ChatManage cm) {
        chatOverrides.applyAgentOverridesToChatManage(req, cm);
    }

    Prompts resolveCustomAgentPrompts(com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode c) {
        return chatOverrides.resolveCustomAgentPrompts(agent, c);
    }

    /** mention/tag 收敛簇（§14.9c 刀 10）。 */
    private final QaMentionTagScope mentionTagScope;

    public SessionKnowledgeQaService.KnowledgeResolution resolveKnowledgeBases(QaSupport.QaRequest req) {
        return mentionTagScope.resolveKnowledgeBases(req);
    }

    public MentionScope restrictMentionsToAgentScope(com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {
        return mentionTagScope.restrictMentionsToAgentScope(agent, agentCfg, sessionTenantId, kbIds, knowledgeIds);
    }

    public List<QaSupport.TagScope> restrictTagScopesToAgentScope(com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId, List<QaSupport.TagScope> tagScopes) {
        return mentionTagScope.restrictTagScopesToAgentScope(agent, agentCfg, sessionTenantId, tagScopes);
    }

    /** KB 范围簇（§14.9c 刀 9）。 */
    private final QaKbScope kbScope;

    public List<String> resolveKnowledgeBasesFromAgent(com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId) {
        return kbScope.resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);
    }

    static boolean kbSatisfiesAgentRequirements(KnowledgeBase kb, ObjectNode agentCfg) {
        return QaKbScope.kbSatisfiesAgentRequirements(kb, agentCfg);
    }

    public long resolveRetrievalTenantId(QaSupport.QaRequest req) {
        return kbScope.resolveRetrievalTenantId(req);
    }

    boolean callerCanReadKb(String kbId, long ownerTenantId, long retrievalTenantId) {
        return kbScope.callerCanReadKb(kbId, ownerTenantId, retrievalTenantId);
    }

    /** 模型选择簇（§14.9c 刀 8）。 */
    private final QaModelSelection modelSelection;

    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds, List<String> knowledgeIds) {
        return modelSelection.resolveChatModelId(req, knowledgeBaseIds, knowledgeIds);
    }

    Model findModel(String id) {
        return modelSelection.findModel(id);
    }

    String selectChatModelId(Session session, List<String> knowledgeBaseIds, List<String> knowledgeIds) {
        return modelSelection.selectChatModelId(session, knowledgeBaseIds, knowledgeIds);
    }

    public KnowledgeBase findKnowledgeBase(String kbId) {
        return modelSelection.findKnowledgeBase(kbId);
    }

    KnowledgeBase findKb(String kbId) {
        return modelSelection.findKb(kbId);
    }



    /** mention 解析结果(agent 授权面收敛后)。 */

    public record MentionScope(List<String> kbIds, List<String> knowledgeIds) {}







    SessionQaResolution(SessionKnowledgeQaService service) {



        this.service = service;



            this.modelSelection = new QaModelSelection(service);
        this.kbScope = new QaKbScope(service, this.modelSelection);
        this.mentionTagScope = new QaMentionTagScope(service, this.kbScope);
        this.chatOverrides = new QaChatManageOverrides(service);
}







    /** resolveKnowledgeBases（Go L21-60）。 */










    /** @mention 收敛结果（对照 Go 的两个多返回值 helper）。 */







    /**



     * 对照 Go {@code restrictMentionsToAgentScope}（session_qa_helpers.go L295-340）：



     * 把 @mention 的 KB/知识收窄到共享 agent 的允许范围——允许集为空则**全部拦下**；



     * 知识按其所属 KB 是否在允许集内判定（批量取按 **agent 的租户**查）。



     */










    /**



     * 对照 Go {@code restrictTagScopesToAgentScope}（session_qa_helpers.go L62-86）：



     * 按允许 KB 集过滤 tag 范围；空输入返回空列表（Go 返回 nil）。



     */










    /**



     * resolveKnowledgeBasesFromAgent（Go L335-433）：能力过滤 + "all" 模式下**非共享



     * agent** 才并入调用方可见的共享 KB（D 批已接线；共享 agent 时显式跳过并入）。



     */

















    /** resolveChatModelID（Go session_qa_helpers.go L97-141）。 */

















    /** selectChatModelID（Go L246-323）。 */










    /** 包内装配面的 KB 只读查询（GetKnowledgeBaseByIDOnly）。 */

















    /** resolveRetrievalTenantID（Go L145-163）。 */










    /**



     * 作用域能否读该 KB（对照 Go {@code access.KBPermissions.Check}，context.go:79-94，required=Viewer）：



     * ① API-key 作用域（拒绝路径）→ ② 同租户 → ③ 组织共享 ≥ viewer



     * （{@code checkTenantKBPermission(...).permits("viewer")} = Go 的 {@code p.shares.Check}）。



     *



     * <p><b>唯一未移植</b>的是 Go 的 {@code KBGrantsContextKey}（{@code HasKBGrant} 的精确授予，



     * 由 KB 传输/导入流注入 ctx；见 {@code access/kb_transfer.go:101}、{@code knowledgebase.go:61-70}）——



     * 那条不经过 QA 检索路径。共享 agent 情形由"用检索作用域租户比较"覆盖（对应 Go 的



     * {@code SharedAgentGrantContextKey}）。差异备案见 09 §7.6。</p>



     */










    /** buildSearchTargets（Go L441-615）。 */



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



            if (!callerCanReadKb(kbId, kb.getTenantId(), tenantId)) {



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







    /** applyAgentOverridesToChatManage（Go session_qa_helpers.go L170-289）。 */










    /** ResolveCustomAgentPrompts（config/agent_prompts.go L10-33）。 */



    record Prompts(String system, String context) {}














    /**



     * 对照 Go {@code types.CustomAgent.IsAgentMode}（internal/types/custom_agent.go



     * L553-556：{@code Config.AgentMode == AgentModeSmartReasoning}）。



     *



     * <p>⚠️ 2026-09-23 修复：原实现误写成 {@code == "agent"}（Go 侧无此取值），



     * 导致所有真实 agent（前端/内置/IM 一律写 {@code smart-reasoning}）在



     * agent-chat 被误判进 RAG 快答分支——实弹 2×2 对拍证据见



     * known-issues/06-wave-5.md 尾部。</p>



     */



    static boolean isAgentMode(ObjectNode c) {



        return "smart-reasoning".equals(c.path("agent_mode").asText(""));



    }







    static String templateContentByIdAndFile(String id, String file) {



        try (java.io.InputStream in = SessionKnowledgeQaService.class.getClassLoader()



                .getResourceAsStream("agentm/prompt_templates/" + file)) {



            if (in == null) {



                return null;



            }



            Object raw = new org.yaml.snakeyaml.Yaml().load(in);



            JsonNode root = JSON.valueToTree(raw);



            JsonNode list = root.get("templates");



            if (list == null || !list.isArray()) {



                return null;



            }



            for (JsonNode t : list) {



                if (id.equals(t.path("id").asText(""))) {



                    return t.path("content").asText("");



                }



            }



            return null;



        } catch (Exception e) {



            return null;



        }



    }



}
