package com.ragagent.retrieval.engine.weaviate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.retrieval.engine.weaviate.WeaviateRestClient.Json;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Weaviate 检索引擎仓储——对照 Go {@code repository/retriever/weaviate/} 全包
 * （repository.go 1064 + structs.go 33 + move.go 77，约 1,170 行非测试）。
 *
 * <h2>协议口径</h2>
 * Go 的 weaviate-go-client v5：GraphQL 检索/列举与批量删除<b>本就是 REST</b>，批量创建默认
 * 走 gRPC（无 gRPC 客户端时同样回落 REST {@code POST /v1/batch/objects}）。本仓统一自持
 * REST（见 {@link WeaviateRestClient}），GraphQL 查询串逐字节对照客户端 {@code Build()}
 * 的 Go 实录（{@code WeaviateGql}）。类名默认 {@code Weknora_embeddings}——<b>照 Go 原文的
 * 拼写</b>（"Weknora"，不是 WeKnora），改名会与既有部署的类名不匹配。
 *
 * <h2>语义要点（照 Go）</h2>
 * <ul>
 *   <li>类按维度命名 {@code <base>_<dim>}；建类时命名向量 {@code embedding}（hnsw + cosine +
 *       efConstruction 128 / maxConnections 32 / ef 64、vectorizer none）、content 用 gse 分词、
 *       chunk/knowledge/kb/tag/is_enabled 可过滤；</li>
 *   <li>对象 ID = chunkID（Weaviate 要求 UUID —— 本仓的 chunk id 即 UUID）；</li>
 *   <li>删除走批量删除（{@code ContainsAny} + output minimal）；</li>
 *   <li>关键词检索用 BM25（properties=[content]，中文依赖服务端 gse 分词）；</li>
 *   <li>向量检索结果分数取 {@code _additional.certainty}，关键词结果有 score 时恒记 1.0
 *       （缺失则 0.0——照 Go 的三元分支）。</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案 + 两处有意修正）</h2>
 * <ol>
 *   <li><b>修正</b>：{@code BatchUpdateChunkEnabledStatus}/{@code BatchUpdateChunkTagID} 用
 *       <b>PATCH merge</b>（Go 的 {@code Updater} 无 merge → PUT 整对象替换——实测会清掉未提供的
 *       属性<b>与向量</b>，是真实数据丢失缺陷）；错误语义仍照 Go：逐对象失败只记日志不冒泡。</li>
 *   <li><b>修正</b>：{@code CopyIndices} 的分页用 {@code where + limit + offset} 且取
 *       {@code _additional{vectors{embedding}}}（Go 用 {@code where + limit + after}——服务端
 *       直接拒绝 "where cannot be set with after and limit parameters"，且命名向量类下
 *       {@code _additional{vector}} 恒空 → Go 的拷贝在本仓服务端版本上必失败）。</li>
 *   <li>批量创建走 REST（Go 默认 gRPC；per-object 错误在 BatchSave 里 Go 不检查，本仓记 WARN）。</li>
 *   <li>{@code grpc_address} 不参与本实现（REST 无 gRPC 面）；地址策略与配置字段保持不变。</li>
 *   <li>Go 的 {@code weaviate.tokenizeQuery} 是死代码（无调用点），未翻译。</li>
 *   <li>Go 的批量更新里有 {@code if err != nil}（用的是上一个调用的陈旧 err）死分支，未复刻。</li>
 * </ol>
 */
