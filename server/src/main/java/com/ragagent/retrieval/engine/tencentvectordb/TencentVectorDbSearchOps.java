package com.ragagent.retrieval.engine.tencentvectordb;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbBm25.SparseVecItem;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRestClient.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tencent VectorDB 引擎的检索簇：vector（判存 + HNSW 搜索，threshold 走 radius）与
 * keywords（BM25 稀疏向量全文检索，全失败要报错）两路、过滤串构造、命中解析。
 */
final class TencentVectorDbSearchOps {

    private static final Logger log = LoggerFactory.getLogger(TencentVectorDbSearchOps.class);

    private final TencentVectorDbRetrieveRepository service;

    TencentVectorDbSearchOps(TencentVectorDbRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        String retrieverType = params == null || params.retrieverType == null
                ? "" : params.retrieverType;
        return switch (retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR -> vectorRetrieve(params);
            case EngineTypes.RETRIEVER_KEYWORDS -> keywordsRetrieve(params);
            default -> throw new IllegalStateException(
                    "invalid retriever type: " + retrieverType);
        };
    }


    List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        if (dimension == 0) {
            return retrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        String collection = service.collectionName(dimension);
        boolean exists;
        try {
            exists = service.client.existsCollection(service.databaseName, collection);
        } catch (RuntimeException e) {
            throw new IllegalStateException("tencent vectordb check collection " + collection
                    + ": " + e.getMessage(), e);
        }
        if (!exists) {
            return retrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        long limit = params.topK <= 0 ? 10 : params.topK;
        ObjectNode search = Json.object();
        search.put("filter", baseFilter(params));
        ObjectNode paramsNode = search.putObject("params");
        paramsNode.put("ef", TencentVectorDbRetrieveRepository.SEARCH_EF);
        search.put("retrieveVector", false);
        search.set("outputFields", TencentVectorDbRetrieveRepository.outputFields());
        search.put("limit", limit);
        if (params.threshold > 0) {
            search.put("radius", (float) params.threshold);
        }
        ArrayNode vectors = search.putArray("vectors");
        ArrayNode vector = vectors.addArray();
        for (float v : embedding) {
            vector.add(v);
        }
        JsonNode res;
        try {
            res = service.client.search(service.databaseName, collection, search);
        } catch (RuntimeException e) {
            throw new IllegalStateException("tencent vectordb vector search " + collection + ": "
                    + e.getMessage(), e);
        }
        List<IndexWithScore> results = parseHits(res, EngineTypes.MATCH_EMBEDDING);
        return retrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 关键词检索：客户端 BM25 查询向量 → 跨匹配集合 fullTextSearch；
     * 单集合失败只跳过，但全失败要报错（提示老集合缺 sparse 索引）；score 降序后截 limit。
     */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query.trim();
        if (query.isEmpty()) {
            return retrieveResult(List.of(), EngineTypes.RETRIEVER_KEYWORDS);
        }
        TencentVectorDbBm25 encoder = service.bm25();
        List<SparseVecItem> queryVector = encoder.encodeQuery(query);
        if (queryVector.isEmpty()) {
            return retrieveResult(List.of(), EngineTypes.RETRIEVER_KEYWORDS);
        }
        List<String> collections = service.listCollectionNames();
        int limit = params.topK <= 0 ? 10 : params.topK;
        List<IndexWithScore> results = new ArrayList<>();
        int matched = 0;
        int failed = 0;
        for (String collection : collections) {
            if (!service.matchesCollection(collection)) {
                continue;
            }
            matched++;
            ObjectNode search = Json.object();
            search.put("filter", baseFilter(params));
            search.put("retrieveVector", false);
            search.set("outputFields", TencentVectorDbRetrieveRepository.outputFields());
            search.put("limit", limit);
            ObjectNode match = search.putObject("match");
            match.put("fieldName", TencentVectorDbRetrieveRepository.FIELD_SPARSE_VECTOR);
            ArrayNode data = match.putArray("data");
            ArrayNode sparse = data.addArray();
            for (SparseVecItem item : queryVector) {
                ArrayNode pair = sparse.addArray();
                pair.add(item.termId());
                pair.add(item.score());
            }
            JsonNode res;
            try {
                res = service.client.fullTextSearch(service.databaseName, collection, search);
            } catch (RuntimeException e) {
                failed++;
                log.warn("[TencentVectorDB] keyword search failed in {}: {}", collection,
                        e.getMessage());
                continue;
            }
            results.addAll(parseHits(res, EngineTypes.MATCH_KEYWORDS));
        }
        if (matched > 0 && failed == matched) {
            throw new IllegalStateException("tencent vectordb keyword search failed in all matched"
                    + " collections; ensure collections have the \"" + TencentVectorDbRetrieveRepository.FIELD_SPARSE_VECTOR
                    + "\" sparse vector index and reimport data if they were created before"
                    + " keyword support");
        }
        results.sort((a, b) -> Double.compare(b.score, a.score));
        if (results.size() > limit) {
            results = new ArrayList<>(results.subList(0, limit));
        }
        return retrieveResult(results, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /** 解析 search/fullTextSearch 的 {@code documents[0]}（只取第一批）。 */
    static List<IndexWithScore> parseHits(JsonNode res, int matchType) {
        List<IndexWithScore> results = new ArrayList<>();
        JsonNode batches = res.path("documents");
        if (!batches.isArray() || batches.isEmpty()) {
            return results;
        }
        for (JsonNode doc : batches.get(0)) {
            results.add(TencentVectorDbRetrieveRepository.toIndexWithScore(TencentVectorDbRetrieveRepository.fromDocument(doc), matchType));
        }
        return results;
    }


    static List<RetrieveResult> retrieveResult(List<IndexWithScore> results, String retrieverType) {
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_TENCENT_VECTORDB,
                retrieverType));
    }

    /** 基础过滤：is_enabled=1 恒在，其余按需 in/not in，空格 and 连接。 */
    static String baseFilter(RetrieveParams params) {
        List<String> conditions = new ArrayList<>();
        conditions.add(TencentVectorDbRetrieveRepository.FIELD_IS_ENABLED + "=1");
        if (params != null) {
            addIfPresent(conditions, TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID, params.knowledgeBaseIds));
            addIfPresent(conditions, TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_ID, params.knowledgeIds));
            addIfPresent(conditions, TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_TAG_ID, params.tagIds));
            addIfPresent(conditions, TencentVectorDbRetrieveRepository.notIn(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_ID, params.excludeKnowledgeIds));
            addIfPresent(conditions, TencentVectorDbRetrieveRepository.notIn(TencentVectorDbRetrieveRepository.FIELD_CHUNK_ID, params.excludeChunkIds));
        }
        return String.join(" and ", conditions);
    }


    static void addIfPresent(List<String> conditions, String condition) {
        if (condition != null && !condition.isEmpty()) {
            conditions.add(condition);
        }
    }
}
