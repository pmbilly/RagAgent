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

import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository.HttpResult;
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository.VectorEmbedding;

/**
 * Elasticsearch v8 引擎的检索簇：基础条件（bool must/must_not，恒排除 is_enabled=false）、
 * vector（script_score + cosineSimilarity + min_score）与 keywords（bool filter+match）两路、
 * 命中解析。
 */
final class ElasticsearchV8SearchOps {

    private final ElasticsearchV8RetrieveRepository service;

    ElasticsearchV8SearchOps(ElasticsearchV8RetrieveRepository service) {
        this.service = service;
    }

    /**
     * 对照 {@code getBaseConds}：返回 {@code [{"bool":{"must":[...],"must_not":[...]}}]}；
     * 空 must/must_not 不写出（照 typedapi 的 omitempty）。
     */
    List<ObjectNode> getBaseConds(RetrieveParams params) {
        ArrayNode must = ElasticsearchV8RetrieveRepository.MAPPER.createArrayNode();
        if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
            must.add(ElasticsearchV8RetrieveRepository.termsQuery(service.idField("knowledge_base_id"), params.knowledgeBaseIds));
        }
        if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
            must.add(ElasticsearchV8RetrieveRepository.termsQuery(service.idField("knowledge_id"), params.knowledgeIds));
        }
        if (params.tagIds != null && !params.tagIds.isEmpty()) {
            must.add(ElasticsearchV8RetrieveRepository.termsQuery(service.idField("tag_id"), params.tagIds));
        }

        ArrayNode mustNot = ElasticsearchV8RetrieveRepository.MAPPER.createArrayNode();
        // 恒排除 is_enabled=false（历史数据无该字段 → 不被排除）
        ObjectNode term = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        term.set("term", ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode().set("is_enabled",
                ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode().put("value", false)));
        mustNot.add(term);
        if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
            mustNot.add(ElasticsearchV8RetrieveRepository.termsQuery(service.idField("knowledge_id"), params.excludeKnowledgeIds));
        }
        if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
            mustNot.add(ElasticsearchV8RetrieveRepository.termsQuery(service.idField("chunk_id"), params.excludeChunkIds));
        }

        ObjectNode bool = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        if (!must.isEmpty()) {
            bool.set("must", must);
        }
        if (!mustNot.isEmpty()) {
            bool.set("must_not", mustNot);
        }
        ObjectNode wrapper = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        wrapper.set("bool", bool);
        return List.of(wrapper);
    }

    /** 对照 {@code Retrieve}：按检索类型分派。 */
    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        if (EngineTypes.RETRIEVER_VECTOR.equals(params.retrieverType)) {
            return vectorRetrieve(params);
        }
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(params.retrieverType)) {
            return keywordsRetrieve(params);
        }
        throw new IllegalArgumentException("invalid retriever type: " + params.retrieverType);
    }

    /** 对照 {@code VectorRetrieve}：script_score + cosineSimilarity + min_score。 */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws Exception {
        List<ObjectNode> filter = getBaseConds(params);

        ObjectNode script = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        script.put("source", "cosineSimilarity(params.query_vector, 'embedding')");
        ArrayNode vector = script.putObject("params").putArray("query_vector");
        if (params.embedding != null) {
            for (float v : params.embedding) {
                vector.add(v);
            }
        }

        ObjectNode boolQuery = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        ArrayNode filterArray = boolQuery.putArray("filter");
        filter.forEach(filterArray::add);

        ObjectNode scriptScore = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        scriptScore.putObject("query").set("bool", boolQuery);
        scriptScore.set("script", script);
        scriptScore.put("min_score", (float) params.threshold);

        ObjectNode query = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        query.set("script_score", scriptScore);

        ObjectNode body = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", query);
        body.put("size", params.topK);
        body.putObject("_source").putArray("excludes").add("embedding");

        HttpResult resp = service.request("POST", "/" + service.index + "/_search", body.toString());
        return List.of(parseSearchResponse(resp, "vector"));
    }

    /** 对照 {@code KeywordsRetrieve}：bool{filter, must:[match content]}。 */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws Exception {
        List<ObjectNode> filter = getBaseConds(params);

        ObjectNode match = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        match.putObject("match").putObject("content").put("query", params.query);

        ObjectNode bool = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        ArrayNode filterArray = bool.putArray("filter");
        filter.forEach(filterArray::add);
        ArrayNode must = bool.putArray("must");
        must.add(match);

        ObjectNode query = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        query.set("bool", bool);

        ObjectNode body = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", query);
        body.put("size", params.topK);
        body.putObject("_source").putArray("excludes").add("embedding");

        HttpResult resp = service.request("POST", "/" + service.index + "/_search", body.toString());
        return List.of(parseSearchResponse(resp, "keywords"));
    }


    RetrieveResult parseSearchResponse(HttpResult resp, String retrieverType)
            throws Exception {
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("elasticsearch search returned " + resp.status()
                    + ": " + resp.body());
        }
        JsonNode root = ElasticsearchV8RetrieveRepository.MAPPER.readTree(resp.body());
        List<IndexWithScore> results = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            VectorEmbedding embedding = ElasticsearchV8RetrieveRepository.parseSource(hit.path("_source"));
            embedding.score = hit.path("_score").asDouble(0);
            results.add(ElasticsearchV8RetrieveRepository.fromDbVectorEmbeddingWithScore(hit.path("_id").asText(""), embedding,
                    EngineTypes.RETRIEVER_VECTOR.equals(retrieverType)
                            ? EngineTypes.MATCH_EMBEDDING : EngineTypes.MATCH_KEYWORDS));
        }
        return new RetrieveResult(results, EngineTypes.ENGINE_ELASTICSEARCH, retrieverType);
    }
}
