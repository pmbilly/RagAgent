package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.TenantContextSnapshot;
import com.ragagent.tracing.langfuse.LangfuseManager;
import com.ragagent.tracing.langfuse.Span;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.retrieval.support.WebResultConverter;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * CHUNK_SEARCH 阶段插件（对照 Go chat_pipeline/search.go 的 PluginSearch +
 * query_expansion.go 的 runQueryExpansion/expandQueries 及其辅助函数）。
 *
 * <h2>编排面（实录组 search/search_by_targets/search_parallel 钉住）</h2>
 * <ul>
 *   <li>OnEvent：无目标且 web 关闭 → null（kb_not_found）；KB 检索与 web 检索并发；
 *       KB 失败且全空 → SEARCH；低召回（EnableQueryExpansion）触发本地查询扩展。</li>
 *   <li>searchByTargets：共享 embedding model（name+endpoint key）的目标合组，
 *       组内算一次查询向量；向量失败时保留有关键词索引的目标并 DisableVectorMatch，
 *       向量-only 的目标上报根因（"knowledge base %s has no keyword fallback: %w"）。</li>
 *   <li>扩展检索：并发窗口 16，阈值 KeywordThreshold*0.8，SkipContextEnrichment。</li>
 * </ul>
 *
 * <p>并发语义：Go 的 errgroup 首错保留（recordError 的 errOnce）；Java 用
 * happens-before 的首个异常字段落定。web 结果转换走 searchutil.WebResultConverter
 * （4.4 已实录对齐）。langfuse span 保持调用形状（Java 恒 no-op）。</p>
 */
public final class PluginSearch implements Plugin {

    private final PipelinePorts.KnowledgeBaseService knowledgeBaseService;
    private final PipelinePorts.KnowledgeService knowledgeService;
    private final PipelinePorts.ChunkService chunkService;
    private final PipelineConfig config;
    private final PipelinePorts.WebSearch webSearchService;
    private final PipelinePorts.TenantService tenantService;
    private final PipelinePorts.SessionService sessionService;
    private final PipelinePorts.WebSearchStateService webSearchStateService;
    private final PipelinePorts.WebSearchProviderRepository webSearchProviderRepo;

