package com.ragagent.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.service.EmbedderClient;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.retrieval.engine.EffectiveEngines;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieverEngineParams;

/**
 * HybridSearch 执行面（对照 Go internal/application/service/knowledgebase_search.go
 * + _fusion/_storegroup/_fanout/_results/_faq 全族 + retriever/postgres 引擎，
 * 检索引擎批 2026-09-22 翻译）。
 *
 * <h2>与 Go 的形状差异（诚实声明）</h2>
 * <ul>
 *   <li>store 分组保留 (storeID, ownerTenant) 结构；引擎解析只实现
 *       <b>postgres env-store</b> 路径（Go 注释原文："every existing KB has
 *       vector_store_id = NULL"——env-store 是现行唯一形态）。绑定了外部
 *       store（ES/milvus/…）的 KB → 2201 unavailable 同形错误（真实后端随
 *       provider 批）。</li>
 *   <li>多组 fan-out 用顺序执行（Go errgroup；单组是现行主路径，快速路径一致）。</li>
 *   <li>langfuse span 与部分 INFO 日志不入契约，省略。</li>
 * </ul>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** 对照 maxRetrievalPoolSize（knowledgebase_search.go L20）。 */
    public static final int MAX_RETRIEVAL_POOL_SIZE = 500;
    /** 对照 types.DefaultRetrievalTopK。 */
    public static final int DEFAULT_RETRIEVAL_TOP_K = 50;

    // MatchType（types/embedding.go iota 序）。
    public static final int MATCH_EMBEDDING = 0;
    public static final int MATCH_KEYWORDS = 1;
    public static final int MATCH_NEAR_BY_CHUNK = 2;
    public static final int MATCH_HISTORY = 3;
    public static final int MATCH_PARENT_CHUNK = 4;
    public static final int MATCH_RELATION_CHUNK = 5;

    private final KnowledgeBaseService kbService;
    private final KnowledgeService knowledgeService;
    private final ChunkRepository chunkRepository;
    private final ModelService modelService;
    private final TenantService tenantService;
    private final EmbedderClient embedderClient;
    private final PgVectorRetrieveRepository pgRepository;

    public HybridSearchService(KnowledgeBaseService kbService, KnowledgeService knowledgeService,
            ChunkRepository chunkRepository, ModelService modelService, TenantService tenantService,
            EmbedderClient embedderClient, PgVectorRetrieveRepository pgRepository) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.chunkRepository = chunkRepository;
        this.modelService = modelService;
        this.tenantService = tenantService;
        this.embedderClient = embedderClient;
        this.pgRepository = pgRepository;
    }

    // ── 检索命中（对照 types.IndexWithScore；复用引擎仓库的 PgVectorRetrieveRepository.IndexHit） ────

    /** 检索失败（Go 的 error 通道；message 对照 AppError 文案）。 */
    public static final class RetrievalException extends RuntimeException {
        public RetrievalException(String message) {
            super(message);
        }
    }

    // ── 有效引擎解析 ────────────────────────────────────────────────────────
    //    2026-09-25 抽出到 EffectiveEngines（接线批第 2 步）：工厂的 env-store 分支与
    //    HybridSearch 的引擎路由要用同一份映射表与派发规则，共享件比两处各抄一份安全。
    //    行为不变（同映射表、同 RETRIEVE_DRIVER 语义、同去重规则）。

    // ── 入口（对照 HybridSearch，knowledgebase_search.go L125-301） ───────

    public List<SearchResult> hybridSearch(String id, SearchParams params) {
        if (params.getMatchCount() <= 0) {
            params.setMatchCount(DEFAULT_RETRIEVAL_TOP_K); // normalizedMatchCount
        }
        int matchCount = params.getMatchCount();

        List<String> searchKbIds = params.getKnowledgeBaseIds() != null
                && !params.getKnowledgeBaseIds().isEmpty()
                        ? params.getKnowledgeBaseIds()
                        : List.of(id);

        List<KnowledgeBase> kbs = new ArrayList<>();
        for (String kbId : searchKbIds) {
            KnowledgeBase kb = kbService.getAllTenantById(kbId);
            if (kb != null) {
                kbs.add(kb);
            }
        }
        if (kbs.isEmpty()) {
            throw new RetrievalException("knowledge base not found");
        }

        KnowledgeBase primary = pickPrimary(kbs, id);
        if (primary == null) {
            throw new RetrievalException("knowledge base not found");
        }

        // Over-retrieval：5x per-KB matchCount，floor DefaultRetrievalTopK，
        // cap maxRetrievalPoolSize（knowledgebase_search.go L193-196）。
        int overMatchCount = Math.max(matchCount * 5, DEFAULT_RETRIEVAL_TOP_K) * searchKbIds.size();
        overMatchCount = Math.min(overMatchCount, MAX_RETRIEVAL_POOL_SIZE);

        // 查询向量：一次现算，沿 params 下行（Go L206-214）。
        boolean vectorEnabled = primary.getIndexingStrategy() != null
                && primary.getIndexingStrategy().isVectorEnabled();
        if ((params.getQueryEmbedding() == null || params.getQueryEmbedding().length == 0)
                && vectorEnabled
                && !primary.getEmbeddingModelId().isEmpty()
                && !params.isDisableVectorMatch()) {
            params.setQueryEmbedding(getQueryEmbedding(primary.getId(), params.getQueryText()));
        }

        // store 分组：env-store（vector_store_id NULL）单组覆盖全部 KB；绑定外部
        // store 的 KB → 2201（引擎后端未接线，见类注释）。
        List<KnowledgeBase> envKbs = new ArrayList<>();
        for (KnowledgeBase kb : kbs) {
            if (kb.getVectorStoreId() == null || kb.getVectorStoreId().isEmpty()) {
                envKbs.add(kb);
            } else {
                throw new RetrievalException("vector store is currently unavailable");
            }
        }
        // env-store 引擎能力闸门（对照 NewCompositeRetrieveEngine(registry,
        // tenantInfo.GetEffectiveEngines()) + SupportRetriever）：租户 engines 空
        // 且 RETRIEVE_DRIVER 未配置 → 无任何引擎 → BaseParams 空 → "No retrievable
        // indexing pipelines" → data:null（Go knowledgebase_search.go L226）。
        List<RetrieverEngineParams> engines = EffectiveEngines.of(currentTenant());

        // buildRetrievalParams（Go L383-472）：FAQ/文档分流 + 阈值/过滤透传。
        List<PgVectorRetrieveRepository.RetrieveResult> results = new ArrayList<>();
        List<String> faqVectorKbIds = new ArrayList<>();
        List<String> docVectorKbIds = new ArrayList<>();
        List<String> docKeywordKbIds = new ArrayList<>();
        for (KnowledgeBase kb : envKbs) {
            boolean kbVector = kb.getIndexingStrategy() != null && kb.getIndexingStrategy().isVectorEnabled();
            boolean kbKeyword = kb.getIndexingStrategy() != null && kb.getIndexingStrategy().isKeywordEnabled();
            if (kbVector && !kb.getEmbeddingModelId().isEmpty()) {
                if ("faq".equals(kb.getType())) {
                    faqVectorKbIds.add(kb.getId());
                } else {
                    docVectorKbIds.add(kb.getId());
                }
            }
            if (kbKeyword && !"faq".equals(kb.getType())) {
                docKeywordKbIds.add(kb.getId());
            }
        }
        boolean supportVector = EffectiveEngines.supportsRetriever(engines, "vector");
        boolean supportKeywords = EffectiveEngines.supportsRetriever(engines, "keywords");

        // 对照 Go knowledgebase_search.go L233-256：retrieve span 包住多存储检索执行段
        //（Input 11 键 / Metadata 4 键照抄；收尾输出走 SummarizeRetrieveOutput）
        Map<String, Object> retrieveInput = new LinkedHashMap<>();
        retrieveInput.put("query_text", params.getQueryText());
        retrieveInput.put("kb_ids", searchKbIds);
        retrieveInput.put("knowledge_ids", params.getKnowledgeIds());
        retrieveInput.put("tag_ids", params.getTagIds());
        retrieveInput.put("scope_tag_ids", params.getScopeTagIds());
        retrieveInput.put("match_count", matchCount);
        retrieveInput.put("vector_threshold", params.getVectorThreshold());
        retrieveInput.put("keyword_threshold", params.getKeywordThreshold());
        retrieveInput.put("disable_vector_match", params.isDisableVectorMatch());
        retrieveInput.put("disable_keywords_match", params.isDisableKeywordsMatch());
        retrieveInput.put("group_count", envKbs.size()); // 对照 Go 的 len(groups)
        Map<String, Object> retrieveMeta = new LinkedHashMap<>();
        retrieveMeta.put("primary_kb_id", primary.getId());
        retrieveMeta.put("primary_kb_type", primary.getType());
        retrieveMeta.put("embedding_model_id", primary.getEmbeddingModelId());
        retrieveMeta.put("has_query_embedding",
                params.getQueryEmbedding() != null && params.getQueryEmbedding().length > 0);
        com.ragagent.tracing.langfuse.Span retrieveSpan =
                com.ragagent.tracing.langfuse.LangfuseManager.get().startSpan(
                        new com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions(
                                "retrieve", retrieveInput, retrieveMeta));
        try {
            if (supportVector && !params.isDisableVectorMatch()
                    && (!faqVectorKbIds.isEmpty() || !docVectorKbIds.isEmpty())) {
                float[] embedding = params.getQueryEmbedding() != null
                        && params.getQueryEmbedding().length > 0
                                ? params.getQueryEmbedding()
                                : getQueryEmbedding(primary.getId(), params.getQueryText());
                if (!docVectorKbIds.isEmpty()) {
                    results.add(pgRepository.vectorRetrieve(params, embedding, docVectorKbIds,
                            params.getKnowledgeIds(), params.getTagIds(), overMatchCount,
                            params.getVectorThreshold()));
                }
                if (!faqVectorKbIds.isEmpty()) {
                    results.add(pgRepository.vectorRetrieve(params, embedding, faqVectorKbIds,
                            params.getKnowledgeIds(), params.getTagIds(), overMatchCount,
                            params.getVectorThreshold()));
                }
            }
            if (supportKeywords && !params.isDisableKeywordsMatch() && !docKeywordKbIds.isEmpty()) {
                results.add(pgRepository.keywordsRetrieve(params, docKeywordKbIds,
                        params.getKnowledgeIds(), params.getTagIds(), overMatchCount,
                        params.getQueryText()));
            }
        } catch (RuntimeException retrieveErr) {
            // 对照 Go：retrieveFromStores 返回 err → span.Finish(summary, nil, err)
            retrieveSpan.finish(null, null, retrieveErr.toString());
            throw retrieveErr;
        }
        retrieveSpan.finish(com.ragagent.chatpipeline.RetrievalObs.summarizeRetrieveOutput(results),
                null, null);

        if (results.isEmpty() || results.stream().allMatch(r -> r.results().isEmpty())) {
            log.info("No retrievable indexing pipelines across {} KBs", kbs.size());
            return null;
        }

        // 分类 + 融合（_fusion.go 全文）。
        List<PgVectorRetrieveRepository.IndexHit> vectorResults = new ArrayList<>();
        List<PgVectorRetrieveRepository.IndexHit> keywordResults = new ArrayList<>();
        for (PgVectorRetrieveRepository.RetrieveResult rr : results) {
            if (PgVectorRetrieveRepository.RETRIEVER_VECTOR.equals(rr.retrieverType())) {
                vectorResults.addAll(rr.results());
            } else {
                keywordResults.addAll(rr.results());
            }
        }
        if (vectorResults.isEmpty() && keywordResults.isEmpty()) {
            return null;
        }
        var rc = currentRetrievalConfig();
        List<PgVectorRetrieveRepository.IndexHit> deduped = fuseOrDeduplicate(vectorResults, keywordResults, rc);

        // FAQ 后处理（_faq.go）。
        deduped = applyFaqPostProcessing(primary, deduped, vectorResults, envKbs, params,
                overMatchCount);

        // 截断到主匹配上限。
        if (deduped.size() > matchCount) {
            deduped = new ArrayList<>(deduped.subList(0, matchCount));
        }

        return processSearchResults(deduped, params.isSkipContextEnrichment());
    }

    /** 对照 GetQueryEmbedding（knowledgebase_search.go L26-54）。 */
    public float[] getQueryEmbedding(String kbId, String queryText) {
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new RetrievalException("knowledge base not found");
        }
        Model model = modelService.getModelByID(kb.getEmbeddingModelId());
        if (model == null) {
            throw new RetrievalException("model not found: " + kb.getEmbeddingModelId());
        }
        try {
            List<float[]> vectors = embedderClient.embedBatch(
                    EmbedderClient.configFrom(model), List.of(queryText));
            if (vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
                throw new RetrievalException("no embedding returned");
            }
            return vectors.get(0);
        } catch (RetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalException("embed query text: " + e.getMessage());
        }
    }

    /** 对照 ResolveEmbeddingModelKeys（knowledgebase_search.go L82-115）。 */
    public Map<String, String> resolveEmbeddingModelKeys(List<KnowledgeBase> kbs) {
        Map<String, String> result = new HashMap<>();
        for (KnowledgeBase kb : kbs) {
            String key;
            try {
                Model model = modelService.getModelByID(kb.getEmbeddingModelId());
                String baseUrl = model != null && model.getParameters() != null
                        ? model.getParameters().getBaseUrl()
                        : "";
                key = model == null ? kb.getEmbeddingModelId()
                        : model.getName() + "|" + baseUrl;
            } catch (Exception e) {
                key = kb.getEmbeddingModelId();
            }
            result.put(kb.getId(), key);
        }
        return result;
    }

    private static KnowledgeBase pickPrimary(List<KnowledgeBase> kbs, String id) {
        for (KnowledgeBase kb : kbs) {
            if (kb.getId().equals(id)) {
                return kb;
            }
        }
        return null;
    }

    // ── 融合（knowledgebase_search_fusion.go 全文） ──────────────────────

    static List<PgVectorRetrieveRepository.IndexHit> fuseOrDeduplicate(
            List<PgVectorRetrieveRepository.IndexHit> vectorResults,
            List<PgVectorRetrieveRepository.IndexHit> keywordResults, RetrievalConfigView rc) {
        if (keywordResults.isEmpty()) {
            return deduplicateByScore(vectorResults);
        }
        if (vectorResults.isEmpty()) {
            return deduplicateByScore(keywordResults);
        }
        return fuseWithRRF(vectorResults, keywordResults, rc);
    }

    record RetrievalConfigView(int rrfK, double rrfVectorWeight, double rrfKeywordWeight) {
        static final RetrievalConfigView DEFAULTS = new RetrievalConfigView(60, 0.7, 0.3);

        int effectiveRrfK() {
            return rrfK <= 0 ? 60 : rrfK;
        }

        double effectiveVectorWeight() {
            if (rrfVectorWeight == 0 && rrfKeywordWeight == 0) {
                return 0.7;
            }
            return rrfVectorWeight <= 0 ? 0.7 : rrfVectorWeight;
        }

        double effectiveKeywordWeight() {
            if (rrfVectorWeight == 0 && rrfKeywordWeight == 0) {
                return 0.3;
            }
            return rrfKeywordWeight <= 0 ? 0.3 : rrfKeywordWeight;
        }
    }

    private Tenant currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        try {
            return tid == null ? null : tenantService.getTenantById(tid);
        } catch (Exception e) {
            return null;
        }
    }

    private RetrievalConfigView currentRetrievalConfig() {
        try {
            Tenant tenant = currentTenant();
            if (tenant == null || tenant.getRetrievalConfig() == null) {
                return RetrievalConfigView.DEFAULTS;
            }
            JsonNode node = tenant.getRetrievalConfig();
            return new RetrievalConfigView(
                    node.path("rrf_k").asInt(0),
                    node.path("rrf_vector_weight").asDouble(0),
                    node.path("rrf_keyword_weight").asDouble(0));
        } catch (Exception e) {
            return RetrievalConfigView.DEFAULTS;
        }
    }

    private static final Comparator<PgVectorRetrieveRepository.IndexHit> SCORE_DESC = (a, b) -> {
        int c = Double.compare(b.score, a.score);
        return c;
    };

    static List<PgVectorRetrieveRepository.IndexHit> deduplicateByScore(
            List<PgVectorRetrieveRepository.IndexHit> results) {
        Map<String, PgVectorRetrieveRepository.IndexHit> chunkInfoMap = new LinkedHashMap<>();
        for (PgVectorRetrieveRepository.IndexHit r : results) {
            PgVectorRetrieveRepository.IndexHit existing = chunkInfoMap.get(r.chunkId);
            if (existing == null || r.score > existing.score) {
                chunkInfoMap.put(r.chunkId, r);
            }
        }
        List<PgVectorRetrieveRepository.IndexHit> deduped = new ArrayList<>(chunkInfoMap.values());
        deduped.sort(SCORE_DESC);
        return deduped;
    }

    /** 对照 fuseWithRRF：RRF = vW/(k+vRank) + kW/(k+kRank)，rank 1-indexed。 */
    static List<PgVectorRetrieveRepository.IndexHit> fuseWithRRF(
            List<PgVectorRetrieveRepository.IndexHit> vectorResults,
            List<PgVectorRetrieveRepository.IndexHit> keywordResults,
            RetrievalConfigView rc) {
        int rrfK = rc.effectiveRrfK();
        double vectorWeight = rc.effectiveVectorWeight();
        double keywordWeight = rc.effectiveKeywordWeight();

        Map<String, Integer> vectorRanks = new HashMap<>();
        for (int i = 0; i < vectorResults.size(); i++) {
            vectorRanks.putIfAbsent(vectorResults.get(i).chunkId, i + 1);
        }
        Map<String, Integer> keywordRanks = new HashMap<>();
        for (int i = 0; i < keywordResults.size(); i++) {
            keywordRanks.putIfAbsent(keywordResults.get(i).chunkId, i + 1);
        }

        Map<String, PgVectorRetrieveRepository.IndexHit> chunkInfoMap = new LinkedHashMap<>();
        for (PgVectorRetrieveRepository.IndexHit r : vectorResults) {
            PgVectorRetrieveRepository.IndexHit existing = chunkInfoMap.get(r.chunkId);
            if (existing == null || r.score > existing.score) {
                chunkInfoMap.put(r.chunkId, r);
            }
        }
        for (PgVectorRetrieveRepository.IndexHit r : keywordResults) {
            chunkInfoMap.putIfAbsent(r.chunkId, r);
        }

        List<PgVectorRetrieveRepository.IndexHit> result = new ArrayList<>(chunkInfoMap.size());
        for (Map.Entry<String, PgVectorRetrieveRepository.IndexHit> e : chunkInfoMap.entrySet()) {
            PgVectorRetrieveRepository.IndexHit info = e.getValue();
            double rrfScore = 0.0;
            Integer vRank = vectorRanks.get(e.getKey());
            if (vRank != null) {
                rrfScore += vectorWeight / (double) (rrfK + vRank);
            }
            Integer kRank = keywordRanks.get(e.getKey());
            if (kRank != null) {
                rrfScore += keywordWeight / (double) (rrfK + kRank);
            }
            info.score = rrfScore;
            result.add(info);
        }
        result.sort(SCORE_DESC);
        return result;
    }

    // ── FAQ 后处理（knowledgebase_search_faq.go） ─────────────────────────

    private List<PgVectorRetrieveRepository.IndexHit> applyFaqPostProcessing(
            KnowledgeBase primary, List<PgVectorRetrieveRepository.IndexHit> chunks,
            List<PgVectorRetrieveRepository.IndexHit> vectorResults, List<KnowledgeBase> envKbs,
            SearchParams params, int matchCount) {
        if (!"faq".equals(primary.getType())) {
            return chunks;
        }
        if (needsIterativeRetrieval(params, matchCount, chunks, vectorResults)) {
            return iterativeRetrieveWithDeduplication(envKbs, params.getMatchCount(),
                    params.getQueryText());
        }
        return filterByNegativeQuestions(chunks, params.getQueryText());
    }

    private static boolean needsIterativeRetrieval(SearchParams params, int matchCount,
            List<PgVectorRetrieveRepository.IndexHit> chunks,
            List<PgVectorRetrieveRepository.IndexHit> vectorResults) {
        // 对照 _faq.go L40：唯一 chunk 不足 且 首轮向量结果打满 over-retrieval 池。
        return chunks.size() < params.getMatchCount() && vectorResults.size() == matchCount;
    }

    private List<PgVectorRetrieveRepository.IndexHit> iterativeRetrieveWithDeduplication(
            List<KnowledgeBase> envKbs, int matchCount, String queryText) {
        final int maxIterations = 5;
        int currentTopK = Math.min(matchCount * 3, MAX_RETRIEVAL_POOL_SIZE);
        Map<String, PgVectorRetrieveRepository.IndexHit> uniqueChunks = new LinkedHashMap<>();
        Map<String, Chunk> chunkDataCache = new HashMap<>();
        Set<String> filteredOutChunks = new HashSet<>();
        String queryTextLower = queryText == null ? "" : queryText.strip().toLowerCase();
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();

        for (int i = 0; i < maxIterations; i++) {
            List<PgVectorRetrieveRepository.IndexHit> iterationResults = new ArrayList<>();
            for (KnowledgeBase kb : envKbs) {
                if (kb.getIndexingStrategy() == null || !kb.getIndexingStrategy().isVectorEnabled()
                        || kb.getEmbeddingModelId().isEmpty()) {
                    continue;
                }
                float[] emb = getQueryEmbedding(kb.getId(), queryText);
                try {
                    var rr = pgRepository.vectorRetrieve(newSearchParams(queryText), emb,
                            List.of(kb.getId()), List.of(), List.of(), currentTopK, 0);
                    iterationResults.addAll(rr.results());
                } catch (Exception e) {
                    log.warn("Iterative retrieval failed at iteration {}: {}", i + 1, e.getMessage());
                    return new ArrayList<>(uniqueChunks.values());
                }
            }
            if (iterationResults.isEmpty()) {
                break;
            }
            List<String> newChunkIds = new ArrayList<>();
            for (PgVectorRetrieveRepository.IndexHit result : iterationResults) {
                if (!chunkDataCache.containsKey(result.chunkId)
                        && !filteredOutChunks.contains(result.chunkId)) {
                    newChunkIds.add(result.chunkId);
                }
            }
            if (!newChunkIds.isEmpty() && tenantId != null) {
                try {
                    List<Chunk> fresh = chunkRepository.listChunksById(tenantId, newChunkIds);
                    for (Chunk c : fresh) {
                        chunkDataCache.put(c.getId(), c);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch chunks at iteration {}: {}", i + 1, e.getMessage());
                }
            }
            for (PgVectorRetrieveRepository.IndexHit result : iterationResults) {
                if (filteredOutChunks.contains(result.chunkId)) {
                    continue;
                }
                Chunk chunkData = chunkDataCache.get(result.chunkId);
                if (chunkData != null && "faq".equals(chunkData.getChunkType())
                        && matchesNegativeQuestions(queryTextLower, faqNegativeQuestions(chunkData))) {
                    filteredOutChunks.add(result.chunkId);
                    uniqueChunks.remove(result.chunkId);
                    continue;
                }
                PgVectorRetrieveRepository.IndexHit existing = uniqueChunks.get(result.chunkId);
                if (existing == null || result.score > existing.score) {
                    uniqueChunks.put(result.chunkId, result);
                }
            }
            if (uniqueChunks.size() >= matchCount) {
                break;
            }
            currentTopK = Math.min(currentTopK * 2, MAX_RETRIEVAL_POOL_SIZE);
        }
        List<PgVectorRetrieveRepository.IndexHit> out = new ArrayList<>(uniqueChunks.values());
        out.sort(SCORE_DESC);
        return out;
    }

    private static SearchParams newSearchParams(String query) {
        SearchParams p = new SearchParams();
        p.setQueryText(query);
        return p;
    }

    private List<String> faqNegativeQuestions(Chunk chunk) {
        try {
            JsonNode meta = chunk.getMetadata() == null ? null : chunk.getMetadata().get("question");
            List<String> negatives = new ArrayList<>();
            JsonNode node = chunk.getMetadata();
            if (node != null && node.has("negative_questions") && node.get("negative_questions").isArray()) {
                for (JsonNode n : node.get("negative_questions")) {
                    negatives.add(n.asText(""));
                }
            }
            if (meta != null) {
                // 占位：metadata.question 不参与负例
            }
            return negatives;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 对照 matchesNegativeQuestions：子串命中即负例。 */
    private static boolean matchesNegativeQuestions(String queryTextLower, List<String> negativeQuestions) {
        if (negativeQuestions == null || negativeQuestions.isEmpty()) {
            return false;
        }
        for (String q : negativeQuestions) {
            if (q == null || q.isEmpty()) {
                continue;
            }
            if (queryTextLower.contains(q.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<PgVectorRetrieveRepository.IndexHit> filterByNegativeQuestions(
            List<PgVectorRetrieveRepository.IndexHit> chunks, String queryText) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        String queryTextLower = queryText == null ? "" : queryText.strip().toLowerCase();
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
        if (tenantId == null) {
            return chunks;
        }
        List<String> ids = chunks.stream().map(c -> c.chunkId).toList();
        Map<String, Chunk> cache = new HashMap<>();
        try {
            for (Chunk c : chunkRepository.listChunksById(tenantId, ids)) {
                cache.put(c.getId(), c);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch chunks for negative question filtering: {}", e.getMessage());
            return chunks;
        }
        List<PgVectorRetrieveRepository.IndexHit> result = new ArrayList<>();
        for (PgVectorRetrieveRepository.IndexHit hit : chunks) {
            Chunk chunkData = cache.get(hit.chunkId);
            if (chunkData != null && "faq".equals(chunkData.getChunkType())
                    && matchesNegativeQuestions(queryTextLower, faqNegativeQuestions(chunkData))) {
                continue;
            }
            result.add(hit);
        }
        return result;
    }

    // ── 结果装配（knowledgebase_search_results.go 全文） ──────────────────

    private List<SearchResult> processSearchResults(
            List<PgVectorRetrieveRepository.IndexHit> chunks, boolean skipEnrichment) {
        if (chunks.isEmpty()) {
            return null;
        }
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();

        Set<String> knowledgeIds = new java.util.LinkedHashSet<>();
        List<String> chunkIds = new ArrayList<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> matchTypes = new HashMap<>();
        Map<String, String> matchedContents = new HashMap<>();
        for (PgVectorRetrieveRepository.IndexHit c : chunks) {
            knowledgeIds.add(c.knowledgeId);
            chunkIds.add(c.chunkId);
            scores.put(c.chunkId, c.score);
            matchTypes.put(c.chunkId, c.matchType);
            matchedContents.put(c.chunkId, c.content);
        }

        List<Knowledge> knowledgeList = knowledgeService.getKnowledgeBatchWithSharedAccess(
                tenantId == null ? 0 : tenantId, new ArrayList<>(knowledgeIds));
        Map<String, Knowledge> knowledgeMap = new HashMap<>();
        for (Knowledge k : knowledgeList) {
            knowledgeMap.put(k.getId(), k);
        }

        List<Chunk> allChunks = tenantId == null ? List.of()
                : chunkRepository.listChunksById(tenantId, chunkIds);
        Map<String, Chunk> chunkMap = new HashMap<>();
        for (Chunk c : allChunks) {
            chunkMap.put(c.getId(), c);
        }

        if (!skipEnrichment) {
            Set<String> processed = new HashSet<>();
            List<String> additional = new ArrayList<>();
            for (Chunk c : allChunks) {
                processed.add(c.getId());
            }
            for (Chunk c : allChunks) {
                if (!c.getParentChunkId().isEmpty() && !processed.contains(c.getParentChunkId())) {
                    additional.add(c.getParentChunkId());
                    processed.add(c.getParentChunkId());
                    scores.put(c.getParentChunkId(), scores.getOrDefault(c.getId(), 0.0));
                    matchTypes.put(c.getParentChunkId(), MATCH_PARENT_CHUNK);
                }
                for (String rel : relatedChunkIds(c, processed)) {
                    additional.add(rel);
                    matchTypes.put(rel, MATCH_RELATION_CHUNK);
                }
                if ("text".equals(c.getChunkType())) {
                    if (!c.getNextChunkId().isEmpty() && !processed.contains(c.getNextChunkId())) {
                        additional.add(c.getNextChunkId());
                        processed.add(c.getNextChunkId());
                        matchTypes.put(c.getNextChunkId(), MATCH_NEAR_BY_CHUNK);
                    }
                    if (!c.getPreChunkId().isEmpty() && !processed.contains(c.getPreChunkId())) {
                        additional.add(c.getPreChunkId());
                        processed.add(c.getPreChunkId());
                        matchTypes.put(c.getPreChunkId(), MATCH_NEAR_BY_CHUNK);
                    }
                }
            }
            for (String aid : additional) {
                Chunk extra = fetchChunk(tenantId, aid);
                if (extra != null) {
                    chunkMap.put(extra.getId(), extra);
                }
            }
        }

        // 首轮：按输入顺序装配。
        List<SearchResult> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (PgVectorRetrieveRepository.IndexHit input : chunks) {
            Chunk chunk = chunkMap.get(input.chunkId);
            if (chunk == null || !isSearchableChunk(chunk) || added.contains(chunk.getId())) {
                continue;
            }
            Knowledge knowledge = knowledgeMap.get(chunk.getKnowledgeId());
            if (knowledge == null) {
                continue;
            }
            out.add(buildSearchResult(chunk, knowledge,
                    scores.getOrDefault(chunk.getId(), 0.0),
                    matchTypes.getOrDefault(chunk.getId(), MATCH_EMBEDDING),
                    matchedContents.getOrDefault(chunk.getId(), "")));
            added.add(chunk.getId());
        }
        return out;
    }

    private Chunk fetchChunk(Long tenantId, String id) {
        try {
            List<Chunk> rows = chunkRepository.listChunksById(tenantId, List.of(id));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> relatedChunkIds(Chunk chunk, Set<String> processed) {
        List<String> related = new ArrayList<>();
        JsonNode rel = chunk.getRelationChunks();
        if (rel != null && rel.isArray()) {
            for (JsonNode n : rel) {
                String id = n.asText("");
                if (!id.isEmpty() && !processed.contains(id)) {
                    related.add(id);
                    processed.add(id);
                }
            }
        }
        return related;
    }

    /** 对照 buildSearchResult（knowledgebase_search_results.go L303-334）。 */
    private static SearchResult buildSearchResult(Chunk chunk, Knowledge knowledge,
            double score, int matchType, String matchedContent) {
        SearchResult r = new SearchResult();
        r.setId(chunk.getId());
        r.setContent(chunk.getContent());
        r.setContentRevision(chunk.getContentRevision());
        r.setKnowledgeId(chunk.getKnowledgeId());
        r.setChunkIndex(chunk.getChunkIndex());
        r.setKnowledgeTitle(knowledge.getTitle());
        r.setStartAt(chunk.getStartAt());
        r.setEndAt(chunk.getEndAt());
        r.setSeq(chunk.getChunkIndex());
        r.setScore(score);
        r.setMatchType(matchType);
        // 对照 Knowledge.GetMetadata（types/knowledge.go L227-240）：nil → 空 map
        // （序列化 "{}"）；不可解析 → null。SearchResult.metadata 是 map[string]string
        // （Go 同型），只挑字符串值（Go 的 types.JSON.Map() 对非字符串值报错 → nil）。
        JsonNode meta = knowledge.getMetadata();
        if (meta == null || meta.isNull()) {
            r.setMetadata(new LinkedHashMap<>());
        } else if (meta.isObject()) {
            Map<String, String> m = new LinkedHashMap<>();
            boolean allStrings = true;
            for (var it = meta.fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() == null || !e.getValue().isTextual()) {
                    allStrings = false;
                    break;
                }
                m.put(e.getKey(), e.getValue().asText());
            }
            r.setMetadata(allStrings ? m : null);
        } else {
            r.setMetadata(null);
        }
        r.setChunkType(chunk.getChunkType());
        r.setParentChunkId(chunk.getParentChunkId());
        r.setKnowledgeFilename(knowledge.getFileName());
        r.setKnowledgeSource(knowledge.getSource());
        r.setKnowledgeChannel(knowledge.getChannel());
        r.setKnowledgeDescription(knowledge.getDescription());
        r.setKnowledgeBaseId(knowledge.getKnowledgeBaseId());
        r.setMatchedContent(matchedContent);
        return r;
    }

    /** 对照 isSearchableChunk（knowledgebase_search_results.go L337-353）。 */
    private static boolean isSearchableChunk(Chunk chunk) {
        if (chunk == null || !chunk.isIsEnabled()) {
            return false;
        }
        String status = chunk.getIndexStatus();
        if ("processing".equals(status) || "failed".equals(status)) {
            return false;
        }
        return List.of("text", "summary", "table_column", "table_summary",
                "faq", "image_ocr", "image_caption").contains(chunk.getChunkType());
    }

}
