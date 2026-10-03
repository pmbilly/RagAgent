package com.ragagent.retrieval.engine.elasticsearch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * Elasticsearch v8 检索引擎仓库（文档结构与双向转换也在本类）。
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>构造即自举：{@code HEAD /{index}} 不存在则 {@code PUT /{index}}（仅在
 *       {@code numberOfShards > 0 || numberOfReplicas >= 0} 时带 settings，值转十进制字符串），
 *       随后 {@code GET /{index}/_mapping}
 *       探测 {@code chunk_id} 是否为 {@code keyword}：是 → 查询不加后缀，否/缺失/出错 →
 *       加 {@code .keyword} 后缀（{@code idField} 全查询统一走它）；索引名解析优先级
 *       {@code indexName > ELASTICSEARCH_INDEX env > xwrag_default}；
 *       连接配置里 shards 默认 0、replicas 默认 -1</li>
 *   <li>存储估算 = 内容字节 + 维度×4 + 250 固定开销 + (内容+向量)×0.5
 *       （整数算式 {@code (c+v)*5/10}）</li>
 *   <li>写入：单条 {@code POST /{index}/_doc}（**空向量直接报错** "empty embedding vector
 *       for chunk ID: X"）；批量 {@code POST /{index}/_bulk}，每行
 *       {@code {"create":{"_index":"<index>"}}} + 文档，**空列表直接跳过**（告警不报错）</li>
 *   <li>删除：{@code _delete_by_query} + {@code terms}（chunk_id/source_id/knowledge_id 走
 *       {@code idField}），空列表跳过</li>
 *   <li>检索：向量 = {@code script_score}（{@code cosineSimilarity(params.query_vector,
 *       'embedding')} + {@code min_score} = float32(threshold)，外层 bool.filter = 基础条件，
 *       {@code size}=topK，{@code _source.excludes=["embedding"]}）；关键词 = 外层
 *       bool{filter=基础条件, must=[{match:{content:{query}}}]}; 两者命中的
 *       {@code _source} 反序列化为 {@link VectorEmbedding} 并取 {@code _score}；
 *       返回单元素 {@link RetrieveResult} 列表（结果 + "elasticsearch" + 检索类型）</li>
 *   <li>基础条件（{@code getBaseConds}）：must = kbIDs → knowledgeIDs → tagIDs（AND 语义）；
 *       must_not = **{@code is_enabled:false} 恒在**（历史数据无该字段者不被排除）→
 *       excludeKnowledgeIDs → excludeChunkIDs</li>
 *   <li>改状态：{@code _update_by_query} + painless（{@code ctx._source.is_enabled = true|false}，
 *       按值分组两次）；改标签：按 tagID 分组，脚本
 *       {@code ctx._source.tag_id = params.tag_id} + {@code params.tag_id}
 *       （tagID 参数为 JSON 字符串值）</li>
 *   <li>复制索引（{@code CopyIndices}）：按源 kb 分页（from/size，批 500）检索 → 逐条按映射
 *       换 chunk/knowledge id → **SourceID 三态变换**（=chunkID 的普通块用目标 chunkID；
 *       {@code <chunkID>-<questionID>} 的生成问题保留 questionID 段；其余生成新 UUID）→
 *       收集目标 chunk → 源向量的映射 → 交 {@code BatchSave}（additionalParams.embedding）；
 *       取回页数 < 批大小即结束</li>
 * </ul>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li>请求体用 Jackson 手搭 JSON（键序 snake_case 固定；ES 不敏感键序）</li>
 *   <li>client 由本类自建：构造期做地址 SSRF 校验（guard 可空 = 测试口）+ Basic Auth</li>
 *   <li>本类为<b>驱动层</b>；引擎工厂/注册表见 {@code EngineFactory}/{@code EngineRegistry}</li>
 * </ul>
 */
