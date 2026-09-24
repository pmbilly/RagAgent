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
 * Elasticsearch v8 检索引擎仓库——对照 Go
 * {@code internal/application/repository/retriever/elasticsearch/v8/repository.go}（820 行）
 * 与 {@code elasticsearch/structs.go}（文档结构与双向转换）。
 *
 * <h2>照抄点</h2>
 * <ul>
 *   <li>构造即自举：{@code HEAD /{index}} 不存在则 {@code PUT /{index}}（仅在
 *       {@code numberOfShards > 0 || numberOfReplicas >= 0} 时带 settings，值转字符串——
 *       照 Go 的 {@code fmt.Sprintf("%d", ...)}），随后 {@code GET /{index}/_mapping}
 *       探测 {@code chunk_id} 是否为 {@code keyword}：是 → 查询不加后缀，否/缺失/出错 →
 *       加 {@code .keyword} 后缀（{@code idField} 全查询统一走它）；索引名解析优先级
 *       {@code indexName > ELASTICSEARCH_INDEX env > xwrag_default}（照 {@code ResolveIndexName}）；
 *       连接配置里 shards 默认 0、replicas 默认 -1（照 {@code GetNumberOfShards/Replicas}）</li>
 *   <li>存储估算 = 内容字节 + 维度×4 + 250 固定开销 + (内容+向量)×0.5（照
 *       {@code calculateStorageSize} 的整数算式 {@code (c+v)*5/10}）</li>
 *   <li>写入：单条 {@code POST /{index}/_doc}（**空向量直接报错** "empty embedding vector
 *       for chunk ID: X"）；批量 {@code POST /{index}/_bulk}，每行
 *       {@code {"create":{"_index":"<index>"}}} + 文档，**空列表直接跳过**（告警不报错）</li>
 *   <li>删除：{@code _delete_by_query} + {@code terms}（chunk_id/source_id/knowledge_id 走
 *       {@code idField}），空列表跳过</li>
 *   <li>检索：向量 = {@code script_score}（{@code cosineSimilarity(params.query_vector,
 *       'embedding')} + {@code min_score} = float32(threshold)，外层 bool.filter = 基础条件，
 *       {@code size}=topK，{@code _source.excludes=["embedding"]}）；关键词 = 外层
 *       bool{filter=基础条件, must=[{match:{content:{query}}}]}；两者命中的
 *       {@code _source} 反序列化为 {@link VectorEmbedding} 并取 {@code _score}；
 *       返回单元素 {@link RetrieveResult} 列表（结果 + "elasticsearch" + 检索类型）</li>
 *   <li>基础条件（{@code getBaseConds}）：must = kbIDs → knowledgeIDs → tagIDs（AND 语义）；
 *       must_not = **{@code is_enabled:false} 恒在**（历史数据无该字段者不被排除）→
 *       excludeKnowledgeIDs → excludeChunkIDs</li>
 *   <li>改状态：{@code _update_by_query} + painless（{@code ctx._source.is_enabled = true|false}，
 *       按值分组两次）；改标签：按 tagID 分组，脚本
 *       {@code ctx._source.tag_id = params.tag_id} + {@code params.tag_id}（照 Go 的
 *       {@code json.RawMessage(`"`+tagID+`"`)}）</li>
 *   <li>复制索引（{@code CopyIndices}）：按源 kb 分页（from/size，批 500）检索 → 逐条按映射
 *       换 chunk/knowledge id → **SourceID 三态变换**（=chunkID 的普通块用目标 chunkID；
 *       {@code <chunkID>-<questionID>} 的生成问题保留 questionID 段；其余生成新 UUID）→
 *       收集目标 chunk → 源向量的映射 → 交 {@code BatchSave}（additionalParams.embedding）；
 *       取回页数 < 批大小即结束</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>Go 的 ES typed client 把请求体结构体直序列化；Java 用 Jackson 手搭同等 JSON
 *       （键序按本仓惯例与 Go 声明的字段序一致；ES 不敏感键序）</li>
 *   <li>Go 侧由 {@code engine_factory.go} 建 client（含 SSRF RoundTripper）；Java 在这里
 *       做等价的地址 SSRF 校验（guard 可空 = 测试口）+ Basic Auth</li>
 *   <li>本类为<b>驱动层</b>：factory/注册表（{@code engine_factory.go} 的等价物）与
 *       {@code NewKVHybridRetrieveEngine} 包装层未在本批——待接线批统一处理</li>
 * </ul>
 */
