package com.ragagent.chatpipeline.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.event.TenantContextSnapshot;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.tracing.langfuse.LangfuseManager;
import com.ragagent.tracing.langfuse.Span;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.retrieval.support.WebResultConverter;

/**
 * 检索执行协作者（对照 Go search.go:343-652，自 {@link PluginSearch} 机械搬出）：
 * embedding 模型分组检索（整库合并一次 HybridSearch + 特定文档逐目标）、web 检索与
 * 租户 web 配置合并。{@code withTenant}/{@code joinQuietly} 留门面（与 onEvent/扩展簇
 * 共用），经类名访问。
 */
final class PluginSearchOps {

    private final PluginSearch service;

    PluginSearchOps(PluginSearch service) {
        this.service = service;
    }

    // searchByTargets（search.go:343-519）
    // ------------------------------------------------------------------

    /**
     * 按 embedding 模型分组检索。共享模型（name+endpoint）的整库目标合并成一次
     * HybridSearch；特定文档目标逐目标检索。
     */
    public List<SearchResult> searchByTargets(ChatManage chatManage) {
        if (chatManage.getSearchTargets().isEmpty()) {
            return null;
        }

        String queryText = chatManage.getRewriteQuery().trim();

        // 批量取 KB 决定分组；失败全部落空 key 组（HybridSearch 逐 KB 算向量，优雅降级）
        List<String> kbIds = new ArrayList<>(chatManage.getSearchTargets().size());
        for (SearchTarget t : chatManage.getSearchTargets()) {
            kbIds.add(t == null ? null : t.knowledgeBaseId());
        }
        List<KnowledgeBase> kbList = new ArrayList<>();
        Map<String, KnowledgeBase> kbMap = new LinkedHashMap<>();
        try {
            List<KnowledgeBase> kbs = service.knowledgeBaseService.getKnowledgeBasesByIdsOnly(kbIds);
            if (kbs != null) {
                kbList = kbs;
                for (KnowledgeBase kb : kbs) {
                    if (kb != null) {
                        kbMap.put(kb.getId(), kb);
                    }
                }
            }
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("Search", "batch_kb_fetch_error", f);
        }

        // 只给"取到了"的 KB 求身份键（与既有行为一致：取不到的 KB 回落空 key 组）
        Map<String, String> modelKeyMap = service.knowledgeBaseService.resolveEmbeddingModelKeys(
                kbList.stream().map(KnowledgeBase::getId).toList());

        // Go 的 map 分组迭代是随机的；结果合并由全局列表承接，组间顺序不影响结果集
        Map<String, List<SearchTarget>> groups = new LinkedHashMap<>();
        for (SearchTarget t : chatManage.getSearchTargets()) {
            String key = t == null ? "" : modelKeyMap.getOrDefault(t.knowledgeBaseId(), "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        Map<String, Object> gf = new LinkedHashMap<>();
        gf.put("total_targets", chatManage.getSearchTargets().size());
        gf.put("unique_models", groups.size());
        PipelineLog.info("Search", "embedding_groups", gf);

        List<SearchResult> results = new ArrayList<>();
        Throwable[] firstErr = new Throwable[] {null};
        Object lock = new Object();
        java.util.concurrent.atomic.AtomicBoolean errOnce = new java.util.concurrent.atomic.AtomicBoolean(false);

        TenantContextSnapshot groupSnap = TenantContextSnapshot.capture();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, List<SearchTarget>> entry : groups.entrySet()) {
                String modelKey = entry.getKey();
                List<SearchTarget> targets = entry.getValue();
                futures.add(executor.submit(PluginSearch.withTenant(groupSnap, () -> {
                    searchModelGroup(modelKey, targets, chatManage, queryText, kbMap,
                            results, firstErr, errOnce, lock);
                    return null;
                })));
            }
            for (var f : futures) {
                PluginSearch.joinQuietly(f);
            }
        }

        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("total_hits", results.size());
        PipelineLog.info("Search", "kb_result_summary", sf);
        if (firstErr[0] != null) {
            throw new PipelinePorts.PipelinePortException(firstErr[0].getMessage(), firstErr[0]);
        }
        return results;
    }
    private void searchModelGroup(String modelKey, List<SearchTarget> targets, ChatManage chatManage,
                                  String queryText, Map<String, KnowledgeBase> kbMap,
                                  List<SearchResult> results, Throwable[] firstErr,
                                  java.util.concurrent.atomic.AtomicBoolean errOnce, Object lock) {
        // 组内算一次查询向量；失败时只保留有关键词索引的目标（向量-only 必须上报根因）
        float[] queryEmbedding = null;
        boolean disableVector = false;
        List<SearchTarget> searchableTargets = targets;
        if (!modelKey.isEmpty()) {
            try {
                queryEmbedding = service.knowledgeBaseService.getQueryEmbedding(
                        targets.get(0).knowledgeBaseId(), queryText);
            } catch (RuntimeException e) {
                List<SearchTarget> keep = new ArrayList<>(targets.size());
                for (SearchTarget target : targets) {
                    KnowledgeBase kb = kbMap.get(target.knowledgeBaseId());
                    if (!targetReportsEmbedFailure(kb)) {
                        keep.add(target);
                        continue;
                    }
                    if (errOnce.compareAndSet(false, true)) {
                        firstErr[0] = new RuntimeException(String.format(
                                "knowledge base %s has no keyword fallback: %s",
                                target.knowledgeBaseId(), e.getMessage()), e);
                    }
                }
                searchableTargets = keep;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("model_key", modelKey);
                f.put("kb_id", targets.get(0).knowledgeBaseId());
                f.put("error", e.getMessage());
                f.put("fallback_targets", keep.size());
                f.put("failed_targets", targets.size() - keep.size());
                PipelineLog.warn("Search", "group_embed_degrade_keyword", f);
                disableVector = true;
            }
        }

        // 整库目标（可合并为一次检索）与特定文档目标分离
        List<String> fullKbIds = new ArrayList<>();
        List<SearchTarget> knowledgeTargets = new ArrayList<>();
        for (SearchTarget t : searchableTargets) {
            if (SearchTarget.TYPE_KNOWLEDGE_BASE.equals(t.type())
                    && (t.tagIds() == null || t.tagIds().isEmpty())) {
                fullKbIds.add(t.knowledgeBaseId());
            } else {
                knowledgeTargets.add(t);
            }
        }

        Map<String, Object> pf = new LinkedHashMap<>();
        pf.put("model_key", modelKey);
        pf.put("combined_kb_count", fullKbIds.size());
        pf.put("individual_targets", knowledgeTargets.size());
        pf.put("vector_len", queryEmbedding == null ? 0 : queryEmbedding.length);
        PipelineLog.info("Search", "group_plan", pf);

        // 合并检索：一次 HybridSearch 跨全部整库目标
        if (!fullKbIds.isEmpty()) {
            SearchParams params = new SearchParams();
            params.setQueryText(queryText);
            params.setQueryEmbedding(queryEmbedding);
            params.setKnowledgeBaseIds(fullKbIds);
            params.setVectorThreshold(chatManage.getVectorThreshold());
            params.setKeywordThreshold(chatManage.getKeywordThreshold());
            params.setMatchCount(chatManage.getEmbeddingTopK());
            params.setSkipContextEnrichment(true);
            params.setDisableVectorMatch(disableVector);
            try {
                List<SearchResult> res = service.knowledgeBaseService.hybridSearch(fullKbIds.get(0), params);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("kb_ids", fullKbIds);
                f.put("hit_count", res == null ? 0 : res.size());
                PipelineLog.info("Search", "combined_kb_result", f);
                // res 可为 null（无可用检索管道时 hybridSearch 返回 null，对照 Go 的 nil 切片——
                // append(dst, nil...) 是 no-op，这里必须显式跳过，否则 addAll(null) 抛 NPE）
                if (res != null) {
                    synchronized (lock) {
                        results.addAll(res);
                    }
                }
            } catch (RuntimeException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("kb_ids", fullKbIds);
                f.put("error", e.getMessage());
                PipelineLog.warn("Search", "combined_kb_search_error", f);
                if (errOnce.compareAndSet(false, true)) {
                    firstErr[0] = e;
                }
            }
        }

        // 逐目标检索
        for (SearchTarget t : knowledgeTargets) {
            try {
                List<SearchResult> res = searchSingleTarget(chatManage, t, queryText,
                        queryEmbedding, disableVector);
                if (res != null) {
                    synchronized (lock) {
                        results.addAll(res);
                    }
                }
            } catch (RuntimeException e) {
                if (errOnce.compareAndSet(false, true)) {
                    firstErr[0] = e;
                }
            }
        }
    }

