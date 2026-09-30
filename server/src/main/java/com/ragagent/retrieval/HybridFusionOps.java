package com.ragagent.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;

import com.ragagent.common.error.BizException;
import com.ragagent.common.knowledge.ChunkFacts;
import com.ragagent.common.knowledge.KnowledgeBaseSearchFacts;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;

import com.ragagent.retrieval.HybridSearchService.RetrievalConfigView;
import com.ragagent.retrieval.HybridSearchService.StoreGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.retrieval.HybridSearchService.StoreGroup;

/**
 * HybridSearch 的融合与 FAQ 后处理簇：RRF 融合/按分去重、FAQ 迭代取回（只涨 TopK，
 * 引擎分组复用）、负向问题过滤。融合作业为纯静态，FAQ 侧经由门面扇出与 chunk 网关。
 */
final class HybridFusionOps {

    private static final Logger log = LoggerFactory.getLogger(HybridFusionOps.class);

    private final HybridSearchService service;

    HybridFusionOps(HybridSearchService service) {
        this.service = service;
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


    static final Comparator<PgVectorRetrieveRepository.IndexHit> SCORE_DESC = (a, b) -> {
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

    // ── FAQ 后处理（knowledgebase_search_faq.go，现按 storeGroups） ────────

    List<PgVectorRetrieveRepository.IndexHit> applyFaqPostProcessing(
            KnowledgeBaseSearchFacts primary, List<PgVectorRetrieveRepository.IndexHit> chunks,
            List<PgVectorRetrieveRepository.IndexHit> vectorResults, List<StoreGroup> groups,
            SearchParams params, int matchCount) {
        if (!"faq".equals(primary.type())) {
            return chunks;
        }
        if (needsIterativeRetrieval(params, matchCount, chunks, vectorResults)) {
            log.info("Not enough unique chunks, using iterative retrieval for FAQ");
            return iterativeRetrieveWithDeduplication(groups, params.getMatchCount(),
                    params.getQueryText());
        }
        return filterByNegativeQuestions(chunks, params.getQueryText());
    }


    static boolean needsIterativeRetrieval(SearchParams params, int matchCount,
            List<PgVectorRetrieveRepository.IndexHit> chunks,
            List<PgVectorRetrieveRepository.IndexHit> vectorResults) {
        // 对照 _faq.go L40：唯一 chunk 不足 且 首轮向量结果打满 over-retrieval 池。
        return chunks.size() < params.getMatchCount() && vectorResults.size() == matchCount;
    }

    /**
     * 对照 iterativeRetrieveWithDeduplication（_faq.go L55-191）：只涨各组的 TopK，
     * 引擎与分组在上游算好复用；类型化失败（2201）上抛，瞬时故障 WARN 后带部分结果退出。
     */
    List<PgVectorRetrieveRepository.IndexHit> iterativeRetrieveWithDeduplication(
            List<StoreGroup> groups, int matchCount, String queryText) {
        final int maxIterations = 5;
        int currentTopK = Math.min(matchCount * 3, HybridSearchService.MAX_RETRIEVAL_POOL_SIZE);
        Map<String, PgVectorRetrieveRepository.IndexHit> uniqueChunks = new LinkedHashMap<>();
        Map<String, ChunkFacts> chunkDataCache = new HashMap<>();
        Set<String> filteredOutChunks = new HashSet<>();
        String queryTextLower = queryText == null ? "" : queryText.strip().toLowerCase();
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();

        for (int i = 0; i < maxIterations; i++) {
            for (StoreGroup grp : groups) {
                grp.topK = currentTopK;
            }
            List<PgVectorRetrieveRepository.IndexHit> iterationResults = new ArrayList<>();
            try {
                iterationResults = HybridSearchService.toPgShape(service.retrieveFromStores(groups)).stream()
                        .flatMap(rr -> rr.results().stream())
                        .collect(java.util.stream.Collectors.toList());
            } catch (BizException e) {
                // 类型化失败（组超时/绑定失效）如实上抛——不静默截断。
                log.warn("Iterative retrieval surfaced typed failure at iteration {}: {}",
                        i + 1, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                log.warn("Iterative retrieval failed at iteration {}: {}", i + 1, e.getMessage());
                break;
            }
            if (iterationResults.isEmpty()) {
                break;
            }
            int totalRetrieved = iterationResults.size();

            List<String> newChunkIds = new ArrayList<>();
            for (PgVectorRetrieveRepository.IndexHit result : iterationResults) {
                if (!chunkDataCache.containsKey(result.chunkId)
                        && !filteredOutChunks.contains(result.chunkId)) {
                    newChunkIds.add(result.chunkId);
                }
            }
            if (!newChunkIds.isEmpty() && tenantId != null) {
                try {
                    List<ChunkFacts> fresh = service.chunkGateway.findChunks(tenantId, newChunkIds);
                    for (ChunkFacts c : fresh) {
                        chunkDataCache.put(c.id(), c);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch chunks at iteration {}: {}", i + 1, e.getMessage());
                }
            }
            for (PgVectorRetrieveRepository.IndexHit result : iterationResults) {
                if (filteredOutChunks.contains(result.chunkId)) {
                    continue;
                }
                ChunkFacts chunkData = chunkDataCache.get(result.chunkId);
                if (chunkData != null && "faq".equals(chunkData.chunkType())
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
            currentTopK = Math.min(currentTopK * 2, HybridSearchService.MAX_RETRIEVAL_POOL_SIZE);
        }
        List<PgVectorRetrieveRepository.IndexHit> out = new ArrayList<>(uniqueChunks.values());
        out.sort(SCORE_DESC);
        return out;
    }


    List<String> faqNegativeQuestions(ChunkFacts chunk) {
        try {
            List<String> negatives = new ArrayList<>();
            JsonNode node = chunk.metadata();
            if (node != null && node.has("negativeQuestions") && node.get("negativeQuestions").isArray()) {
                for (JsonNode n : node.get("negativeQuestions")) {
                    negatives.add(n.asText(""));
                }
            }
            return negatives;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 对照 matchesNegativeQuestions：子串命中即负例。 */
    static boolean matchesNegativeQuestions(String queryTextLower, List<String> negativeQuestions) {
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


    List<PgVectorRetrieveRepository.IndexHit> filterByNegativeQuestions(
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
        Map<String, ChunkFacts> cache = new HashMap<>();
        try {
            for (ChunkFacts c : service.chunkGateway.findChunks(tenantId, ids)) {
                cache.put(c.id(), c);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch chunks for negative question filtering: {}", e.getMessage());
            return chunks;
        }
        List<PgVectorRetrieveRepository.IndexHit> result = new ArrayList<>();
        for (PgVectorRetrieveRepository.IndexHit hit : chunks) {
            ChunkFacts chunkData = cache.get(hit.chunkId);
            if (chunkData != null && "faq".equals(chunkData.chunkType())
                    && matchesNegativeQuestions(queryTextLower, faqNegativeQuestions(chunkData))) {
                continue;
            }
            result.add(hit);
        }
        return result;
    }
}
