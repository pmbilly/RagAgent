package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.retrieval.graph.RetrieveGraphRepository;
import com.ragagent.llm.extract.PipelineConfig;

/**
 * CHUNK_SEARCH_PARALLEL 阶段插件（对照 Go chat_pipeline/search_parallel.go）：
 * 并发跑 chunk 检索（内部 PluginSearch 的克隆）与实体检索（内部 PluginSearchEntity
 * 的克隆），两路结果合并去重。
 *
 * <p>Deep-copy：Go 用 chatManage.Clone()（SearchResult=nil）避免并发读写共享切片；
 * Java 同样 {@code cloneChatManage()}。ErrSearchNothing 会被吞掉（两个 Run 任务把
 * SEARCH_NOTHING 归 nil）；合并序恒 chunk 在前 entity 在后（确定性，实录钉住）。
 * 全空时优先返回 chunk_search 的错误（errs map 命中），否则 SEARCH_NOTHING。</p>
 */
public final class PluginSearchParallel implements Plugin {

    private final PipelinePorts.KnowledgeBaseService knowledgeBaseService;
    private final PipelinePorts.KnowledgeService knowledgeService;
    private final PipelineConfig config;
    private final PipelinePorts.WebSearch webSearchService;
    private final PipelinePorts.TenantService tenantService;
    private final PipelinePorts.SessionService sessionService;

    private final RetrieveGraphRepository graphRepo;
    private final PipelinePorts.ChunkRepository chunkRepo;
    private final PipelinePorts.KnowledgeRepository knowledgeRepo;

    private final PluginSearch searchPlugin;
    private final PluginSearchEntity searchEntityPlugin;

    public PluginSearchParallel(EventManager eventManager,
                                PipelinePorts.KnowledgeBaseService knowledgeBaseService,
                                PipelinePorts.KnowledgeService knowledgeService,
                                PipelinePorts.ChunkService chunkService,
                                PipelineConfig config,
                                PipelinePorts.WebSearch webSearchService,
                                PipelinePorts.TenantService tenantService,
                                PipelinePorts.SessionService sessionService,
                                PipelinePorts.WebSearchStateService webSearchStateService,
                                PipelinePorts.WebSearchProviderRepository webSearchProviderRepo,
                                RetrieveGraphRepository graphRepository,
                                PipelinePorts.ChunkRepository chunkRepository,
                                PipelinePorts.KnowledgeRepository knowledgeRepository) {
        // 内部插件不注册到 manager（对照 Go 的未注册构造）
        this.searchPlugin = new PluginSearch(knowledgeBaseService, knowledgeService, chunkService,
                config, webSearchService, tenantService, sessionService,
                webSearchStateService, webSearchProviderRepo);
        this.searchEntityPlugin = new PluginSearchEntity(graphRepository, chunkRepository, knowledgeRepository);

        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeService = knowledgeService;
        this.config = config;
        this.webSearchService = webSearchService;
        this.tenantService = tenantService;
        this.sessionService = sessionService;
        this.graphRepo = graphRepository;
        this.chunkRepo = chunkRepository;
        this.knowledgeRepo = knowledgeRepository;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHUNK_SEARCH_PARALLEL};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (!chatManage.needsRetrieval()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("reason", "intent_no_search");
            PipelineLog.info("SearchParallel", "skip", f);
            return next.next();
        }

        Map<String, Object> start = new LinkedHashMap<>();
        start.put("session_id", chatManage.getSessionId());
        start.put("has_entities", chatManage.getEntity() != null && !chatManage.getEntity().isEmpty());
        start.put("rewrite_query", chatManage.getRewriteQuery());
        PipelineLog.info("SearchParallel", "start", start);

        ChatManage chunkCM = chatManage.cloneChatManage();
        chunkCM.setSearchResult(null);
        ChatManage entityCM = chatManage.cloneChatManage();
        entityCM.setSearchResult(null);

        final ChatManage fChunkCM = chunkCM;
        final ChatManage fEntityCM = entityCM;
        final ChatManage fOrig = chatManage;

        Map<String, PluginError> errs = PipelineCommon.runParallel(
                new PipelineCommon.ParallelTask("chunk_search", () -> {
                    PluginError err = searchPlugin.onEvent(
                            PipelineEventType.CHUNK_SEARCH, fChunkCM, () -> null);
                    boolean isNothing = err == PluginError.SEARCH_NOTHING;
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("result_count", fChunkCM.getSearchResult() == null ? 0 : fChunkCM.getSearchResult().size());
                    f.put("has_error", err != null && !isNothing);
                    PipelineLog.info("SearchParallel", "chunk_search_done", f);
                    return isNothing ? null : err;
                }),
                new PipelineCommon.ParallelTask("entity_search", () -> {
                    if (fOrig.getEntity() == null || fOrig.getEntity().isEmpty()) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("reason", "no_entities");
                        PipelineLog.info("SearchParallel", "entity_search_skip", f);
                        return null;
                    }
                    PluginError err = searchEntityPlugin.onEvent(
                            PipelineEventType.ENTITY_SEARCH, fEntityCM, () -> null);
                    boolean isNothing = err == PluginError.SEARCH_NOTHING;
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("result_count", fEntityCM.getSearchResult() == null ? 0 : fEntityCM.getSearchResult().size());
                    f.put("has_error", err != null && !isNothing);
                    PipelineLog.info("SearchParallel", "entity_search_done", f);
                    return isNothing ? null : err;
                }));

        // 合并两路结果（chunk 在前 entity 在后）+ 去重
        List<SearchResult> merged = new ArrayList<>();
        if (chunkCM.getSearchResult() != null) {
            merged.addAll(chunkCM.getSearchResult());
        }
        if (entityCM.getSearchResult() != null) {
            merged.addAll(entityCM.getSearchResult());
        }
        chatManage.setSearchResult(SearchSupport.removeDuplicateResults(merged));

        for (Map.Entry<String, PluginError> e : errs.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("task", e.getKey());
            f.put("err", e.getValue() == null ? null
                    : (e.getValue().err == null ? e.getValue().errorType : e.getValue().err.getMessage()));
            PipelineLog.warn("SearchParallel", "task_error", f);
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("session_id", chatManage.getSessionId());
        done.put("chunk_results", chunkCM.getSearchResult() == null ? 0 : chunkCM.getSearchResult().size());
        done.put("entity_results", entityCM.getSearchResult() == null ? 0 : entityCM.getSearchResult().size());
        done.put("total_results", chatManage.getSearchResult() == null ? 0 : chatManage.getSearchResult().size());
        done.put("error_count", errs.size());
        PipelineLog.info("SearchParallel", "complete", done);

        if (chatManage.getSearchResult() == null || chatManage.getSearchResult().isEmpty()) {
            PluginError err = errs.get("chunk_search");
            if (err != null) {
                return err;
            }
            return PluginError.SEARCH_NOTHING;
        }

        return next.next();
    }
}
