package com.ragagent.retrieval.engine.milvus;

import java.util.ArrayList;
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
import com.ragagent.retrieval.engine.milvus.MilvusRestClient.Json;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Milvus 检索引擎仓储——对照 Go {@code repository/retriever/milvus/} 全包
 * （repository.go 1160 + filter.go 304 + move.go 61 + structs.go 37，约 1,560 行非测试）。
 *
 * <h2>协议口径</h2>
 * Go 用 milvus-sdk-go v2（gRPC）。本仓自持 <b>REST v2</b>（{@code /v2/vectordb/…}，零新依赖）：
 * 建集合（BM25 函数 + {@code SparseFloatVector} + {@code indexParams} 内联）、load、list、
 * upsert、query、search（向量与 BM25 文本）、delete 已对真服务端（{@code milvusdb/milvus:v2.6.11}）
 * 逐端点实测（见 known-issues）。
 *
 * <h2>语义要点（照 Go）</h2>
 * <ul>
 *   <li>集合按维度命名 {@code <base>_<dim>}；schema：{@code id}(VarChar PK) + {@code embedding}
 *       (FloatVector) + {@code content}(VarChar，enable_analyzer + enable_match) +
 *       {@code content_sparse}(稀疏，由 BM25 函数 {@code text_bm25_emb} 填充) + 五个 VarChar +
 *       {@code source_type}(Int64) + {@code is_enabled}(Bool)；</li>
 *   <li>索引：embedding HNSW(metric=MILVUS_METRIC_TYPE 缺省 IP, M=16, efConstruction=128)、
 *       content_sparse AUTOINDEX(metric=BM25)、chunk/knowledge/kb/source/is_enabled AUTOINDEX；</li>
 *   <li>{@code ensureCollection} 每次都 LoadCollection（缓存只挡"建表"；load 幂等）；</li>
 *   <li>行主键恒新 UUID（Upsert 语义 → 更新靠"查整行→改字段→回写"）；</li>
 *   <li>向量检索的 threshold 走<b>范围搜索的 radius</b>（threshold>0 才带）；关键词检索是
 *       BM25 全文（文本进 {@code data}、{@code annsField=content_sparse}），单集合失败只跳过、
 *       score 恒 1.0、合并后截 TopK；</li>
 *   <li>enabled 批量更新的失败<b>聚合后冒泡</b>（照 {@code errors.Join}）；tag 批量更新只 WARN；</li>
 *   <li>move 用 Upsert 重写整行（kb/tag），重复 ID → {@code invalid or repeated move index}。</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ol>
 *   <li>传输 gRPC → REST v2；行式 JSON 取代 SDK 的列式写入（服务端等价）；</li>
 *   <li>过滤表达式<b>内联字面量</b>（REST 无模板参数；Go 用 {@code {param}} + WithTemplateParam）——
 *       算子、括号、转义规则照 Go；</li>
 *   <li>稀疏列名 {@code SparseFloatVector}（REST 拼写；SDK 为 SparseVector）；</li>
 *   <li>{@code shardsNum} 在 REST create 里服务端忽略（实测 describe 恒 1）——照传保留配置面；</li>
 *   <li>load 是同步调用（SDK 是异步 task + Await）；错误文案合并为 {@code failed to load collection}；</li>
 *   <li>Go 的 {@code MILVUS_METRIC_TYPE} 进程级读 env（构造期一次）照旧；表名/度量口径不变。</li>
 * </ol>
 */
