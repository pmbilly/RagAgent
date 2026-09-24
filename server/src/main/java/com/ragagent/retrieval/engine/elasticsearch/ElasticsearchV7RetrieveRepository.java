package com.ragagent.retrieval.engine.elasticsearch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * Elasticsearch <b>v7</b> 检索引擎仓库——对照 Go
 * {@code internal/application/repository/retriever/elasticsearch/v7/repository.go}（1452 行）
 * + {@code v7/move.go}，文档结构与双向转换复用 {@code elasticsearch/structs.go}（同 v8）。
 *
 * <h2>与 v8 的差异（逐条照抄）</h2>
 * <ul>
 *   <li>{@code Support()} 只报 <b>keywords</b>（v7 不带向量）；{@code Retrieve()} 也只分派
 *       keywords——传 vector 直接 {@code invalid retriever type}（{@code VectorRetrieve}
 *       仍实现且可直呼，但不在分派表里）</li>
 *   <li>命中一律标 <b>MatchTypeKeywords</b>（照 Go：{@code processHit} 恒传
 *       {@code MatchTypeKeywords}，向量结果也是 1——Go 的怪癖，照抄）；单条命中缺
 *       {@code _id}/{@code _source}/{@code _score} 时<b>跳过该条继续</b>（v8 是整请求报错）</li>
 *   <li>基础条件是 <b>JSON 字符串</b>（{@code getBaseConds} 返回 string，供拼装）；关键词查询走
 *       模板 {@code {"query":{"bool":{"must":[{"match":{"content":<q>}}],"filter":[<cond>]}}}}</li>
 *   <li>建索引的 settings 是 <b>数字</b>（v8 是字符串）；建索引失败文案
 *       {@code failed to create index <index>}</li>
 *   <li>单条写入走 {@code PUT /{index}/_create/{uuid}}（显式 UUID 文档 ID，v8 是 POST /_doc 自增）；
 *       批量 NDJSON 的动作行是 {@code { "index" : { "_id" : "<uuid>" } }}（带空格，照 Go），
 *       响应里 {@code errors:true} 只<b>计数并告警</b>（不失败）、解析失败也放行</li>
 *   <li>删除/改状态/改标签的 body 是手拼/直构的 {@code {"query":{"terms":{...}}}}——改状态<b>不套
 *       bool</b>（v8 套 bool.must）；脚本带 {@code lang: painless}</li>
 *   <li>{@code MoveKnowledgeIndices}（v7/move.go）：filter 里用 <b>singular {@code term}</b> +
 *       字符串值（v8 用 {@code terms} 数组）；脚本<b>带 lang</b>；{@code ?refresh=true}；
 *       完整性校验同 v8</li>
 * </ul>
 *
 * <h2>照抄的 Go 缺陷（备案）</h2>
 * <p>{@code CopyIndices} 的 {@code saveCopiedIndices} 里 {@code embeddingMap} 是<b>新建的空 map</b>
 * （{@code processSourceBatch} 里收集的向量被丢弃）→ 复制过去的文档<b>不带向量</b>。本类照抄该行为
 * （与 v8 的"同 chunk 后者覆盖"同理：先保真，不擅自修 Go）。</p>
 */
public class ElasticsearchV7RetrieveRepository {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchV7RetrieveRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int COPY_BATCH_SIZE = 500;

    private final String addr;
    private final String index;
    private final int numberOfShards;
    private final int numberOfReplicas;
    private final String username;
    private final String password;
    private final HttpClient http;
    private volatile boolean useKeywordSuffix;