public class ElasticsearchV8RetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchV8RetrieveRepository.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 索引名环境键与缺省值：{@code indexName > env ELASTICSEARCH_INDEX > xwrag_default}。 */
    public static final String ENV_INDEX_KEY = "ELASTICSEARCH_INDEX";
    public static final String DEFAULT_INDEX = "xwrag_default";
    /** CopyIndices 分页批大小。 */
    static final int COPY_BATCH_SIZE = 500;

    /** ES 文档结构。 */
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
        /** 检索命中时回填（非文档字段）。 */
        public double score;
    }

    private final String addr;
    final String index;
    private final int numberOfShards;
    private final int numberOfReplicas;
    private final String username;
    private final String password;
    private final HttpClient http;
    private volatile boolean useKeywordSuffix;

    final ElasticsearchV8SearchOps searchOps;
    final ElasticsearchV8WriteOps writeOps;

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
        // 地址先过 SSRF 校验（guard 为空 = 测试口，跳过）
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

        // 建索引（失败只记日志）+ 探测字段类型
        try {
            createIndexIfNotExists();
        } catch (Exception e) {
            log.error("[Elasticsearch] Failed to create index: {}", e.toString());
        }
        detectFieldTypes();
        this.searchOps = new ElasticsearchV8SearchOps(this);
        this.writeOps = new ElasticsearchV8WriteOps(this);
    }

    /** 索引名解析：indexName > env > default（共享助手）。 */
    static String resolveIndexName(String indexName) {
        return EngineTypes.resolveIndexName(indexName, ENV_INDEX_KEY, DEFAULT_INDEX);
    }

    /** ID 字段名：text 映射时要带 .keyword 后缀。 */
    String idField(String name) {
        return useKeywordSuffix ? name + ".keyword" : name;
    }

    /** 供测试观察。 */
    boolean useKeywordSuffix() {
        return useKeywordSuffix;
    }

    /** 引擎类型常量。 */
    public String engineType() {
        return EngineTypes.ENGINE_ELASTICSEARCH;
    }

    /** 支持 keywords + 向量两路。 */
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    // ── 索引自举 ────────────────────────────────────────────────────────────

    /** 索引不存在则按缺省 settings 建。 */
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

    /** 字段类型探测：chunk_id 是 keyword → 不加后缀，否则加（含出错）。 */
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

    /** 单文档存储估算（整数算式，见类注释）。 */
    static long calculateStorageSize(VectorEmbedding embedding) {
        long contentSizeBytes = embedding.content == null ? 0
                : embedding.content.getBytes(StandardCharsets.UTF_8).length;
        long vectorSizeBytes = embedding.embedding == null ? 0 : (long) embedding.embedding.length * 4;
        long metadataSizeBytes = 250L;
        long indexOverheadBytes = (contentSizeBytes + vectorSizeBytes) * 5 / 10;
        return contentSizeBytes + vectorSizeBytes + metadataSizeBytes + indexOverheadBytes;
    }

    /** 存储估算。 */
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

    // ── 文档转换 ─────────────────────────────────────────────────────────────

    /** IndexInfo → 文档字段；embedding/chunk_enabled 经 additionalParams 映射注入。 */
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

    /** 文档 → 命中项（id/score/matchType 由调用方补齐）。 */
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


    /** 文档 JSON（键序 snake_case 固定）。 */
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

    /** terms 查询：{@code {"terms":{field:[...]}}}。 */
    static ObjectNode termsQuery(String field, List<String> values) {
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

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** {@code _source} → 文档（字段缺失按零值）。 */
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

    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return searchOps.retrieve(params);
    }

    public List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws Exception {
        return searchOps.vectorRetrieve(params);
    }

    public List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws Exception {
        return searchOps.keywordsRetrieve(params);
    }

    /** 检索簇搬移后的门面委托（copyIndices/写簇仍经此取基础条件）。 */
    List<ObjectNode> getBaseConds(RetrieveParams params) {
        return searchOps.getBaseConds(params);
    }



    public void save(IndexInfo embedding, Map<String, Object> additionalParams) throws Exception {
        writeOps.save(embedding, additionalParams);
    }

    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> additionalParams)
            throws Exception {
        writeOps.batchSave(embeddingList, additionalParams);
    }

    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteByChunkIdList(chunkIdList, dimension, knowledgeType);
    }

    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteBySourceIdList(sourceIdList, dimension, knowledgeType);
    }

    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        writeOps.deleteByKnowledgeIdList(knowledgeIdList, dimension, knowledgeType);
    }

    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        writeOps.batchUpdateChunkEnabledStatus(chunkStatusMap);
    }

    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        writeOps.batchUpdateChunkTagID(chunkTagMap);
    }

    public void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        writeOps.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
    }


    // ── 迁移知识 ────────────────────────────────────────────────────────────

    /**
     * 把某知识的所有分块挪到目标
     * kb 并清空 tag；查询是 {@code bool.filter = [terms(kb), terms(knowledge)]}（**terms** 数组），
     * 脚本**不带 lang**（只带 source + params，ES 默认 painless）；带
     * {@code ?refresh=true}；响应必须"完全成功"——total/updated 都在、total ≥ 0、
     * total == updated、未 timed_out、version_conflicts == 0、failures 为空，
     * 否则报 {@code move indices was incomplete}。
     */
    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
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


    // ── HTTP ────────────────────────────────────────────────────────────────

    /** 一次 HTTP 往返（状态 + 正文）。 */
    record HttpResult(int status, String body) {
    }

    HttpResult request(String method, String path, String jsonBody) throws Exception {
        return requestRaw(method, path, jsonBody,
                jsonBody == null ? null : "application/json");
    }

    HttpResult requestRaw(String method, String path, String body, String contentType)
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
