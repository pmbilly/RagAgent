package com.ragagent.retrieval.engine.weaviate;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Weaviate 引擎的检索簇：vector（GraphQL nearVector，certainty=threshold）与 keywords
 * （跨集合 BM25，单集合失败直接报错——与 Qdrant 的跳过相反）两路、GraphQL 响应解析。
 */
final class WeaviateSearchOps {

    private static final Logger log = LoggerFactory.getLogger(WeaviateSearchOps.class);

    private final WeaviateRetrieveRepository service;

    WeaviateSearchOps(WeaviateRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        String retrieverType = params == null || params.retrieverType == null
                ? "" : params.retrieverType;
        return switch (retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR -> vectorRetrieve(params);
            case EngineTypes.RETRIEVER_KEYWORDS -> keywordsRetrieve(params);
            default -> {
                log.error("[Weaviate] invalid retriever type: {}", retrieverType);
                throw new IllegalStateException("invalid retriever type: " + retrieverType);
            }
        };
    }

    /**
     * 对照 {@code VectorRetrieve}：类判存 → GraphQL nearVector（certainty = threshold）→
     * 解析 {@code _additional.certainty} 为分数；类不存在 → 空结果。
     */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        log.info("[Weaviate] Vector retrieval: dim={}, topK={}, threshold={}",
                dimension, params.topK, params.threshold);
        String collection = service.collectionName(dimension);
        boolean exists;
        try {
            exists = service.client.classExists(collection);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException("failed to check collection: " + e.getMessage(), e);
        }
        if (!exists) {
            log.warn("[Weaviate] Collection {} does not exist, returning empty results",
                    collection);
            return WeaviateRetrieveRepository.buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        // 照 Go：scoreThreshold := float32(params.Threshold)
        String query = WeaviateGql.vectorQuery(collection, baseFilter(params), params.topK,
                embedding, (float) params.threshold);
        JsonNode result;
        try {
            result = service.client.graphql(query);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Vector search failed: {}", e.getMessage());
            throw new IllegalStateException("failed to search: " + e.getMessage(), e);
        }
        JsonNode items = service.extractItems(result, collection);
        if (items == null) {
            log.warn("[Weaviate] No vector matches found that meet threshold {}",
                    params.threshold);
            return WeaviateRetrieveRepository.buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        List<IndexWithScore> results = parseItems(items, EngineTypes.MATCH_EMBEDDING);
        if (results.isEmpty()) {
            log.warn("[Weaviate] No vector matches found that meet threshold {}",
                    params.threshold);
        } else {
            log.info("[Weaviate] Vector retrieval found {} results", results.size());
        }
        return WeaviateRetrieveRepository.buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：跨集合 BM25；单集合查询失败<b>直接返回错误</b>
     * （照 Go——与 Qdrant 的"跳过继续"相反），缺数据则 continue；合并后截 TopK。
     */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query;
        log.info("[Weaviate] Performing keywords retrieval with query: {}, topK: {}",
                query, params.topK);
        List<String> collections = service.listCollectionsOrThrow();
        List<IndexWithScore> allResults = new ArrayList<>();
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            String gql = WeaviateGql.bm25Query(collection, baseFilter(params), params.topK, query,
                    List.of(WeaviateRetrieveRepository.FIELD_CONTENT));
            JsonNode result;
            try {
                result = service.client.graphql(gql);
            } catch (RuntimeException e) {
                log.error("[Weaviate] keywords search failed: {}", e.getMessage());
                throw new IllegalStateException("failed to search: " + e.getMessage(), e);
            }
            JsonNode items = service.extractItems(result, collection);
            if (items == null) {
                log.warn("[Weaviate] No keywords matches found that meet threshold {}",
                        params.threshold);
                continue;
            }
            allResults.addAll(parseItems(items, EngineTypes.MATCH_KEYWORDS));
        }
        int topK = Math.max(0, params.topK);
        if (allResults.size() > topK) {
            allResults = new ArrayList<>(allResults.subList(0, topK));
        }
        if (allResults.isEmpty()) {
            log.warn("[Weaviate] No keyword matches found for query: {}", query);
        } else {
            log.info("[Weaviate] Keywords retrieval found {} results", allResults.size());
        }
        return WeaviateRetrieveRepository.buildRetrieveResult(allResults, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /**
     * 对照 {@code parseGraphQLResponse}：向量取 certainty、关键词恒 1.0。
     *
     * <p><b>有意修正（Go 实录实锤）</b>：Weaviate 1.28.4 把 BM25 的 {@code _additional.score}
     * 编码成<b>字符串</b>（{@code "0.48952064"}），Go 的 {@code .(float64)} 类型断言因此恒失败
     * → Go 的关键词结果分数**恒 0.0**（其"keywords → 1.0"分支是死代码；用 Go 客户端对真服务端
     * 实测：{@code score="0.48952064" type=string isFloat64=false}）。本仓按代码意图修正为
     * "存在 score 值即 1.0"（与 Qdrant/Doris 驱动的关键词分数一致），并把字符串形态的数字
     * 也解析进 certainty（服务端版本差异的容错）。</p>
     */
    static List<IndexWithScore> parseItems(JsonNode items, int matchType) {
        List<IndexWithScore> results = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode additional = item.path("_additional");
            String pointId = additional.path("id").asText("");
            double score = 0.0;
            String additionalName = matchType == EngineTypes.MATCH_EMBEDDING
                    ? "certainty" : "score";
            JsonNode raw = additional.get(additionalName);
            if (raw != null && !raw.isNull()) {
                if (matchType == EngineTypes.MATCH_KEYWORDS) {
                    score = 1.0;
                } else if (raw.isNumber()) {
                    score = raw.asDouble();
                } else if (raw.isTextual()) {
                    try {
                        score = Double.parseDouble(raw.asText());
                    } catch (NumberFormatException ignored) {
                        // 保持 0.0（照 Go 的"解析不了就不给分"）
                    }
                }
            }
            IndexWithScore out = new IndexWithScore();
            out.id = pointId;
            out.sourceId = item.path(WeaviateRetrieveRepository.FIELD_SOURCE_ID).asText("");
            out.sourceType = item.path(WeaviateRetrieveRepository.FIELD_SOURCE_TYPE).asInt(0);
            out.chunkId = item.path(WeaviateRetrieveRepository.FIELD_CHUNK_ID).asText("");
            out.knowledgeId = item.path(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_ID).asText("");
            out.knowledgeBaseId = item.path(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID).asText("");
            out.tagId = item.path(WeaviateRetrieveRepository.FIELD_TAG_ID).asText("");
            out.content = item.path(WeaviateRetrieveRepository.FIELD_CONTENT).asText("");
            out.score = score;
            out.matchType = matchType;
            results.add(out);
        }
        return results;
    }

    // ── 过滤器（照 getBaseFilter） ─────────────────────────────────────────

    static WeaviateGql.Where baseFilter(RetrieveParams params) {
        List<WeaviateGql.Where> operands = new ArrayList<>();
        operands.add(WeaviateGql.Where.equal(WeaviateRetrieveRepository.FIELD_IS_ENABLED).valueBoolean(true));
        if (params != null) {
            if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
                operands.add(WeaviateGql.Where.containsAny(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID)
                        .valueText(params.knowledgeBaseIds.toArray(new String[0])));
            }
            if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
                operands.add(WeaviateGql.Where.containsAny(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_ID)
                        .valueText(params.knowledgeIds.toArray(new String[0])));
            }
            if (params.tagIds != null && !params.tagIds.isEmpty()) {
                operands.add(WeaviateGql.Where.containsAny(WeaviateRetrieveRepository.FIELD_TAG_ID)
                        .valueText(params.tagIds.toArray(new String[0])));
            }
            if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
                operands.add(WeaviateGql.Where.notEqual(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_ID)
                        .valueText(params.excludeKnowledgeIds.toArray(new String[0])));
            }
            if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
                operands.add(WeaviateGql.Where.notEqual(WeaviateRetrieveRepository.FIELD_CHUNK_ID)
                        .valueText(params.excludeChunkIds.toArray(new String[0])));
            }
        }
        return WeaviateGql.Where.and(operands);
    }
}