public class WeaviateRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(WeaviateRetrieveRepository.class);

    /** 对照 {@code defaultCollectionName}（Go 原文拼写）。 */
    public static final String DEFAULT_COLLECTION_NAME = "Weknora_embeddings";
    /** 对照 {@code envWeaviateCollection}。 */
    public static final String ENV_WEAVIATE_COLLECTION = "WEAVIATE_COLLECTION";

    static final String FIELD_CONTENT = "content";
    static final String FIELD_SOURCE_ID = "source_id";
    static final String FIELD_SOURCE_TYPE = "source_type";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_KNOWLEDGE_ID = "knowledge_id";
    static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    static final String FIELD_TAG_ID = "tag_id";
    static final String FIELD_EMBEDDING = "embedding";
    static final String FIELD_IS_ENABLED = "is_enabled";

    /** 对照 CopyIndices 的 {@code batchSize := 64}。 */
    static final int COPY_PAGE_SIZE = 64;
    /** 对照 move 的每页 100。 */
    static final int MOVE_PAGE_SIZE = 100;

    final WeaviateRestClient client;
    private final String collectionBaseName;
    private final int replicationFactor;
    private final int desiredShardCount;

    final WeaviateSearchOps searchOps;
    final WeaviateWriteOps writeOps;

    /** 对照 {@code initializedCollections sync.Map}：dim -> true。 */
    private final ConcurrentHashMap<Integer, Boolean> initializedCollections =
            new ConcurrentHashMap<>();

    public WeaviateRetrieveRepository(WeaviateRestClient client, String collectionBaseName,
                                      int replicationFactor, int desiredShardCount) {
        this.client = client;
        this.collectionBaseName = collectionBaseName == null || collectionBaseName.isEmpty()
                ? DEFAULT_COLLECTION_NAME : collectionBaseName;
        this.replicationFactor = replicationFactor;
        this.desiredShardCount = desiredShardCount;
        this.searchOps = new WeaviateSearchOps(this);
        this.writeOps = new WeaviateWriteOps(this);
    }

    /** 照 Go {@code NewWeaviateRetrieveEngineRepository} + {@code createWeaviateEngine}。 */
    public static WeaviateRetrieveRepository create(String host, String scheme, String apiKey,
                                                    IndexConfig indexCfg, SsrfGuard guard) {
        log.info("[Weaviate] Initializing Weaviate retriever engine repository");
        String baseName = resolveCollectionName(indexCfg);
        WeaviateRestClient client = new WeaviateRestClient(host, scheme, apiKey, guard);
        WeaviateRetrieveRepository repo = new WeaviateRetrieveRepository(client, baseName,
                indexCfg == null ? 0 : indexCfg.replicationFactor,
                indexCfg == null ? 0 : indexCfg.desiredShardCount);
        log.info("[Weaviate] Successfully initialized repository");
        return repo;
    }

    /** 对照 {@code types.ResolveCollectionName(indexCfg, WEAVIATE_COLLECTION, default)}。 */
    static String resolveCollectionName(IndexConfig indexCfg) {
        if (indexCfg != null) {
            if (indexCfg.collectionPrefix != null && !indexCfg.collectionPrefix.isEmpty()) {
                return indexCfg.collectionPrefix;
            }
            if (indexCfg.collectionName != null && !indexCfg.collectionName.isEmpty()) {
                return indexCfg.collectionName;
            }
        }
        String env = System.getenv(ENV_WEAVIATE_COLLECTION);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return DEFAULT_COLLECTION_NAME;
    }

    // ── 引擎面 ──────────────────────────────────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_WEAVIATE;
    }

    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    /** 对照 {@code EstimateStorageSize}（HNSW M=32；payload 不含 tag_id——照 Go）。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null) {
            return 0;
        }
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            total += calculateStorageSize(toEmbedding(info, params));
        }
        log.info("[Weaviate] Storage size for {} indices: {} bytes",
                indexInfoList.size(), total);
        return total;
    }

    static long calculateStorageSize(WeaviateVectorEmbedding embedding) {
        long payload = 0;
        payload += utf8Length(embedding.content);
        payload += utf8Length(embedding.sourceId);
        payload += utf8Length(embedding.chunkId);
        payload += utf8Length(embedding.knowledgeId);
        payload += utf8Length(embedding.knowledgeBaseId);
        payload += 8; // source_type int64
        long vectorSizeBytes = 0;
        long hnswIndexBytes = 0;
        if (embedding.embedding != null) {
            vectorSizeBytes = embedding.embedding.length * 4L;
            final long hnswM = 32; // 图链接数只与 M 有关，与维度无关
            hnswIndexBytes = hnswM * 2 * 8;
        }
        final long idTrackerBytes = 24;
        return payload + vectorSizeBytes + hnswIndexBytes + idTrackerBytes;
    }

    static long utf8Length(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int bytes = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80) {
                if (c < 0x800) {
                    bytes++;
                } else if (!Character.isSurrogate(c)) {
                    bytes += 2;
                }
            }
        }
        return bytes;
    }

    // ── 类管理（照 ensureCollection） ──────────────────────────────────────

    String collectionName(int dimension) {
        return collectionBaseName + "_" + dimension;
    }

    void ensureCollection(int dimension) {
        if (initializedCollections.containsKey(dimension)) {
            return;
        }
        String name = collectionName(dimension);
        boolean exists;
        try {
            exists = client.classExists(name);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException(
                    "failed to check collection existence: " + e.getMessage(), e);
        }
        if (!exists) {
            log.info("[Weaviate] Creating collection {} with dimension {}", name, dimension);
            try {
                client.createClass(classBodyWithClusterOptions(name, dimension));
            } catch (RuntimeException e) {
                log.error("[Weaviate] Failed to create collection: {}", e.getMessage());
                throw new IllegalStateException(
                        "failed to create collection: " + e.getMessage(), e);
            }
            log.info("[Weaviate] Successfully created collection {}", name);
        }
        initializedCollections.put(dimension, true);
    }

    /** 对照 {@code models.Class}：命名向量 + gse 分词 + 可过滤属性（字段名与值逐条照 Go）。 */
    static ObjectNode classBody(String className, int dimension) {
        ObjectNode body = Json.object();
        body.put("class", className);
        body.put("description", "WeKnora embeddings collection with dimension " + dimension);

        ObjectNode vectorConfig = body.putObject("vectorConfig");
        ObjectNode embedding = vectorConfig.putObject(FIELD_EMBEDDING);
        embedding.put("vectorIndexType", "hnsw");
        ObjectNode indexConfig = embedding.putObject("vectorIndexConfig");
        indexConfig.put("distance", "cosine");
        indexConfig.put("efConstruction", 128);
        indexConfig.put("maxConnections", 32);
        indexConfig.put("ef", 64);
        embedding.putObject("vectorizer").putObject("none");

        ArrayNode properties = body.putArray("properties");
        properties.add(textProperty(FIELD_CONTENT, "gse", false));
        properties.add(textProperty(FIELD_SOURCE_ID, null, false));
        properties.add(intProperty(FIELD_SOURCE_TYPE));
        properties.add(textProperty(FIELD_CHUNK_ID, null, true));
        properties.add(textProperty(FIELD_KNOWLEDGE_ID, null, true));
        properties.add(textProperty(FIELD_KNOWLEDGE_BASE_ID, null, true));
        properties.add(textProperty(FIELD_TAG_ID, null, true));
        properties.add(booleanProperty(FIELD_IS_ENABLED));
        return body;
    }

    private static ObjectNode textProperty(String name, String tokenization, boolean filterable) {
        ObjectNode node = Json.object();
        node.put("name", name);
        node.putArray("dataType").add("text");
        if (tokenization != null) {
            node.put("tokenization", tokenization);
        }
        if (filterable) {
            node.put("indexFilterable", true);
        }
        return node;
    }

    private static ObjectNode intProperty(String name) {
        ObjectNode node = Json.object();
        node.put("name", name);
        node.putArray("dataType").add("int");
        return node;
    }

    private static ObjectNode booleanProperty(String name) {
        ObjectNode node = Json.object();
        node.put("name", name);
        node.putArray("dataType").add("boolean");
        node.put("indexFilterable", true);
        return node;
    }

    /** 建类请求体 + 可选 replicationConfig / shardingConfig（照 {@code ensureCollection} 后段）。 */
    private ObjectNode classBodyWithClusterOptions(String className, int dimension) {
        ObjectNode body = classBody(className, dimension);
        if (replicationFactor > 0) {
            body.putObject("replicationConfig").put("factor", replicationFactor);
        }
        if (desiredShardCount > 0) {
            body.putObject("shardingConfig").put("desiredCount", desiredShardCount);
        }
        return body;
    }


    /** 对照 {@code BatchSave}：按维度分组 → 每维一次批量创建（REST，照客户端的回落路径）。 */


    List<String> listCollectionsOrThrow() {
        try {
            return listCollections();
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
    }

    /** 对照 {@code ListCollections}（{@code Schema().Getter()}；错误文案照 Go 的中文原文）。 */
    List<String> listCollections() {
        JsonNode schema;
        try {
            schema = client.schema();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "weaviate 获取 schema 失败: " + e.getMessage(), e);
        }
        List<String> names = new ArrayList<>();
        JsonNode classes = schema == null ? null : schema.get("classes");
        if (classes != null) {
            for (JsonNode node : classes) {
                names.add(node.path("class").asText(""));
            }
        }
        return names;
    }

    /** 照 Go 的集合名前缀过滤：严格长于 base 且以此为前缀。 */
    boolean isPrefixed(String collection) {
        return collection.length() > collectionBaseName.length()
                && collection.startsWith(collectionBaseName);
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /**
     * 取 {@code data.Get.<collection>}：GraphQL errors → {@code graphql search failed: <first>}；
     * 缺 data/缺类 → null（照 Go 的 continue/空结果分支）；类存在但空集 → 空数组。
     */
    static JsonNode extractItems(JsonNode response, String collection) {
        if (response == null) {
            return null;
        }
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("graphql search failed: "
                    + errors.path(0).path("message").asText(""));
        }
        JsonNode get = response.path("data").path("Get");
        if (get.isMissingNode() || get.isNull()) {
            return null;
        }
        JsonNode items = get.get(collection);
        if (items == null || items.isNull()) {
            return null;
        }
        return items.isArray() ? items : null;
    }

    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return searchOps.retrieve(params);
    }



    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        writeOps.save(indexInfo, params);
    }

    @Override
    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        writeOps.batchSave(embeddingList, params);
    }

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteByChunkIdList(chunkIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        writeOps.deleteByKnowledgeIdList(knowledgeIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteBySourceIdList(sourceIdList, dimension, knowledgeType);
    }

    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        writeOps.batchUpdateChunkEnabledStatus(chunkStatusMap);
    }

    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        writeOps.batchUpdateChunkTagID(chunkTagMap);
    }

    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        writeOps.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
    }


    // ── move（照 move.go：seen-set 循环 + merge） ──────────────────────────

    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        String collection = collectionName(dimension);
        WeaviateGql.Where where = WeaviateGql.Where.and(List.of(
                WeaviateGql.Where.equal(FIELD_KNOWLEDGE_BASE_ID).valueString(sourceKb),
                WeaviateGql.Where.equal(FIELD_KNOWLEDGE_ID).valueString(knowledgeId)));
        Set<String> seen = new LinkedHashSet<>();
        while (true) {
            JsonNode response = client.graphql(
                    WeaviateGql.moveListQuery(collection, where, MOVE_PAGE_SIZE));
            if (response == null) {
                throw new IllegalStateException("failed to list move indices");
            }
            JsonNode errors = response.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                throw new IllegalStateException("failed to list move indices");
            }
            JsonNode get = response.path("data").path("Get");
            if (!get.isObject()) {
                throw new IllegalStateException("invalid move index response");
            }
            JsonNode rows = get.get(collection);
            if (rows == null || rows.isNull()) {
                throw new IllegalStateException("invalid move index collection");
            }
            if (rows.isEmpty()) {
                return; // 空集 = 完成（局部页更新后同样成立）
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    throw new IllegalStateException("invalid move index row");
                }
                JsonNode additional = row.get("_additional");
                if (additional == null || !additional.isObject()) {
                    throw new IllegalStateException("missing move index ID");
                }
                String id = additional.path("id").asText("");
                if (id.isEmpty()) {
                    throw new IllegalStateException("invalid move index ID");
                }
                if (!seen.add(id)) {
                    throw new IllegalStateException("move indices made no progress for " + id);
                }
                ids.add(id);
            }
            for (String id : ids) {
                ObjectNode properties = Json.object();
                properties.put(FIELD_KNOWLEDGE_BASE_ID, targetKb);
                properties.put(FIELD_TAG_ID, "");
                client.mergeUpdate(collection, id, properties);
            }
        }
    }

    // ── 行映射与辅助 ───────────────────────────────────────────────────────

    /** 对照 {@code toWeaviateVectorEmbedding}：embedding 按 SourceID 取（缺失 → null）。 */
    static WeaviateVectorEmbedding toEmbedding(IndexInfo info, Map<String, Object> params) {
        WeaviateVectorEmbedding row = new WeaviateVectorEmbedding();
        row.content = info.content == null ? "" : info.content;
        row.sourceId = info.sourceId == null ? "" : info.sourceId;
        row.sourceType = info.sourceType;
        row.chunkId = info.chunkId == null ? "" : info.chunkId;
        row.knowledgeId = info.knowledgeId == null ? "" : info.knowledgeId;
        row.knowledgeBaseId = info.knowledgeBaseId == null ? "" : info.knowledgeBaseId;
        row.tagId = info.tagId == null ? "" : info.tagId;
        row.isEnabled = info.isEnabled;
        if (params != null) {
            Object raw = params.get(FIELD_EMBEDDING);
            if (raw instanceof Map<?, ?> map) {
                Object vector = map.get(row.sourceId);
                if (vector instanceof float[] f) {
                    row.embedding = f;
                } else if (vector instanceof List<?> list) {
                    float[] out = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
                    }
                    row.embedding = out;
                }
            }
        }
        return row;
    }

    static List<RetrieveResult> buildRetrieveResult(List<IndexWithScore> results,
                                                    String retrieverType) {
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_WEAVIATE, retrieverType));
    }

    /**
     * 对照 {@code translateSourceID} 的三态（与 Qdrant/Doris 实现镜像）：
     * 普通 chunk（{@code SourceID == ChunkID}）→ targetChunkID；生成型问题
     * （{@code "<chunkID>-<questionID>"}）→ 换前缀；其他 → 新 UUID。
     */
    static String translateSourceId(String originalSourceId, String sourceChunkId,
                                    String targetChunkId) {
        String original = originalSourceId == null ? "" : originalSourceId;
        String srcChunk = sourceChunkId == null ? "" : sourceChunkId;
        if (original.equals(srcChunk)) {
            return targetChunkId;
        }
        if (original.startsWith(srcChunk + "-")) {
            return targetChunkId + "-" + original.substring(srcChunk.length() + 1);
        }
        return UUID.randomUUID().toString();
    }

    // ── test-connection 探针（照 testWeaviateConnection） ──────────────────

    /**
     * 连通性探针：ready 检查（失败 → 异常，调用方折叠成
     * "failed to connect to weaviate: server not ready or authentication failed"）；
     * 再取 {@code /v1/meta} 的 version（失败 → ""，照 Go 的"连上了但版本未知"）。
     */
    public static String testConnection(String host, String scheme, String apiKey,
                                        SsrfGuard guard) {
        WeaviateRestClient client = new WeaviateRestClient(host, scheme, apiKey, guard);
        if (!client.ready()) {
            throw new IllegalStateException("weaviate server not ready");
        }
        return client.metaVersion();
    }
}
