package com.ragagent.session.service;







import java.util.ArrayList;



import java.util.HashSet;



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



import com.ragagent.agentm.service.AgentConfigJson;



import com.ragagent.chatpipeline.ChatManage;






























import com.ragagent.common.context.TenantContext;



























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



    /** mention 解析结果(agent 授权面收敛后)。 */

    public record MentionScope(List<String> kbIds, List<String> knowledgeIds) {}







    SessionQaResolution(SessionKnowledgeQaService service) {



        this.service = service;



    }







    /** resolveKnowledgeBases（Go L21-60）。 */



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



                MentionScope scope = restrictMentionsToAgentScope(req.agentRow, req.agentConfig,



                        req.session.getTenantId(), kbIds, knowledgeIds);



                kbIds = scope.kbIds();



                knowledgeIds = scope.knowledgeIds();



                req.tagScopes = restrictTagScopesToAgentScope(req.agentRow, req.agentConfig,



                        req.session.getTenantId(), req.tagScopes);



            }



        } else if (req.agentConfig != null



                && req.agentConfig.path("retrieve_kb_only_when_mentioned").asBoolean(false)) {



            kbIds = new ArrayList<>();



            knowledgeIds = new ArrayList<>();



            log.info("RetrieveKBOnlyWhenMentioned is enabled and no @ mention found, "



                    + "KB retrieval disabled for this request");



        } else if (req.agentConfig != null) {



            kbIds = resolveKnowledgeBasesFromAgent(req.agentRow, req.agentConfig,



                    req.session.getTenantId());



        }







        // API-Key KB 白名单（Go AuthorizeTenantAPIKeyKnowledgeTargets + Filter*）。



        // 拒绝形态是 BizException（波 1 通道）。



        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeTargets(requestedKbIds, req.knowledgeIds);



        kbIds = com.ragagent.apikey.domain.TenantAPIKeyScope.filterKnowledgeBases(requestedKbIds, kbIds);



        return new SessionKnowledgeQaService.KnowledgeResolution(kbIds, knowledgeIds);



    }







    /** @mention 收敛结果（对照 Go 的两个多返回值 helper）。 */







    /**



     * 对照 Go {@code restrictMentionsToAgentScope}（session_qa_helpers.go L295-340）：



     * 把 @mention 的 KB/知识收窄到共享 agent 的允许范围——允许集为空则**全部拦下**；



     * 知识按其所属 KB 是否在允许集内判定（批量取按 **agent 的租户**查）。



     */



    public MentionScope restrictMentionsToAgentScope(



            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg,



            long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {



        List<String> allowed = resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);



        if (allowed.isEmpty()) {



            log.warn("Shared agent has no allowed KBs, blocking all @mentions");



            return new MentionScope(new ArrayList<>(), new ArrayList<>());



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



        return new MentionScope(filteredKbs, filteredKnowledge);



    }







    /**



     * 对照 Go {@code restrictTagScopesToAgentScope}（session_qa_helpers.go L62-86）：



     * 按允许 KB 集过滤 tag 范围；空输入返回空列表（Go 返回 nil）。



     */



    public List<QaSupport.TagScope> restrictTagScopesToAgentScope(



            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode agentCfg,



            long sessionTenantId, List<QaSupport.TagScope> tagScopes) {



        if (tagScopes == null || tagScopes.isEmpty()) {



            return new ArrayList<>();



        }



        List<String> allowed = resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);



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







    /**



     * resolveKnowledgeBasesFromAgent（Go L335-433）：能力过滤 + "all" 模式下**非共享



     * agent** 才并入调用方可见的共享 KB（D 批已接线；共享 agent 时显式跳过并入）。



     */



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



                List<String> configured = service.stringListOf(agentCfg.get("knowledge_bases"));



                log.info("KBSelectionMode=selected: using {} configured knowledge bases", configured.size());



                return configured;



            }



            case "none" -> {



                log.info("KBSelectionMode=none: no knowledge bases configured");



                return new ArrayList<>();



            }



            default -> {



                List<String> configured = service.stringListOf(agentCfg.get("knowledge_bases"));



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







    /** resolveChatModelID（Go session_qa_helpers.go L97-141）。 */



    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds,



            List<String> knowledgeIds) {



        String summaryModelId = req.summaryModelId == null ? "" : req.summaryModelId.trim();



        String configuredAgentModelId = "";



        if (req.agentConfig != null) {



            configuredAgentModelId = req.agentConfig.path("model_id").asText("").trim();



            if (configuredAgentModelId.isEmpty()



                    && !"builtin-wiki-fixer".equals(req.agentRow.getId())) {



                throw new RuntimeException("chat model is not configured: please set model_id on agent "



                        + req.agentRow.getId());



            }



            if (!configuredAgentModelId.isEmpty()) {



                Model model = findModel(configuredAgentModelId);



                if (model == null || !"KnowledgeQA".equals(model.getType())) {



                    throw new RuntimeException("configured chat model " + configuredAgentModelId



                            + " is unavailable for agent " + req.agentRow.getId());



                }



            }



        }







        if (!summaryModelId.isEmpty()) {



            Model model = findModel(summaryModelId);



            if (model != null && "KnowledgeQA".equals(model.getType())) {



                log.info("Using request's summary model override: {}", summaryModelId);



                return summaryModelId;



            }



            log.warn("Request provided invalid summary model ID {}, falling back", summaryModelId);



        }



        if (!configuredAgentModelId.isEmpty()) {



            log.info("Using custom agent's model_id: {}", configuredAgentModelId);



            return configuredAgentModelId;



        }



        return selectChatModelId(req.session, knowledgeBaseIds, knowledgeIds);



    }







    Model findModel(String id) {



        try {



            return service.modelService.getModelByID(id);



        } catch (RuntimeException e) {



            return null;



        }



    }







    /** selectChatModelID（Go L246-323）。 */



    String selectChatModelId(Session session, List<String> knowledgeBaseIds, List<String> knowledgeIds) {



        List<String> kbIds = new ArrayList<>(knowledgeBaseIds);



        if (kbIds.isEmpty() && !knowledgeIds.isEmpty()) {



            long tenantId = service.requireTenantId();



            try {



                List<Knowledge> knowledgeList = service.knowledgeService.getKnowledgeBatchWithSharedAccess(



                        tenantId, knowledgeIds);



                Set<String> kbIdSet = new LinkedHashSet<>();



                for (Knowledge k : knowledgeList) {



                    if (k != null && k.getKnowledgeBaseId() != null && !k.getKnowledgeBaseId().isEmpty()) {



                        kbIdSet.add(k.getKnowledgeBaseId());



                    }



                }



                kbIds.addAll(kbIdSet);



                log.info("Derived {} knowledge base IDs from {} knowledge IDs for model selection",



                        kbIds.size(), knowledgeIds.size());



            } catch (RuntimeException e) {



                log.warn("Failed to get knowledge batch for model selection: {}", e.toString());



            }



        }



        if (!kbIds.isEmpty()) {



            for (String kbId : kbIds) {



                KnowledgeBase kb = findKb(kbId);



                if (kb != null && kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty()) {



                    Model model = findModel(kb.getSummaryModelId());



                    if (model != null && "remote".equals(model.getSource())) {



                        log.info("Using Remote summary model from knowledge base");



                        return kb.getSummaryModelId();



                    }



                }



            }



            KnowledgeBase kb = findKb(kbIds.get(0));



            if (kb == null) {



                throw new RuntimeException("failed to get knowledge base " + kbIds.get(0) + ": record not found");



            }



            if (kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty()) {



                log.info("Using summary model from first knowledge base {}: {}", kbIds.get(0), kb.getSummaryModelId());



                return kb.getSummaryModelId();



            }



        }







        List<Model> models = service.modelService.listModels();



        for (Model model : models) {



            if (model != null && "KnowledgeQA".equals(model.getType())) {



                log.info("Using first available KnowledgeQA model: {}", model.getId());



                return model.getId();



            }



        }



        throw new RuntimeException("no chat model ID available: no knowledge bases configured and no available models");



    }







    /** 包内装配面的 KB 只读查询（GetKnowledgeBaseByIDOnly）。 */



    public KnowledgeBase findKnowledgeBase(String kbId) {



        return findKb(kbId);



    }







    KnowledgeBase findKb(String kbId) {



        try {



            return service.knowledgeBaseService.getAllTenantById(kbId);



        } catch (RuntimeException e) {



            return null;



        }



    }







    /** resolveRetrievalTenantID（Go L145-163）。 */



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



    boolean callerCanReadKb(String kbId, long ownerTenantId, long retrievalTenantId) {



        // ① API-key 作用域（对照 Go Check 的第二步 AuthorizeTenantAPIKeyKnowledgeBases，



        //    tenant_api_key.go:376-385）——**拒绝**路径：KB 受限的 Key 指向白名单外 ⇒ 不可读。



        //    等价物（TenantAPIKeyScope）早已存在，此前未在会话/QA 模块接线（该文件自述的"需决策点"）。



        com.ragagent.apikey.domain.TenantAPIKeyScope scope =



                com.ragagent.apikey.domain.APIKeyScopeContext.current();



        if (scope != null && scope.isKnowledgeBaseRestricted()



                && !scope.allowsKnowledgeBases(java.util.List.of(kbId))) {



            return false;



        }



        // ② 租户判定（空间分享裁撤：跨租户共享授权链已退役，仅本租户可读）。



        return SessionKnowledgeQaService.kbReadableByCaller(retrievalTenantId, ownerTenantId, () -> false);



    }







    /** buildSearchTargets（Go L441-615）。 */



    public List<SessionKnowledgeQaService.SearchTargetView> buildSearchTargets(long tenantId, List<String> knowledgeBaseIds,



            List<String> knowledgeIds, List<QaSupport.TagScope> tagScopes) {



        List<SessionKnowledgeQaService.SearchTargetView> targets = new ArrayList<>();



        Map<String, List<String>> tagIdsByKb = service.mergeTagScopesByKb(tagScopes);







        Map<String, Long> kbTenantMap = new LinkedHashMap<>();



        Set<String> fullKbSet = new LinkedHashSet<>();







        List<String> kbIdsToFetch = new ArrayList<>(knowledgeBaseIds);



        kbIdsToFetch.addAll(tagIdsByKb.keySet());



        kbIdsToFetch = service.uniqueNonEmptyStrings(kbIdsToFetch);







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



            List<String> explicitKnowledgeIds = service.uniqueNonEmptyStrings(



                    kbToKnowledgeIds.getOrDefault(kbId, new ArrayList<>()));







            boolean useDocumentTagResolution = kb == null || !"faq".equals(kb.getType());



            if (kb == null) {



                log.warn("Knowledge base metadata missing for tag scope, kb_id={}, using document tag resolution", kbId);



            }



            if (useDocumentTagResolution) {



                List<String> tagKnowledgeIds;



                tagKnowledgeIds = service.listKnowledgeIdsByTagIds(kbTenant, kbId, tagIds);



                if (!explicitKnowledgeIds.isEmpty()) {



                    tagKnowledgeIds = service.intersectStrings(tagKnowledgeIds, explicitKnowledgeIds);



                }



                tagKnowledgeIds = service.uniqueNonEmptyStrings(tagKnowledgeIds);



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



    void applyAgentOverridesToChatManage(QaSupport.QaRequest req, ChatManage cm) {



        if (req.agentConfig == null) {



            return;



        }



        ObjectNode c = AgentConfigJson.ensureDefaults(req.agentConfig);



        Prompts prompts = resolveCustomAgentPrompts(req.agentRow, c);



        if (!prompts.system.isEmpty()) {



            cm.getSummaryConfig().setPrompt(prompts.system);



            log.info("Using custom agent's system_prompt");



        }



        if (!prompts.context.isEmpty()) {



            cm.getSummaryConfig().setContextTemplate(prompts.context);



            log.info("Using custom agent's context_template");



        }



        double temperature = c.path("temperature").asDouble(-1);



        if (temperature >= 0) {



            cm.getSummaryConfig().setTemperature(temperature);



        }



        int maxCompletionTokens = c.path("max_completion_tokens").asInt(0);



        if (maxCompletionTokens > 0) {



            cm.getSummaryConfig().setMaxCompletionTokens(maxCompletionTokens);



        }



        JsonNode thinking = c.get("thinking");



        cm.getSummaryConfig().setThinking(thinking != null && thinking.isBoolean() ? thinking.asBoolean() : null);



        cm.setCitationEnabled(c.path("citation_enabled").asBoolean(true));







        int embeddingTopK = c.path("embedding_top_k").asInt(0);



        if (embeddingTopK > 0) {



            cm.setEmbeddingTopK(embeddingTopK);



        }



        double keywordThreshold = c.path("keyword_threshold").asDouble(0);



        if (keywordThreshold > 0) {



            cm.setKeywordThreshold(keywordThreshold);



        }



        double vectorThreshold = c.path("vector_threshold").asDouble(0);



        if (vectorThreshold > 0) {



            cm.setVectorThreshold(vectorThreshold);



        }



        int rerankTopK = c.path("rerank_top_k").asInt(0);



        if (rerankTopK > 0) {



            cm.setRerankTopK(rerankTopK);



        }



        cm.setRerankThreshold(c.path("rerank_threshold").asDouble(0));



        String rerankModelId = c.path("rerank_model_id").asText("");



        if (!rerankModelId.isEmpty()) {



            cm.setRerankModelId(rerankModelId);



        }







        cm.setEnableRewrite(c.path("enable_rewrite").asBoolean(false));



        cm.setEnableQueryExpansion(c.path("enable_query_expansion").asBoolean(false));



        String rwSys = c.path("rewrite_prompt_system").asText("");



        if (!rwSys.isEmpty()) {



            cm.setRewritePromptSystem(rwSys);



        }



        String rwUser = c.path("rewrite_prompt_user").asText("");



        if (!rwUser.isEmpty()) {



            cm.setRewritePromptUser(rwUser);



        }



        String quModel = c.path("query_understand_model_id").asText("");



        if (!quModel.isEmpty()) {



            cm.setQueryUnderstandModelId(quModel);



        }







        String fallbackStrategy = c.path("fallback_strategy").asText("");



        if (!fallbackStrategy.isEmpty()) {



            cm.setFallbackStrategy(fallbackStrategy);



        }



        String fallbackResponse = c.path("fallback_response").asText("");



        if (!fallbackResponse.isEmpty()) {



            cm.setFallbackResponse(fallbackResponse);



        }



        String fallbackPrompt = c.path("fallback_prompt").asText("");



        if (!fallbackPrompt.isEmpty()) {



            cm.setFallbackPrompt(fallbackPrompt);



        }







        int webSearchMaxResults = c.path("web_search_max_results").asInt(0);



        if (webSearchMaxResults > 0) {



            cm.setWebSearchMaxResults(webSearchMaxResults);



        }







        int historyTurns = c.path("history_turns").asInt(0);



        if (historyTurns > 0) {



            cm.setMaxRounds(historyTurns);



            log.info("Using custom agent's history_turns: {}", cm.getMaxRounds());



        }



        if (!c.path("multi_turn_enabled").asBoolean(true)) {



            cm.setMaxRounds(0);



            log.info("Multi-turn disabled by custom agent, clearing history");



        }







        cm.setFaqPriorityEnabled(c.path("faq_priority_enabled").asBoolean(false));



        cm.setFaqDirectAnswerThreshold(c.path("faq_direct_answer_threshold").asDouble(0.0));



        cm.setFaqScoreBoost(c.path("faq_score_boost").asDouble(0.0));







        cm.setDataAnalysisEnabled(c.path("data_analysis_enabled").asBoolean(false));







        JsonNode intentPrompts = c.get("intent_prompts");



        if (intentPrompts != null && intentPrompts.isObject() && intentPrompts.size() > 0) {



            Map<String, String> overrides = new LinkedHashMap<>();



            intentPrompts.fields().forEachRemaining(e -> overrides.put(e.getKey(), e.getValue().asText("")));



            cm.setIntentPromptOverrides(overrides);



        }



    }







    /** ResolveCustomAgentPrompts（config/agent_prompts.go L10-33）。 */



    record Prompts(String system, String context) {}







    Prompts resolveCustomAgentPrompts(



            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode c) {



        if (c == null) {



            return new Prompts("", "");



        }



        String system = c.path("system_prompt").asText("");



        String context = c.path("context_template").asText("");



        boolean agentMode = service.isAgentMode(c);



        String systemId = c.path("system_prompt_id").asText("");



        if (system.isEmpty() && !systemId.isEmpty()) {



            String content = templateContentByIdAndFile(systemId,



                    agentMode ? "agent_system_prompt.yaml" : "system_prompt.yaml");



            if (content != null) {



                system = content;



            }



        }



        String contextId = c.path("context_template_id").asText("");



        if (context.isEmpty() && !contextId.isEmpty()) {



            String content = templateContentByIdAndFile(contextId, "context_template.yaml");



            if (content != null) {



                context = content;



            }



        }



        return new Prompts(system, context);



    }







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
