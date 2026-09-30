package com.ragagent.retrieval.engine.qdrant;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qdrant 引擎的检索簇：vector（/points/search，集合不存在返回空）与 keywords
 * （跨集合 /points/scroll 合并截 TopK）两路、过滤构造（baseFilter）、行映射与结果包装。
 */
final class QdrantSearchOps {

    private static final Logger log = LoggerFactory.getLogger(QdrantSearchOps.class);

    private final QdrantRetrieveRepository service;

    QdrantSearchOps(QdrantRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        String retrieverType = params == null || params.retrieverType == null
                ? "" : params.retrieverType;
        return switch (retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR -> vectorRetrieve(params);
            case EngineTypes.RETRIEVER_KEYWORDS -> keywordsRetrieve(params);
            default -> {
                log.error("[Qdrant] invalid retriever type: {}", retrieverType);
                throw new IllegalStateException("invalid retriever type: " + retrieverType);
            }
        };
    }

    /**
     * 对照 {@code VectorRetrieve}：集合不存在 → 空结果；否则
     * {@code /points/search}（filter + limit=TopK + score_threshold + with_payload）；
     * 失败包 {@code <collection>: <err>}。
     */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        log.info("[Qdrant] Vector retrieval: dim={}, topK={}, threshold={}",
                dimension, params.topK, params.threshold);
        String collection = service.collectionName(dimension);
        JsonNode existing;
        try {
            existing = service.client.request("GET", "/collections/" + collection, null, true);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException("failed to check collection: " + e.getMessage(), e);
        }
        if (existing == null) {
            log.warn("[Qdrant] Collection {} does not exist, returning empty results", collection);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        ObjectNode body = QdrantRestClient.object();
        ArrayNode vector = body.putArray("vector");
        for (float v : embedding) {
            vector.add(v);
        }
        body.set("filter", baseFilter(params));
        body.put("limit", params.topK);
        body.put("score_threshold", params.threshold);
        body.put("with_payload", true);
        JsonNode result;
        try {
            result = service.client.request("POST", "/collections/" + collection + "/points/search", body);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Vector search failed: {}", e.getMessage());
            throw new IllegalStateException(collection + ": " + e.getMessage(), e);
        }
        List<IndexWithScore> results = new ArrayList<>();
        if (result != null) {
            for (JsonNode point : result) {
                results.add(fromPoint(point, EngineTypes.MATCH_EMBEDDING,
                        point.path("score").asDouble()));
            }
        }
        if (results.isEmpty()) {
            log.warn("[Qdrant] No vector matches found that meet threshold {}", params.threshold);
        } else {
            log.info("[Qdrant] Vector retrieval found {} results", results.size());
        }
        return buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：跨集合 {@code /points/scroll}，filter 的 Should 装
     * 每个 token 的 content 全文匹配（OR）；无 token 时回落 must 里塞原 query；跨集合合并后
     * 截 TopK；score 恒 1.0；单集合失败只 WARN 继续。
     */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query;
        log.info("[Qdrant] Performing keywords retrieval with query: {}, topK: {}",
                query, params.topK);
        List<String> collections;
        try {
            collections = service.listCollections();
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
        List<IndexWithScore> allResults = new ArrayList<>();
        List<String> tokens = QdrantRetrieveRepository.tokenizeQuery(query);
        log.debug("[Qdrant] Tokenized query into {} tokens: {}", tokens.size(), tokens);
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            ObjectNode filter = baseFilter(params);
            if (!tokens.isEmpty()) {
                ArrayNode should = filter.putArray("should");
                for (String token : tokens) {
                    should.add(QdrantRetrieveRepository.matchText(QdrantRetrieveRepository.FIELD_CONTENT, token));
                }
            } else {
                filter.withArray("must").add(QdrantRetrieveRepository.matchText(QdrantRetrieveRepository.FIELD_CONTENT, query));
            }
            ObjectNode body = QdrantRestClient.object();
            body.set("filter", filter);
            body.put("limit", params.topK);
            body.put("with_payload", true);
            JsonNode scroll;
            try {
                scroll = service.client.request("POST",
                        "/collections/" + collection + "/points/scroll", body);
            } catch (RuntimeException e) {
                log.warn("[Qdrant] Keywords search failed in {}: {}", collection, e.getMessage());
                continue;
            }
            JsonNode points = scroll == null ? null : scroll.get("points");
            if (points != null) {
                for (JsonNode point : points) {
                    allResults.add(fromPoint(point, EngineTypes.MATCH_KEYWORDS, 1.0));
                }
            }
        }
        int topK = Math.max(0, params.topK);
        if (allResults.size() > topK) {
            allResults = new ArrayList<>(allResults.subList(0, topK));
        }
        if (allResults.isEmpty()) {
            log.warn("[Qdrant] No keyword matches found for query: {}", query);
        } else {
            log.info("[Qdrant] Keywords retrieval found {} results", allResults.size());
        }
        return buildRetrieveResult(allResults, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /** 对照 {@code getBaseFilter}：is_enabled=true 隐含 + KB/知识/标签过滤 + 排除项。 */
    static ObjectNode baseFilter(RetrieveParams params) {
        ObjectNode filter = QdrantRestClient.object();
        ArrayNode must = filter.putArray("must");
        ArrayNode mustNot = filter.putArray("must_not");
        must.add(QdrantRetrieveRepository.matchValue(QdrantRetrieveRepository.FIELD_IS_ENABLED, true));
        if (params != null) {
            if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
                must.add(QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID, params.knowledgeBaseIds));
            }
            if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
                must.add(QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_KNOWLEDGE_ID, params.knowledgeIds));
            }
            if (params.tagIds != null && !params.tagIds.isEmpty()) {
                must.add(QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_TAG_ID, params.tagIds));
            }
            if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
                mustNot.add(QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_KNOWLEDGE_ID, params.excludeKnowledgeIds));
            }
            if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
                mustNot.add(QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_CHUNK_ID, params.excludeChunkIds));
            }
        }
        return filter;
    }

    /** 对照 {@code fromQdrantVectorEmbedding}：payload + score → IndexWithScore（IsEnabled 不回填，照 Go）。 */
    static IndexWithScore fromPoint(JsonNode point, int matchType, double score) {
        JsonNode payload = point.path("payload");
        IndexWithScore out = new IndexWithScore();
        out.id = point.path("id").asText("");
        out.sourceId = payload.path(QdrantRetrieveRepository.FIELD_SOURCE_ID).asText("");
        out.sourceType = payload.path(QdrantRetrieveRepository.FIELD_SOURCE_TYPE).asInt(0);
        out.chunkId = payload.path(QdrantRetrieveRepository.FIELD_CHUNK_ID).asText("");
        out.knowledgeId = payload.path(QdrantRetrieveRepository.FIELD_KNOWLEDGE_ID).asText("");
        out.knowledgeBaseId = payload.path(QdrantRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID).asText("");
        out.tagId = payload.path(QdrantRetrieveRepository.FIELD_TAG_ID).asText("");
        out.content = payload.path(QdrantRetrieveRepository.FIELD_CONTENT).asText("");
        out.score = score;
        out.matchType = matchType;
        return out;
    }


    static List<RetrieveResult> buildRetrieveResult(List<IndexWithScore> results,
                                                    String retrieverType) {
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_QDRANT, retrieverType));
    }
}