public class ElasticsearchV8RetrieveRepository {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchV8RetrieveRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 {@code ResolveIndexName(indexCfg, "ELASTICSEARCH_INDEX", "xwrag_default")}。 */
    public static final String ENV_INDEX_KEY = "ELASTICSEARCH_INDEX";
    public static final String DEFAULT_INDEX = "xwrag_default";
    /** 照 Go {@code batchSize := 500}（CopyIndices 分页）。 */
    static final int COPY_BATCH_SIZE = 500;

    /** 对照 {@code elasticsearch/structs.go} 的 {@code VectorEmbedding}（文档结构）。 */
    public static final class VectorEmbedding {
        public String content = "";
        public String sourceId = "";
        public int sourceType;
        public String chunkId = "";
        public String knowledgeId = "";
        public String knowledgeBaseId = "";
        public String tagId = "";
        public float[] embedding;
        public boolean isEnabled;
        public boolean isRecommended;
        /** 检索命中时回填（照 {@code VectorEmbeddingWithScore.Score}，非文档字段）。 */
        public double score;
    }

    private final String addr;
    private final String index;
    private final int numberOfShards;
    private final int numberOfReplicas;
    private final String username;
    private final String password;
    private final HttpClient http;
    private volatile boolean useKeywordSuffix;

    public ElasticsearchV8RetrieveRepository(String addr, String indexName, int numberOfShards,
                                             int numberOfReplicas, String username,
                                             String password, SsrfGuard ssrfGuard) {
        this(addr, indexName, numberOfShards, numberOfReplicas, username, password, ssrfGuard,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public ElasticsearchV8RetrieveRepository(String addr, String indexName, int numberOfShards,
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
        // 照 engine_factory.go：地址先过 SSRF 校验（guard 为空 = 测试口，跳过）
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(address);
        }
        this.addr = address;
        this.index = resolveIndexName(indexName);
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.http = http;

        // 照 NewElasticsearchEngineRepository：建索引（失败只记日志）+ 探测字段类型
        try {
            createIndexIfNotExists();
        } catch (Exception e) {
            log.error("[Elasticsearch] Failed to create index: {}", e.toString());
        }
        detectFieldTypes();
    }

    /** 对照 {@code types.ResolveIndexName}：indexName > env > default（共享助手）。 */
    static String resolveIndexName(String indexName) {
        return EngineTypes.resolveIndexName(indexName, ENV_INDEX_KEY, DEFAULT_INDEX);
    }

    /** 对照 {@code idField}：text 映射时 ID 字段要带 .keyword 后缀。 */
    String idField(String name) {
        return useKeywordSuffix ? name + ".keyword" : name;
    }

    /** 供测试观察。 */
    boolean useKeywordSuffix() {
        return useKeywordSuffix;
    }

    /** 对照 {@code EngineType}。 */
    public String engineType() {
        return EngineTypes.ENGINE_ELASTICSEARCH;
    }

    /** 对照 {@code Support}：关键词 + 向量。 */
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    // ── 索引自举 ────────────────────────────────────────────────────────────

    /** 对照 {@code createIndexIfNotExists}。 */
    void createIndexIfNotExists() throws Exception {
        HttpResult exists = request("HEAD", "/" + index, null);
        if (exists.status() == 200) {
            log.debug("[Elasticsearch] Index already exists: {}", index);
            return;
        }
        if (exists.status() != 404) {
            throw new IllegalStateException("elasticsearch HEAD /" + index + " returned "
                    + exists.status() + ": " + exists.body());
        }
        ObjectNode body = MAPPER.createObjectNode();
        if (numberOfShards > 0 || numberOfReplicas >= 0) {
            ObjectNode settings = MAPPER.createObjectNode();
            if (numberOfShards > 0) {
                settings.put("number_of_shards", String.valueOf(numberOfShards));
            }
            if (numberOfReplicas >= 0) {
                settings.put("number_of_replicas", String.valueOf(numberOfReplicas));
            }
            body.set("settings", settings);
        }
        HttpResult created = request("PUT", "/" + index, body.toString());
        if (created.status() < 200 || created.status() >= 300) {
            throw new IllegalStateException("elasticsearch create index returned "
                    + created.status() + ": " + created.body());
        }
        log.info("[Elasticsearch] Index created successfully: {}", index);
    }

    /** 对照 {@code detectFieldTypes}：chunk_id 是 keyword → 不加后缀，否则加（含出错）。 */
    void detectFieldTypes() {
        try {
            HttpResult resp = request("GET", "/" + index + "/_mapping", null);
            if (resp.status() != 200) {
                log.warn("[Elasticsearch] Failed to get index mapping, defaulting to .keyword"
                        + " suffix: status={}", resp.status());
                useKeywordSuffix = true;
                return;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode indexNode = root.path(index);
            if (indexNode.isMissingNode()) {
                log.warn("[Elasticsearch] Index {} not found in mapping response, defaulting to"
                        + " .keyword suffix", index);
                useKeywordSuffix = true;
                return;
            }
            JsonNode chunkId = indexNode.path("mappings").path("properties").path("chunk_id");
            if (!chunkId.isMissingNode() && "keyword".equals(chunkId.path("type").asText())) {
                useKeywordSuffix = false;
                log.info("[Elasticsearch] Detected keyword type for ID fields, querying without"
                        + " .keyword suffix");
                return;
            }
            if (!chunkId.isMissingNode()) {
                useKeywordSuffix = true;
                log.info("[Elasticsearch] ID fields are not keyword type, querying with .keyword"
                        + " suffix");
                return;
            }
            useKeywordSuffix = true;
            log.info("[Elasticsearch] No mapping detected for chunk_id (empty index?),"
                    + " defaulting to .keyword suffix");
        } catch (Exception e) {
            log.warn("[Elasticsearch] Failed to get index mapping, defaulting to .keyword"
                    + " suffix: {}", e.toString());
            useKeywordSuffix = true;
        }
    }

    // ── 存储估算 ────────────────────────────────────────────────────────────

    /** 对照 {@code calculateStorageSize}。 */
    static long calculateStorageSize(VectorEmbedding embedding) {
        long contentSizeBytes = embedding.content == null ? 0
                : embedding.content.getBytes(StandardCharsets.UTF_8).length;
        long vectorSizeBytes = embedding.embedding == null ? 0 : (long) embedding.embedding.length * 4;
        long metadataSizeBytes = 250L;
        long indexOverheadBytes = (contentSizeBytes + vectorSizeBytes) * 5 / 10;
        return contentSizeBytes + vectorSizeBytes + metadataSizeBytes + indexOverheadBytes;
    }

    /** 对照 {@code EstimateStorageSize}。 */
    public long estimateStorageSize(List<IndexInfo> indexInfoList,
                                    Map<String, Object> params) {
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            total += calculateStorageSize(toDbVectorEmbedding(info, params));
        }
        log.info("[Elasticsearch] Storage size for {} indices: {} bytes", indexInfoList.size(),
                total);
        return total;
    }

    // ── 文档转换（照 elasticsearch/structs.go） ──────────────────────────────

    /** 对照 {@code ToDBVectorEmbedding}。 */
    @SuppressWarnings("unchecked")
    static VectorEmbedding toDbVectorEmbedding(IndexInfo info, Map<String, Object> additionalParams) {
        VectorEmbedding vector = new VectorEmbedding();
        vector.content = info.content;
        vector.sourceId = info.sourceId;
        vector.sourceType = info.sourceType;
        vector.chunkId = info.chunkId;
        vector.knowledgeId = info.knowledgeId;
        vector.knowledgeBaseId = info.knowledgeBaseId;
        vector.tagId = info.tagId;
        vector.isEnabled = info.isEnabled;
        vector.isRecommended = info.isRecommended;
        if (additionalParams != null && additionalParams.containsKey("embedding")
                && additionalParams.get("embedding") instanceof Map<?, ?> embeddingMap) {
            Object value = ((Map<String, Object>) embeddingMap).get(info.sourceId);
            if (value instanceof float[] floats) {
                vector.embedding = floats;
            }
        }
        if (additionalParams != null && additionalParams.get("chunk_enabled") instanceof Map<?, ?> map) {
            Object enabled = ((Map<String, Object>) map).get(info.chunkId);
            if (enabled instanceof Boolean b) {
                vector.isEnabled = b;
            }
        }
        return vector;
    }

    /** 对照 {@code FromDBVectorEmbeddingWithScore}。 */
    static IndexWithScore fromDbVectorEmbeddingWithScore(String id, VectorEmbedding embedding,
                                                         int matchType) {
        IndexWithScore out = new IndexWithScore();
        out.id = id;
        out.sourceId = embedding.sourceId;
        out.sourceType = embedding.sourceType;
        out.chunkId = embedding.chunkId;
        out.knowledgeId = embedding.knowledgeId;
        out.knowledgeBaseId = embedding.knowledgeBaseId;
        out.tagId = embedding.tagId;
        out.content = embedding.content;
        out.score = embedding.score;
        out.matchType = matchType;
        out.isEnabled = embedding.isEnabled;
        return out;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** 对照 {@code Save}。 */
    public void save(IndexInfo embedding, Map<String, Object> additionalParams) throws Exception {
        VectorEmbedding doc = toDbVectorEmbedding(embedding, additionalParams);
        if (doc.embedding == null || doc.embedding.length == 0) {
            throw new IllegalStateException(
                    "empty embedding vector for chunk ID: " + embedding.chunkId);
        }
        HttpResult resp = request("POST", "/" + index + "/_doc", docJson(doc));
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("elasticsearch index document returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    /** 对照 {@code BatchSave}：bulk NDJSON，create 语义。 */
    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> additionalParams)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Elasticsearch] Empty list provided to BatchSave, skipping");
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (IndexInfo embedding : embeddingList) {
            VectorEmbedding doc = toDbVectorEmbedding(embedding, additionalParams);
            ObjectNode action = MAPPER.createObjectNode();
            action.set("create", MAPPER.createObjectNode().put("_index", index));
            ndjson.append(action).append('\n').append(docJson(doc)).append('\n');
        }
        HttpResult resp = requestRaw("POST", "/" + index + "/_bulk", ndjson.toString(),
                "application/x-ndjson");
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to do bulk: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        log.info("[Elasticsearch] Successfully batch saved {} indices", embeddingList.size());
    }

    /** 文档 JSON（键序与 Go struct 声明一致，snake_case）。 */
    static String docJson(VectorEmbedding doc) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", doc.content);
        node.put("source_id", doc.sourceId);
        node.put("source_type", doc.sourceType);
        node.put("chunk_id", doc.chunkId);
        node.put("knowledge_id", doc.knowledgeId);
        node.put("knowledge_base_id", doc.knowledgeBaseId);
        node.put("tag_id", doc.tagId);
        if (doc.embedding != null) {
            ArrayNode vector = node.putArray("embedding");
            for (float v : doc.embedding) {
                vector.add(v);
            }
        } else {
            node.putNull("embedding");
        }
        node.put("is_enabled", doc.isEnabled);
        node.put("is_recommended", doc.isRecommended);
        return node.toString();
    }

