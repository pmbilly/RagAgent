package com.ragagent.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.embedding.EmbeddingGateway;
import com.ragagent.common.knowledge.ChunkSearchGateway;
import com.ragagent.common.knowledge.KnowledgeBaseSearchFacts;
import com.ragagent.common.knowledge.KnowledgeBaseSearchGateway;
import com.ragagent.common.knowledge.KnowledgeDocumentGateway;
import com.ragagent.common.model.ModelFacts;
import com.ragagent.common.model.ModelGateway;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.EffectiveEngines;
import com.ragagent.retrieval.engine.EngineAwareNormalizer;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieveEngineException;
import com.ragagent.retrieval.engine.RetrieveEngineFactories;
import com.ragagent.retrieval.engine.RetrieveEngineRegistry;
import com.ragagent.retrieval.engine.RetrieverEngineParams;
import com.ragagent.retrieval.engine.TenantStoreOwnership;

/**
 * HybridSearch 执行面（对照 Go internal/application/service/knowledgebase_search.go
 * + _fusion/_storegroup/_fanout/_results/_faq 全族 + retriever/postgres 引擎，
 * 检索引擎批 2026-09-22 翻译；接线批第 4 步 2026-09-25 换装 store-group 路由）。
 *
 * <h2>store-group 路由（对照 knowledgebase_search_storegroup.go + _fanout.go）</h2>
 * <ul>
 *   <li>KB 按 (vectorStoreId, kb.tenantId) 分组；每组经
 *       {@link RetrieveEngineFactories#createForKb} 解析复合引擎——env-store 组
 *       （vector_store_id NULL）回落租户有效引擎（RETRIEVE_DRIVER），绑定组走
 *       归属校验（跨租户 2200）+ 注册表解析（未注册 2201）。</li>
 *   <li>工厂哨兵按 {@code classifyFactoryError} 映射成 2200/2201 的
 *       {@link BizException}（HTTP 400，store UUID 只进结构化日志）。</li>
 *   <li>单组快速路径直接 {@code engine.retrieve}（零扇出开销，现行主形态）；
 *       多组虚拟线程扇出（并发上限 4、组超时 {@code MULTI_STORE_RETRIEVE_TIMEOUT_SEC}
 *       缺省 30s），all-or-nothing，任一组失败整条检索报 2201。</li>
 *   <li>多组结果跨<b>引擎类型</b>时先过 {@link EngineAwareNormalizer} 归一化再融合
 *       （同引擎保持原刻度）；BM25 分透传。</li>
 *   <li>authorizeKBAccess 在控制器层落地（KnowledgeBaseController 的
 *       kb-permission 段，2026-09-23 走查批接线）——服务层不再重复查授予。</li>
 * </ul>
 *
 * <h2>与 Go 的形状差异（诚实声明）</h2>
 * <ul>
 *   <li>组序确定（LinkedHashMap 按首见序）：Go 的 map 遍历随机——检索批的既有
 *       确定性备案延续到分组层，对 golden 友好。</li>
 *   <li>多组扇出的组超时到点即判失败（Go 的 ctx 取消能打断底层 HTTP；Java 引擎
 *       不收 ctx，超时后该组线程自然跑完、结果被丢弃——备案：仅多绑定店场景可达）。</li>
 *   <li>langfuse span 与部分 INFO 日志不入契约。</li>
 * </ul>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** 对照 maxRetrievalPoolSize（knowledgebase_search.go L20）。 */
    public static final int MAX_RETRIEVAL_POOL_SIZE = 500;
    /** 对照 types.DefaultRetrievalTopK。 */
    public static final int DEFAULT_RETRIEVAL_TOP_K = 50;
    /** 对照 defaultMultiStoreFanoutLimit（_fanout.go L27）。 */
    static final int MULTI_STORE_FANOUT_LIMIT = 4;
    /** 对照 defaultMultiStoreRetrieveTimeout（_fanout.go L26）。 */
    static final long MULTI_STORE_RETRIEVE_TIMEOUT_SEC_DEFAULT = 30;

    // MatchType（types/embedding.go iota 序）。
    public static final int MATCH_EMBEDDING = 0;
    public static final int MATCH_KEYWORDS = 1;
    public static final int MATCH_NEAR_BY_CHUNK = 2;
    public static final int MATCH_HISTORY = 3;
    public static final int MATCH_PARENT_CHUNK = 4;
    public static final int MATCH_RELATION_CHUNK = 5;

    private final KnowledgeBaseSearchGateway kbGateway;
    final KnowledgeDocumentGateway documentGateway;
    final ChunkSearchGateway chunkGateway;
    private final ModelGateway modelGateway;
    private final TenantService tenantService;
    private final EmbeddingGateway embeddingGateway;
    final PgVectorRetrieveRepository pgRepository;
    private final RetrieveEngineRegistry engineRegistry;
    private final TenantStoreOwnership storeOwnership;

    final HybridFusionOps fusionOps;
    final HybridResultOps resultOps;

    public HybridSearchService(KnowledgeBaseSearchGateway kbGateway,
            KnowledgeDocumentGateway documentGateway, ChunkSearchGateway chunkGateway,
            ModelGateway modelGateway, TenantService tenantService, EmbeddingGateway embeddingGateway,
            PgVectorRetrieveRepository pgRepository, RetrieveEngineRegistry engineRegistry,
            TenantStoreOwnership storeOwnership) {
        this.kbGateway = kbGateway;
        this.documentGateway = documentGateway;
        this.chunkGateway = chunkGateway;
        this.modelGateway = modelGateway;
        this.tenantService = tenantService;
        this.embeddingGateway = embeddingGateway;
        this.pgRepository = pgRepository;
        this.engineRegistry = engineRegistry;
        this.storeOwnership = storeOwnership;
        this.fusionOps = new HybridFusionOps(this);
        this.resultOps = new HybridResultOps(this);
    }

    // ── 检索命中（对照 types.IndexWithScore；复用引擎仓库的 PgVectorRetrieveRepository.IndexHit） ────

    /** 检索失败（Go 的 error 通道；message 对照 AppError 文案）。 */
    public static final class RetrievalException extends RuntimeException {
        public RetrievalException(String message) {
            super(message);
        }
    }

    // ── storeGroup（对照 knowledgebase_search_storegroup.go L39-81） ────────

    /**
     * 一组共享 (VectorStore, 属主租户) 的 KB——HybridSearch 的一个扇出单元。
     * BaseParams 组内不可变，TopK 是唯一的逐迭代值（FAQ 迭代路径只改它）。
     */
    static final class StoreGroup {
        /** 绑定的 VectorStore UUID；"" = env-store 组。只进日志，不出现在用户可见文案。 */
        final String storeId;
        /** 拥有该组 KB（与 store）的租户——org-share KB 与请求租户不同，归属校验用它。 */
        final long ownerTenantId;
        final List<String> kbIds;
        final CompositeRetrieveEngine engine;
        final List<RetrieveParams> baseParams;
        int topK;

        StoreGroup(String storeId, long ownerTenantId, List<String> kbIds,
                   CompositeRetrieveEngine engine, List<RetrieveParams> baseParams, int topK) {
            this.storeId = storeId;
            this.ownerTenantId = ownerTenantId;
            this.kbIds = kbIds;
            this.engine = engine;
            this.baseParams = baseParams;
            this.topK = topK;
        }
    }

    // ── 有效引擎解析 ────────────────────────────────────────────────────────
    //    2026-09-25 抽出到 EffectiveEngines（接线批第 2 步）：工厂的 env-store 分支与
    //    HybridSearch 的引擎路由用同一份映射表与派发规则。

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

        List<KnowledgeBaseSearchFacts> kbs = new ArrayList<>();
        for (String kbId : searchKbIds) {
            KnowledgeBaseSearchFacts kb = kbGateway.findSearchFacts(kbId);
            if (kb != null) {
                kbs.add(kb);
            }
        }
        if (kbs.isEmpty()) {
            throw new RetrievalException("knowledge base not found");
        }

        // 多 KB 检索的嵌入模型一致性闸门（对照 validateSameEmbeddingModel，
        // storegroup.go L248-289；wiki/graph-only KB 的空键豁免）。
        validateSameEmbeddingModel(kbs);

        KnowledgeBaseSearchFacts primary = pickPrimary(kbs, id);
        if (primary == null) {
            throw new RetrievalException("knowledge base not found");
        }

        // Over-retrieval：5x per-KB matchCount，floor DefaultRetrievalTopK，
        // cap maxRetrievalPoolSize（knowledgebase_search.go L193-196）。
        int overMatchCount = Math.max(matchCount * 5, DEFAULT_RETRIEVAL_TOP_K) * searchKbIds.size();
        overMatchCount = Math.min(overMatchCount, MAX_RETRIEVAL_POOL_SIZE);

        // 查询向量：一次现算，沿 params 下行（Go L206-214）。
        boolean vectorEnabled = primary.vectorEnabled();
        if ((params.getQueryEmbedding() == null || params.getQueryEmbedding().length == 0)
                && vectorEnabled
                && !primary.embeddingModelId().isEmpty()
                && !params.isDisableVectorMatch()) {
            params.setQueryEmbedding(getQueryEmbedding(primary.id(), params.getQueryText()));
        }

        // 按 (storeID, 属主租户) 分组并解析各组引擎（Go resolveStoreGroups）。
        List<StoreGroup> groups = resolveStoreGroups(primary, kbs, params, overMatchCount);
        if (groups.isEmpty() || allBaseParamsEmpty(groups)) {
            // Wiki-only / graph-only 扇出：全部 KB 不可检索 → 空而非错（agent 工具
            // 的多 KB 作用域优雅降级，Go knowledgebase_search.go L245-250）。
            log.info("No retrievable indexing pipelines across {} KBs", kbs.size());
            return null;
        }

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
        retrieveInput.put("group_count", groups.size()); // 对照 Go 的 len(groups)
        Map<String, Object> retrieveMeta = new LinkedHashMap<>();
        retrieveMeta.put("primary_kb_id", primary.id());
        retrieveMeta.put("primary_kb_type", primary.type());
        retrieveMeta.put("embedding_model_id", primary.embeddingModelId());
        retrieveMeta.put("has_query_embedding",
                params.getQueryEmbedding() != null && params.getQueryEmbedding().length > 0);
        com.ragagent.tracing.langfuse.Span retrieveSpan =
                com.ragagent.tracing.langfuse.LangfuseManager.get().startSpan(
                        new com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions(
                                "retrieve", retrieveInput, retrieveMeta));
        List<PgVectorRetrieveRepository.RetrieveResult> results;
        try {
            List<RetrieveResult> retrieveResults = retrieveFromStores(groups);
            results = toPgShape(retrieveResults);
        } catch (RuntimeException retrieveErr) {
            // 对照 Go：retrieveFromStores 返回 err → span.Finish(summary, nil, err)
            retrieveSpan.finish(null, null, retrieveErr.toString());
            throw retrieveErr;
        }
        retrieveSpan.finish(com.ragagent.retrieval.obs.RetrievalObs.summarizeRetrieveOutput(results),
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

        // FAQ 后处理（_faq.go，现按 storeGroups 扇出——迭代 TopK 对全部绑定店统一生长）。
        deduped = fusionOps.applyFaqPostProcessing(primary, deduped, vectorResults, groups, params,
                overMatchCount);

        // 截断到主匹配上限。
        if (deduped.size() > matchCount) {
            deduped = new ArrayList<>(deduped.subList(0, matchCount));
        }

        return processSearchResults(deduped, params.isSkipContextEnrichment());
    }

    /** 对照 GetQueryEmbedding（knowledgebase_search.go L26-54）。 */
    public float[] getQueryEmbedding(String kbId, String queryText) {
        KnowledgeBaseSearchFacts kb = kbGateway.findSearchFacts(kbId);
        if (kb == null) {
            throw new RetrievalException("knowledge base not found");
        }
        ModelFacts model = modelGateway.findFacts(kb.embeddingModelId());
        if (model == null) {
            throw new RetrievalException("model not found: " + kb.embeddingModelId());
        }
        try {
            List<float[]> vectors = embeddingGateway.embed(model, List.of(queryText));
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

    /** 去重键：模型 ID + KB 属主租户（对照 Go 的 modelRef）。 */
    private record ModelRef(String modelId, Long tenantId) {
    }

    /**
     * 对照 ResolveEmbeddingModelKeys（knowledgebase_search.go L82-115）：按
     * (modelID, KB 属主租户) 去重后逐个解析身份键（name + base URL）——
     * 跨租户的模型解析在<b>属主租户</b>上下文里做（WithExecutionTenant），同一个
     * 底层模型跨租户共享时键相同，多库分组才最优。解析失败回落 modelID。
     */
    public Map<String, String> resolveEmbeddingModelKeys(List<String> kbIds) {
        List<KnowledgeBaseSearchFacts> kbs = new ArrayList<>();
        for (String kbId : kbIds) {
            KnowledgeBaseSearchFacts kb = kbGateway.findSearchFacts(kbId);
            if (kb != null) {
                kbs.add(kb);
            }
        }
        return embeddingModelKeys(kbs);
    }

    /** 身份键解析核心（事实已在手时不重复查库，如多 KB 一致性闸门）。 */
    private Map<String, String> embeddingModelKeys(List<KnowledgeBaseSearchFacts> kbs) {
        Map<ModelRef, String> resolved = new LinkedHashMap<>();
        Map<String, ModelRef> kbRefs = new HashMap<>();
        for (KnowledgeBaseSearchFacts kb : kbs) {
            ModelRef ref = new ModelRef(kb.embeddingModelId(), kb.tenantId());
            kbRefs.put(kb.id(), ref);
            if (!resolved.containsKey(ref)) {
                resolved.put(ref, resolveModelIdentity(ref));
            }
        }
        Map<String, String> result = new HashMap<>();
        for (KnowledgeBaseSearchFacts kb : kbs) {
            result.put(kb.id(), resolved.get(kbRefs.get(kb.id())));
        }
        return result;
    }

    /** 单个 (modelID, tenantId) 的身份键解析（属主租户上下文；失败回落 modelID）。 */
    private String resolveModelIdentity(ModelRef ref) {
        com.ragagent.event.TenantContextSnapshot prev = com.ragagent.event.TenantContextSnapshot.capture();
        try {
            if (ref.tenantId() != null) {
                prev.withTenantId(ref.tenantId()).replay();
            }
            ModelFacts model = modelGateway.findFacts(ref.modelId());
            String baseUrl = model == null ? "" : model.baseUrl();
            return model == null ? ref.modelId() : model.name() + "|" + baseUrl;
        } catch (Exception e) {
            log.warn("ResolveEmbeddingModelKeys: cannot resolve model {} for tenant {}: {}",
                    ref.modelId(), ref.tenantId(), e.toString());
            return ref.modelId();
        } finally {
            prev.replay();
        }
    }

    /**
     * 对照 validateSameEmbeddingModel（storegroup.go L248-289）：多 KB 检索若跨多个
     * 嵌入模型身份键则 400——不同嵌入空间的分数没有可比性。单 KB no-op；
     * 解析键为空（wiki-only / graph-only KB 无嵌入模型）豁免。
     */
    private void validateSameEmbeddingModel(List<KnowledgeBaseSearchFacts> kbs) {
        if (kbs.size() <= 1) {
            return;
        }
        Map<String, String> keys = embeddingModelKeys(kbs);
        String seen = null;
        for (KnowledgeBaseSearchFacts kb : kbs) {
            String k = keys.get(kb.id());
            if (k == null || k.isEmpty()) {
                // wiki-only / graph-only 豁免：KB 没有嵌入模型。
                continue;
            }
            if (seen == null) {
                seen = k;
                continue;
            }
            if (!k.equals(seen)) {
                log.warn("multi-KB search rejected: embedding models differ, kb_id={}",
                        com.ragagent.common.security.LogSanitizer.sanitize(kb.id()));
                throw new BizException(AppError.badRequest(
                        "selected knowledge bases use different embedding models; "
                                + "multi-KB search requires every knowledge base to share a single "
                                + "embedding model"));
            }
        }
    }

    // ── store-group 解析（knowledgebase_search_storegroup.go L110-163） ────

    /**
     * KB 按 (vectorStoreId, kb.tenantId) 分桶，逐组经工厂解析复合引擎并构建
     * 基础检索参数。桶序 = KB 首见序（Go 为 map 随机序，本仓确定性备案）。
     */
    private List<StoreGroup> resolveStoreGroups(KnowledgeBaseSearchFacts primary, List<KnowledgeBaseSearchFacts> kbs,
                                                SearchParams params, int matchCount) {
        Map<String, List<KnowledgeBaseSearchFacts>> buckets = new LinkedHashMap<>();
        for (KnowledgeBaseSearchFacts kb : kbs) {
            String sid = kb.vectorStoreId() == null ? "" : kb.vectorStoreId();
            long tid = kb.tenantId() == null ? 0L : kb.tenantId();
            buckets.computeIfAbsent(sid + ":" + tid, key -> new ArrayList<>()).add(kb);
        }

        // env-store 组的租户有效引擎（Go 从 ctx 的 TenantInfo 取——按当前租户行解析，
        // 引擎列表为空 = RETRIEVE_DRIVER 未配置 = 检索全关，Go 实测行为）。
        List<RetrieverEngineParams> tenantEngines = EffectiveEngines.of(currentTenant());

        List<StoreGroup> groups = new ArrayList<>(buckets.size());
        for (List<KnowledgeBaseSearchFacts> groupKbs : buckets.values()) {
            KnowledgeBaseSearchFacts first = groupKbs.get(0);
            String storeId = first.vectorStoreId() == null ? "" : first.vectorStoreId();
            long ownerTenantId = first.tenantId() == null ? 0L : first.tenantId();
            CompositeRetrieveEngine engine;
            try {
                engine = RetrieveEngineFactories.createForKb(engineRegistry, storeOwnership,
                        ownerTenantId, storeId, tenantEngines);
            } catch (RuntimeException e) {
                throw classifyFactoryError(e, ownerTenantId, storeId);
            }
            List<RetrieveParams> baseParams =
                    buildRetrievalParams(engine, primary, groupKbs, params, matchCount);
            List<String> ids = new ArrayList<>(groupKbs.size());
            for (KnowledgeBaseSearchFacts kb : groupKbs) {
                ids.add(kb.id());
            }
            groups.add(new StoreGroup(storeId, ownerTenantId, ids, engine, baseParams, matchCount));
        }
        return groups;
    }

    /**
     * 对照 classifyFactoryError（storegroup.go L165-190）：工厂哨兵 → 2200/2201 的
     * AppError，不向用户泄漏 store UUID（UUID 只进结构化日志，经 sanitizer）。
     */
    private static BizException classifyFactoryError(RuntimeException err, long tenantId,
                                                     String storeId) {
        log.warn("resolve store engine failed: tenant_id={} store_id={} reason={} err={}",
                tenantId, com.ragagent.common.security.LogSanitizer.sanitize(storeId),
                "resolve store engine", err.toString());
        if (err instanceof RetrieveEngineException e) {
            switch (e.kind()) {
                case VECTOR_STORE_FORBIDDEN:
                    return new BizException(new AppError(
                            ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                            "vector store bound to the knowledge base is not available", null, 400));
                case VECTOR_STORE_NOT_FOUND:
                case VECTOR_STORE_UNAVAILABLE:
                    return vectorStoreUnavailable();
                case TENANT_INFO_MISSING:
                    return new BizException(new AppError(
                            ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                            "tenant information missing in context", null, 400));
                default:
                    break;
            }
        }
        // 解析超时也报 2201（绑定没问题，重试可能成功）——照 Go 的
        // DeadlineExceeded 分支；其余错误原样上抛（handler 折 500 原文）。
        if (RetrieveEngineException.isCancellation(err)) {
            return vectorStoreUnavailable();
        }
        if (err instanceof BizException biz) {
            return biz;
        }
        return new BizException(AppError.internal(
                err.getMessage() == null ? err.toString() : err.getMessage()));
    }

    private static BizException vectorStoreUnavailable() {
        return new BizException(new AppError(ErrorCode.VECTOR_STORE_UNAVAILABLE.value(),
                "vector store is currently unavailable", null, 400));
    }

    // ── 检索参数构建（knowledgebase_search.go L383-472） ───────────────────

    /**
     * 构建一个 store 组的基础检索参数。FAQ/文档分流是<b>逐 KB</b> 属性（kb.Type），
     * 不看主 KB；引擎的 {@code SupportRetriever} 决定该组要不要发对应类型。
     */
    private List<RetrieveParams> buildRetrievalParams(CompositeRetrieveEngine engine,
                                                      KnowledgeBaseSearchFacts primary,
                                                      List<KnowledgeBaseSearchFacts> groupKbs,
                                                      SearchParams params, int matchCount) {
        List<RetrieveParams> retrieveParams = new ArrayList<>();

        List<String> faqVectorKbIds = new ArrayList<>();
        List<String> docVectorKbIds = new ArrayList<>();
        List<String> docKeywordKbIds = new ArrayList<>();
        for (KnowledgeBaseSearchFacts kb : groupKbs) {
            boolean kbVector = kb.vectorEnabled();
            boolean kbKeyword = kb.keywordEnabled();
            if (kbVector && !kb.embeddingModelId().isEmpty()) {
                if ("faq".equals(kb.type())) {
                    faqVectorKbIds.add(kb.id());
                } else {
                    docVectorKbIds.add(kb.id());
                }
            }
            // FAQ KB 只走 FAQ 向量索引、没有关键词索引；仅文档型 KB 参与关键词检索。
            if (kbKeyword && !"faq".equals(kb.type())) {
                docKeywordKbIds.add(kb.id());
            }
        }

        if (engine.supportRetriever(EngineTypes.RETRIEVER_VECTOR) && !params.isDisableVectorMatch()
                && (!faqVectorKbIds.isEmpty() || !docVectorKbIds.isEmpty())) {
            float[] embedding = params.getQueryEmbedding() != null
                    && params.getQueryEmbedding().length > 0
                            ? params.getQueryEmbedding()
                            : getQueryEmbedding(primary.id(), params.getQueryText());
            // 文档 KB 用默认向量索引；FAQ KB 用 FAQ 索引——各组一份参数，各查各的索引。
            if (!docVectorKbIds.isEmpty()) {
                retrieveParams.add(vectorParams(params, docVectorKbIds, embedding, matchCount, ""));
            }
            if (!faqVectorKbIds.isEmpty()) {
                retrieveParams.add(vectorParams(params, faqVectorKbIds, embedding, matchCount,
                        "faq"));
            }
        }

        if (engine.supportRetriever(EngineTypes.RETRIEVER_KEYWORDS) && !params.isDisableKeywordsMatch()
                && !docKeywordKbIds.isEmpty()) {
            RetrieveParams p = new RetrieveParams();
            p.query = params.getQueryText();
            p.knowledgeBaseIds = docKeywordKbIds;
            p.topK = matchCount;
            p.threshold = params.getKeywordThreshold();
            p.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
            p.knowledgeIds = params.getKnowledgeIds();
            p.tagIds = params.getTagIds();
            retrieveParams.add(p);
        }
        return retrieveParams;
    }

    private static RetrieveParams vectorParams(SearchParams params, List<String> kbIds,
                                               float[] embedding, int matchCount,
                                               String knowledgeType) {
        RetrieveParams p = new RetrieveParams();
        p.query = params.getQueryText();
        p.embedding = embedding;
        p.knowledgeBaseIds = kbIds;
        p.topK = matchCount;
        p.threshold = params.getVectorThreshold();
        p.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        p.knowledgeIds = params.getKnowledgeIds();
        p.tagIds = params.getTagIds();
        p.knowledgeType = knowledgeType;
        return p;
    }

    /** 对照 allBaseParamsEmpty（knowledgebase_search.go L360-368）。 */
    private static boolean allBaseParamsEmpty(List<StoreGroup> groups) {
        for (StoreGroup g : groups) {
            if (!g.baseParams.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ── 扇出执行（knowledgebase_search_fanout.go 全文） ────────────────────

    /**
     * 对照 retrieveFromStores：单组直接 Retrieve（快速路径，现行主形态）；
     * 多组虚拟线程扇出（上限 {@link #MULTI_STORE_FANOUT_LIMIT}、组超时），all-or-nothing。
     * 结果跨引擎类型时先过 {@link EngineAwareNormalizer}（同引擎保持原刻度）。
     * 包内可见供测试（StoreGroup 同包）。
     */
    List<RetrieveResult> retrieveFromStores(List<StoreGroup> groups) {
        if (groups.isEmpty()) {
            return new ArrayList<>();
        }
        if (groups.size() == 1) {
            try {
                return groups.get(0).engine.retrieve(paramsWithTopK(groups.get(0)));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                // 引擎口的受检异常（Go 的 error 通道）——原样冒给调用方。
                throw new IllegalStateException(String.valueOf(e.getMessage()), e);
            }
        }

        long timeoutSec = multiStoreRetrieveTimeout();
        Semaphore limit = new Semaphore(MULTI_STORE_FANOUT_LIMIT);
        List<CompletableFuture<List<RetrieveResult>>> futures = new ArrayList<>(groups.size());
        for (StoreGroup grp : groups) {
            CompletableFuture<List<RetrieveResult>> future = new CompletableFuture<>();
            futures.add(future);
            Thread.ofVirtual().start(() -> {
                try {
                    limit.acquire();
                    try {
                        future.complete(grp.engine.retrieve(paramsWithTopK(grp)));
                    } finally {
                        limit.release();
                    }
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        }

        List<RetrieveResult> all = new ArrayList<>();
        RuntimeException failure = null;
        for (int i = 0; i < futures.size(); i++) {
            StoreGroup grp = groups.get(i);
            try {
                all.addAll(futures.get(i).get(timeoutSec, TimeUnit.SECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("multi-store retrieve failed: tenant_id={} kb_count={} store_kind={} err={}",
                        grp.ownerTenantId, grp.kbIds.size(), storeKindLabel(grp.storeId),
                        "group timeout after " + timeoutSec + "s");
                failure = failure == null
                        ? new RuntimeException("store group retrieve: timed out") : failure;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = failure == null
                        ? new RuntimeException("store group retrieve: interrupted") : failure;
            } catch (Exception e) {
                log.warn("multi-store retrieve failed: tenant_id={} kb_count={} store_kind={} err={}",
                        grp.ownerTenantId, grp.kbIds.size(), storeKindLabel(grp.storeId),
                        e.toString());
                failure = failure == null
                        ? new RuntimeException("store group retrieve: " + e.getMessage(), e) : failure;
            }
        }
        if (failure != null) {
            // 照 Go：任何一组失败整条检索塌成 2201（内部原因只在结构化日志里）。
            throw vectorStoreUnavailableStoreFailure();
        }

        // 结果跨 >1 个引擎类型才归一化（hasMixedEngineTypes）。
        if (hasMixedEngineTypes(all)) {
            Set<String> seenUnknown = new HashSet<>();
            for (RetrieveResult rr : all) {
                for (EngineTypes.IndexWithScore hit : rr.results()) {
                    hit.score = EngineAwareNormalizer.INSTANCE.normalize(
                            hit.score, rr.retrieverType(), rr.retrieverEngineType());
                }
                if (!isKnownEngineType(rr.retrieverEngineType())
                        && seenUnknown.add(rr.retrieverEngineType())) {
                    log.warn("score normalizer: unknown engine type, applying clamp01 fallback: "
                            + "engine_type={}", com.ragagent.common.security.LogSanitizer.sanitize(
                                    rr.retrieverEngineType()));
                }
            }
        }
        return all;
    }

    private static BizException vectorStoreUnavailableStoreFailure() {
        return new BizException(new AppError(ErrorCode.VECTOR_STORE_UNAVAILABLE.value(),
                "vector retrieval failed for one or more bound stores", null, 400));
    }

    /**
     * 对照 paramsWithTopK：BaseParams 不可变，TopK 在调用时才覆写——每次重建新列表，
     * FAQ 迭代对 TopK 的变更不会被并发读取方观察到。
     */
    private static List<RetrieveParams> paramsWithTopK(StoreGroup group) {
        List<RetrieveParams> out = new ArrayList<>(group.baseParams.size());
        for (RetrieveParams p : group.baseParams) {
            RetrieveParams copy = new RetrieveParams();
            copy.query = p.query;
            copy.embedding = p.embedding;
            copy.knowledgeBaseIds = p.knowledgeBaseIds;
            copy.knowledgeIds = p.knowledgeIds;
            copy.tagIds = p.tagIds;
            copy.excludeKnowledgeIds = p.excludeKnowledgeIds;
            copy.excludeChunkIds = p.excludeChunkIds;
            copy.topK = group.topK;
            copy.threshold = p.threshold;
            copy.knowledgeType = p.knowledgeType;
            copy.additionalParams = p.additionalParams;
            copy.retrieverType = p.retrieverType;
            out.add(copy);
        }
        return out;
    }

    /** 对照 hasMixedEngineTypes：结果跨 2+ 个引擎类型（空值自成一类）。 */
    static boolean hasMixedEngineTypes(List<RetrieveResult> results) {
        if (results == null || results.size() < 2) {
            return false;
        }
        String first = results.get(0).retrieverEngineType();
        for (RetrieveResult r : results.subList(1, results.size())) {
            if (!java.util.Objects.equals(r.retrieverEngineType(), first)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 isKnownEngineType：EngineAwareNormalizer 有硬编码归一条目的引擎类型
     * （含 Go 的两个死枚举引用）。未知类型由调用方按请求去重 WARN。
     */
    static boolean isKnownEngineType(String t) {
        return EngineTypes.ENGINE_ELASTICSEARCH.equals(t) || "elastic_faiss".equals(t)
                || EngineTypes.ENGINE_OPENSEARCH.equals(t) || EngineTypes.ENGINE_MILVUS.equals(t)
                || EngineTypes.ENGINE_POSTGRES.equals(t) || EngineTypes.ENGINE_QDRANT.equals(t)
                || EngineTypes.ENGINE_WEAVIATE.equals(t) || "infinity".equals(t)
                || EngineTypes.ENGINE_TENCENT_VECTORDB.equals(t) || EngineTypes.ENGINE_DORIS.equals(t)
                || EngineTypes.ENGINE_SQLITE.equals(t);
    }

    /**
     * 对照 multiStoreRetrieveTimeout：读 MULTI_STORE_RETRIEVE_TIMEOUT_SEC，缺省/解析
     * 失败/非正值一律回落 30s。
     */
    static long multiStoreRetrieveTimeout() {
        String raw = System.getenv("MULTI_STORE_RETRIEVE_TIMEOUT_SEC");
        if (raw == null || raw.isEmpty()) {
            return MULTI_STORE_RETRIEVE_TIMEOUT_SEC_DEFAULT;
        }
        try {
            long n = Long.parseLong(raw.trim());
            if (n <= 0) {
                return MULTI_STORE_RETRIEVE_TIMEOUT_SEC_DEFAULT;
            }
            return n;
        } catch (NumberFormatException e) {
            return MULTI_STORE_RETRIEVE_TIMEOUT_SEC_DEFAULT;
        }
    }

    /** 对照 storeKindLabel：日志里只报 "env" / "bound"，不回显 store UUID。 */
    static String storeKindLabel(String storeId) {
        return storeId == null || storeId.isEmpty() ? "env" : "bound";
    }

    /**
     * 引擎结果 → 既有 pg 形态（IndexHit）。下游融合/FAQ/富化代码按 pg 形态写就且已被
     * golden 锁定——在扇出出口做一次逐字段拷贝，下游零改动。
     */
    static List<PgVectorRetrieveRepository.RetrieveResult> toPgShape(
            List<RetrieveResult> engineResults) {
        List<PgVectorRetrieveRepository.RetrieveResult> out = new ArrayList<>(engineResults.size());
        for (RetrieveResult rr : engineResults) {
            List<PgVectorRetrieveRepository.IndexHit> hits = new ArrayList<>(
                    rr.results() == null ? 0 : rr.results().size());
            if (rr.results() != null) {
                for (EngineTypes.IndexWithScore s : rr.results()) {
                    PgVectorRetrieveRepository.IndexHit h = PgVectorRetrieveRepository.IndexHit.of(
                            s.id, s.sourceId, s.sourceType, s.chunkId, s.knowledgeId,
                            s.knowledgeBaseId, s.tagId, s.content, s.score, s.matchType);
                    hits.add(h);
                }
            }
            out.add(PgVectorRetrieveRepository.RetrieveResult.of(hits,
                    rr.retrieverEngineType(), rr.retrieverType()));
        }
        return out;
    }

    private static KnowledgeBaseSearchFacts pickPrimary(List<KnowledgeBaseSearchFacts> kbs, String id) {
        for (KnowledgeBaseSearchFacts kb : kbs) {
            if (kb.id().equals(id)) {
                return kb;
            }
        }
        return null;
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
    static List<PgVectorRetrieveRepository.IndexHit> fuseOrDeduplicate(
            List<PgVectorRetrieveRepository.IndexHit> vectorResults,
            List<PgVectorRetrieveRepository.IndexHit> keywordResults, RetrievalConfigView rc) {
        return HybridFusionOps.fuseOrDeduplicate(vectorResults, keywordResults, rc);
    }

    static List<PgVectorRetrieveRepository.IndexHit> deduplicateByScore(
            List<PgVectorRetrieveRepository.IndexHit> hits) {
        return HybridFusionOps.deduplicateByScore(hits);
    }



    private List<SearchResult> processSearchResults(
            List<PgVectorRetrieveRepository.IndexHit> chunks, boolean skipEnrichment) {
        return resultOps.processSearchResults(chunks, skipEnrichment);
    }

}
