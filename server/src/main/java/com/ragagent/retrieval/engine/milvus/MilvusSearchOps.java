package com.ragagent.retrieval.engine.milvus;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.milvus.MilvusRestClient.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus 引擎的检索簇：vector（判存 + 范围搜索，threshold 走 radius）与 keywords
 * （跨前缀集合 BM25 全文，单集合失败只跳过、score 恒 1.0）两路，命中解析与结果包装。
 */
final class MilvusSearchOps {

    private static final Logger log = LoggerFactory.getLogger(MilvusSearchOps.class);

    private final MilvusRetrieveRepository service;

    MilvusSearchOps(MilvusRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        String retrieverType = params == null || params.retrieverType == null
                ? "" : params.retrieverType;
        return switch (retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR -> vectorRetrieve(params);
            case EngineTypes.RETRIEVER_KEYWORDS -> keywordsRetrieve(params);
            default -> {
                log.error("[Milvus] invalid retriever type: {}", retrieverType);
                throw new IllegalStateException("invalid retriever type: " + retrieverType);
            }
        };
    }

    /**
     * 对照 {@code VectorRetrieve}：判存 → 基础过滤 + 范围搜索（threshold>0 → radius）→
     * distance 即分数；类不存在 → 空结果。
     */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        log.info("[Milvus] Vector retrieval: dim={}, topK={}, threshold={}",
                dimension, params.topK, params.threshold);
        String collection = service.collectionName(dimension);
        boolean has;
        try {
            has = service.client.hasCollection(collection);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException("failed to check collection: " + e.getMessage(), e);
        }
        if (!has) {
            log.warn("[Milvus] Collection {} does not exist, returning empty results", collection);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        String filter;
        try {
            filter = MilvusRetrieveRepository.baseFilter(params);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to build base filter: {}", e.getMessage());
            throw new IllegalStateException("failed to build filter: " + e.getMessage(), e);
        }
        ArrayNode data = Json.array();
        ArrayNode vector = data.addArray();
        for (float v : embedding) {
            vector.add(v);
        }
        JsonNode hits;
        try {
            hits = service.client.search(collection, data, MilvusRetrieveRepository.FIELD_EMBEDDING, filter, params.topK,
                    List.of("*"), params.threshold > 0 ? params.threshold : null);
        } catch (RuntimeException e) {
            log.error("[Milvus] Vector search failed: {}", e.getMessage());
            throw new IllegalStateException("failed to search: " + e.getMessage(), e);
        }
        List<IndexWithScore> results = parseSearchHits(hits, EngineTypes.MATCH_EMBEDDING, false);
        if (results.isEmpty()) {
            log.warn("[Milvus] No vector matches found that meet threshold {}", params.threshold);
        } else {
            log.info("[Milvus] Vector retrieval found {} results", results.size());
        }
        return buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：跨前缀集合 BM25 全文检索（文本进 data、
     * annsField=content_sparse）；单集合失败只跳过；score 恒 1.0；合并后截 TopK。
     */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query;
        log.info("[Milvus] Performing keywords retrieval with query: {}, topK: {}", query,
                params.topK);
        List<String> collections = service.listCollectionsOrThrow();
        List<IndexWithScore> allResults = new ArrayList<>();
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            String filter;
            try {
                filter = MilvusRetrieveRepository.baseFilter(params);
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to build base filter: {}", e.getMessage());
                continue;
            }
            ArrayNode data = Json.array();
            data.add(query);
            JsonNode hits;
            try {
                hits = service.client.search(collection, data, MilvusRetrieveRepository.FIELD_CONTENT_SPARSE, filter, params.topK,
                        List.of("*"), null);
            } catch (RuntimeException e) {
                log.error("[Milvus] Keywords search failed: {}", e.getMessage());
                continue;
            }
            allResults.addAll(parseSearchHits(hits, EngineTypes.MATCH_KEYWORDS, true));
        }
        int topK = Math.max(0, params.topK);
        if (allResults.size() > topK) {
            allResults = new ArrayList<>(allResults.subList(0, topK));
        }
        if (allResults.isEmpty()) {
            log.warn("[Milvus] No keyword matches found for query: {}", query);
        } else {
            log.info("[Milvus] Keywords retrieval found {} results", allResults.size());
        }
        return buildRetrieveResult(allResults, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /** 解析 search 返回：{@code data} 是命中数组（每行含 id + distance + 字段）。 */
    static List<IndexWithScore> parseSearchHits(JsonNode hits, int matchType,
                                                boolean forceKeywordScore) {
        List<IndexWithScore> results = new ArrayList<>();
        if (hits == null || !hits.isArray()) {
            return results;
        }
        for (JsonNode hit : hits) {
            MilvusVectorEmbedding row = MilvusRetrieveRepository.fromNode(hit);
            IndexWithScore out = new IndexWithScore();
            out.id = row.id;
            out.sourceId = row.sourceId;
            out.sourceType = row.sourceType;
            out.chunkId = row.chunkId;
            out.knowledgeId = row.knowledgeId;
            out.knowledgeBaseId = row.knowledgeBaseId;
            out.tagId = row.tagId;
            out.content = row.content;
            out.score = forceKeywordScore ? 1.0 : hit.path("distance").asDouble(0);
            out.matchType = matchType;
            results.add(out);
        }
        return results;
    }


    static List<RetrieveResult> buildRetrieveResult(List<IndexWithScore> results,
                                                    String retrieverType) {
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_MILVUS, retrieverType));
    }
}
