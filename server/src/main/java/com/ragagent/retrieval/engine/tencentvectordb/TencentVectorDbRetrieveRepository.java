package com.ragagent.retrieval.engine.tencentvectordb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbBm25.SparseVecItem;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRestClient.Json;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRestClient.TencentVectorDbApiException;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * 腾讯 VectorDB 检索引擎仓储——对照 Go {@code repository/retriever/tencentvectordb/}
 * （repository.go 782 + structs.go 57 + move.go 33，约 870 行非测试）。
 *
 * <h2>协议口径</h2>
 * Go 用官方 SDK 的 {@code tcvectordb.RpcClient}（集合/文档操作走 gRPC/olama，仅 database 走
 * HTTP）；本仓自持 SDK 的 <b>HTTP 面</b>（{@code /collection/*}、{@code /document/*}，
 * {@code Authorization: Bearer account=…&api_key=…}）——同一服务端支持的等价接口，零新依赖、
 * 不引 protobuf（详见 {@link TencentVectorDbRestClient}）。
 *
 * <h2>语义要点（照 Go，别"顺手统一"）</h2>
 * <ul>
 *   <li>集合命名有<b>开关</b>：{@code indexCfg == nil || collectionName 为空} → 带维度后缀
 *       {@code <base>_<dim>}（默认）；否则<b>单集合</b>（所有维度混存）；前缀匹配也随之变
 *       （带后缀 → {@code base_ 前缀}，单集合 → 精确名）；</li>
 *   <li>建集合一次带齐索引：vector(HNSW, COSINE, M=16, efConstruction=200) +
 *       sparse_vector(SPARSE_INVERTED/inverted, IP) + 9 个标量(primaryKey/filter)；
 *       shard/replica 缺省 1/1（replica 可被 {@code TENCENT_VECTORDB_REPLICA_NUMBER} 覆盖）；</li>
 *   <li>写入是 <b>Upsert + buildIndex=true</b>，稀疏向量由<b>客户端 BM25</b> 计算（见
 *       {@link TencentVectorDbBm25}）；id 兜底序 ID→SourceID→ChunkID；</li>
 *   <li>删除用 filter {@code field in ("…")}（照 {@code tcvectordb.In}：双引号 + 圆括号）；</li>
 *   <li>enabled/tag 批量更新走 <b>Update API</b>（不是查改回写），跨"匹配到的集合"逐个更新；
 *       <b>任一集合失败即返回错误</b>（照 Go，不聚合也不忽略）；</li>
 *   <li>向量检索：dim=0 → 空；集合不存在 → 空；{@code params:{ef:100}}；
 *       threshold&gt;0 → {@code radius}；TopK ≤ 0 → 10；</li>
 *   <li>关键词检索：BM25 查询向量 + {@code /document/fullTextSearch}（fieldName=sparse_vector），
 *       跨匹配集合逐个搜，单集合失败只 WARN 跳过——但<b>全部匹配集合都失败</b>时返回错误
 *       （提示老的集合缺 sparse 索引、需重导入）；结果按 score 降序（稳定排序）后截 limit；</li>
 *   <li>拷贝：{@code chunk_id in (…)} +（源 kb 非空时）{@code knowledge_base_id in (…)} 的
 *       Query（retrieveVector=true，offset 分页 500）；三态 SourceID 的第 3 态是
 *       <b>sha256(targetChunkID\0sourceChunkID\0originalSourceID) 前 16 个十六进制</b>
 *       （与 Milvus 的"新 UUID"不同！）；目标 id = 改后的 SourceID；</li>
 *   <li>move 走 Update API（kb + tag 清空），不做 seen 守卫；</li>
 *   <li>存储估算：content 字节 + 向量 4×dim + content×2 + (四 id 字节 + 256)。</li>
 * </ul>
 */
public class TencentVectorDbRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(TencentVectorDbRetrieveRepository.class);

    public static final String ENV_DATABASE = "TENCENT_VECTORDB_DATABASE";
    public static final String ENV_COLLECTION = "TENCENT_VECTORDB_COLLECTION";
    public static final String ENV_REPLICA_NUMBER = "TENCENT_VECTORDB_REPLICA_NUMBER";
    public static final String DEFAULT_DATABASE_NAME = "weknora";
    public static final String DEFAULT_COLLECTION_NAME = "weknora_embeddings";
    public static final int DEFAULT_REPLICA_NUMBER = 1;

    static final String FIELD_ID = "id";
    static final String FIELD_VECTOR = "vector";
    static final String FIELD_SPARSE_VECTOR = "sparse_vector";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_SOURCE_ID = "source_id";
    static final String FIELD_SOURCE_TYPE = "source_type";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_KNOWLEDGE_ID = "knowledge_id";
    static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    static final String FIELD_TAG_ID = "tag_id";
    static final String FIELD_IS_ENABLED = "is_enabled";

    /** 对照 {@code copyIndicesQueryPageSize = 500}。 */
    static final int COPY_PAGE_SIZE = 500;
    /** 对照搜索的 {@code Ef: 100}。 */
    static final int SEARCH_EF = 100;

    final TencentVectorDbRestClient client;
    final String databaseName;
    private final String collectionBaseName;
    private final boolean useDimensionSuffix;
    private final int shardsNum;
    private final int replicasNum;

    final TencentVectorDbSearchOps searchOps;
    final TencentVectorDbWriteOps writeOps;

    private final ConcurrentHashMap<Integer, Boolean> initialized = new ConcurrentHashMap<>();
    private volatile TencentVectorDbBm25 bm25;
    private volatile RuntimeException bm25Error;

    public TencentVectorDbRetrieveRepository(TencentVectorDbRestClient client, String databaseName,
                                             String collectionBaseName, boolean useDimensionSuffix,
                                             int shardsNum, int replicasNum) {
        this.client = client;
        this.databaseName = databaseName == null || databaseName.isEmpty()
                ? DEFAULT_DATABASE_NAME : databaseName;
        this.collectionBaseName = collectionBaseName == null || collectionBaseName.isEmpty()
                ? DEFAULT_COLLECTION_NAME : collectionBaseName;
        this.useDimensionSuffix = useDimensionSuffix;
        this.shardsNum = shardsNum <= 0 ? 1 : shardsNum;
        this.replicasNum = replicasNum;
        this.searchOps = new TencentVectorDbSearchOps(this);
        this.writeOps = new TencentVectorDbWriteOps(this);
    }

    /** 照 {@code NewTencentVectorDBRetrieveEngineRepository} + {@code createTencentVectorDBEngine}。 */
    public static TencentVectorDbRetrieveRepository create(String addr, String username,
                                                           String apiKey, String database,
                                                           IndexConfig indexCfg, SsrfGuard guard) {
        TencentVectorDbRestClient client = new TencentVectorDbRestClient(addr, username, apiKey,
                guard);
        return new TencentVectorDbRetrieveRepository(client, resolveDatabase(database),
                resolveCollectionBase(indexCfg), shouldUseDimensionSuffix(indexCfg),
                indexCfg == null ? 1 : indexCfg.shardsNum, resolveReplicaNumber(indexCfg));
    }

    static String resolveDatabase(String database) {
        if (database != null && !database.isEmpty()) {
            return database;
        }
        String env = com.ragagent.retrieval.config.RetrievalEnvLookup.get(ENV_DATABASE);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return DEFAULT_DATABASE_NAME;
    }

    /** 对照 {@code types.ResolveCollectionName(indexCfg, TENCENT_VECTORDB_COLLECTION, default)}。 */
    static String resolveCollectionBase(IndexConfig indexCfg) {
        if (indexCfg != null) {
            if (indexCfg.collectionName != null && !indexCfg.collectionName.isEmpty()) {
                return indexCfg.collectionName;
            }
            if (indexCfg.collectionPrefix != null && !indexCfg.collectionPrefix.isEmpty()) {
                return indexCfg.collectionPrefix;
            }
        }
        String env = com.ragagent.retrieval.config.RetrievalEnvLookup.get(ENV_COLLECTION);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return DEFAULT_COLLECTION_NAME;
    }

    /** 照 {@code shouldUseDimensionSuffix}：indexCfg 为空或 collectionName 为空 → 带维度后缀。 */
    static boolean shouldUseDimensionSuffix(IndexConfig indexCfg) {
        return indexCfg == null || indexCfg.collectionName == null
                || indexCfg.collectionName.isEmpty();
    }

    /** 照 {@code resolveReplicaNumber}：indexCfg > env > 1；env 非法或负数回落缺省。 */
    static int resolveReplicaNumber(IndexConfig indexCfg) {
        if (indexCfg != null && indexCfg.replicaNumber > 0) {
            return indexCfg.replicaNumber;
        }
        String raw = com.ragagent.retrieval.config.RetrievalEnvLookup.get(ENV_REPLICA_NUMBER);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                int replicas = Integer.parseInt(raw.trim());
                if (replicas >= 0) {
                    return replicas;
                }
            } catch (NumberFormatException ignored) {
                // 照 Go：失败回落缺省
            }
        }
        return DEFAULT_REPLICA_NUMBER;
    }

    // ── 引擎面 ──────────────────────────────────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_TENCENT_VECTORDB;
    }

    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    /** 照 {@code EstimateStorageSize}（注意 content 计两次：一次字节、一次 ×2）。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null) {
            return 0;
        }
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            Document doc = toDocument(info, params);
            total += byteLength(doc.content);
            total += (long) doc.vector.length * 4;
            total += (long) byteLength(doc.content) * 2;
            total += byteLength(doc.sourceId) + byteLength(doc.chunkId)
                    + byteLength(doc.knowledgeId) + byteLength(doc.knowledgeBaseId) + 256;
        }
        log.info("[TencentVectorDB] estimated storage size for {} indices: {} bytes",
                indexInfoList.size(), total);
        return total;
    }

    static long byteLength(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ── 集合管理 ───────────────────────────────────────────────────────────

    String collectionName(int dimension) {
        return useDimensionSuffix ? collectionBaseName + "_" + dimension : collectionBaseName;
    }

    boolean matchesCollection(String name) {
        return useDimensionSuffix ? name.startsWith(collectionBaseName + "_")
                : name.equals(collectionBaseName);
    }

    void ensureCollection(int dimension) {
        if (initialized.containsKey(dimension)) {
            return;
        }
        try {
            client.createDatabaseIfNotExists(databaseName);
        } catch (RuntimeException e) {
            throw new IllegalStateException("tencent vectordb ensure database " + databaseName
                    + ": " + e.getMessage(), e);
        }
        String name = collectionName(dimension);
        boolean exists;
        try {
            exists = client.existsCollection(databaseName, name);
        } catch (RuntimeException e) {
            throw new IllegalStateException("tencent vectordb check collection " + name + ": "
                    + e.getMessage(), e);
        }
        if (exists) {
            initialized.put(dimension, true);
            return;
        }
        try {
            client.createCollection(createBody(name, dimension));
        } catch (RuntimeException e) {
            if (isCollectionAlreadyExistsError(e)) {
                log.info("[TencentVectorDB] collection {} already exists, skip create", name);
                initialized.put(dimension, true);
                return;
            }
            throw new IllegalStateException("tencent vectordb create collection " + name + ": "
                    + e.getMessage(), e);
        }
        initialized.put(dimension, true);
    }

    /** 照 {@code isCollectionAlreadyExistsErr}：含 "code: 15202" 或 "already exist"。 */
    static boolean isCollectionAlreadyExistsError(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("code: 15202") || msg.contains("already exist");
    }

    /** 建集合请求体（照 Go 的 Indexes 三项；字段/索引类型串照 SDK 常量）。 */
    ObjectNode createBody(String name, int dimension) {
        ObjectNode body = Json.object();
        body.put("database", databaseName);
        body.put("collection", name);
        body.put("replicaNum", replicasNum);
        body.put("shardNum", shardsNum);
        body.put("description", "WeKnora embeddings collection with dimension " + dimension);
        ArrayNode indexes = body.putArray("indexes");
        // vector 索引：HNSW + COSINE + M16/efConstruction200
        ObjectNode vectorIndex = indexes.addObject();
        vectorIndex.put("fieldName", FIELD_VECTOR);
        vectorIndex.put("fieldType", "vector");
        vectorIndex.put("indexType", "HNSW");
        vectorIndex.put("dimension", dimension);
        vectorIndex.put("metricType", "COSINE");
        ObjectNode params = vectorIndex.putObject("params");
        params.put("M", 16);
        params.put("efConstruction", 200);
        // 稀疏向量索引：inverted + IP
        ObjectNode sparseIndex = indexes.addObject();
        sparseIndex.put("fieldName", FIELD_SPARSE_VECTOR);
        sparseIndex.put("fieldType", "sparseVector");
        sparseIndex.put("indexType", "inverted");
        sparseIndex.put("metricType", "IP");
        // 标量索引：主键 + filter
        addScalarIndex(indexes, FIELD_ID, "string", "primaryKey");
        addScalarIndex(indexes, FIELD_CONTENT, "string", "filter");
        addScalarIndex(indexes, FIELD_SOURCE_ID, "string", "filter");
        addScalarIndex(indexes, FIELD_SOURCE_TYPE, "uint64", "filter");
        addScalarIndex(indexes, FIELD_CHUNK_ID, "string", "filter");
        addScalarIndex(indexes, FIELD_KNOWLEDGE_ID, "string", "filter");
        addScalarIndex(indexes, FIELD_KNOWLEDGE_BASE_ID, "string", "filter");
        addScalarIndex(indexes, FIELD_TAG_ID, "string", "filter");
        addScalarIndex(indexes, FIELD_IS_ENABLED, "uint64", "filter");
        return body;
    }

    private static void addScalarIndex(ArrayNode indexes, String field, String fieldType,
                                       String indexType) {
        ObjectNode node = indexes.addObject();
        node.put("fieldName", field);
        node.put("fieldType", fieldType);
        node.put("indexType", indexType);
    }

    // ── BM25 编码器（懒加载，照 sync.Once 语义：失败也缓存） ───────────────

    TencentVectorDbBm25 bm25() {
        TencentVectorDbBm25 local = bm25;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (bm25Error != null) {
                throw bm25Error;
            }
            if (bm25 != null) {
                return bm25;
            }
            try {
                bm25 = TencentVectorDbBm25.create();
                return bm25;
            } catch (RuntimeException e) {
                bm25Error = new IllegalStateException(
                        "tencent vectordb init BM25 encoder: " + e.getMessage(), e);
                throw bm25Error;
            }
        }
    }

    /** 测试口：注入 BM25（用内存参数表，避免下载 85 MB 参数文件）。 */
    void useBm25ForTest(TencentVectorDbBm25 encoder) {
        this.bm25 = encoder;
    }


    /** 照 {@code BatchSave}：按维度分组 → BM25 编码 → Upsert（buildIndex=true）。 */

    /** 照 {@code tcvectordb.In}：{@code key in ("v1","v2")}（双引号 + 圆括号）。 */
    static String in(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>(values.size());
        for (String v : values) {
            rendered.add("\"" + (v == null ? "" : v) + "\"");
        }
        return key + " in (" + String.join(",", rendered) + ")";
    }

    static String notIn(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>(values.size());
        for (String v : values) {
            rendered.add("\"" + (v == null ? "" : v) + "\"");
        }
        return key + " not in (" + String.join(",", rendered) + ")";
    }


    List<String> listCollectionNames() {
        JsonNode res = client.listCollections(databaseName);
        List<String> names = new ArrayList<>();
        for (JsonNode node : res.path("collections")) {
            String name = node.path("collectionName").asText(node.asText(""));
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    // ── 过滤器 ─────────────────────────────────────────────────────────────

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return searchOps.retrieve(params);
    }



    /**
     * 三态：等 chunkID → targetChunkID；{@code "<chunkID>-<qid>"} → 换前缀；
     * 其他 → {@code "<targetChunkID>-<sha256(targetChunkID NUL sourceChunkID NUL original) 前 16 hex>"}。
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(targetChunkId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(srcChunk.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(original.getBytes(StandardCharsets.UTF_8));
            byte[] sum = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", sum[i]));
            }
            return targetChunkId + "-" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }

    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        writeOps.save(indexInfo, params);
    }

    @Override
    public void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params)
            throws Exception {
        writeOps.batchSave(indexInfoList, params);
    }

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteByChunkIdList(chunkIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        writeOps.deleteBySourceIdList(sourceIdList, dimension, knowledgeType);
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        writeOps.deleteByKnowledgeIdList(knowledgeIdList, dimension, knowledgeType);
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
    public void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        writeOps.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
    }


    // ── move（照 move.go：Update API 一次搞定，无 seen 守卫） ──────────────

    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        ObjectNode query = Json.object();
        query.put("filter", in(FIELD_KNOWLEDGE_BASE_ID, List.of(sourceKb)) + " and "
                + in(FIELD_KNOWLEDGE_ID, List.of(knowledgeId)));
        ObjectNode fields = Json.object();
        fields.put(FIELD_KNOWLEDGE_BASE_ID, targetKb);
        fields.put(FIELD_TAG_ID, "");
        client.update(databaseName, collectionName(dimension), query, fields);
    }

    // ── 映射 ────────────────────────────────────────────────────────────────

    /** 行模型（照 {@code vectorEmbedding}）。 */
    static final class Document {

        String id = "";
        String content = "";
        String sourceId = "";
        int sourceType;
        String chunkId = "";
        String knowledgeId = "";
        String knowledgeBaseId = "";
        String tagId = "";
        float[] vector = new float[0];
        List<SparseVecItem> sparseVector = List.of();
        boolean isEnabled;
        double score;
    }

    /** 照 {@code toVectorEmbedding}：id 兜底 ID→SourceID→ChunkID；embedding 查 vector/embedding 两个键。 */
    static Document toDocument(IndexInfo info, Map<String, Object> params) {
        Document doc = new Document();
        doc.id = info.id == null ? "" : info.id;
        doc.content = cleanInvalidUtf8(info.content);
        doc.sourceId = info.sourceId == null ? "" : info.sourceId;
        doc.sourceType = info.sourceType;
        doc.chunkId = info.chunkId == null ? "" : info.chunkId;
        doc.knowledgeId = info.knowledgeId == null ? "" : info.knowledgeId;
        doc.knowledgeBaseId = info.knowledgeBaseId == null ? "" : info.knowledgeBaseId;
        doc.tagId = info.tagId == null ? "" : info.tagId;
        doc.isEnabled = info.isEnabled;
        if (doc.id.isEmpty()) {
            doc.id = doc.sourceId;
        }
        if (doc.id.isEmpty()) {
            doc.id = doc.chunkId;
        }
        if (params != null) {
            Object raw = params.containsKey(FIELD_VECTOR) ? params.get(FIELD_VECTOR)
                    : params.get("embedding");
            if (raw instanceof Map<?, ?> map) {
                doc.vector = lookupEmbedding(map, info);
            }
        }
        return doc;
    }

    private static float[] lookupEmbedding(Map<?, ?> embeddingMap, IndexInfo info) {
        Object bySource = embeddingMap.get(info.sourceId);
        Object value = bySource != null ? bySource : embeddingMap.get(info.chunkId);
        if (value instanceof float[] f) {
            return f;
        }
        if (value instanceof List<?> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
            }
            return out;
        }
        return new float[0];
    }

    static Document fromDocument(JsonNode node) {
        Document doc = new Document();
        doc.id = node.path(FIELD_ID).asText("");
        doc.content = node.path(FIELD_CONTENT).asText("");
        doc.sourceId = node.path(FIELD_SOURCE_ID).asText("");
        doc.sourceType = node.path(FIELD_SOURCE_TYPE).asInt(0);
        doc.chunkId = node.path(FIELD_CHUNK_ID).asText("");
        doc.knowledgeId = node.path(FIELD_KNOWLEDGE_ID).asText("");
        doc.knowledgeBaseId = node.path(FIELD_KNOWLEDGE_BASE_ID).asText("");
        doc.tagId = node.path(FIELD_TAG_ID).asText("");
        doc.isEnabled = node.path(FIELD_IS_ENABLED).asLong(0) == 1;
        doc.score = node.path("score").asDouble(0);
        JsonNode vector = node.get(FIELD_VECTOR);
        if (vector != null && vector.isArray()) {
            float[] out = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                out[i] = (float) vector.get(i).asDouble();
            }
            doc.vector = out;
        }
        return doc;
    }

    static IndexWithScore toIndexWithScore(Document doc, int matchType) {
        IndexWithScore out = new IndexWithScore();
        out.id = doc.id;
        out.content = doc.content;
        out.sourceId = doc.sourceId;
        out.sourceType = doc.sourceType;
        out.chunkId = doc.chunkId;
        out.knowledgeId = doc.knowledgeId;
        out.knowledgeBaseId = doc.knowledgeBaseId;
        out.tagId = doc.tagId;
        out.score = doc.score;
        out.matchType = matchType;
        out.isEnabled = doc.isEnabled;
        return out;
    }

    /** 照 Go {@code outputFields()}：9 个字段（不含向量；向量由 retrieveVector 控制）。 */
    static ArrayNode outputFields() {
        ArrayNode fields = Json.array();
        for (String name : List.of(FIELD_ID, FIELD_CONTENT, FIELD_SOURCE_ID, FIELD_SOURCE_TYPE,
                FIELD_CHUNK_ID, FIELD_KNOWLEDGE_ID, FIELD_KNOWLEDGE_BASE_ID, FIELD_TAG_ID,
                FIELD_IS_ENABLED)) {
            fields.add(name);
        }
        return fields;
    }

    /** 对照 Go {@code cleanInvalidUTF8}：丢 NUL 与非法序列（Java 侧重点是孤立代理项）。 */
    static String cleanInvalidUtf8(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0) {
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ── 探针（照 testTencentVectorDBConnection：ListDatabase，版本恒 ""） ──

    public static String testConnection(String addr, String username, String apiKey,
                                        SsrfGuard guard) {
        new TencentVectorDbRestClient(addr, username, apiKey, guard).probe();
        return "";
    }

    /** 供测试引用（错误类型）。 */
    static Class<? extends RuntimeException> apiErrorType() {
        return TencentVectorDbApiException.class;
    }
}
