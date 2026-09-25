package com.ragagent.retrieval.engine.weaviate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    private final WeaviateRestClient client;
    private final String collectionBaseName;
    private final int replicationFactor;
    private final int desiredShardCount;

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

    private void ensureCollection(int dimension) {
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

    // ── 写入 ────────────────────────────────────────────────────────────────

    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        log.debug("[Weaviate] Saving index for chunk ID: {}", indexInfo.chunkId);
        WeaviateVectorEmbedding row = toEmbedding(indexInfo, params);
        if (row.embedding == null || row.embedding.length == 0) {
            IllegalStateException e = new IllegalStateException(
                    "empty embedding vector for chunk ID: " + indexInfo.chunkId);
            log.error("[Weaviate] {}", e.getMessage());
            throw e;
        }
        int dimension = row.embedding.length;
        ensureCollection(dimension);
        ObjectNode object = objectBody(collectionName(dimension), row.chunkId, row);
        try {
            client.createObject(object);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to save index: {}", e.getMessage());
            throw new IllegalStateException(e.getMessage() == null
                    ? e.toString() : e.getMessage(), e);
        }
        log.info("[Weaviate] Successfully saved index for chunk ID: {}", indexInfo.chunkId);
    }

    /** 对照 {@code BatchSave}：按维度分组 → 每维一次批量创建（REST，照客户端的回落路径）。 */
    @Override
    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Weaviate] Empty list provided to BatchSave, skipping");
            return;
        }
        log.info("[Weaviate] Batch saving {} indices", embeddingList.size());
        Map<Integer, List<WeaviateVectorEmbedding>> byDimension = new TreeMap<>();
        for (IndexInfo info : embeddingList) {
            WeaviateVectorEmbedding row = toEmbedding(info, params);
            if (row.embedding == null || row.embedding.length == 0) {
                log.warn("[Weaviate] Skipping empty embedding for chunk ID: {}", info.chunkId);
                continue;
            }
            byDimension.computeIfAbsent(row.embedding.length, k -> new ArrayList<>()).add(row);
        }
        if (byDimension.isEmpty()) {
            log.warn("[Weaviate] No valid points to save after filtering");
            return;
        }
        int totalSaved = 0;
        for (Map.Entry<Integer, List<WeaviateVectorEmbedding>> entry : byDimension.entrySet()) {
            int dimension = entry.getKey();
            ensureCollection(dimension);
            String collection = collectionName(dimension);
            ArrayNode objects = Json.array();
            for (WeaviateVectorEmbedding row : entry.getValue()) {
                objects.add(objectBody(collection, row.chunkId, row));
            }
            try {
                JsonNode response = client.batchCreate(objects);
                logObjectErrors(response, "BatchSave");
            } catch (RuntimeException e) {
                log.error("[Weaviate] Failed to execute batch operation for dimension {}: {}",
                        dimension, e.getMessage());
                throw new IllegalStateException(
                        "failed to batch save (dimension " + dimension + "): " + e.getMessage(), e);
            }
            totalSaved += entry.getValue().size();
            log.info("[Weaviate] Saved {} points to collection {}", entry.getValue().size(),
                    collection);
        }
        log.info("[Weaviate] Successfully batch saved {} indices", totalSaved);
    }

    /**
     * 对象体：{@code {class, id, properties, vector}}——id = chunkID（另见类注释）。
     * 与 Go 的差异（备案）：Go 单对象路径用 {@code Vector} 字段；命名向量类下服务端把
     * 单向量映射到唯一命名向量 {@code embedding}（1.28.4 实测接受）。
     */
    private static ObjectNode objectBody(String className, String chunkId,
                                         WeaviateVectorEmbedding row) {
        ObjectNode object = Json.object();
        object.put("class", className);
        object.put("id", row.chunkId == null || row.chunkId.isEmpty() ? chunkId : row.chunkId);
        object.set("properties", createPayload(row));
        ArrayNode vector = object.putArray("vector");
        for (float v : row.embedding == null ? new float[0] : row.embedding) {
            vector.add(v);
        }
        return object;
    }

    /** 对照 {@code createPayload}：键序按写入序（服务端无键序约束）。 */
    static ObjectNode createPayload(WeaviateVectorEmbedding row) {
        ObjectNode payload = Json.object();
        payload.put(FIELD_CONTENT, row.content == null ? "" : row.content);
        payload.put(FIELD_SOURCE_ID, row.sourceId == null ? "" : row.sourceId);
        payload.put(FIELD_SOURCE_TYPE, row.sourceType);
        payload.put(FIELD_CHUNK_ID, row.chunkId == null ? "" : row.chunkId);
        payload.put(FIELD_KNOWLEDGE_ID, row.knowledgeId == null ? "" : row.knowledgeId);
        payload.put(FIELD_KNOWLEDGE_BASE_ID,
                row.knowledgeBaseId == null ? "" : row.knowledgeBaseId);
        payload.put(FIELD_TAG_ID, row.tagId == null ? "" : row.tagId);
        payload.put(FIELD_IS_ENABLED, row.isEnabled);
        return payload;
    }

    /** 批量响应里的逐对象错误只记日志（照 Go 的批量语义：对象级错误不冒泡）。 */
    private static void logObjectErrors(JsonNode response, String op) {
        if (response == null || !response.isArray()) {
            return;
        }
        for (JsonNode item : response) {
            JsonNode errors = item.path("result").path("errors");
            if (errors.isObject() && errors.hasNonNull("error")) {
                JsonNode first = errors.path("error").isArray()
                        ? errors.path("error").path(0) : errors.path("error");
                log.warn("[Weaviate] {} object error: {}", op, first.path("message").asText(""));
            }
        }
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(FIELD_CHUNK_ID, chunkIdList, dimension, "chunk IDs",
                "Empty chunk ID list provided for deletion, skipping",
                "failed to delete by chunk IDs");
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension, "knowledge IDs",
                "Empty knowledge ID list provided for deletion, skipping",
                "failed to delete by knowledge IDs");
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(FIELD_SOURCE_ID, sourceIdList, dimension, "Source IDs",
                "Empty Source ID list provided for deletion, skipping",
                "failed to delete by source IDs");
    }

    private void deleteByField(String field, List<String> ids, int dimension, String subject,
                               String emptyWarning, String errorPrefix) {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Weaviate] {}", emptyWarning);
            return;
        }
        String collection = collectionName(dimension);
        log.info("[Weaviate] Deleting indices by {} from {}, count: {}", subject, collection,
                ids.size());
        WeaviateGql.Where where = WeaviateGql.Where.containsAny(field)
                .valueText(ids.toArray(new String[0]));
        try {
            client.batchDelete(collection, where.json(), "minimal");
        } catch (RuntimeException e) {
            log.error("[Weaviate] {}: {}", errorPrefix, e.getMessage());
            throw new IllegalStateException(errorPrefix + ": " + e.getMessage(), e);
        }
        log.info("[Weaviate] Successfully deleted documents by {}", subject);
    }

    // ── 批量更新（有意修正：merge 而非整对象替换） ─────────────────────────

    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Weaviate] Empty chunk status map provided, skipping");
            return;
        }
        log.info("[Weaviate] Batch updating chunk enabled status, count: {}",
                chunkStatusMap.size());
        List<String> collections = listCollectionsOrThrow();
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
                ObjectNode properties = Json.object();
                properties.put(FIELD_IS_ENABLED, Boolean.TRUE.equals(entry.getValue()));
                try {
                    client.mergeUpdate(collection, entry.getKey(), properties);
                } catch (RuntimeException e) {
                    log.error("[Weaviate] Failed to update chunk {} status in {}: {}",
                            Boolean.TRUE.equals(entry.getValue()) ? "enabled" : "disabled",
                            collection, e.getMessage());
                }
            }
        }
        log.info("[Weaviate] Batch update chunk enabled status completed");
    }

    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Weaviate] Empty chunk tag map provided, skipping");
            return;
        }
        log.info("[Weaviate] Batch updating chunk tag ID, count: {}", chunkTagMap.size());
        List<String> collections = listCollectionsOrThrow();
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
                ObjectNode properties = Json.object();
                properties.put(FIELD_TAG_ID, entry.getValue() == null ? "" : entry.getValue());
                try {
                    client.mergeUpdate(collection, entry.getKey(), properties);
                } catch (RuntimeException e) {
                    log.warn("[Weaviate] Failed to update chunk {} tag ID in {}: {}",
                            entry.getKey(), collection, e.getMessage());
                }
            }
        }
        log.info("[Weaviate] Batch update chunk tag ID completed");
    }

    private List<String> listCollectionsOrThrow() {
        try {
            return listCollections();
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
    }

    /** 对照 {@code ListCollections}（{@code Schema().Getter()}；错误文案照 Go 的中文原文）。 */
    private List<String> listCollections() {
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
    private boolean isPrefixed(String collection) {
        return collection.length() > collectionBaseName.length()
                && collection.startsWith(collectionBaseName);
    }

    // ── 过滤器（照 getBaseFilter） ─────────────────────────────────────────

    static WeaviateGql.Where baseFilter(RetrieveParams params) {
        List<WeaviateGql.Where> operands = new ArrayList<>();
        operands.add(WeaviateGql.Where.equal(FIELD_IS_ENABLED).valueBoolean(true));
        if (params != null) {
            if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
                operands.add(WeaviateGql.Where.containsAny(FIELD_KNOWLEDGE_BASE_ID)
                        .valueText(params.knowledgeBaseIds.toArray(new String[0])));
            }
            if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
                operands.add(WeaviateGql.Where.containsAny(FIELD_KNOWLEDGE_ID)
                        .valueText(params.knowledgeIds.toArray(new String[0])));
            }
            if (params.tagIds != null && !params.tagIds.isEmpty()) {
                operands.add(WeaviateGql.Where.containsAny(FIELD_TAG_ID)
                        .valueText(params.tagIds.toArray(new String[0])));
            }
            if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
                operands.add(WeaviateGql.Where.notEqual(FIELD_KNOWLEDGE_ID)
                        .valueText(params.excludeKnowledgeIds.toArray(new String[0])));
            }
            if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
                operands.add(WeaviateGql.Where.notEqual(FIELD_CHUNK_ID)
                        .valueText(params.excludeChunkIds.toArray(new String[0])));
            }
        }
        return WeaviateGql.Where.and(operands);
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
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
    private List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        log.info("[Weaviate] Vector retrieval: dim={}, topK={}, threshold={}",
                dimension, params.topK, params.threshold);
        String collection = collectionName(dimension);
        boolean exists;
        try {
            exists = client.classExists(collection);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException("failed to check collection: " + e.getMessage(), e);
        }
        if (!exists) {
            log.warn("[Weaviate] Collection {} does not exist, returning empty results",
                    collection);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        // 照 Go：scoreThreshold := float32(params.Threshold)
        String query = WeaviateGql.vectorQuery(collection, baseFilter(params), params.topK,
                embedding, (float) params.threshold);
        JsonNode result;
        try {
            result = client.graphql(query);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Vector search failed: {}", e.getMessage());
            throw new IllegalStateException("failed to search: " + e.getMessage(), e);
        }
        JsonNode items = extractItems(result, collection);
        if (items == null) {
            log.warn("[Weaviate] No vector matches found that meet threshold {}",
                    params.threshold);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        List<IndexWithScore> results = parseItems(items, EngineTypes.MATCH_EMBEDDING);
        if (results.isEmpty()) {
            log.warn("[Weaviate] No vector matches found that meet threshold {}",
                    params.threshold);
        } else {
            log.info("[Weaviate] Vector retrieval found {} results", results.size());
        }
        return buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：跨集合 BM25；单集合查询失败<b>直接返回错误</b>
     * （照 Go——与 Qdrant 的"跳过继续"相反），缺数据则 continue；合并后截 TopK。
     */
    private List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query;
        log.info("[Weaviate] Performing keywords retrieval with query: {}, topK: {}",
                query, params.topK);
        List<String> collections = listCollectionsOrThrow();
        List<IndexWithScore> allResults = new ArrayList<>();
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            String gql = WeaviateGql.bm25Query(collection, baseFilter(params), params.topK, query,
                    List.of(FIELD_CONTENT));
            JsonNode result;
            try {
                result = client.graphql(gql);
            } catch (RuntimeException e) {
                log.error("[Weaviate] keywords search failed: {}", e.getMessage());
                throw new IllegalStateException("failed to search: " + e.getMessage(), e);
            }
            JsonNode items = extractItems(result, collection);
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
        return buildRetrieveResult(allResults, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /**
     * 取 {@code data.Get.<collection>}：GraphQL errors → {@code graphql search failed: <first>}；
     * 缺 data/缺类 → null（照 Go 的 continue/空结果分支）；类存在但空集 → 空数组。
     */
    private static JsonNode extractItems(JsonNode response, String collection) {
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
            out.sourceId = item.path(FIELD_SOURCE_ID).asText("");
            out.sourceType = item.path(FIELD_SOURCE_TYPE).asInt(0);
            out.chunkId = item.path(FIELD_CHUNK_ID).asText("");
            out.knowledgeId = item.path(FIELD_KNOWLEDGE_ID).asText("");
            out.knowledgeBaseId = item.path(FIELD_KNOWLEDGE_BASE_ID).asText("");
            out.tagId = item.path(FIELD_TAG_ID).asText("");
            out.content = item.path(FIELD_CONTENT).asText("");
            out.score = score;
            out.matchType = matchType;
            results.add(out);
        }
        return results;
    }

    // ── CopyIndices（有意修正：where+offset 与命名向量） ────────────────────

    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("[Weaviate] Copying indices from {} to {}, count: {}",
                sourceKnowledgeBaseId, targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size());
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            return;
        }
        String collection = collectionName(dimension);
        WeaviateGql.Where where = WeaviateGql.Where.equal(FIELD_KNOWLEDGE_BASE_ID)
                .valueString(sourceKnowledgeBaseId);
        int offset = 0;
        int totalCopied = 0;
        while (true) {
            String query = WeaviateGql.copyPageQuery(collection, where, COPY_PAGE_SIZE, offset);
            JsonNode response = client.graphql(query);
            JsonNode items = extractItems(response, collection);
            if (items == null || items.isEmpty()) {
                break;
            }
            log.info("[Weaviate] Found {} source points in batch", items.size());
            ArrayNode targets = Json.array();
            for (JsonNode item : items) {
                JsonNode additional = item.path("_additional");
                JsonNode vectors = additional.path("vectors").path(FIELD_EMBEDDING);
                if (!vectors.isArray() || vectors.isEmpty()) {
                    log.warn("[Weaviate] No vectors found for source point with chunk {}, skipping",
                            item.path(FIELD_CHUNK_ID).asText(""));
                    continue;
                }
                String sourceChunkId = item.path(FIELD_CHUNK_ID).asText("");
                String sourceKnowledgeId = item.path(FIELD_KNOWLEDGE_ID).asText("");
                String targetChunkId = sourceToTargetChunkIdMap.get(sourceChunkId);
                String targetKnowledgeId = sourceToTargetKbIdMap == null ? null
                        : sourceToTargetKbIdMap.get(sourceKnowledgeId);
                if (targetChunkId == null || targetKnowledgeId == null) {
                    continue;
                }
                String originalSourceId = item.path(FIELD_SOURCE_ID).asText("");
                WeaviateVectorEmbedding row = new WeaviateVectorEmbedding();
                row.content = item.path(FIELD_CONTENT).asText("");
                row.sourceId = translateSourceId(originalSourceId, sourceChunkId,
                        targetChunkId);
                row.sourceType = item.path(FIELD_SOURCE_TYPE).asInt(0);
                row.chunkId = targetChunkId;
                row.knowledgeId = targetKnowledgeId;
                row.knowledgeBaseId = targetKnowledgeBaseId;
                row.tagId = item.path(FIELD_TAG_ID).asText("");
                row.isEnabled = true; // 照 Go：拷贝一律置 true（不沿用源值）
                row.embedding = new float[vectors.size()];
                for (int i = 0; i < vectors.size(); i++) {
                    row.embedding[i] = (float) vectors.get(i).asDouble();
                }
                ObjectNode object = Json.object();
                object.put("class", collection);
                object.put("id", UUID.randomUUID().toString());
                object.set("properties", createPayload(row));
                ArrayNode vector = object.putArray("vector");
                for (float v : row.embedding) {
                    vector.add(v);
                }
                targets.add(object);
            }
            if (!targets.isEmpty()) {
                try {
                    JsonNode resp = client.batchCreate(targets);
                    logObjectErrors(resp, "CopyIndices");
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "batch upsert failed: " + e.getMessage(), e);
                }
                totalCopied += targets.size();
                log.info("[Weaviate] Successfully copied batch, total: {}", totalCopied);
            }
            if (items.size() < COPY_PAGE_SIZE) {
                break;
            }
            offset += COPY_PAGE_SIZE;
        }
        log.info("[Weaviate] Index copy completed, total copied: {}", totalCopied);
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