    /** 对照 {@code DeleteByChunkIDList}。 */
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByTerms("chunk_id", chunkIdList);
    }

    /** 对照 {@code DeleteBySourceIDList}。 */
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByTerms("source_id", sourceIdList);
    }

    /** 对照 {@code DeleteByKnowledgeIDList}。 */
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByTerms("knowledge_id", knowledgeIdList);
    }

    private void deleteByTerms(String field, List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Elasticsearch] Empty {} list provided for deletion, skipping", field);
            return;
        }
        ObjectNode query = termsQuery(idField(field), ids);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", query);
        HttpResult resp = request("POST", "/" + index + "/_delete_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to delete by query: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    /** {@code {"terms":{field:[...]}}}（照 types.TermsQuery）。 */
    private static ObjectNode termsQuery(String field, List<String> values) {
        ObjectNode terms = MAPPER.createObjectNode();
        ArrayNode array = terms.putArray(field);
        for (String value : values) {
            array.add(value);
        }
        ObjectNode query = MAPPER.createObjectNode();
        query.set("terms", terms);
        return query;
    }

    // ── 基础条件 ────────────────────────────────────────────────────────────

    /**
     * 对照 {@code getBaseConds}：返回 {@code [{"bool":{"must":[...],"must_not":[...]}}]}；
     * 空 must/must_not 不写出（照 typedapi 的 omitempty）。
     */
    List<ObjectNode> getBaseConds(RetrieveParams params) {
        ArrayNode must = MAPPER.createArrayNode();
        if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
            must.add(termsQuery(idField("knowledge_base_id"), params.knowledgeBaseIds));
        }
        if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
            must.add(termsQuery(idField("knowledge_id"), params.knowledgeIds));
        }
        if (params.tagIds != null && !params.tagIds.isEmpty()) {
            must.add(termsQuery(idField("tag_id"), params.tagIds));
        }

        ArrayNode mustNot = MAPPER.createArrayNode();
        // 恒排除 is_enabled=false（历史数据无该字段 → 不被排除）
        ObjectNode term = MAPPER.createObjectNode();
        term.set("term", MAPPER.createObjectNode().set("is_enabled",
                MAPPER.createObjectNode().put("value", false)));
        mustNot.add(term);
        if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
            mustNot.add(termsQuery(idField("knowledge_id"), params.excludeKnowledgeIds));
        }
        if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
            mustNot.add(termsQuery(idField("chunk_id"), params.excludeChunkIds));
        }

        ObjectNode bool = MAPPER.createObjectNode();
        if (!must.isEmpty()) {
            bool.set("must", must);
        }
        if (!mustNot.isEmpty()) {
            bool.set("must_not", mustNot);
        }
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("bool", bool);
        return List.of(wrapper);
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** 对照 {@code Retrieve}：按检索类型分派。 */
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        if (EngineTypes.RETRIEVER_VECTOR.equals(params.retrieverType)) {
            return vectorRetrieve(params);
        }
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(params.retrieverType)) {
            return keywordsRetrieve(params);
        }
        throw new IllegalArgumentException("invalid retriever type: " + params.retrieverType);
    }

    /** 对照 {@code VectorRetrieve}：script_score + cosineSimilarity + min_score。 */
    public List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws Exception {
        List<ObjectNode> filter = getBaseConds(params);

        ObjectNode script = MAPPER.createObjectNode();
        script.put("source", "cosineSimilarity(params.query_vector, 'embedding')");
        ArrayNode vector = script.putObject("params").putArray("query_vector");
        if (params.embedding != null) {
            for (float v : params.embedding) {
                vector.add(v);
            }
        }

        ObjectNode boolQuery = MAPPER.createObjectNode();
        ArrayNode filterArray = boolQuery.putArray("filter");
        filter.forEach(filterArray::add);

        ObjectNode scriptScore = MAPPER.createObjectNode();
        scriptScore.putObject("query").set("bool", boolQuery);
        scriptScore.set("script", script);
        scriptScore.put("min_score", (float) params.threshold);

        ObjectNode query = MAPPER.createObjectNode();
        query.set("script_score", scriptScore);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", query);
        body.put("size", params.topK);
        body.putObject("_source").putArray("excludes").add("embedding");

        HttpResult resp = request("POST", "/" + index + "/_search", body.toString());
        return List.of(parseSearchResponse(resp, "vector"));
    }

    /** 对照 {@code KeywordsRetrieve}：bool{filter, must:[match content]}。 */
    public List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws Exception {
        List<ObjectNode> filter = getBaseConds(params);

        ObjectNode match = MAPPER.createObjectNode();
        match.putObject("match").putObject("content").put("query", params.query);

        ObjectNode bool = MAPPER.createObjectNode();
        ArrayNode filterArray = bool.putArray("filter");
        filter.forEach(filterArray::add);
        ArrayNode must = bool.putArray("must");
        must.add(match);

        ObjectNode query = MAPPER.createObjectNode();
        query.set("bool", bool);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", query);
        body.put("size", params.topK);
        body.putObject("_source").putArray("excludes").add("embedding");

        HttpResult resp = request("POST", "/" + index + "/_search", body.toString());
        return List.of(parseSearchResponse(resp, "keywords"));
    }

    private RetrieveResult parseSearchResponse(HttpResult resp, String retrieverType)
            throws Exception {
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("elasticsearch search returned " + resp.status()
                    + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        List<IndexWithScore> results = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            VectorEmbedding embedding = parseSource(hit.path("_source"));
            embedding.score = hit.path("_score").asDouble(0);
            results.add(fromDbVectorEmbeddingWithScore(hit.path("_id").asText(""), embedding,
                    EngineTypes.RETRIEVER_VECTOR.equals(retrieverType)
                            ? EngineTypes.MATCH_EMBEDDING : EngineTypes.MATCH_KEYWORDS));
        }
        return new RetrieveResult(results, EngineTypes.ENGINE_ELASTICSEARCH, retrieverType);
    }

    /** {@code _source} → 文档（字段缺失按零值，照 Go 的 json.Unmarshal 语义）。 */
    static VectorEmbedding parseSource(JsonNode source) {
        VectorEmbedding doc = new VectorEmbedding();
        doc.content = source.path("content").asText("");
        doc.sourceId = source.path("source_id").asText("");
        doc.sourceType = source.path("source_type").asInt(0);
        doc.chunkId = source.path("chunk_id").asText("");
        doc.knowledgeId = source.path("knowledge_id").asText("");
        doc.knowledgeBaseId = source.path("knowledge_base_id").asText("");
        doc.tagId = source.path("tag_id").asText("");
        JsonNode vector = source.path("embedding");
        if (vector.isArray() && !vector.isEmpty()) {
            float[] floats = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                floats[i] = (float) vector.get(i).asDouble();
            }
            doc.embedding = floats;
        }
        doc.isEnabled = source.path("is_enabled").asBoolean(false);
        doc.isRecommended = source.path("is_recommended").asBoolean(false);
        return doc;
    }

    // ── 复制索引 ────────────────────────────────────────────────────────────

    /** 对照 {@code CopyIndices}（分页 + 映射改名 + SourceID 三态 + 目标向量回填）。 */
    public void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Elasticsearch] Empty mapping, skipping copy");
            return;
        }
        RetrieveParams params = new RetrieveParams();
        params.knowledgeBaseIds = List.of(sourceKnowledgeBaseId);
        List<ObjectNode> filter = getBaseConds(params);

        int from = 0;
        int totalCopied = 0;
        while (true) {
            ObjectNode bool = MAPPER.createObjectNode();
            ArrayNode filterArray = bool.putArray("filter");
            filter.forEach(filterArray::add);
            ObjectNode query = MAPPER.createObjectNode();
            query.set("bool", bool);

            ObjectNode body = MAPPER.createObjectNode();
            body.set("query", query);
            body.put("from", from);
            body.put("size", COPY_BATCH_SIZE);

            HttpResult resp = request("POST", "/" + index + "/_search", body.toString());
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new IllegalStateException("elasticsearch search returned " + resp.status()
                        + ": " + resp.body());
            }
            JsonNode hits = MAPPER.readTree(resp.body()).path("hits").path("hits");
            int hitsCount = hits.size();
            if (hitsCount == 0) {
                break;
            }

            List<IndexInfo> indexInfoList = new ArrayList<>();
            Map<String, float[]> embeddingMap = new LinkedHashMap<>();
            for (JsonNode hit : hits) {
                VectorEmbedding sourceDoc = parseSource(hit.path("_source"));
                String targetChunkId = sourceToTargetChunkIdMap.get(sourceDoc.chunkId);
                if (targetChunkId == null) {
                    log.warn("[Elasticsearch] Source chunk {} not found in target mapping,"
                            + " skipping", sourceDoc.chunkId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap.get(sourceDoc.knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[Elasticsearch] Source knowledge {} not found in target mapping,"
                            + " skipping", sourceDoc.knowledgeId);
                    continue;
                }
                String targetSourceId;
                if (sourceDoc.sourceId.equals(sourceDoc.chunkId)) {
                    targetSourceId = targetChunkId;
                } else if (sourceDoc.sourceId.startsWith(sourceDoc.chunkId + "-")) {
                    String questionId = sourceDoc.sourceId.substring(sourceDoc.chunkId.length() + 1);
                    targetSourceId = targetChunkId + "-" + questionId;
                } else {
                    targetSourceId = UUID.randomUUID().toString();
                }
                if (sourceDoc.embedding != null && sourceDoc.embedding.length > 0) {
                    // 修复（有意偏离 Go v8）：Go 以"目标 chunkID"为键、而查表用的是 SourceID →
                    // 生成问题（<chunk>-<qid> 形态）取不到向量、同 chunk 多文档互相覆盖；
                    // 这里改键为目标 SourceID（逐文档唯一），toDbVectorEmbedding 按 SourceID 查表即命中
                    embeddingMap.put(targetSourceId, sourceDoc.embedding);
                }

                IndexInfo info = new IndexInfo();
                info.content = sourceDoc.content;
                info.sourceId = targetSourceId;
                info.sourceType = sourceDoc.sourceType;
                info.chunkId = targetChunkId;
                info.knowledgeId = targetKnowledgeId;
                info.knowledgeBaseId = targetKnowledgeBaseId;
                indexInfoList.add(info);
                totalCopied++;
            }

            if (!indexInfoList.isEmpty()) {
                Map<String, Object> additionalParams = new LinkedHashMap<>();
                additionalParams.put("embedding", embeddingMap);
                batchSave(indexInfoList, additionalParams);
            }

            from += hitsCount;
            if (hitsCount < COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[Elasticsearch] Index copy completed, total copied: {}", totalCopied);
    }

    // ── 迁移知识 ────────────────────────────────────────────────────────────

    /**
     * 对照 {@code v8/move.go} 的 {@code MoveKnowledgeIndices}：把某知识的所有分块挪到目标
     * kb 并清空 tag；查询是 {@code bool.filter = [terms(kb), terms(knowledge)]}（**terms** 数组），
     * 脚本**不带 lang**（照 Go 只有 source + params，ES 默认 painless）；带
     * {@code ?refresh=true}；响应必须"完全成功"——total/updated 都在、total ≥ 0、
     * total == updated、未 timed_out、version_conflicts == 0、failures 为空，
     * 否则报 {@code move indices was incomplete}（照 Go）。
     */
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId)
            throws Exception {
        ObjectNode filterBody = MAPPER.createObjectNode();
        ArrayNode filterArray = filterBody.putArray("filter");
        filterArray.add(termsQuery(idField("knowledge_base_id"), List.of(sourceKb)));
        filterArray.add(termsQuery(idField("knowledge_id"), List.of(knowledgeId)));
        ObjectNode bool = MAPPER.createObjectNode();
        bool.set("bool", filterBody);
        ObjectNode query = MAPPER.createObjectNode();
        query.set("query", bool);

        ObjectNode script = MAPPER.createObjectNode();
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

    // ── 批量改状态 / 标签 ───────────────────────────────────────────────────

    /** 对照 {@code BatchUpdateChunkEnabledStatus}：按值分两组 update_by_query。 */
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Elasticsearch] Chunk status map is empty, skipping update");
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
    }

    /** 对照 {@code BatchUpdateChunkTagID}：按 tagID 分组逐组 update_by_query。 */
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Elasticsearch] Chunk tag map is empty, skipping update");
            return;
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            groups.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            updateByQuery(group.getValue(), "ctx._source.tag_id = params.tag_id",
                    group.getKey());
        }
    }

    /** {@code _update_by_query}：query = bool.must[terms chunk_id]；脚本 painless。 */
    private void updateByQuery(List<String> chunkIds, String scriptSource, String tagId)
            throws Exception {
        ObjectNode boolBody = MAPPER.createObjectNode();
        ArrayNode mustArray = boolBody.putArray("must");
        mustArray.add(termsQuery(idField("chunk_id"), chunkIds));
        ObjectNode bool = MAPPER.createObjectNode();
        bool.set("bool", boolBody);

        ObjectNode script = MAPPER.createObjectNode();
        script.put("source", scriptSource);
        script.put("lang", "painless");
        if (tagId != null) {
            script.putObject("params").put("tag_id", tagId);
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", bool);
        body.set("script", script);

        HttpResult resp = request("POST", "/" + index + "/_update_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("elasticsearch update_by_query returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    /** 一次 HTTP 往返（状态 + 正文）。 */
    record HttpResult(int status, String body) {
    }

    private HttpResult request(String method, String path, String jsonBody) throws Exception {
        return requestRaw(method, path, jsonBody,
                jsonBody == null ? null : "application/json");
    }

    private HttpResult requestRaw(String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(addr + path))
                .timeout(Duration.ofSeconds(60));
        if (!username.isEmpty() || !password.isEmpty()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body,
                    StandardCharsets.UTF_8));
        }
        HttpResponse<byte[]> resp = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = resp.body() == null ? new byte[0] : resp.body();
        return new HttpResult(resp.statusCode(), new String(bytes, StandardCharsets.UTF_8));
    }

}
