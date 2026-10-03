package com.ragagent.retrieval.engine.elasticsearch;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV7RetrieveRepository.HttpResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch v7 引擎的检索簇：基础条件（JSON 字符串拼接口，v7 形状）、
 * vector（script_score）与 keywords（bool filter+match）两路、命中解析。
 * v7 的 Retrieve 只分派 keywords（vector → invalid retriever type）。
 */
final class ElasticsearchV7SearchOps {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchV7SearchOps.class);

    private final ElasticsearchV7RetrieveRepository service;

    ElasticsearchV7SearchOps(ElasticsearchV7RetrieveRepository service) {
        this.service = service;
    }

    /** 基础条件：返回 JSON <b>字符串</b>（供上层拼接口）。 */
    String getBaseCondsJson(RetrieveParams params) {
        List<ObjectNode> must = new ArrayList<>();
        if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
            must.add(ElasticsearchV7RetrieveRepository.termsOnly(service.idField("knowledge_base_id"), params.knowledgeBaseIds));
        }
        if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
            must.add(ElasticsearchV7RetrieveRepository.termsOnly(service.idField("knowledge_id"), params.knowledgeIds));
        }
        if (params.tagIds != null && !params.tagIds.isEmpty()) {
            must.add(ElasticsearchV7RetrieveRepository.termsOnly(service.idField("tag_id"), params.tagIds));
        }
        List<ObjectNode> mustNot = new ArrayList<>();
        mustNot.add(termIsEnabledFalse());
        if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
            mustNot.add(ElasticsearchV7RetrieveRepository.termsOnly(service.idField("knowledge_id"), params.excludeKnowledgeIds));
        }
        if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
            mustNot.add(ElasticsearchV7RetrieveRepository.termsOnly(service.idField("chunk_id"), params.excludeChunkIds));
        }

        ObjectNode query;
        if (must.isEmpty() && mustNot.isEmpty()) {
            query = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        } else if (must.isEmpty()) {
            query = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("bool",
                    ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("must_not", arrayOf(mustNot)));
        } else if (mustNot.isEmpty()) {
            query = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("bool",
                    ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("must", arrayOf(must)));
        } else {
            ObjectNode bool = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
            bool.set("must", arrayOf(must));
            bool.set("must_not", arrayOf(mustNot));
            query = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("bool", bool);
        }
        return query.toString();
    }

    /** <b>只分派 keywords</b>（vector → invalid retriever type）。 */
    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(params.retrieverType)) {
            return keywordsRetrieve(params);
        }
        throw new IllegalArgumentException("invalid retriever type: " + params.retrieverType);
    }

    /** 向量路（不在分派表里，可直呼）。 */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws Exception {
        JsonNode filter;
        try {
            filter = ElasticsearchV7RetrieveRepository.MAPPER.readTree(getBaseCondsJson(params));
        } catch (Exception e) {
            filter = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        }
        ObjectNode scriptScore = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        ObjectNode filterArrayBody = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        filterArrayBody.putArray("filter").add(filter);
        scriptScore.putObject("query").set("bool", filterArrayBody);
        ObjectNode script = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        script.put("source", "cosineSimilarity(params.query_vector,'embedding')");
        ArrayNode vector = script.putObject("params").putArray("query_vector");
        if (params.embedding != null) {
            for (float v : params.embedding) {
                vector.add(v);
            }
        }
        scriptScore.set("script", script);
        scriptScore.put("min_score", params.threshold);

        ObjectNode body = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("script_score", scriptScore));
        body.put("size", params.topK);

        HttpResult resp = service.request("POST", "/" + service.index + "/_search", body.toString());
        List<IndexWithScore> results = processSearchResponse(resp, EngineTypes.RETRIEVER_VECTOR);
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_ELASTICSEARCH,
                EngineTypes.RETRIEVER_VECTOR));
    }

    /** 关键词路：{@code {"query":{"bool":{"must":[{"match":{"content":q}}],"filter":[<cond>]}}}}。 */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws Exception {
        JsonNode filter;
        try {
            filter = ElasticsearchV7RetrieveRepository.MAPPER.readTree(getBaseCondsJson(params));
        } catch (Exception e) {
            filter = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        }
        ObjectNode match = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        match.putObject("match").putObject("content").put("query", params.query);
        ObjectNode bool = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        bool.putArray("must").add(match);
        bool.putArray("filter").add(filter);

        ObjectNode body = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("bool", bool));

        HttpResult resp = service.request("POST", "/" + service.index + "/_search", body.toString());
        List<IndexWithScore> results = processSearchResponse(resp, EngineTypes.RETRIEVER_KEYWORDS);
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_ELASTICSEARCH,
                EngineTypes.RETRIEVER_KEYWORDS));
    }

    /**
     * 命中解析：单条命中缺
     * {@code _id}/{@code _source}/{@code _score} 时<b>跳过继续</b>（v8 是整请求报错）。
     *
     * <p>命中类型按实际检索路径标注：vector → MatchTypeEmbedding、
     * keywords → MatchTypeKeywords。</p>
     */
    List<IndexWithScore> processSearchResponse(HttpResult resp, String retrieverType)
            throws Exception {
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to retrieve: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        JsonNode root;
        try {
            root = ElasticsearchV7RetrieveRepository.MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode search response: " + e);
        }
        JsonNode hitsObj = root.get("hits");
        if (hitsObj == null || !hitsObj.isObject()) {
            throw new IllegalStateException("invalid search response format");
        }
        JsonNode hits = hitsObj.get("hits");
        List<IndexWithScore> results = new ArrayList<>();
        if (hits == null || !hits.isArray()) {
            log.warn("[ElasticsearchV7] No hits found in search response");
            return results;
        }
        for (JsonNode hit : hits) {
            if (!hit.isObject() || !hit.hasNonNull("_id")) {
                log.warn("[ElasticsearchV7] Error processing hit: hit missing document ID");
                continue;
            }
            String docId = hit.path("_id").asText();
            if (!hit.has("_source")) {
                log.warn("[ElasticsearchV7] Error processing hit: hit {} missing _source", docId);
                continue;
            }
            if (!hit.has("_score") || !hit.path("_score").isNumber()) {
                log.warn("[ElasticsearchV7] Error processing hit: hit {} missing score", docId);
                continue;
            }
            double score = hit.path("_score").asDouble();
            ElasticsearchV8RetrieveRepository.VectorEmbedding embedding =
                    ElasticsearchV8RetrieveRepository.parseSource(hit.path("_source"));
            embedding.score = score;
            results.add(ElasticsearchV8RetrieveRepository.fromDbVectorEmbeddingWithScore(
                    docId, embedding, EngineTypes.RETRIEVER_VECTOR.equals(retrieverType)
                            ? EngineTypes.MATCH_EMBEDDING : EngineTypes.MATCH_KEYWORDS));
        }
        if (results.isEmpty()) {
            if (EngineTypes.RETRIEVER_KEYWORDS.equals(retrieverType)) {
                log.warn("[ElasticsearchV7] No keyword matches found");
            } else {
                log.warn("[ElasticsearchV7] No vector matches found that meet threshold");
            }
        } else {
            log.info("[ElasticsearchV7] {} retrieval found {} results",
                    EngineTypes.RETRIEVER_VECTOR.equals(retrieverType) ? "Vector" : "Keywords",
                    results.size());
        }
        return results;
    }


    static ObjectNode termIsEnabledFalse() {
        return ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("term",
                ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().put("is_enabled", false));
    }


    static ArrayNode arrayOf(List<ObjectNode> nodes) {
        ArrayNode array = ElasticsearchV7RetrieveRepository.MAPPER.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }
}