    /**
     * 对照 targetReportsEmbedFailure：wiki/图-only 的 KB 无向量或关键词索引可降级，
     * HybridSearch 返回空且无错；FAQ KB 必须上报；其余看索引开关。
     */
    static boolean targetReportsEmbedFailure(KnowledgeBase kb) {
        if (kb == null) {
            return false;
        }
        if ("faq".equals(kb.getType())) {
            return true;
        }
        if (isKeywordEnabled(kb)) {
            return false;
        }
        return isVectorEnabled(kb);
    }

    /** 对照 KnowledgeBase.IsVectorEnabled（IndexingStrategy.VectorEnabled）。 */
    static boolean isVectorEnabled(KnowledgeBase kb) {
        return kb != null && kb.getIndexingStrategy().isVectorEnabled();
    }

    /** 对照 KnowledgeBase.IsKeywordEnabled。 */
    static boolean isKeywordEnabled(KnowledgeBase kb) {
        return kb != null && kb.getIndexingStrategy().isKeywordEnabled();
    }

    private List<SearchResult> searchSingleTarget(ChatManage chatManage, SearchTarget t,
                                                  String queryText, float[] queryEmbedding,
                                                  boolean disableVector) {
        if (SearchTarget.TYPE_KNOWLEDGE.equals(t.type())
                && (t.knowledgeIds() == null || t.knowledgeIds().isEmpty())) {
            return null;
        }

        double[] th = t.recallThresholds(chatManage.getVectorThreshold(), chatManage.getKeywordThreshold());
        double vectorThreshold = th[0];
        double keywordThreshold = th[1];
        if (t.disableRecallThresholds()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kb_id", t.knowledgeBaseId());
            f.put("knowledge_id_count", t.knowledgeIds() == null ? 0 : t.knowledgeIds().size());
            f.put("tag_id_count", t.tagIds() == null ? 0 : t.tagIds().size());
            PipelineLog.info("Search", "explicit_scope_threshold_override", f);
        }
        SearchParams params = new SearchParams();
        params.setQueryText(queryText);
        params.setQueryEmbedding(queryEmbedding);
        params.setVectorThreshold(vectorThreshold);
        params.setKeywordThreshold(keywordThreshold);
        params.setMatchCount(chatManage.getEmbeddingTopK());
        params.setTagIds(t.tagIds());
        params.setScopeTagIds(t.scopeTagIds());
        params.setSkipContextEnrichment(true);
        params.setDisableVectorMatch(disableVector);
        if (SearchTarget.TYPE_KNOWLEDGE.equals(t.type())) {
            params.setKnowledgeIds(t.knowledgeIds());
        }
        try {
            List<SearchResult> res = service.knowledgeBaseService.hybridSearch(t.knowledgeBaseId(), params);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kb_id", t.knowledgeBaseId());
            f.put("target_type", t.type());
            f.put("hit_count", res == null ? 0 : res.size());
            PipelineLog.info("Search", "kb_result", f);
            return res;
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kb_id", t.knowledgeBaseId());
            f.put("target_type", t.type());
            f.put("query", params.getQueryText());
            f.put("error", e.getMessage());
            PipelineLog.warn("Search", "kb_search_error", f);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // searchWebIfEnabled（search.go:582-652）
    // ------------------------------------------------------------------

    List<SearchResult> searchWebIfEnabled(ChatManage chatManage) {
        if (!chatManage.isWebSearchEnabled() || service.webSearchService == null || service.tenantService == null) {
            return null;
        }
        String providerId = chatManage.getWebSearchProviderId();

        if (providerId.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("tenant_id", chatManage.getTenantId());
            PipelineLog.warn("Search", "web_config_missing", f);
            return null;
        }

        // 租户级 web 配置（ctx 里的租户信息；Java 侧从 TenantContext 取，探针/装配期可注入）
        com.ragagent.auth.domain.tenantconfig.WebSearchConfig tenantCfg = currentTenantWebSearchConfig();
        com.ragagent.websearch.service.WebSearchService.WebSearchConfig webConfig =
                effectiveWebSearchConfig(tenantCfg);

        // agent 级覆写
        if (chatManage.getWebSearchMaxResults() > 0) {
            webConfig.maxResults = chatManage.getWebSearchMaxResults();
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("tenant_id", chatManage.getTenantId());
        f.put("provider_id", providerId);
        PipelineLog.info("Search", "web_request", f);

        Span webSpan = LangfuseManager.get().startSpan(new LangfuseManager.SpanOptions(
                "web_search",
                mapOf("provider_id", providerId, "query", chatManage.getRewriteQuery(),
                        "max_results", webConfig.maxResults),
                null));
        List<WebSearchResult> webResults;
        try {
            webResults = service.webSearchService.search(providerId, webConfig, chatManage.getRewriteQuery());
        } catch (RuntimeException e) {
            webSpan.finish(null, null, e.getMessage());
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("tenant_id", chatManage.getTenantId());
            w.put("error", e.getMessage());
            PipelineLog.warn("Search", "web_search_error", w);
            return null;
        }
        webSpan.finish(mapOf("hit_count", webResults == null ? 0 : webResults.size()), null, null);

        List<SearchResult> res = WebResultConverter.convert(webResults);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("hit_count", res == null ? 0 : res.size());
        PipelineLog.info("Search", "web_hits", h);
        return res;
    }

    /**
     * 租户 web 配置（对照 Go search.go L597-600：ctx 里的 TenantInfo；
     * 2026-09-25 评审批接线——port 实现在 QaWiring，按 TenantContext 实时读取，
     * 无租户上下文 → null，走 EffectiveWebSearchConfig(nil) 缺省分支）。
     */
    private com.ragagent.auth.domain.tenantconfig.WebSearchConfig currentTenantWebSearchConfig() {
        if (service.tenantService == null) {
            return null;
        }
        return service.tenantService.currentWebSearchConfig();
    }

    /** 对照 types.EffectiveWebSearchConfig 的执行面缺省（web_search.go:47 的生效值合并）。 */
    static com.ragagent.websearch.service.WebSearchService.WebSearchConfig effectiveWebSearchConfig(
            com.ragagent.auth.domain.tenantconfig.WebSearchConfig cfg) {
        com.ragagent.websearch.service.WebSearchService.WebSearchConfig out =
                new com.ragagent.websearch.service.WebSearchService.WebSearchConfig();
        if (cfg == null) {
            return out;
        }
        out.blacklist = cfg.getBlacklist() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(cfg.getBlacklist());
        out.apiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        out.documentFragments = cfg.getDocumentFragments();
        out.embeddingModelId = cfg.getEmbeddingModelId() == null ? "" : cfg.getEmbeddingModelId();
        out.includeDate = cfg.isIncludeDate();
        out.maxResults = cfg.getMaxResults();
        out.provider = cfg.getProvider() == null ? "" : cfg.getProvider();
        out.proxyUrl = cfg.getProxyUrl() == null ? "" : cfg.getProxyUrl();
        return out;
        // Go 全量拷贝还含 rerank_model_id/embedding_dimension——执行形状
        // WebSearchConfig 未承载（仅 RAG 压缩消费，search 路径不用），随压缩
        // 路径接线时补。
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
