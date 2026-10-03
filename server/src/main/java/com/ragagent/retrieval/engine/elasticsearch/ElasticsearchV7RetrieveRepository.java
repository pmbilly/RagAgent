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
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * Elasticsearch <b>v7</b> 检索引擎仓库（文档结构与双向转换与 v8 共用）。
 *
 * <h2>与 v8 的差异</h2>
 * <ul>
 *   <li>{@code Support()} 只报 <b>keywords</b>（v7 不带向量）；{@code Retrieve()} 也只分派
 *       keywords——传 vector 直接 {@code invalid retriever type}（{@code VectorRetrieve}
 *       仍实现且可直呼，但不在分派表里）</li>
 *   <li>命中按实际检索路径标注：vector → <b>MatchTypeEmbedding</b>、keywords →
 *       <b>MatchTypeKeywords</b>；单条命中缺
 *       {@code _id}/{@code _source}/{@code _score} 时<b>跳过该条继续</b>（v8 是整请求报错）</li>
 *   <li>基础条件是 <b>JSON 字符串</b>（{@code getBaseConds} 返回 string，供拼装）；关键词查询走
 *       模板 {@code {"query":{"bool":{"must":[{"match":{"content":<q>}}],"filter":[<cond>]}}}}</li>
 *   <li>建索引的 settings 是 <b>数字</b>（v8 是字符串）；建索引失败文案
 *       {@code failed to create index <index>}</li>
 *   <li>单条写入走 {@code PUT /{index}/_create/{uuid}}（显式 UUID 文档 ID，v8 是 POST /_doc 自增）；
 *       批量 NDJSON 的动作行是 {@code { "index" : { "_id" : "<uuid>" } }}（键与值之间带空格），
 *       响应里 {@code errors:true} 只<b>计数并告警</b>（不失败）、解析失败也放行</li>
 *   <li>删除/改状态/改标签的 body 是手拼/直构的 {@code {"query":{"terms":{...}}}}——改状态<b>不套
 *       bool</b>（v8 套 bool.must）；脚本带 {@code lang: painless}</li>
 *   <li>{@code MoveKnowledgeIndices}：filter 里用 <b>singular {@code term}</b> +
 *       字符串值（v8 用 {@code terms} 数组）；脚本<b>带 lang</b>；{@code ?refresh=true}；
 *       完整性校验同 v8</li>
 * </ul>
 *
 * <h2>CopyIndices 的向量回填</h2>
 * <p>复制时按<b>目标 SourceID</b> 为键回填源文档向量，复制出的文档带向量（与 v8 语义一致）。</p>
 */
public class ElasticsearchV7RetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchV7RetrieveRepository.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final int COPY_BATCH_SIZE = 500;

    private final String addr;
    final String index;
    private final int numberOfShards;
    private final int numberOfReplicas;
    private final String username;
    private final String password;
    private final HttpClient http;
    private volatile boolean useKeywordSuffix;

    final ElasticsearchV7SearchOps searchOps;
    final ElasticsearchV7WriteOps writeOps;

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
        this.searchOps = new ElasticsearchV7SearchOps(this);
        this.writeOps = new ElasticsearchV7WriteOps(this);
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

    /** 只支持 keywords（无向量）。 */
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS);
    }

    // ── 索引自举 ────────────────────────────────────────────────────────────

    /** 建索引 settings 为<b>数字</b>；失败文案固定。 */
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

    /** 字段类型探测（与 v8 同判定，逐层判空）。 */
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

    /** 存储估算（与 v8 同式）。 */
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

    // ── 基础条件（JSON 字符串） ─────────────────────────────────────────────

    static ObjectNode termsOnly(String field, List<String> values) {
        ObjectNode terms = MAPPER.createObjectNode();
        ArrayNode array = terms.putArray(field);
        for (String value : values) {
            array.add(value);
        }
        return MAPPER.createObjectNode().set("terms", terms);
    }
    // ── 检索 ────────────────────────────────────────────────────────────────

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
    String getBaseCondsJson(RetrieveParams params) {
        return searchOps.getBaseCondsJson(params);
    }


    // ── 复制索引 ────────────────────────────────────────────────────────────

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
     * {@code bool.filter} 用 <b>singular {@code term}</b> + 字符串值
     * （v8 是 {@code terms} 数组）；脚本<b>带 lang</b>；{@code ?refresh=true}；完整性校验同 v8。
     */
    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
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

    // ── 批量改状态 / 标签（不套 bool） ─────────────────────────────────────

    // ── HTTP ────────────────────────────────────────────────────────────────

    record HttpResult(int status, String body) {
    }

    HttpResult request(String method, String path, String jsonBody) throws Exception {
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
