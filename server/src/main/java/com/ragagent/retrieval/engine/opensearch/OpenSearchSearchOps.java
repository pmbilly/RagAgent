package com.ragagent.retrieval.engine.opensearch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenSearch 引擎的检索簇：k-NN（min_score 直通 COSINESIMIL）与 BM25 两路、
 * 类型化过滤构造（无 JSON 注入面）、D12 不变量（_id==chunk_id）告警、单包结果协议。
 */
final class OpenSearchSearchOps {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchSearchOps.class);

    private final OpenSearchRetrieveRepository service;

    OpenSearchSearchOps(OpenSearchRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        int dim;
        boolean multiIndex;
        if (params.additionalParams != null
                && params.additionalParams.get("dim") instanceof Integer v && v > 0) {
            dim = v;
            multiIndex = false;
        } else if (params.embedding != null && params.embedding.length > 0) {
            dim = params.embedding.length;
            multiIndex = false;
        } else {
            dim = 0;
            multiIndex = true;
        }
        switch (params.retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR: {
                if (dim == 0) {
                    throw new OpenSearchDriverException(
                            OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                            "opensearch: vector retrieve requires embedding or AdditionalParams"
                                    + "[\"dim\"]: opensearch: embedding dimension mismatch");
                }
                service.ensureReady(dim);
                String body = knnQueryJson(params.embedding, OpenSearchRetrieveRepository.effectiveTopK(params),
                        params.threshold, filtersOf(params));
                List<IndexWithScore> hits = search(service.indexAlias(dim), body);
                return List.of(wrapResults(hits, params.retrieverType,
                        EngineTypes.MATCH_EMBEDDING));
            }
            case EngineTypes.RETRIEVER_KEYWORDS: {
                String indexPattern = multiIndex ? service.baseIndex + "_*" : null;
                if (!multiIndex) {
                    service.ensureReady(dim);
                    indexPattern = service.indexAlias(dim);
                }
                String body = keywordQueryJson(params.query, OpenSearchRetrieveRepository.effectiveTopK(params),
                        params.threshold, filtersOf(params));
                List<IndexWithScore> hits = search(indexPattern, body);
                return List.of(wrapResults(hits, params.retrieverType,
                        EngineTypes.MATCH_KEYWORDS));
            }
            default:
                throw new OpenSearchDriverException(
                        OpenSearchDriverException.Kind.CONFIG_INVALID,
                        "opensearch: unsupported retriever type \"" + params.retrieverType + "\"");
        }
    }

    /** 对照 retrieveFilters + fromParams：类型化过滤（无 JSON 注入面）。 */
    static Map<String, Object> filtersOf(RetrieveParams p) {
        Map<String, Object> f = new TreeMap<>();
        f.put("kbIds", p.knowledgeBaseIds == null ? List.of() : p.knowledgeBaseIds);
        f.put("knowledgeIds", p.knowledgeIds == null ? List.of() : p.knowledgeIds);
        f.put("tagIds", p.tagIds == null ? List.of() : p.tagIds);
        f.put("excludeChunkIds", p.excludeChunkIds == null ? List.of() : p.excludeChunkIds);
        f.put("excludeKnowledgeIds",
                p.excludeKnowledgeIds == null ? List.of() : p.excludeKnowledgeIds);
        return f;
    }

    /** 对照 toBoolMust：terms IN → 嵌套 must_not → is_enabled=true 隐含子句。 */
    static List<Map<String, Object>> toBoolMust(Map<String, Object> f) {
        List<Map<String, Object>> must = new ArrayList<>();
        List<String> kbIds = cast(f.get("kbIds"));
        if (!kbIds.isEmpty()) {
            must.add(wrapTerms("knowledge_base_id", kbIds));
        }
        List<String> knowledgeIds = cast(f.get("knowledgeIds"));
        if (!knowledgeIds.isEmpty()) {
            must.add(wrapTerms("knowledge_id", knowledgeIds));
        }
        List<String> tagIds = cast(f.get("tagIds"));
        if (!tagIds.isEmpty()) {
            must.add(wrapTerms("tag_id", tagIds));
        }
        List<String> excludeChunks = cast(f.get("excludeChunkIds"));
        if (!excludeChunks.isEmpty()) {
            must.add(wrapMustNot("chunk_id", excludeChunks));
        }
        List<String> excludeKnowledge = cast(f.get("excludeKnowledgeIds"));
        if (!excludeKnowledge.isEmpty()) {
            must.add(wrapMustNot("knowledge_id", excludeKnowledge));
        }
        must.add(wrapTerm("is_enabled", true));
        return must;
    }

    @SuppressWarnings("unchecked")
    static List<String> cast(Object o) {
        return (List<String>) o;
    }


    static Map<String, Object> wrapTerms(String field, List<String> values) {
        Map<String, Object> terms = new TreeMap<>();
        terms.put(field, values);
        Map<String, Object> out = new TreeMap<>();
        out.put("terms", terms);
        return out;
    }


    static Map<String, Object> wrapTerm(String field, Object value) {
        Map<String, Object> term = new TreeMap<>();
        term.put(field, value);
        Map<String, Object> out = new TreeMap<>();
        out.put("term", term);
        return out;
    }


    static Map<String, Object> wrapMustNot(String field, List<String> values) {
        Map<String, Object> bool = new TreeMap<>();
        bool.put("must_not", wrapTerms(field, values));
        Map<String, Object> out = new TreeMap<>();
        out.put("bool", bool);
        return out;
    }

    /** 对照 buildKNNQuery：min_score 直通（COSINESIMIL 已映射 [0,1]）。 */
    String knnQueryJson(float[] embedding, int topK, double threshold,
                                Map<String, Object> f) throws Exception {
        Map<String, Object> bool = new TreeMap<>();
        bool.put("must", toBoolMust(f));
        Map<String, Object> filter = new TreeMap<>();
        filter.put("bool", bool);
        Map<String, Object> embeddingClause = new TreeMap<>();
        embeddingClause.put("vector", embedding);
        embeddingClause.put("k", topK);
        embeddingClause.put("filter", filter);
        Map<String, Object> knn = new TreeMap<>();
        knn.put("embedding", embeddingClause);
        Map<String, Object> query = new TreeMap<>();
        query.put("knn", knn);
        Map<String, Object> body = new TreeMap<>();
        body.put("size", topK);
        body.put("query", query);
        if (threshold > 0) {
            body.put("min_score", threshold);
        }
        return OpenSearchRetrieveRepository.MAPPER.writeValueAsString(body);
    }

    /** 对照 buildKeywordQuery：BM25 match + 过滤；min_score 语义同上。 */
    String keywordQueryJson(String queryText, int topK, double threshold,
                                    Map<String, Object> f) throws Exception {
        List<Map<String, Object>> must = toBoolMust(f);
        Map<String, Object> match = new TreeMap<>();
        match.put("content", queryText);
        Map<String, Object> matchWrap = new TreeMap<>();
        matchWrap.put("match", match);
        must.add(matchWrap);
        Map<String, Object> bool = new TreeMap<>();
        bool.put("must", must);
        Map<String, Object> query = new TreeMap<>();
        query.put("bool", bool);
        Map<String, Object> body = new TreeMap<>();
        body.put("size", topK);
        body.put("query", query);
        if (threshold > 0) {
            body.put("min_score", threshold);
        }
        return OpenSearchRetrieveRepository.MAPPER.writeValueAsString(body);
    }

    /** 对照 search：404 → INDEX_NOT_FOUND；响应 16MB cap。 */
    List<IndexWithScore> search(String indexPattern, String body) throws Exception {
        String response = service.send("POST", "/" + indexPattern + "/_search",
                body.getBytes(StandardCharsets.UTF_8), "application/json", OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
        return parseSearchHits(response);
    }


    static List<IndexWithScore> parseSearchHits(String response) throws Exception {
        List<IndexWithScore> out = new ArrayList<>();
        JsonNode hits = OpenSearchRetrieveRepository.MAPPER.readTree(response).path("hits").path("hits");
        for (JsonNode h : hits) {
            IndexWithScore s = new IndexWithScore();
            s.id = h.path("_id").asText("");
            s.score = h.path("_score").asDouble(0);
            JsonNode source = h.path("_source");
            s.chunkId = source.path("chunk_id").asText("");
            s.knowledgeId = source.path("knowledge_id").asText("");
            s.knowledgeBaseId = source.path("knowledge_base_id").asText("");
            s.sourceId = source.path("source_id").asText("");
            s.sourceType = source.path("source_type").asInt(0);
            s.tagId = source.path("tag_id").asText("");
            s.content = source.path("content").asText("");
            s.isEnabled = source.path("is_enabled").asBoolean(false);
            if (!s.id.equals(s.chunkId)) {
                // 对照 wrapResults 的 D12 不变量告警（_id 恒 = chunk_id）
                log.warn("[OpenSearch] hit._id=\"{}\" != _source.chunk_id=\"{}\""
                        + " (D12 invariant violation)", s.id, s.chunkId);
            }
            out.add(s);
        }
        return out;
    }

    /** 对照 wrapResults：恒返回单包结果（服务层扇出的 one-bundle-per-driver 协议）。 */
    RetrieveResult wrapResults(List<IndexWithScore> hits, String retrieverType,
                                       int matchType) {
        for (IndexWithScore s : hits) {
            s.matchType = matchType;
        }
        return new RetrieveResult(hits, EngineTypes.ENGINE_OPENSEARCH, retrieverType);
    }
}