    public PluginSearch(PipelinePorts.KnowledgeBaseService knowledgeBaseService,
                        PipelinePorts.KnowledgeService knowledgeService,
                        PipelinePorts.ChunkService chunkService,
                        PipelineConfig config,
                        PipelinePorts.WebSearch webSearchService,
                        PipelinePorts.TenantService tenantService,
                        PipelinePorts.SessionService sessionService,
                        PipelinePorts.WebSearchStateService webSearchStateService,
                        PipelinePorts.WebSearchProviderRepository webSearchProviderRepo) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeService = knowledgeService;
        this.chunkService = chunkService;
        this.config = config;
        this.webSearchService = webSearchService;
        this.tenantService = tenantService;
        this.sessionService = sessionService;
        this.webSearchStateService = webSearchStateService;
        this.webSearchProviderRepo = webSearchProviderRepo;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHUNK_SEARCH};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        boolean hasKBTargets = SearchTarget.SearchTargets.hasKnowledgeRetrievalScope(
                new SearchTarget.SearchTargets(chatManage.getSearchTargets()),
                chatManage.getKnowledgeBaseIds(), chatManage.getKnowledgeIds());
        if (!hasKBTargets && !chatManage.isWebSearchEnabled()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            PipelineLog.error("Search", "kb_not_found", f);
            return null;
        }

        logInput(chatManage);

        // KB 检索与 web 检索并发（对照两个 goroutine + mu）。
        // Go 的 ctx 值随 goroutine 捕获流转；Java ThreadLocal 不跨线程，必须显式快照/回放
        // （约定 §5，与 EventBus 异步派发同款纪律——否则虚拟线程上 tenantId()=0，
        // getModelByID 抛 ModelNotFoundException，整组静默降级为关键词-only）。
        TenantContextSnapshot tenantSnap = TenantContextSnapshot.capture();
        List<SearchResult> allResults = new ArrayList<>();
        Object lock = new Object();
        // kbErr 必须是每次调用的局部量：本类是单例插件，实例字段会在并发/后续请求间
        // 泄漏上一次的检索异常，把"检索成功但 0 命中"误判成 search_failed 硬错。
        java.util.concurrent.atomic.AtomicReference<Throwable> kbErrHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var f1 = executor.submit(withTenant(tenantSnap, () -> {
                try {
                    List<SearchResult> kbResults = searchByTargets(chatManage);
                    if (kbResults != null && !kbResults.isEmpty()) {
                        synchronized (lock) {
                            allResults.addAll(kbResults);
                        }
                    }
                    return null;
                } catch (Throwable t) {
                    kbErrHolder.set(t);
                    return null;
                }
            }));
            var f2 = executor.submit(withTenant(tenantSnap, () -> {
                List<SearchResult> webResults = searchWebIfEnabled(chatManage);
                if (webResults != null && !webResults.isEmpty()) {
                    synchronized (lock) {
                        allResults.addAll(webResults);
                    }
                }
                return null;
            }));
            joinQuietly(f1);
            joinQuietly(f2);
        }
        Throwable kbErr = kbErrHolder.get();
        if (kbErr != null && allResults.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", kbErr.getMessage());
            PipelineLog.error("Search", "kb_search_failed", f);
            return PluginError.SEARCH.withError(kbErr);
        }
        if (kbErr != null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", kbErr.getMessage());
            f.put("result_count", allResults.size());
            PipelineLog.warn("Search", "kb_search_partial_failure", f);
        }

        chatManage.setSearchResult(allResults);

        SearchSupport.logSearchScoreSample("result_score_before_normalize", chatManage.getSearchResult());

        // 低召回 → 本地查询扩展补召回
        int minRecall = Math.max(1, chatManage.getEmbeddingTopK());
        if (chatManage.isEnableQueryExpansion() && chatManage.getSearchResult().size() < minRecall) {
            List<SearchResult> expResults = runQueryExpansion(chatManage);
            if (expResults != null && !expResults.isEmpty()) {
                chatManage.getSearchResult().addAll(expResults);
            }
        }

        SearchSupport.logSearchScoreSample("final_score", chatManage.getSearchResult());

        if (!chatManage.getSearchResult().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("result_count", chatManage.getSearchResult().size());
            PipelineLog.info("Search", "output", f);
            return next.next();
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("session_id", chatManage.getSessionId());
        f.put("result_count", 0);
        PipelineLog.warn("Search", "output", f);
        return PluginError.SEARCH_NOTHING;
    }

    private void logInput(ChatManage chatManage) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("session_id", chatManage.getSessionId());
        f.put("rewrite_query", chatManage.getRewriteQuery());
        f.put("search_targets", chatManage.getSearchTargets().size());
        f.put("tenant_id", chatManage.getTenantId());
        f.put("web_enabled", chatManage.isWebSearchEnabled());
        PipelineLog.info("Search", "input", f);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("search_targets", chatManage.getSearchTargets().size());
        p.put("embedding_top_k", chatManage.getEmbeddingTopK());
        p.put("vector_threshold", chatManage.getVectorThreshold());
        p.put("keyword_threshold", chatManage.getKeywordThreshold());
        PipelineLog.info("Search", "plan", p);
    }

    private static void joinQuietly(java.util.concurrent.Future<?> f) {
        try {
            f.get();
        } catch (Exception e) {
            throw new IllegalStateException("search task failed", e);
        }
    }

    // ------------------------------------------------------------------
    // searchByTargets（search.go:343-519）
    // ------------------------------------------------------------------

    /**
     * 按 embedding 模型分组检索。共享模型（name+endpoint）的整库目标合并成一次
     * HybridSearch；特定文档目标逐目标检索。
     */
    List<SearchResult> searchByTargets(ChatManage chatManage) {
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
            List<KnowledgeBase> kbs = knowledgeBaseService.getKnowledgeBasesByIdsOnly(kbIds);
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

        Map<String, String> modelKeyMap =
                knowledgeBaseService.resolveEmbeddingModelKeys(kbList);

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
                futures.add(executor.submit(withTenant(groupSnap, () -> {
                    searchModelGroup(modelKey, targets, chatManage, queryText, kbMap,
                            results, firstErr, errOnce, lock);
                    return null;
                })));
            }
            for (var f : futures) {
                joinQuietly(f);
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

    /**
     * 跨虚拟线程显式传 TenantContext（约定 §5）：提交线程 capture，工作线程 replay，
     * finally clear（虚拟线程由 JVM 池化复用载体，不清理会污染下一个任务）。
     */
    private static java.util.concurrent.Callable<Object> withTenant(
            TenantContextSnapshot snap, java.util.concurrent.Callable<Object> body) {
        return () -> {
            snap.replay();
            try {
                return body.call();
            } finally {
                TenantContext.clear();
            }
        };
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
                queryEmbedding = knowledgeBaseService.getQueryEmbedding(
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
                List<SearchResult> res = knowledgeBaseService.hybridSearch(fullKbIds.get(0), params);
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
            List<SearchResult> res = knowledgeBaseService.hybridSearch(t.knowledgeBaseId(), params);
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

    private List<SearchResult> searchWebIfEnabled(ChatManage chatManage) {
        if (!chatManage.isWebSearchEnabled() || webSearchService == null || tenantService == null) {
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
            webResults = webSearchService.search(providerId, webConfig, chatManage.getRewriteQuery());
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
        if (tenantService == null) {
            return null;
        }
        return tenantService.currentWebSearchConfig();
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

    // ------------------------------------------------------------------
    // 查询扩展（query_expansion.go 全文）
    // ------------------------------------------------------------------

    /** 对照 runQueryExpansion：低召回时的本地变体检索，并发窗口 16。 */
    List<SearchResult> runQueryExpansion(ChatManage chatManage) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("current", chatManage.getSearchResult().size());
        f.put("threshold", chatManage.getEmbeddingTopK());
        PipelineLog.info("Search", "recall_low", f);

        List<String> expansions = expandQueries(chatManage);
        if (expansions == null || expansions.isEmpty()) {
            return null;
        }

        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("variants", expansions.size());
        PipelineLog.info("Search", "expansion_start", sf);

        int expTopK = Math.max(chatManage.getEmbeddingTopK() * 2, chatManage.getRerankTopK() * 2);
        double expKwTh = chatManage.getKeywordThreshold() * 0.8;

        List<SearchResult> expResults = new ArrayList<>();
        Object lock = new Object();

        // 统计有效作业数（跳过 nil / 空 KB ID 的目标）
        List<Object[]> jobs = new ArrayList<>();
        for (String q : expansions) {
            for (SearchTarget target : chatManage.getSearchTargets()) {
                if (target == null || target.knowledgeBaseId().isEmpty()) {
                    continue;
                }
                jobs.add(new Object[] {q, target});
            }
        }
        int jobsN = jobs.size();
        int capSem = Math.min(16, jobsN);
        if (capSem <= 0) {
            capSem = 1;
        }
        Semaphore sem = new Semaphore(capSem);
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("jobs", jobsN);
        cf.put("cap", capSem);
        PipelineLog.info("Search", "expansion_concurrency", cf);

        TenantContextSnapshot expansionSnap = TenantContextSnapshot.capture();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Object[] job : jobs) {
                String q = (String) job[0];
                SearchTarget t = (SearchTarget) job[1];
                futures.add(executor.submit(withTenant(expansionSnap, () -> {
                    sem.acquireUninterruptibly();
                    try {
                        double[] th = t.recallThresholds(chatManage.getVectorThreshold(), expKwTh);
                        SearchParams params = new SearchParams();
                        params.setQueryText(q);
                        params.setVectorThreshold(th[0]);
                        params.setKeywordThreshold(th[1]);
                        params.setMatchCount(expTopK);
                        params.setTagIds(t.tagIds());
                        params.setScopeTagIds(t.scopeTagIds());
                        params.setDisableVectorMatch(false);
                        params.setDisableKeywordsMatch(false);
                        params.setSkipContextEnrichment(true); // 上下文组装在 merge 阶段
                        if (SearchTarget.TYPE_KNOWLEDGE.equals(t.type())) {
                            params.setKnowledgeIds(t.knowledgeIds());
                        }
                        List<SearchResult> res;
                        try {
                            res = knowledgeBaseService.hybridSearch(t.knowledgeBaseId(), params);
                        } catch (RuntimeException e) {
                            Map<String, Object> w = new LinkedHashMap<>();
                            w.put("kb_id", t.knowledgeBaseId());
                            w.put("error", e.getMessage());
                            PipelineLog.warn("Search", "expansion_error", w);
                            return null;
                        }
                        if (res != null && !res.isEmpty()) {
                            for (SearchResult r : res) {
                                r.setKnowledgeBaseId(t.knowledgeBaseId());
                            }
                            Map<String, Object> h = new LinkedHashMap<>();
                            h.put("kb_id", t.knowledgeBaseId());
                            h.put("query", q);
                            h.put("hits", res.size());
                            PipelineLog.info("Search", "expansion_hits", h);
                            synchronized (lock) {
                                expResults.addAll(res);
                            }
                        }
                    } finally {
                        sem.release();
                    }
                    return null;
                })));
            }
            for (var fu : futures) {
                try {
                    fu.get();
                } catch (Exception e) {
                    throw new IllegalStateException("expansion job failed", e);
                }
            }
        }

        if (!expResults.isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("added", expResults.size());
            PipelineLog.info("Search", "expansion_done", d);
        }
        return expResults;
    }

    /**
     * 对照 expandQueries：无 LLM 的本地变体生成（去停用词、引号短语、分隔符切段、
     * 去疑问词），最多 5 条。
     */
    List<String> expandQueries(ChatManage chatManage) {
        String query = chatManage.getRewriteQuery().trim();
        if (query.isEmpty()) {
            return null;
        }

        List<String> expansions = new ArrayList<>(5);
        Set<String> seen = new HashSet<>();
        seen.add(query.toLowerCase(java.util.Locale.ROOT));
        String lowerQuery = chatManage.getQuery().toLowerCase(java.util.Locale.ROOT);
        if (!lowerQuery.isEmpty()) {
            seen.add(lowerQuery);
        }

        // 1. 去停用词 → 纯关键词变体
        List<String> keywords = extractKeywords(query);
        if (keywords.size() >= 2) {
            addIfNew(expansions, seen, String.join(" ", keywords));
        }

        // 2. 引号短语
        for (String phrase : extractPhrases(query)) {
            addIfNew(expansions, seen, phrase);
        }

        // 3. 分隔符切段取长段（Go 的 len(seg) > 5 是 UTF-8 字节数：CJK 短语按字节计入）
        for (String seg : splitByDelimiters(query)) {
            if (seg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5) {
                addIfNew(expansions, seen, seg);
            }
        }

        // 4. 去疑问词
        String cleaned = removeQuestionWords(query);
        if (!cleaned.equals(query)) {
            addIfNew(expansions, seen, cleaned);
        }

        if (expansions.size() > 5) {
            expansions = new ArrayList<>(expansions.subList(0, 5));
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("variants", expansions.size());
        PipelineLog.info("Search", "local_expansion_result", f);
        return expansions;
    }

    private static void addIfNew(List<String> expansions, Set<String> seen, String s) {
        String v = s.trim();
        // Go 的 len(s) 是 UTF-8 字节数（ASCII 短语 &lt;3 与单 CJK 字符 =3 字节的分界都要对齐）
        if (v.isEmpty() || v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 3) {
            return;
        }
        String key = v.toLowerCase(java.util.Locale.ROOT);
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        expansions.add(v);
    }

    // ----- 中英停用词（对照 stopwords） -----

    private static final Set<String> STOPWORDS = Set.of(
            "的", "是", "在", "了", "和", "与", "或",
            "a", "an", "the", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "can",
            "to", "of", "in", "for", "on", "with", "at",
            "by", "from", "as", "into", "through", "about",
            "what", "how", "why", "when", "where", "which",
            "who", "whom", "whose");

    /** 中文疑问词前缀（对照 questionWords，锚定开头）。 */
    private static final Pattern QUESTION_WORDS =
            Pattern.compile("^(什么是|什么|如何|怎么|怎样|为什么|为何|哪个|哪些|谁|何时|何地|请问|请告诉我|帮我|我想知道|我想了解)");

    static List<String> extractKeywords(String text) {
        List<String> words = tokenize(text);
        List<String> keywords = new ArrayList<>(words.size());
        for (String w : words) {
            String lower = w.toLowerCase(java.util.Locale.ROOT);
            if (!STOPWORDS.contains(lower) && runeCount(w) > 1) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    static List<String> extractPhrases(String text) {
        List<String> phrases = new ArrayList<>();
        java.util.regex.Matcher m = QUOTED_PHRASE.matcher(text);
        while (m.find()) {
            // Go 的 len(m[1]) > 2 是 UTF-8 字节数（CJK 短语两字 = 6 字节仍入选）
            if (m.groupCount() >= 1
                    && m.group(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2) {
                phrases.add(m.group(1));
            }
        }
        return phrases;
    }

    /** 引号对（Go 字符类 hexdump 对照：直双引号、直单引号、「」『』）。 */
    private static final Pattern QUOTED_PHRASE =
            Pattern.compile("[\"'\u300c\u300d\u300e\u300f]([^\"'\u300c\u300d\u300e\u300f]+)[\"'\u300c\u300d\u300e\u300f]");

    private static final Pattern DELIMITERS = Pattern.compile("[,，;；、。！？!?\\s]+");

    static List<String> splitByDelimiters(String text) {
        String[] parts = DELIMITERS.split(text);
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty()) {
                result.add(v);
            }
        }
        return result;
    }

    static String removeQuestionWords(String text) {
        return QUESTION_WORDS.matcher(text).replaceAll("").trim();
    }

    /**
     * 对照 tokenize：Han 连续段走 jieba CutForSearch（searchutil 的分词 seam，
     * 4.4 的已知降级：Java 默认二字滑窗，可注入恢复）；字母数字段整段成词。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean[] currentIsHan = new boolean[] {false};

        Runnable flush = () -> {
            if (current.length() == 0) {
                return;
            }
            if (currentIsHan[0]) {
                for (String word : QueryTokenizer.cutForSearch(current.toString())) {
                    String w = word.trim();
                    if (!w.isEmpty()) {
                        tokens.add(w);
                    }
                }
            } else {
                tokens.add(current.toString());
            }
            current.setLength(0);
            currentIsHan[0] = false;
        };

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isHan(cp)) {
                if (current.length() > 0 && !currentIsHan[0]) {
                    flush.run();
                }
                currentIsHan[0] = true;
                current.appendCodePoint(cp);
            } else if (Character.isLetter(cp) || Character.isDigit(cp)) {
                if (current.length() > 0 && currentIsHan[0]) {
                    flush.run();
                }
                currentIsHan[0] = false;
                current.appendCodePoint(cp);
            } else {
                flush.run();
            }
            i += Character.charCount(cp);
        }
        flush.run();
        return tokens;
    }

    /** Unicode Han 判定（对照 unicode.Is(unicode.Han, r)）。 */
    private static boolean isHan(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN;
    }

    private static int runeCount(String s) {
        return s.codePointCount(0, s.length());
    }
}
