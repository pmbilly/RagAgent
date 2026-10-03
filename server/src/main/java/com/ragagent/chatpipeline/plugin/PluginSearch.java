package com.ragagent.chatpipeline.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.chatpipeline.support.SearchSupport;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.TenantContextSnapshot;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.llm.extract.PipelineConfig;

/**
 * CHUNK_SEARCH 阶段插件（含查询扩展与检索执行协作者）。
 *
 * <h2>编排面</h2>
 * <ul>
 *   <li>OnEvent：无目标且 web 关闭 → null（kb_not_found）；KB 检索与 web 检索并发；
 *       KB 失败且全空 → SEARCH；低召回（EnableQueryExpansion）触发本地查询扩展。</li>
 *   <li>searchByTargets：共享 embedding model（name+endpoint key）的目标合组，
 *       组内算一次查询向量；向量失败时保留有关键词索引的目标并 DisableVectorMatch，
 *       向量-only 的目标上报根因（"knowledge base %s has no keyword fallback: %w"）。</li>
 *   <li>扩展检索：并发窗口 16，阈值 KeywordThreshold*0.8，SkipContextEnrichment。</li>
 * </ul>
 *
 * <p>并发语义：并发任务的首个错误保留（happens-before 的首个异常字段落定）。
 * web 结果转换走 WebResultConverter。langfuse span 保持调用形状（恒 no-op）。</p>
 */
public final class PluginSearch implements Plugin {

    final PipelinePorts.KnowledgeBaseService knowledgeBaseService;
    private final PipelinePorts.KnowledgeService knowledgeService;
    private final PipelinePorts.ChunkService chunkService;
    private final PipelineConfig config;
    final PipelinePorts.WebSearch webSearchService;
    final PipelinePorts.TenantService tenantService;
    private final PipelinePorts.SessionService sessionService;
    private final PipelinePorts.WebSearchStateService webSearchStateService;
    private final PipelinePorts.WebSearchProviderRepository webSearchProviderRepo;

    /** 查询扩展协作者。 */
    final PluginExpansionOps expansionOps;

    /** 检索执行协作者。 */
    final PluginSearchOps searchOps;

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
        this.expansionOps = new PluginExpansionOps(this);
        this.searchOps = new PluginSearchOps(this);
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

        // KB 检索与 web 检索并发。
        // ThreadLocal 不跨线程，必须显式快照/回放
        // （与 EventBus 异步派发同款纪律——否则虚拟线程上 tenantId()=0，
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

    static void joinQuietly(java.util.concurrent.Future<?> f) {
        try {
            f.get();
        } catch (Exception e) {
            throw new IllegalStateException("search task failed", e);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 跨虚拟线程显式传 TenantContext：提交线程 capture，工作线程 replay，
     * finally clear（虚拟线程由 JVM 池化复用载体，不清理会污染下一个任务）。
     */
    static java.util.concurrent.Callable<Object> withTenant(
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

    /** 薄委托：见 {@link PluginSearchOps#searchByTargets}。 */
    public List<SearchResult> searchByTargets(ChatManage chatManage) {
        return searchOps.searchByTargets(chatManage);
    }

    /** 薄委托：见 {@link PluginSearchOps#searchWebIfEnabled}（onEvent 消费）。 */
    List<SearchResult> searchWebIfEnabled(ChatManage chatManage) {
        return searchOps.searchWebIfEnabled(chatManage);
    }




    // ------------------------------------------------------------------


    /** 薄委托：见 {@link QueryTextOps#extractKeywords}（测试直调）。 */
    public static List<String> extractKeywords(String text) {
        return QueryTextOps.extractKeywords(text);
    }

    /** 薄委托：见 {@link QueryTextOps#extractPhrases}（测试直调）。 */
    public static List<String> extractPhrases(String text) {
        return QueryTextOps.extractPhrases(text);
    }

    /** 薄委托：见 {@link QueryTextOps#splitByDelimiters}（测试直调）。 */
    public static List<String> splitByDelimiters(String text) {
        return QueryTextOps.splitByDelimiters(text);
    }

    /** 薄委托：见 {@link QueryTextOps#removeQuestionWords}（测试直调）。 */
    public static String removeQuestionWords(String text) {
        return QueryTextOps.removeQuestionWords(text);
    }

    /** 薄委托：见 {@link QueryTextOps#tokenize}（测试直调）。 */
    public static List<String> tokenize(String text) {
        return QueryTextOps.tokenize(text);
    }

    /** 薄委托：见 {@link PluginExpansionOps#runQueryExpansion}。 */
    public List<SearchResult> runQueryExpansion(ChatManage chatManage) {
        return expansionOps.runQueryExpansion(chatManage);
    }

    /** 薄委托：见 {@link PluginExpansionOps#expandQueries}。 */
    public List<String> expandQueries(ChatManage chatManage) {
        return expansionOps.expandQueries(chatManage);
    }
}