    public ElasticsearchV7RetrieveRepository(String addr, String indexName, int numberOfShards,
                                             int numberOfReplicas, String username,
                                             String password, SsrfGuard ssrfGuard) {
        this(addr, indexName, numberOfShards, numberOfReplicas, username, password, ssrfGuard,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public ElasticsearchV7RetrieveRepository(String addr, String indexName, int numberOfShards,
                                             int numberOfReplicas, String username,
                                             String password, SsrfGuard ssrfGuard,
                                             HttpClient http) {
        String address = addr == null ? "" : addr.trim();
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        if (address.isEmpty()) {
            throw new IllegalArgumentException("elasticsearch address is required");
        }
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(address);
        }
        this.addr = address;
        this.index = EngineTypes.resolveIndexName(indexName, EngineTypes.ENV_ELASTICSEARCH_INDEX,
                EngineTypes.DEFAULT_INDEX);
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.http = http;

        try {
            createIndexIfNotExists();
        } catch (Exception e) {
            log.error("[ElasticsearchV7] Failed to create index: {}", e.toString());
        }
        detectFieldTypes();
    }

    /** 供测试观察。 */
    String idField(String name) {
        return useKeywordSuffix ? name + ".keyword" : name;
    }

    boolean useKeywordSuffix() {
        return useKeywordSuffix;
    }

    public String engineType() {
        return EngineTypes.ENGINE_ELASTICSEARCH;
    }

    /** 对照 v7 {@code Support}：只有 keywords（无向量）。 */
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS);
    }

    // ── 索引自举 ────────────────────────────────────────────────────────────

    /** 对照 v7 {@code createIndexIfNotExists}：settings 为<b>数字</b>；失败文案固定。 */
    void createIndexIfNotExists() throws Exception {
        HttpResult exists = request("HEAD", "/" + index, null);
        if (exists.status() >= 200 && exists.status() < 300) {
            log.debug("[ElasticsearchV7] Index already exists: {}", index);
            return;
        }
        String body = null;
        if (numberOfShards > 0 || numberOfReplicas >= 0) {
            ObjectNode settings = MAPPER.createObjectNode();
            if (numberOfShards > 0) {
                settings.put("number_of_shards", numberOfShards);
            }
            if (numberOfReplicas >= 0) {
                settings.put("number_of_replicas", numberOfReplicas);
            }
            body = MAPPER.createObjectNode().set("settings", settings).toString();
        }
        HttpResult created = request("PUT", "/" + index, body);
        if (created.status() < 200 || created.status() >= 300) {
            log.error("[ElasticsearchV7] Create index response: {}", created.body());
            throw new IllegalStateException("failed to create index " + index);
        }
        log.info("[ElasticsearchV7] Index created successfully: {}", index);
    }

    /** 对照 v7 {@code detectFieldTypes}（与 v8 同判定，逐层判空）。 */
    void detectFieldTypes() {
        try {
            HttpResult resp = request("GET", "/" + index + "/_mapping", null);
            if (resp.status() < 200 || resp.status() >= 300) {
                log.warn("[ElasticsearchV7] GetMapping returned error, defaulting to .keyword"
                        + " suffix");
                useKeywordSuffix = true;
                return;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode indexData = root.get(index);
            if (indexData == null || indexData.isNull()) {
                useKeywordSuffix = true;
                return;
            }
            JsonNode properties = indexData.path("mappings").path("properties");
            if (properties.isMissingNode() || properties.isEmpty()) {
                log.info("[ElasticsearchV7] No mapping detected for ID fields (empty index?),"
                        + " defaulting to .keyword suffix");
                useKeywordSuffix = true;
                return;
            }
            JsonNode chunkIdProp = properties.get("chunk_id");
            if (chunkIdProp == null || chunkIdProp.isNull()) {
                useKeywordSuffix = true;
                return;
            }
            if ("keyword".equals(chunkIdProp.path("type").asText())) {
                useKeywordSuffix = false;
                log.info("[ElasticsearchV7] Detected keyword type for ID fields, querying without"
                        + " .keyword suffix");
            } else {
                useKeywordSuffix = true;
                log.info("[ElasticsearchV7] Detected {} type for ID fields, querying with"
                        + " .keyword suffix", chunkIdProp.path("type").asText());
            }
        } catch (Exception e) {
            log.warn("[ElasticsearchV7] Failed to get index mapping, defaulting to .keyword"
                    + " suffix: {}", e.toString());
            useKeywordSuffix = true;
        }
    }

    // ── 存储估算 ────────────────────────────────────────────────────────────

    /** 对照 v7 {@code calculateStorageSize}（与 v8 同式）。 */
    static long calculateStorageSize(ElasticsearchV8RetrieveRepository.VectorEmbedding embedding) {
        return ElasticsearchV8RetrieveRepository.calculateStorageSize(embedding);
    }

    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            total += calculateStorageSize(
                    ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(info, params));
        }
        log.info("[ElasticsearchV7] Estimated storage size: {} bytes ({} MB) for {} indices",
                total, total / (1024 * 1024), indexInfoList.size());
        return total;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** 对照 v7 {@code Save}：显式 UUID 文档 ID 走 {@code PUT /{index}/_create/{id}}。 */
    public void save(IndexInfo embedding, Map<String, Object> additionalParams) throws Exception {
        ElasticsearchV8RetrieveRepository.VectorEmbedding doc =
                ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(embedding, additionalParams);
        if (doc.embedding == null || doc.embedding.length == 0) {
            throw new IllegalStateException(
                    "empty embedding vector for chunk ID: " + embedding.chunkId);
        }
        String docId = UUID.randomUUID().toString();
        HttpResult resp = request("PUT", "/" + index + "/_create/" + docId,
                ElasticsearchV8RetrieveRepository.docJson(doc));
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to index document: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    /**
     * 对照 v7 {@code BatchSave} + {@code prepareBulkRequestBody} + {@code processBulkResponse}：
     * 动作行 {@code { "index" : { "_id" : "<uuid>" } }}（带空格，照 Go）；
     * 响应 {@code errors:true} 只计数告警、解析失败也放行（**永不因此失败**）。
     */
    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> additionalParams)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[ElasticsearchV7] Empty list provided to BatchSave, skipping");
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        int processedCount = 0;
        for (IndexInfo embedding : embeddingList) {
            ElasticsearchV8RetrieveRepository.VectorEmbedding doc =
                    ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(embedding,
                            additionalParams);
            String docId = UUID.randomUUID().toString();
            ndjson.append("{ \"index\" : { \"_id\" : \"").append(docId).append("\" } }\n");
            ndjson.append(ElasticsearchV8RetrieveRepository.docJson(doc)).append('\n');
            processedCount++;
        }
        if (processedCount == 0) {
            log.warn("[ElasticsearchV7] No valid documents to index after filtering, skipping"
                    + " bulk request");
            return;
        }
        HttpResult resp = request("POST", "/" + index + "/_bulk", ndjson.toString());
        processBulkResponse(resp, embeddingList.size());
    }

    /** 对照 v7 {@code processBulkResponse}/{@code countBulkErrors}：只告警，不抛。 */
    private void processBulkResponse(HttpResult resp, int totalDocuments) {
        if (resp.status() < 200 || resp.status() >= 300) {
            // 照 Go：resp.IsError() 时才返回错误；非 2xx 且非"错误响应"的情形照 IsError 语义处理
            if (resp.status() >= 400) {
                throw new IllegalStateException("failed to index documents: elasticsearch"
                        + " returned " + resp.status() + ": " + resp.body());
            }
        }
        JsonNode bulkResponse;
        try {
            bulkResponse = MAPPER.readTree(resp.body());
        } catch (Exception e) {
            log.warn("[ElasticsearchV7] Could not parse bulk response: {}", e.toString());
            return;
        }
        if (bulkResponse != null && bulkResponse.path("errors").asBoolean(false)) {
            int errorCount = countBulkErrors(bulkResponse);
            if (errorCount > 0) {
                log.warn("[ElasticsearchV7] {}/{} documents failed to index", errorCount,
                        totalDocuments);
            }
        }
    }

    private int countBulkErrors(JsonNode bulkResponse) {
        log.warn("[ElasticsearchV7] Bulk operation completed with some errors");
        int errorCount = 0;
        for (JsonNode item : bulkResponse.path("items")) {
            JsonNode indexResp = item.path("index");
            if (indexResp.has("error") && !indexResp.path("error").isNull()) {
                errorCount++;
                log.error("[ElasticsearchV7] Item error: {}", indexResp.path("error"));
            }
        }
        return errorCount;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByFieldList(idField("chunk_id"), chunkIdList);
    }

    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByFieldList(idField("source_id"), sourceIdList);
    }

    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByFieldList(idField("knowledge_id"), knowledgeIdList);
    }

    /** 对照 v7 {@code deleteByFieldList}：手拼 {@code {"query": {"terms": {field: [...]}}}}。 */
    private void deleteByFieldList(String field, List<String> valueList) throws Exception {
        if (valueList == null || valueList.isEmpty()) {
            log.warn("[ElasticsearchV7] Empty {} list provided for deletion, skipping", field);
            return;
        }
        ObjectNode terms = MAPPER.createObjectNode();
        ArrayNode array = terms.putArray(field);
        for (String value : valueList) {
            array.add(value);
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", MAPPER.createObjectNode().set("terms", terms));

        HttpResult resp = request("POST", "/" + index + "/_delete_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to delete by query: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        try {
            JsonNode deleteResponse = MAPPER.readTree(resp.body());
            if (deleteResponse != null && deleteResponse.has("deleted")) {
                log.info("[ElasticsearchV7] Successfully deleted {} documents by {}",
                        deleteResponse.path("deleted").asLong(), field);
            } else {
                log.info("[ElasticsearchV7] Successfully deleted documents by {}", field);
            }
        } catch (Exception e) {
            log.warn("[ElasticsearchV7] Could not parse delete response: {}", e.toString());
        }
    }

    // ── 基础条件（JSON 字符串） ─────────────────────────────────────────────

    /** 对照 v7 {@code getBaseConds}：返回 JSON <b>字符串</b>（Go 是 string 拼接口）。 */
    String getBaseCondsJson(RetrieveParams params) {
        List<ObjectNode> must = new ArrayList<>();
        if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
            must.add(termsOnly(idField("knowledge_base_id"), params.knowledgeBaseIds));
        }
        if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
            must.add(termsOnly(idField("knowledge_id"), params.knowledgeIds));
        }
        if (params.tagIds != null && !params.tagIds.isEmpty()) {
            must.add(termsOnly(idField("tag_id"), params.tagIds));
        }
        List<ObjectNode> mustNot = new ArrayList<>();
        mustNot.add(termIsEnabledFalse());
        if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
            mustNot.add(termsOnly(idField("knowledge_id"), params.excludeKnowledgeIds));
        }
        if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
            mustNot.add(termsOnly(idField("chunk_id"), params.excludeChunkIds));
        }

        ObjectNode query;
        if (must.isEmpty() && mustNot.isEmpty()) {
            query = MAPPER.createObjectNode();
        } else if (must.isEmpty()) {
            query = MAPPER.createObjectNode().set("bool",
                    MAPPER.createObjectNode().set("must_not", arrayOf(mustNot)));
        } else if (mustNot.isEmpty()) {
            query = MAPPER.createObjectNode().set("bool",
                    MAPPER.createObjectNode().set("must", arrayOf(must)));
        } else {
            ObjectNode bool = MAPPER.createObjectNode();
            bool.set("must", arrayOf(must));
            bool.set("must_not", arrayOf(mustNot));
            query = MAPPER.createObjectNode().set("bool", bool);
        }
        return query.toString();
    }

    private static ObjectNode termsOnly(String field, List<String> values) {
        ObjectNode terms = MAPPER.createObjectNode();
        ArrayNode array = terms.putArray(field);
        for (String value : values) {
            array.add(value);
        }
        return MAPPER.createObjectNode().set("terms", terms);
    }

    private static ObjectNode termIsEnabledFalse() {
        return MAPPER.createObjectNode().set("term",
                MAPPER.createObjectNode().put("is_enabled", false));
    }

    private static ArrayNode arrayOf(List<ObjectNode> nodes) {
        ArrayNode array = MAPPER.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** 对照 v7 {@code Retrieve}：<b>只分派 keywords</b>（vector → invalid retriever type）。 */
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(params.retrieverType)) {
            return keywordsRetrieve(params);
        }
        throw new IllegalArgumentException("invalid retriever type: " + params.retrieverType);
    }

    /** 对照 v7 {@code VectorRetrieve}（不在分派表里，可直呼）。 */
    public List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws Exception {
        JsonNode filter;
        try {
            filter = MAPPER.readTree(getBaseCondsJson(params));
        } catch (Exception e) {
            filter = MAPPER.createObjectNode();
        }
        ObjectNode scriptScore = MAPPER.createObjectNode();
        ObjectNode filterArrayBody = MAPPER.createObjectNode();
        filterArrayBody.putArray("filter").add(filter);
        scriptScore.putObject("query").set("bool", filterArrayBody);
        ObjectNode script = MAPPER.createObjectNode();
        script.put("source", "cosineSimilarity(params.query_vector,'embedding')");
        ArrayNode vector = script.putObject("params").putArray("query_vector");
        if (params.embedding != null) {
            for (float v : params.embedding) {
                vector.add(v);
            }
        }
        scriptScore.set("script", script);
        scriptScore.put("min_score", params.threshold);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", MAPPER.createObjectNode().set("script_score", scriptScore));
        body.put("size", params.topK);

        HttpResult resp = request("POST", "/" + index + "/_search", body.toString());
        List<IndexWithScore> results = processSearchResponse(resp);
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_ELASTICSEARCH,
                EngineTypes.RETRIEVER_VECTOR));
    }

    /** 对照 v7 {@code KeywordsRetrieve}：{@code {"query":{"bool":{"must":[{"match":{"content":q}}],"filter":[<cond>]}}}}。 */
    public List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws Exception {
        JsonNode filter;
        try {
            filter = MAPPER.readTree(getBaseCondsJson(params));
        } catch (Exception e) {
            filter = MAPPER.createObjectNode();
        }
        ObjectNode match = MAPPER.createObjectNode();
        match.putObject("match").putObject("content").put("query", params.query);
        ObjectNode bool = MAPPER.createObjectNode();
        bool.putArray("must").add(match);
        bool.putArray("filter").add(filter);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", MAPPER.createObjectNode().set("bool", bool));

        HttpResult resp = request("POST", "/" + index + "/_search", body.toString());
        List<IndexWithScore> results = processSearchResponse(resp);
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_ELASTICSEARCH,
                EngineTypes.RETRIEVER_KEYWORDS));
    }

    /**
     * 对照 v7 {@code processSearchResponse} + {@code processHits}：单条命中缺
     * {@code _id}/{@code _source}/{@code _score} 时<b>跳过继续</b>（v8 是整请求报错）；
     * 命中一律标 MatchTypeKeywords（照 Go 的 {@code processHit}，向量结果也是 1）。
     */
    private List<IndexWithScore> processSearchResponse(HttpResult resp) throws Exception {
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to retrieve: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(resp.body());
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
            // 照 Go v7 的 processHit：命中一律标 MatchTypeKeywords（向量结果也是 1）
            results.add(ElasticsearchV8RetrieveRepository.fromDbVectorEmbeddingWithScore(
                    docId, embedding, EngineTypes.MATCH_KEYWORDS));
        }
        if (results.isEmpty()) {
            log.warn("[ElasticsearchV7] No matches found");
        } else {
            log.info("[ElasticsearchV7] Retrieval found {} results", results.size());
        }
        return results;
    }

    // ── 复制索引 ────────────────────────────────────────────────────────────

    /**
     * 对照 v7 {@code CopyIndices}（分页 + 改名 + SourceID 三态）。
     *
     * <p><b>照抄的 Go 缺陷</b>：{@code saveCopiedIndices} 里 embeddingMap 是新建空 map →
     * 收集到的向量被丢弃 → 目标文档<b>不带向量</b>（见类注释）。</p>
     */
    public void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[ElasticsearchV7] Empty mapping, skipping copy");
            return;
        }
        RetrieveParams retrieveParams = new RetrieveParams();
        retrieveParams.knowledgeBaseIds = List.of(sourceKnowledgeBaseId);

        int from = 0;
        int totalCopied = 0;
        while (true) {
            JsonNode hitsList = querySourceBatch(retrieveParams, from, COPY_BATCH_SIZE);
            if (hitsList.isEmpty()) {
                break;
            }
            List<IndexInfo> indexInfoList = new ArrayList<>();
            for (JsonNode hit : hitsList) {
                IndexInfo info = processSingleHit(hit, sourceToTargetKbIdMap,
                        sourceToTargetChunkIdMap, targetKnowledgeBaseId);
                if (info != null) {
                    indexInfoList.add(info);
                }
            }
            if (!indexInfoList.isEmpty()) {
                saveCopiedIndices(indexInfoList);
                totalCopied += indexInfoList.size();
            }
            from += hitsList.size();
            if (hitsList.size() < COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[ElasticsearchV7] Index copy completed, total copied: {}", totalCopied);
    }

    private JsonNode querySourceBatch(RetrieveParams retrieveParams, int from, int batchSize)
            throws Exception {
        JsonNode filter;
        try {
            filter = MAPPER.readTree(getBaseCondsJson(retrieveParams));
        } catch (Exception e) {
            filter = MAPPER.createObjectNode();
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", filter);
        body.put("from", from);
        body.put("size", batchSize);
        HttpResult resp = request("POST", "/" + index + "/_search", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to query source index data: elasticsearch"
                    + " returned " + resp.status() + ": " + resp.body());
        }
        JsonNode searchResult = MAPPER.readTree(resp.body());
        JsonNode hitsObj = searchResult.get("hits");
        if (hitsObj == null || !hitsObj.isObject()) {
            throw new IllegalStateException("invalid search result format");
        }
        JsonNode hitsList = hitsObj.get("hits");
        if (hitsList == null || !hitsList.isArray() || hitsList.isEmpty()) {
            if (from == 0) {
                log.warn("[ElasticsearchV7] No source index data found");
            }
            return MAPPER.createArrayNode();
        }
        return hitsList;
    }

    /** 对照 v7 {@code processSingleHit}：缺字段/映射缺失 → 返回 null（调用方跳过）。 */
    private IndexInfo processSingleHit(JsonNode hit, Map<String, String> sourceToTargetKbIdMap,
                                       Map<String, String> sourceToTargetChunkIdMap,
                                       String targetKnowledgeBaseId) {
        JsonNode sourceObj = hit.get("_source");
        if (sourceObj == null || !sourceObj.isObject()) {
            log.warn("[ElasticsearchV7] Hit missing _source field");
            return null;
        }
        if (!sourceObj.hasNonNull("chunk_id")) {
            log.warn("[ElasticsearchV7] Source index data missing chunk_id field");
            return null;
        }
        String sourceChunkId = sourceObj.path("chunk_id").asText();
        String targetChunkId = sourceToTargetChunkIdMap.get(sourceChunkId);
        if (targetChunkId == null) {
            log.warn("[ElasticsearchV7] Source chunk ID {} not found in mapping", sourceChunkId);
            return null;
        }
        if (!sourceObj.hasNonNull("knowledge_id")) {
            log.warn("[ElasticsearchV7] Source index data missing knowledge_id field");
            return null;
        }
        String sourceKnowledgeId = sourceObj.path("knowledge_id").asText();
        String targetKnowledgeId = sourceToTargetKbIdMap.get(sourceKnowledgeId);
        if (targetKnowledgeId == null) {
            log.warn("[ElasticsearchV7] Source knowledge ID {} not found in mapping",
                    sourceKnowledgeId);
            return null;
        }

        String content = sourceObj.path("content").asText("");
        String originalSourceId = sourceObj.path("source_id").asText("");
        int sourceType = sourceObj.path("source_type").asInt(0);
        // is_enabled 缺省 true（Go 的向后兼容）、is_recommended 缺省 false
        boolean isEnabled = !sourceObj.has("is_enabled") || sourceObj.path("is_enabled")
                .asBoolean(true);
        boolean isRecommended = sourceObj.path("is_recommended").asBoolean(false);
        String tagId = sourceObj.path("tag_id").asText("");

        String targetSourceId;
        if (originalSourceId.equals(sourceChunkId)) {
            targetSourceId = targetChunkId;
        } else if (originalSourceId.startsWith(sourceChunkId + "-")) {
            targetSourceId = targetChunkId + "-" + originalSourceId.substring(
                    sourceChunkId.length() + 1);
        } else {
            targetSourceId = UUID.randomUUID().toString();
        }

        IndexInfo info = new IndexInfo();
        info.chunkId = targetChunkId;
        info.sourceId = targetSourceId;
        info.knowledgeId = targetKnowledgeId;
        info.knowledgeBaseId = targetKnowledgeBaseId;
        info.content = content;
        info.sourceType = sourceType;
        info.isEnabled = isEnabled;
        info.isRecommended = isRecommended;
        info.tagId = tagId;
        return info;
    }

    /** 对照 v7 {@code saveCopiedIndices}：embeddingMap 新建空 → 向量不落（照抄 Go 缺陷）。 */
    private void saveCopiedIndices(List<IndexInfo> indexInfoList) throws Exception {
        if (indexInfoList.isEmpty()) {
            log.info("[ElasticsearchV7] No indices to save, skipping");
            return;
        }
        Map<String, Object> additionalParams = new LinkedHashMap<>();
        Map<String, float[]> embeddingMap = new LinkedHashMap<>();
        if (!embeddingMap.isEmpty()) {
            additionalParams.put("embedding", embeddingMap);
        }
        batchSave(indexInfoList, additionalParams);
        log.info("[ElasticsearchV7] Successfully saved {} indices", indexInfoList.size());
    }

    // ── 迁移知识 ────────────────────────────────────────────────────────────

    /**
     * 对照 {@code v7/move.go}：{@code bool.filter} 用 <b>singular {@code term}</b> + 字符串值
     * （v8 是 {@code terms} 数组）；脚本<b>带 lang</b>；{@code ?refresh=true}；完整性校验同 v8。
     */
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId)
            throws Exception {
        ObjectNode boolBody = MAPPER.createObjectNode();
        ArrayNode filterArray = boolBody.putArray("filter");
        filterArray.add(termOnly(idField("knowledge_base_id"), sourceKb));
        filterArray.add(termOnly(idField("knowledge_id"), knowledgeId));
        ObjectNode bool = MAPPER.createObjectNode();
        bool.set("bool", boolBody);

        ObjectNode script = MAPPER.createObjectNode();
        script.put("lang", "painless");
        script.put("source",
                "ctx._source.knowledge_base_id = params.target; ctx._source.tag_id = ''");
        script.putObject("params").put("target", targetKb);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", bool);
        body.set("script", script);

        HttpResult resp = request("POST",
                "/" + index + "/_update_by_query?refresh=true", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("move indices: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        JsonNode result = MAPPER.readTree(resp.body());
        JsonNode total = result.get("total");
        JsonNode updated = result.get("updated");
        boolean incomplete = total == null || total.isNull() || updated == null
                || updated.isNull() || total.asLong() < 0 || total.asLong() != updated.asLong()
                || result.path("timed_out").asBoolean(false)
                || result.path("version_conflicts").asInt(0) != 0
                || (result.has("failures") && !result.path("failures").isEmpty());
        if (incomplete) {
            throw new IllegalStateException("move indices was incomplete");
        }
    }

    private static ObjectNode termOnly(String field, String value) {
        return MAPPER.createObjectNode().set("term",
                MAPPER.createObjectNode().put(field, value));
    }

    // ── 批量改状态 / 标签（不套 bool，照 v7） ───────────────────────────────

    /** 对照 v7 {@code BatchUpdateChunkEnabledStatus}：query 是直构 terms（无 bool 包裹）。 */
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[ElasticsearchV7] Chunk status map is empty, skipping update");
            return;
        }
        List<String> enabled = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabled.add(entry.getKey());
            } else {
                disabled.add(entry.getKey());
            }
        }
        if (!enabled.isEmpty()) {
            updateByQuery(enabled, "ctx._source.is_enabled = true", null);
        }
        if (!disabled.isEmpty()) {
            updateByQuery(disabled, "ctx._source.is_enabled = false", null);
        }
        log.info("[ElasticsearchV7] Successfully batch updated chunk enabled status");
    }

    /** 对照 v7 {@code BatchUpdateChunkTagID}：按 tag 分组，脚本带 params.tag_id。 */
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[ElasticsearchV7] Chunk tag map is empty, skipping update");
            return;
        }
        Map<String, List<String>> tagGroups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            tagGroups.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<String>> group : tagGroups.entrySet()) {
            updateByQuery(group.getValue(), "ctx._source.tag_id = params.tag_id",
                    group.getKey());
        }
        log.info("[ElasticsearchV7] Successfully batch updated chunk tag ID");
    }

    /** {@code _update_by_query}：body = {query:{terms:{...}}, script:{source,lang[,params]}}。 */
    private void updateByQuery(List<String> chunkIds, String scriptSource, String tagId)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", termsOnly(idField("chunk_id"), chunkIds));
        ObjectNode script = MAPPER.createObjectNode();
        script.put("source", scriptSource);
        script.put("lang", "painless");
        if (tagId != null) {
            script.putObject("params").put("tag_id", tagId);
        }
        body.set("script", script);

        HttpResult resp = request("POST", "/" + index + "/_update_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException(
                    "elasticsearch update_by_query failed with status: " + resp.status());
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    record HttpResult(int status, String body) {
    }

    private HttpResult request(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(addr + path))
                .timeout(Duration.ofSeconds(60));
        if (!username.isEmpty() || !password.isEmpty()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody,
                    StandardCharsets.UTF_8));
        }
        HttpResponse<byte[]> resp = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = resp.body() == null ? new byte[0] : resp.body();
        return new HttpResult(resp.statusCode(), new String(bytes, StandardCharsets.UTF_8));
    }

}