public class MilvusRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(MilvusRetrieveRepository.class);

    public static final String ENV_MILVUS_COLLECTION = "MILVUS_COLLECTION";
    public static final String ENV_MILVUS_METRIC_TYPE = "MILVUS_METRIC_TYPE";
    public static final String DEFAULT_COLLECTION_NAME = "weknora_embeddings";

    static final String FIELD_ID = "id";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_SOURCE_ID = "source_id";
    static final String FIELD_SOURCE_TYPE = "source_type";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_KNOWLEDGE_ID = "knowledge_id";
    static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    static final String FIELD_TAG_ID = "tag_id";
    static final String FIELD_EMBEDDING = "embedding";
    static final String FIELD_IS_ENABLED = "is_enabled";
    static final String FIELD_CONTENT_SPARSE = "content_sparse";

    /** 对照 {@code allFields}（结果解析的列序）。 */
    static final List<String> ALL_FIELDS = List.of(FIELD_ID, FIELD_CONTENT, FIELD_SOURCE_ID,
            FIELD_SOURCE_TYPE, FIELD_CHUNK_ID, FIELD_KNOWLEDGE_ID, FIELD_KNOWLEDGE_BASE_ID,
            FIELD_TAG_ID, FIELD_IS_ENABLED, FIELD_EMBEDDING);

    /** 对照 CopyIndices 的 {@code batchSize := 64}。 */
    static final int COPY_PAGE_SIZE = 64;
    /** 对照 move 的每页 100。 */
    static final int MOVE_PAGE_SIZE = 100;
    /** 对照 {@code index.NewHNSWIndex(metric, 16, 128)}。 */
    static final int HNSW_M = 16;
    static final int HNSW_EF_CONSTRUCTION = 128;

    private final MilvusRestClient client;
    private final String collectionBaseName;
    private final String metricType;
    private final int shardsNum;
    private final int replicaNumber;

    private final ConcurrentHashMap<Integer, Boolean> initializedCollections =
            new ConcurrentHashMap<>();

    public MilvusRetrieveRepository(MilvusRestClient client, String collectionBaseName,
                                    String metricType, int shardsNum, int replicaNumber) {
        this.client = client;
        this.collectionBaseName = collectionBaseName == null || collectionBaseName.isEmpty()
                ? DEFAULT_COLLECTION_NAME : collectionBaseName;
        this.metricType = metricType == null || metricType.isEmpty() ? "IP" : metricType;
        this.shardsNum = shardsNum;
        this.replicaNumber = replicaNumber;
    }

    /** 照 Go {@code NewMilvusRetrieveEngineRepository} + {@code createMilvusEngine}。 */
    public static MilvusRetrieveRepository create(String addr, String username, String password,
                                                  String dbName, IndexConfig indexCfg,
                                                  SsrfGuard guard) {
        log.info("[Milvus] Initializing Milvus retriever engine repository");
        String baseName = resolveCollectionName(indexCfg);
        String metric = resolveMetricType(System.getenv(ENV_MILVUS_METRIC_TYPE));
        MilvusRestClient client = new MilvusRestClient(addr, username, password, dbName, guard);
        MilvusRetrieveRepository repo = new MilvusRetrieveRepository(client, baseName, metric,
                indexCfg == null ? 0 : indexCfg.shardsNum,
                indexCfg == null ? 0 : indexCfg.replicaNumber);
        log.info("[Milvus] Using metric type: {}", metric);
        log.info("[Milvus] Successfully initialized repository");
        return repo;
    }

    /** 对照 {@code types.ResolveCollectionName(indexCfg, MILVUS_COLLECTION, default)}。 */
    static String resolveCollectionName(IndexConfig indexCfg) {
        if (indexCfg != null) {
            if (indexCfg.collectionPrefix != null && !indexCfg.collectionPrefix.isEmpty()) {
                return indexCfg.collectionPrefix;
            }
            if (indexCfg.collectionName != null && !indexCfg.collectionName.isEmpty()) {
                return indexCfg.collectionName;
            }
        }
        String env = System.getenv(ENV_MILVUS_COLLECTION);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return DEFAULT_COLLECTION_NAME;
    }

    /** 对照构造期的 {@code MILVUS_METRIC_TYPE} 解析（未知值 WARN 并回落 IP）。 */
    static String resolveMetricType(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "IP";
        }
        return switch (raw.toUpperCase(java.util.Locale.ROOT)) {
            case "COSINE" -> "COSINE";
            case "L2" -> "L2";
            case "IP" -> "IP";
            default -> {
                log.warn("[Milvus] Unknown MILVUS_METRIC_TYPE '{}', using default IP", raw);
                yield "IP";
            }
        };
    }

    // ── 引擎面 ──────────────────────────────────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_MILVUS;
    }

    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    /** 对照 {@code EstimateStorageSize}（IVF_FLAT 口径：向量 + 向量 + 16；元数据 32）。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null) {
            return 0;
        }
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            total += calculateStorageSize(toEmbedding(info, params));
        }
        log.info("[Milvus] Storage size for {} indices: {} bytes", indexInfoList.size(), total);
        return total;
    }

    static long calculateStorageSize(MilvusVectorEmbedding embedding) {
        long payload = 0;
        payload += utf8Length(embedding.content);
        payload += utf8Length(embedding.sourceId);
        payload += utf8Length(embedding.chunkId);
        payload += utf8Length(embedding.knowledgeId);
        payload += utf8Length(embedding.knowledgeBaseId);
        payload += 8; // source_type int64
        long vectorSizeBytes = 0;
        long indexBytes = 0;
        if (embedding.embedding != null) {
            vectorSizeBytes = embedding.embedding.length * 4L;
            indexBytes = vectorSizeBytes + 16;
        }
        final long metadataBytes = 32;
        return payload + vectorSizeBytes + indexBytes + metadataBytes;
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

    // ── 集合管理（照 ensureCollection） ────────────────────────────────────

    String collectionName(int dimension) {
        return collectionBaseName + "_" + dimension;
    }

    private void ensureCollection(int dimension) {
        if (initializedCollections.containsKey(dimension)) {
            return;
        }
        String name = collectionName(dimension);
        boolean has;
        try {
            has = client.hasCollection(name);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException(
                    "failed to check collection existence: " + e.getMessage(), e);
        }
        if (!has) {
            log.info("[Milvus] Creating collection {} with dimension {}", name, dimension);
            try {
                client.createCollection(collectionBody(name, dimension));
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to create collection: {}", e.getMessage());
                throw new IllegalStateException(
                        "failed to create collection: " + e.getMessage(), e);
            }
            log.info("[Milvus] Successfully created collection {}", name);
        }
        try {
            client.loadCollection(name, replicaNumber);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to load collection: {}", e.getMessage());
            throw new IllegalStateException("failed to load collection: " + e.getMessage(), e);
        }
        initializedCollections.put(dimension, true);
    }

    /** 集合体：schema（含 BM25 函数）+ indexParams（照 Go 的 WithIndexOptions 顺序）。 */
    ObjectNode collectionBody(String name, int dimension) {
        ObjectNode body = Json.object();
        body.put("collectionName", name);
        if (shardsNum > 0) {
            body.put("shardsNum", shardsNum);
        }
        ObjectNode schema = body.putObject("schema");
        schema.put("autoID", false);
        schema.put("enableDynamicField", false);
        schema.put("description", "WeKnora embeddings collection with dimension " + dimension);
        ArrayNode fields = schema.putArray("fields");
        fields.add(varcharField(FIELD_ID, 1024, true));
        fields.add(floatVectorField(FIELD_EMBEDDING, dimension));
        ObjectNode content = varcharField(FIELD_CONTENT, 65535, false);
        // 保留 max_length，再补 analyzer/match 开关（照 Go 的 WithEnableAnalyzer/WithEnableMatch）
        ObjectNode contentParams = (ObjectNode) content.path("elementTypeParams");
        contentParams.put("enable_analyzer", true);
        contentParams.put("enable_match", true);
        fields.add(content);
        ObjectNode sparse = Json.object();
        sparse.put("fieldName", FIELD_CONTENT_SPARSE);
        sparse.put("dataType", "SparseFloatVector");
        fields.add(sparse);
        fields.add(varcharField(FIELD_SOURCE_ID, 255, false));
        ObjectNode sourceType = Json.object();
        sourceType.put("fieldName", FIELD_SOURCE_TYPE);
        sourceType.put("dataType", "Int64");
        fields.add(sourceType);
        fields.add(varcharField(FIELD_CHUNK_ID, 255, false));
        fields.add(varcharField(FIELD_KNOWLEDGE_ID, 255, false));
        fields.add(varcharField(FIELD_KNOWLEDGE_BASE_ID, 255, false));
        fields.add(varcharField(FIELD_TAG_ID, 255, false));
        ObjectNode enabled = Json.object();
        enabled.put("fieldName", FIELD_IS_ENABLED);
        enabled.put("dataType", "Bool");
        fields.add(enabled);

        ArrayNode functions = schema.putArray("functions");
        ObjectNode bm25 = functions.addObject();
        bm25.put("name", "text_bm25_emb");
        bm25.put("type", "BM25");
        bm25.putArray("inputFieldNames").add(FIELD_CONTENT);
        bm25.putArray("outputFieldNames").add(FIELD_CONTENT_SPARSE);

        ArrayNode indexParams = body.putArray("indexParams");
        ObjectNode embeddingIndex = indexParams.addObject();
        embeddingIndex.put("fieldName", FIELD_EMBEDDING);
        embeddingIndex.put("indexName", FIELD_EMBEDDING);
        embeddingIndex.put("metricType", metricType);
        embeddingIndex.put("indexType", "HNSW");
        ObjectNode params = embeddingIndex.putObject("params");
        params.put("M", HNSW_M);
        params.put("efConstruction", HNSW_EF_CONSTRUCTION);
        ObjectNode sparseIndex = indexParams.addObject();
        sparseIndex.put("fieldName", FIELD_CONTENT_SPARSE);
        sparseIndex.put("indexName", FIELD_CONTENT_SPARSE);
        sparseIndex.put("metricType", "BM25");
        sparseIndex.put("indexType", "AUTOINDEX");
        for (String scalar : List.of(FIELD_CHUNK_ID, FIELD_KNOWLEDGE_ID, FIELD_KNOWLEDGE_BASE_ID,
                FIELD_SOURCE_ID, FIELD_IS_ENABLED)) {
            ObjectNode index = indexParams.addObject();
            index.put("fieldName", scalar);
            index.put("indexName", scalar);
            index.put("indexType", "AUTOINDEX");
        }
        return body;
    }

    private static ObjectNode varcharField(String name, int maxLength, boolean primary) {
        ObjectNode field = Json.object();
        field.put("fieldName", name);
        field.put("dataType", "VarChar");
        if (primary) {
            field.put("isPrimary", true);
        }
        field.putObject("elementTypeParams").put("max_length", maxLength);
        return field;
    }

    private static ObjectNode floatVectorField(String name, int dimension) {
        ObjectNode field = Json.object();
        field.put("fieldName", name);
        field.put("dataType", "FloatVector");
        field.putObject("elementTypeParams").put("dim", dimension);
        return field;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        log.debug("[Milvus] Saving index for chunk ID: {}", indexInfo.chunkId);
        MilvusVectorEmbedding row = toEmbedding(indexInfo, params);
        if (row.embedding == null || row.embedding.length == 0) {
            IllegalStateException e = new IllegalStateException(
                    "empty embedding vector for chunk ID: " + indexInfo.chunkId);
            log.error("[Milvus] {}", e.getMessage());
            throw e;
        }
        int dimension = row.embedding.length;
        ensureCollection(dimension);
        row.id = UUID.randomUUID().toString();
        ArrayNode rows = Json.array();
        rows.add(rowNode(row));
        try {
            client.upsert(collectionName(dimension), rows);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to save index: {}", e.getMessage());
            throw new IllegalStateException(e.getMessage() == null ? e.toString()
                    : e.getMessage(), e);
        }
        log.info("[Milvus] Successfully saved index for chunk ID: {}", indexInfo.chunkId);
    }

    /** 对照 {@code BatchSave}：按维度分组（升序确定性）→ 每组一次 Upsert。 */
    @Override
    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Milvus] Empty list provided to BatchSave, skipping");
            return;
        }
        log.info("[Milvus] Batch saving {} indices", embeddingList.size());
        Map<Integer, List<MilvusVectorEmbedding>> byDimension = new TreeMap<>();
        for (IndexInfo info : embeddingList) {
            MilvusVectorEmbedding row = toEmbedding(info, params);
            if (row.embedding == null || row.embedding.length == 0) {
                log.warn("[Milvus] Skipping empty embedding for chunk ID: {}", info.chunkId);
                continue;
            }
            byDimension.computeIfAbsent(row.embedding.length, k -> new ArrayList<>()).add(row);
        }
        if (byDimension.isEmpty()) {
            log.warn("[Milvus] No valid points to save after filtering");
            return;
        }
        int totalSaved = 0;
        for (Map.Entry<Integer, List<MilvusVectorEmbedding>> entry : byDimension.entrySet()) {
            int dimension = entry.getKey();
            ensureCollection(dimension);
            String collection = collectionName(dimension);
            ArrayNode rows = Json.array();
            for (MilvusVectorEmbedding row : entry.getValue()) {
                row.id = UUID.randomUUID().toString();
                rows.add(rowNode(row));
            }
            try {
                client.upsert(collection, rows);
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to execute batch operation for dimension {}: {}",
                        dimension, e.getMessage());
                throw new IllegalStateException("failed to batch save (dimension " + dimension
                        + "): " + e.getMessage(), e);
            }
            totalSaved += entry.getValue().size();
            log.info("[Milvus] Saved {} points to collection {}", entry.getValue().size(),
                    collection);
        }
        log.info("[Milvus] Successfully batch saved {} indices", totalSaved);
    }

    /** 行体（REST 行式 JSON；列名照 {@code createUpsert} 的列集合）。 */
    static ObjectNode rowNode(MilvusVectorEmbedding row) {
        ObjectNode node = Json.object();
        node.put(FIELD_ID, row.id == null ? "" : row.id);
        ArrayNode vector = node.putArray(FIELD_EMBEDDING);
        for (float v : row.embedding == null ? new float[0] : row.embedding) {
            vector.add(v);
        }
        node.put(FIELD_CONTENT, row.content == null ? "" : row.content);
        node.put(FIELD_SOURCE_ID, row.sourceId == null ? "" : row.sourceId);
        node.put(FIELD_SOURCE_TYPE, row.sourceType);
        node.put(FIELD_CHUNK_ID, row.chunkId == null ? "" : row.chunkId);
        node.put(FIELD_KNOWLEDGE_ID, row.knowledgeId == null ? "" : row.knowledgeId);
        node.put(FIELD_KNOWLEDGE_BASE_ID,
                row.knowledgeBaseId == null ? "" : row.knowledgeBaseId);
        node.put(FIELD_TAG_ID, row.tagId == null ? "" : row.tagId);
        node.put(FIELD_IS_ENABLED, row.isEnabled);
        return node;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(FIELD_CHUNK_ID, chunkIdList, dimension,
                "Empty chunk ID list provided for deletion, skipping",
                "failed to delete by chunk IDs");
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension,
                "Empty knowledge ID list provided for deletion, skipping",
                "failed to delete by knowledge IDs");
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(FIELD_SOURCE_ID, sourceIdList, dimension,
                "Empty Source ID list provided for deletion, skipping",
                "failed to delete by source IDs");
    }

    /** 照 {@code WithStringIDs}：{@code field in ["a","b"]}（不转义，照 SDK 原文形状）。 */
    static String inFilter(String field, List<String> ids) {
        List<String> rendered = new ArrayList<>(ids.size());
        for (String id : ids) {
            rendered.add("\"" + (id == null ? "" : id) + "\"");
        }
        return field + " in [" + String.join(",", rendered) + "]";
    }

    private void deleteByField(String field, List<String> ids, int dimension, String emptyWarning,
                               String errorPrefix) {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Milvus] {}", emptyWarning);
            return;
        }
        String collection = collectionName(dimension);
        log.info("[Milvus] Deleting indices by {} from {}, count: {}", field, collection,
                ids.size());
        try {
            client.delete(collection, inFilter(field, ids));
        } catch (RuntimeException e) {
            log.error("[Milvus] {}: {}", errorPrefix, e.getMessage());
            throw new IllegalStateException(errorPrefix + ": " + e.getMessage(), e);
        }
        log.info("[Milvus] Successfully deleted documents by {}", field);
    }

    // ── 批量更新（查整行 → 改字段 → Upsert 回写，照 Go） ──────────────────

    /**
     * 对照 {@code BatchUpdateChunkEnabledStatus}：按 true/false 分组，跨前缀集合逐组回写；
     * 失败<b>聚合后冒泡</b>（{@code errors.Join} 语义——"停用必须让索引不可搜"）。
     */
    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Milvus] Empty chunk status map provided, skipping");
            return;
        }
        log.info("[Milvus] Batch updating chunk enabled status, count: {}", chunkStatusMap.size());
        List<String> collections = listCollectionsOrThrow();
        List<String> enabledChunkIds = new ArrayList<>();
        List<String> disabledChunkIds = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabledChunkIds.add(entry.getKey());
            } else {
                disabledChunkIds.add(entry.getKey());
            }
        }
        List<String> failures = new ArrayList<>();
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            try {
                updateEnabledInCollection(collection, enabledChunkIds, true);
            } catch (RuntimeException e) {
                failures.add("update enabled chunks in " + collection + ": " + e.getMessage());
            }
            try {
                updateEnabledInCollection(collection, disabledChunkIds, false);
            } catch (RuntimeException e) {
                failures.add("update disabled chunks in " + collection + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            String joined = String.join("; ", failures);
            log.warn("[Milvus] Failed to update chunk enabled status: {}", joined);
            throw new IllegalStateException(joined);
        }
        log.info("[Milvus] Batch update chunk enabled status completed");
    }

    private void updateEnabledInCollection(String collection, List<String> chunkIds,
                                           boolean enabled) {
        if (chunkIds.isEmpty()) {
            return;
        }
        List<MilvusVectorEmbedding> rows = searchByFilter(collection,
                MilvusFilter.Condition.in(FIELD_CHUNK_ID, chunkIds));
        if (rows.isEmpty()) {
            return;
        }
        ArrayNode data = Json.array();
        for (MilvusVectorEmbedding row : rows) {
            row.isEnabled = enabled;
            data.add(rowNode(row));
        }
        client.upsert(collection, data);
    }

    /** 对照 {@code BatchUpdateChunkTagID}：逐 tag 组回写；失败只 WARN 继续。 */
    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Milvus] Empty chunk tag map provided, skipping");
            return;
        }
        log.info("[Milvus] Batch updating chunk tag ID, count: {}", chunkTagMap.size());
        List<String> collections = listCollectionsOrThrow();
        Map<String, List<String>> tagGroups = new TreeMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            tagGroups.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, List<String>> group : tagGroups.entrySet()) {
                List<MilvusVectorEmbedding> rows;
                try {
                    rows = searchByFilter(collection,
                            MilvusFilter.Condition.in(FIELD_CHUNK_ID, group.getValue()));
                } catch (RuntimeException e) {
                    log.warn("[Milvus] Failed to search chunks in {}: {}", collection,
                            e.getMessage());
                    continue;
                }
                if (rows.isEmpty()) {
                    continue;
                }
                ArrayNode data = Json.array();
                for (MilvusVectorEmbedding row : rows) {
                    row.tagId = group.getKey();
                    data.add(rowNode(row));
                }
                try {
                    client.upsert(collection, data);
                } catch (RuntimeException e) {
                    log.warn("[Milvus] Failed to update chunks in {}: {}", collection,
                            e.getMessage());
                }
            }
        }
        log.info("[Milvus] Batch update chunk tag ID completed");
    }

    private List<String> listCollectionsOrThrow() {
        try {
            return client.listCollections();
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
    }

    /** 照 Go 的集合名前缀过滤：严格长于 base 且以此为前缀。 */
    private boolean isPrefixed(String collection) {
        return collection.length() > collectionBaseName.length()
                && collection.startsWith(collectionBaseName);
    }

    // ── 过滤器（照 getBaseFilterForQuery） ─────────────────────────────────

    static String baseFilter(RetrieveParams params) {
        List<MilvusFilter.Condition> filters = new ArrayList<>();
        if (params != null) {
            if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
                filters.add(MilvusFilter.Condition.in(FIELD_KNOWLEDGE_BASE_ID,
                        params.knowledgeBaseIds));
            }
            if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
                filters.add(MilvusFilter.Condition.in(FIELD_KNOWLEDGE_ID, params.knowledgeIds));
            }
            if (params.tagIds != null && !params.tagIds.isEmpty()) {
                filters.add(MilvusFilter.Condition.in(FIELD_TAG_ID, params.tagIds));
            }
            if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
                filters.add(MilvusFilter.Condition.notIn(FIELD_KNOWLEDGE_ID,
                        params.excludeKnowledgeIds));
            }
            if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
                filters.add(MilvusFilter.Condition.notIn(FIELD_CHUNK_ID, params.excludeChunkIds));
            }
        }
        filters.add(MilvusFilter.Condition.equal(FIELD_IS_ENABLED, true));
        return MilvusFilter.expr(MilvusFilter.Condition.and(filters));
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
                log.error("[Milvus] invalid retriever type: {}", retrieverType);
                throw new IllegalStateException("invalid retriever type: " + retrieverType);
            }
        };
    }

    /**
     * 对照 {@code VectorRetrieve}：判存 → 基础过滤 + 范围搜索（threshold>0 → radius）→
     * distance 即分数；类不存在 → 空结果。
     */
    private List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        log.info("[Milvus] Vector retrieval: dim={}, topK={}, threshold={}",
                dimension, params.topK, params.threshold);
        String collection = collectionName(dimension);
        boolean has;
        try {
            has = client.hasCollection(collection);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException("failed to check collection: " + e.getMessage(), e);
        }
        if (!has) {
            log.warn("[Milvus] Collection {} does not exist, returning empty results", collection);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        String filter;
        try {
            filter = baseFilter(params);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to build base filter: {}", e.getMessage());
            throw new IllegalStateException("failed to build filter: " + e.getMessage(), e);
        }
        ArrayNode data = Json.array();
        ArrayNode vector = data.addArray();
        for (float v : embedding) {
            vector.add(v);
        }
        JsonNode hits;
        try {
            hits = client.search(collection, data, FIELD_EMBEDDING, filter, params.topK,
                    List.of("*"), params.threshold > 0 ? params.threshold : null);
        } catch (RuntimeException e) {
            log.error("[Milvus] Vector search failed: {}", e.getMessage());
            throw new IllegalStateException("failed to search: " + e.getMessage(), e);
        }
        List<IndexWithScore> results = parseSearchHits(hits, EngineTypes.MATCH_EMBEDDING, false);
        if (results.isEmpty()) {
            log.warn("[Milvus] No vector matches found that meet threshold {}", params.threshold);
        } else {
            log.info("[Milvus] Vector retrieval found {} results", results.size());
        }
        return buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：跨前缀集合 BM25 全文检索（文本进 data、
     * annsField=content_sparse）；单集合失败只跳过；score 恒 1.0；合并后截 TopK。
     */
    private List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query;
        log.info("[Milvus] Performing keywords retrieval with query: {}, topK: {}", query,
                params.topK);
        List<String> collections = listCollectionsOrThrow();
        List<IndexWithScore> allResults = new ArrayList<>();
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            String filter;
            try {
                filter = baseFilter(params);
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to build base filter: {}", e.getMessage());
                continue;
            }
            ArrayNode data = Json.array();
            data.add(query);
            JsonNode hits;
            try {
                hits = client.search(collection, data, FIELD_CONTENT_SPARSE, filter, params.topK,
                        List.of("*"), null);
            } catch (RuntimeException e) {
                log.error("[Milvus] Keywords search failed: {}", e.getMessage());
                continue;
            }
            allResults.addAll(parseSearchHits(hits, EngineTypes.MATCH_KEYWORDS, true));
        }
        int topK = Math.max(0, params.topK);
        if (allResults.size() > topK) {
            allResults = new ArrayList<>(allResults.subList(0, topK));
        }
        if (allResults.isEmpty()) {
            log.warn("[Milvus] No keyword matches found for query: {}", query);
        } else {
            log.info("[Milvus] Keywords retrieval found {} results", allResults.size());
        }
        return buildRetrieveResult(allResults, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /** 解析 search 返回：{@code data} 是命中数组（每行含 id + distance + 字段）。 */
    static List<IndexWithScore> parseSearchHits(JsonNode hits, int matchType,
                                                boolean forceKeywordScore) {
        List<IndexWithScore> results = new ArrayList<>();
        if (hits == null || !hits.isArray()) {
            return results;
        }
        for (JsonNode hit : hits) {
            MilvusVectorEmbedding row = fromNode(hit);
            IndexWithScore out = new IndexWithScore();
            out.id = row.id;
            out.sourceId = row.sourceId;
            out.sourceType = row.sourceType;
            out.chunkId = row.chunkId;
            out.knowledgeId = row.knowledgeId;
            out.knowledgeBaseId = row.knowledgeBaseId;
            out.tagId = row.tagId;
            out.content = row.content;
            out.score = forceKeywordScore ? 1.0 : hit.path("distance").asDouble(0);
            out.matchType = matchType;
            results.add(out);
        }
        return results;
    }

    /** 行节点 → 模型（照 {@code convertResultSet} 的逐列读法；缺列留空）。 */
    static MilvusVectorEmbedding fromNode(JsonNode node) {
        MilvusVectorEmbedding row = new MilvusVectorEmbedding();
        row.id = node.path(FIELD_ID).asText("");
        row.content = node.path(FIELD_CONTENT).asText("");
        row.sourceId = node.path(FIELD_SOURCE_ID).asText("");
        row.sourceType = node.path(FIELD_SOURCE_TYPE).asInt(0);
        row.chunkId = node.path(FIELD_CHUNK_ID).asText("");
        row.knowledgeId = node.path(FIELD_KNOWLEDGE_ID).asText("");
        row.knowledgeBaseId = node.path(FIELD_KNOWLEDGE_BASE_ID).asText("");
        row.tagId = node.path(FIELD_TAG_ID).asText("");
        row.isEnabled = node.path(FIELD_IS_ENABLED).asBoolean(false);
        JsonNode embedding = node.get(FIELD_EMBEDDING);
        if (embedding != null && embedding.isArray()) {
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            row.embedding = vector;
        }
        return row;
    }

    /** 对照 {@code searchByFilter}：Query（无分数的整行读取，供更新/拷贝/move 用）。 */
    List<MilvusVectorEmbedding> searchByFilter(String collection,
                                               MilvusFilter.Condition condition) {
        String filter;
        try {
            filter = MilvusFilter.expr(condition);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        JsonNode rows = client.query(collection, filter, List.of("*"), null, null);
        List<MilvusVectorEmbedding> out = new ArrayList<>();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                out.add(fromNode(row));
            }
        }
        return out;
    }

    // ── CopyIndices（照 Go：offset 分页 + 三态 SourceID + isEnabled 沿用源值） ──

    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("[Milvus] Copying indices from source knowledge base {} to target knowledge base"
                + " {}, count: {}, dimension: {}", sourceKnowledgeBaseId, targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size(), dimension);
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Milvus] Empty mapping, skipping copy");
            return;
        }
        String collection = collectionName(dimension);
        ensureCollection(dimension);
        MilvusFilter.Condition filter = MilvusFilter.Condition.equal(FIELD_KNOWLEDGE_BASE_ID,
                sourceKnowledgeBaseId);
        int offset = 0;
        int totalCopied = 0;
        while (true) {
            JsonNode page;
            try {
                page = client.query(collection, MilvusFilter.expr(filter), List.of("*"),
                        COPY_PAGE_SIZE, offset);
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to query source points: {}", e.getMessage());
                throw new IllegalStateException(e.getMessage(), e);
            }
            int pageSize = page == null || !page.isArray() ? 0 : page.size();
            if (pageSize == 0) {
                break;
            }
            ArrayNode targets = Json.array();
            for (JsonNode node : page) {
                MilvusVectorEmbedding source = fromNode(node);
                String targetChunkId = sourceToTargetChunkIdMap.get(source.chunkId);
                if (targetChunkId == null) {
                    log.warn("[Milvus] Source chunk {} not found in target mapping, skipping",
                            source.chunkId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap == null ? null
                        : sourceToTargetKbIdMap.get(source.knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[Milvus] Source knowledge {} not found in target mapping, skipping",
                            source.knowledgeId);
                    continue;
                }
                MilvusVectorEmbedding target = new MilvusVectorEmbedding();
                target.id = UUID.randomUUID().toString();
                target.content = source.content;
                target.sourceId = translateSourceId(source.sourceId, source.chunkId, targetChunkId);
                target.sourceType = source.sourceType;
                target.chunkId = targetChunkId;
                target.knowledgeId = targetKnowledgeId;
                target.knowledgeBaseId = targetKnowledgeBaseId;
                target.tagId = source.tagId;
                target.embedding = source.embedding;
                target.isEnabled = source.isEnabled;
                targets.add(rowNode(target));
            }
            if (!targets.isEmpty()) {
                try {
                    client.upsert(collection, targets);
                } catch (RuntimeException e) {
                    log.error("[Milvus] Failed to batch upsert target points: {}", e.getMessage());
                    throw new IllegalStateException(e.getMessage(), e);
                }
                totalCopied += targets.size();
                log.info("[Milvus] Successfully copied batch, batch size: {}, total copied: {}",
                        targets.size(), totalCopied);
            }
            if (pageSize < COPY_PAGE_SIZE) {
                break;
            }
            offset += pageSize;
        }
        log.info("[Milvus] Index copy completed, total copied: {}", totalCopied);
    }

    /**
     * 对照 {@code translateSourceID} 的三态：普通 chunk → targetChunkID；生成型问题
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

    // ── move（照 move.go：drain 循环 + seen 守卫 + Upsert 整行） ───────────

    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        String collection = collectionName(dimension);
        MilvusFilter.Condition filter = MilvusFilter.Condition.and(List.of(
                MilvusFilter.Condition.equal(FIELD_KNOWLEDGE_BASE_ID, sourceKb),
                MilvusFilter.Condition.equal(FIELD_KNOWLEDGE_ID, knowledgeId)));
        Set<String> seen = new LinkedHashSet<>();
        while (true) {
            JsonNode page = client.query(collection, MilvusFilter.expr(filter), List.of("*"),
                    MOVE_PAGE_SIZE, null);
            int pageSize = page == null || !page.isArray() ? 0 : page.size();
            if (pageSize == 0) {
                return; // 空集 = 完成
            }
            ArrayNode batch = Json.array();
            for (JsonNode node : page) {
                MilvusVectorEmbedding row = fromNode(node);
                if (row.id == null || row.id.isEmpty() || !seen.add(row.id)) {
                    throw new IllegalStateException("invalid or repeated move index");
                }
                row.knowledgeBaseId = targetKb;
                row.tagId = "";
                batch.add(rowNode(row));
            }
            try {
                client.upsert(collection, batch);
            } catch (RuntimeException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    // ── 行映射与辅助 ───────────────────────────────────────────────────────

    /** 对照 {@code toMilvusVectorEmbedding}：embedding 按 SourceID 取（缺失 → null）。 */
    static MilvusVectorEmbedding toEmbedding(IndexInfo info, Map<String, Object> params) {
        MilvusVectorEmbedding row = new MilvusVectorEmbedding();
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
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_MILVUS, retrieverType));
    }

    // ── test-connection 探针（照 testMilvusConnection：版本恒 ""） ─────────

    /**
     * 连通性探针：Go 用 TCP 拨号（因 protobuf 命名冲突不走 SDK），版本恒 ""。本仓有 REST
     * 客户端，改用 {@code collections/list} 做<b>更强的</b>连通性+认证验证（仍返回 ""——
     * Milvus 无版本端点，照 Go 的空版本口径）。
     */
    public static String testConnection(String addr, String username, String password,
                                        String dbName, SsrfGuard guard) {
        MilvusRestClient client = new MilvusRestClient(addr, username, password, dbName, guard);
        client.probe();
        return "";
    }
}
