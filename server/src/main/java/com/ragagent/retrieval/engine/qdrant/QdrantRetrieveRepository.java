package com.ragagent.retrieval.engine.qdrant;

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
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.searchutil.SearchTextUtil;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Qdrant 检索引擎仓储——对照 Go {@code repository/retriever/qdrant/} 全包
 * （repository.go 1009 + structs.go 33 + move.go 26，约 1,070 行非测试）。
 *
 * <h2>协议口径（本仓的"协议决策"）</h2>
 * Go 走 {@code qdrant/go-client} 的 gRPC；本仓照 ES/OpenSearch 先例<b>自持 HTTP/JSON</b>
 * （REST），逐方法等价的端点映射见 {@link QdrantRestClient} 与各方法注释
 * （gRPC {@code Query} → REST {@code /points/search}、{@code Scroll} → {@code /points/scroll}、
 * {@code SetPayload} → {@code /points/payload}、{@code CreateFieldIndex} → {@code /index}）。
 *
 * <h2>语义要点（照 Go 注释）</h2>
 * <ul>
 *   <li><b>按维度分集合</b> {@code <base>_<dim>}；collection 名由
 *       {@code ResolveCollectionName(indexCfg, QDRANT_COLLECTION, "weknora_embeddings")} 决定；</li>
 *   <li>建集合时带 keyword 索引（chunk/knowledge/kb/source）+ bool 索引（is_enabled）+
 *       content 的 multilingual text 索引（lowercase=true）——索引创建失败只 WARN；</li>
 *   <li>点 ID 恒为新 UUID（Qdrant 不承载业务主键）；payload 字符串过
 *       {@link CleanInvalidUtf8}（NUL/非法编码单元丢弃，照 Go 的 newQdrantValueMap）；</li>
 *   <li>关键词检索是 {@code should(or) + content match text} 的 Scroll，跨集合合并后截 TopK、
 *       score 恒 1.0；单集合失败只 WARN 继续；</li>
 *   <li>批量按 100 分片 upsert（{@code batchSize = 100}）；CopyIndices 每页 64 并带向量回搬。</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>传输 REST vs gRPC（语义等价面已逐条对齐；gRPC 专有字段不适用）；</li>
 *   <li>Go 的 Delete/Upsert/SetPayload(批量更新) 都不带 wait（异步默认）；本仓同样不传
 *       {@code wait=true}，只有 Move 的 SetPayload 带 wait（照 Go 的 {@code wait := true}）；</li>
 *   <li>分词走本仓独有的 {@link SearchTextUtil#segmenter()} 接缝（jieba 默认降级为二字滑窗，
 *       与 Go 的 gojieba 词表不逐词一致——这是既有的文档化降级，Qdrant 驱动只是复用）；</li>
 *   <li>TopK ≤ 0 时 Go 的截断会 panic（负下标）；本仓 clamp 到 0（防御性偏离，正数语义不变）。</li>
 * </ul>
 */
public class QdrantRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover,
        AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QdrantRetrieveRepository.class);

    /** 对照 {@code defaultCollectionName}。 */
    public static final String DEFAULT_COLLECTION_NAME = "weknora_embeddings";
    /** 对照 {@code envQdrantCollection}。 */
    public static final String ENV_QDRANT_COLLECTION = "QDRANT_COLLECTION";

    static final String FIELD_CONTENT = "content";
    static final String FIELD_SOURCE_ID = "source_id";
    static final String FIELD_SOURCE_TYPE = "source_type";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_KNOWLEDGE_ID = "knowledge_id";
    static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    static final String FIELD_TAG_ID = "tag_id";
    static final String FIELD_EMBEDDING = "embedding";
    static final String FIELD_IS_ENABLED = "is_enabled";

    /** 对照 {@code const batchSize = 100}（BatchSave 分片）。 */
    static final int UPSERT_BATCH_SIZE = 100;
    /** 对照 CopyIndices 的 {@code batchSize := uint32(64)}。 */
    static final int COPY_PAGE_SIZE = 64;

    private final QdrantRestClient client;
    private final String collectionBaseName;
    private final int shardNumber;
    private final int replicationFactor;

    /** 对照 {@code initializedCollections sync.Map}：dim -> true。 */
    private final ConcurrentHashMap<Integer, Boolean> initializedCollections =
            new ConcurrentHashMap<>();

    public QdrantRetrieveRepository(QdrantRestClient client, String collectionBaseName,
                                    int shardNumber, int replicationFactor) {
        this.client = client;
        this.collectionBaseName = collectionBaseName == null || collectionBaseName.isEmpty()
                ? DEFAULT_COLLECTION_NAME : collectionBaseName;
        this.shardNumber = shardNumber;
        this.replicationFactor = replicationFactor;
    }

    /** 照 Go {@code NewQdrantRetrieveEngineRepository} + {@code createQdrantEngine} 的构造链。 */
    public static QdrantRetrieveRepository create(String host, int port, String apiKey,
                                                  boolean useTls, IndexConfig indexCfg,
                                                  SsrfGuard guard) {
        log.info("[Qdrant] Initializing Qdrant retriever engine repository");
        String baseName = resolveCollectionName(indexCfg);
        QdrantRestClient client = new QdrantRestClient(
                QdrantRestClient.buildBaseUrl(host, port, useTls), apiKey, guard);
        int shards = indexCfg == null ? 0 : indexCfg.shardNumber;
        int replicas = indexCfg == null ? 0 : indexCfg.replicationFactor;
        QdrantRetrieveRepository repo = new QdrantRetrieveRepository(client, baseName,
                shards > 0 ? shards : 0, replicas > 0 ? replicas : 0);
        log.info("[Qdrant] Successfully initialized repository");
        return repo;
    }

    /** 对照 {@code types.ResolveCollectionName(indexCfg, QDRANT_COLLECTION, default)}。 */
    static String resolveCollectionName(IndexConfig indexCfg) {
        if (indexCfg != null) {
            if (indexCfg.collectionPrefix != null && !indexCfg.collectionPrefix.isEmpty()) {
                return indexCfg.collectionPrefix;
            }
            if (indexCfg.collectionName != null && !indexCfg.collectionName.isEmpty()) {
                return indexCfg.collectionName;
            }
        }
        String env = System.getenv(ENV_QDRANT_COLLECTION);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return DEFAULT_COLLECTION_NAME;
    }

    @Override
    public void close() {
        // REST 客户端无长连接池需收尾（HttpClient 由 JDK 管理）。
    }

    // ── 引擎面 ──────────────────────────────────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_QDRANT;
    }

    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    /** 对照 {@code EstimateStorageSize}（HNSW M=16；payload 不含 tag_id——照 Go 原文）。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null) {
            return 0;
        }
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            total += calculateStorageSize(toEmbedding(info, params));
        }
        log.info("[Qdrant] Storage size for {} indices: {} bytes", indexInfoList.size(), total);
        return total;
    }

    /** 对照 {@code calculateStorageSize}（Ref: qdrant-sizing-calculator）。 */
    static long calculateStorageSize(QdrantVectorEmbedding embedding) {
        long payload = 0;
        payload += utf8Length(embedding.content);
        payload += utf8Length(embedding.sourceId);
        payload += utf8Length(embedding.chunkId);
        payload += utf8Length(embedding.knowledgeId);
        payload += utf8Length(embedding.knowledgeBaseId);
        payload += 8; // source_type int64
        long vectorBytes = 0;
        long hnswBytes = 0;
        if (embedding.embedding != null) {
            long dimensions = embedding.embedding.length;
            vectorBytes = dimensions * 4;
            final long hnswM = 16; // 图链接数只与 M 有关，与维度无关
            hnswBytes = hnswM * 2 * 8;
        }
        final long idTrackerBytes = 24; // forward/backward refs + version
        return payload + vectorBytes + hnswBytes + idTrackerBytes;
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

    /**
     * 对照 {@code ensureCollection}：存在性探测（GET，404 视为不存在）→ 建集合
     * （size/distance=Cosine + 可选的 shard/replication）→ payload 索引（keyword×4 +
     * bool + text）；索引失败只 WARN；结果按维度缓存。
     */
    private void ensureCollection(int dimension) {
        if (initializedCollections.containsKey(dimension)) {
            return;
        }
        String name = collectionName(dimension);
        JsonNode existing;
        try {
            existing = client.request("GET", "/collections/" + name, null, true);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException(
                    "failed to check collection existence: " + e.getMessage(), e);
        }
        if (existing == null) {
            log.info("[Qdrant] Creating collection {} with dimension {}", name, dimension);
            ObjectNode body = QdrantRestClient.object();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", dimension);
            vectors.put("distance", "Cosine");
            if (shardNumber > 0) {
                body.put("shard_number", shardNumber);
            }
            if (replicationFactor > 0) {
                body.put("replication_factor", replicationFactor);
            }
            try {
                client.request("PUT", "/collections/" + name, body);
            } catch (RuntimeException e) {
                log.error("[Qdrant] Failed to create collection: {}", e.getMessage());
                throw new IllegalStateException(
                        "failed to create collection: " + e.getMessage(), e);
            }
            for (String field : List.of(FIELD_CHUNK_ID, FIELD_KNOWLEDGE_ID,
                    FIELD_KNOWLEDGE_BASE_ID, FIELD_SOURCE_ID)) {
                createFieldIndex(name, field, QdrantRestClient.mapper().getNodeFactory()
                        .textNode("keyword"));
            }
            createFieldIndex(name, FIELD_IS_ENABLED,
                    QdrantRestClient.mapper().getNodeFactory().textNode("bool"));
            ObjectNode textSchema = QdrantRestClient.object();
            textSchema.put("type", "text");
            textSchema.put("tokenizer", "multilingual");
            textSchema.put("lowercase", true);
            createFieldIndex(name, FIELD_CONTENT, textSchema);
            log.info("[Qdrant] Successfully created collection {}", name);
        }
        initializedCollections.put(dimension, true);
    }

    private void createFieldIndex(String collection, String field, JsonNode schema) {
        ObjectNode body = QdrantRestClient.object();
        body.put("field_name", field);
        body.set("field_schema", schema);
        try {
            client.request("PUT", "/collections/" + collection + "/index?wait=true", body);
        } catch (RuntimeException e) {
            log.warn("[Qdrant] Failed to create index for field {}: {}", field, e.getMessage());
        }
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        log.debug("[Qdrant] Saving index for chunk ID: {}", indexInfo.chunkId);
        QdrantVectorEmbedding row = toEmbedding(indexInfo, params);
        if (row.embedding == null || row.embedding.length == 0) {
            IllegalStateException e = new IllegalStateException(
                    "empty embedding vector for chunk ID: " + indexInfo.chunkId);
            log.error("[Qdrant] {}", e.getMessage());
            throw e;
        }
        int dimension = row.embedding.length;
        ensureCollection(dimension);
        String collection = collectionName(dimension);
        String pointId = UUID.randomUUID().toString();
        try {
            client.request("PUT", "/collections/" + collection + "/points",
                    upsertBody(List.of(pointBody(pointId, row))));
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to save index: {}", e.getMessage());
            throw new IllegalStateException("failed to save index for chunk ID "
                    + indexInfo.chunkId + ": " + e.getMessage(), e);
        }
        log.info("[Qdrant] Successfully saved index for chunk ID: {}, point ID: {}",
                indexInfo.chunkId, pointId);
    }

    /** 对照 {@code BatchSave}：按维度分组 → 每维 ensureCollection → 100 分片 upsert。 */
    @Override
    public void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Qdrant] Empty list provided to BatchSave, skipping");
            return;
        }
        log.info("[Qdrant] Batch saving {} indices", embeddingList.size());
        Map<Integer, List<ObjectNode>> pointsByDimension = new TreeMap<>();
        for (IndexInfo info : embeddingList) {
            QdrantVectorEmbedding row = toEmbedding(info, params);
            if (row.embedding == null || row.embedding.length == 0) {
                log.warn("[Qdrant] Skipping empty embedding for chunk ID: {}", info.chunkId);
                continue;
            }
            int dimension = row.embedding.length;
            pointsByDimension.computeIfAbsent(dimension, k -> new ArrayList<>())
                    .add(pointBody(UUID.randomUUID().toString(), row));
        }
        if (pointsByDimension.isEmpty()) {
            log.warn("[Qdrant] No valid points to save after filtering");
            return;
        }
        int totalSaved = 0;
        for (Map.Entry<Integer, List<ObjectNode>> entry : pointsByDimension.entrySet()) {
            int dimension = entry.getKey();
            ensureCollection(dimension);
            String collection = collectionName(dimension);
            List<ObjectNode> points = entry.getValue();
            for (int i = 0; i < points.size(); i += UPSERT_BATCH_SIZE) {
                List<ObjectNode> batch = points.subList(i,
                        Math.min(i + UPSERT_BATCH_SIZE, points.size()));
                try {
                    client.request("PUT", "/collections/" + collection + "/points",
                            upsertBody(batch));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "failed to upsert batch: " + e.getMessage(), e);
                }
            }
            totalSaved += points.size();
            log.info("[Qdrant] Saved {} points to collection {}", points.size(), collection);
        }
        log.info("[Qdrant] Successfully batch saved {} indices", totalSaved);
    }

    private static ObjectNode upsertBody(List<ObjectNode> points) {
        ObjectNode body = QdrantRestClient.object();
        ArrayNode array = body.putArray("points");
        points.forEach(array::add);
        return body;
    }

    /** 对照 {@code PointStruct}：id + vector + payload。 */
    private static ObjectNode pointBody(String pointId, QdrantVectorEmbedding row) {
        ObjectNode point = QdrantRestClient.object();
        point.put("id", pointId);
        ArrayNode vector = point.putArray("vector");
        for (float v : row.embedding) {
            vector.add(v);
        }
        point.set("payload", createPayload(row));
        return point;
    }

    /** 对照 {@code createPayload}：payload 键序按写入序（Go map → 库端无键序约束）。 */
    private static ObjectNode createPayload(QdrantVectorEmbedding row) {
        ObjectNode payload = QdrantRestClient.object();
        payload.put(FIELD_CONTENT, sanitize(row.content));
        payload.put(FIELD_SOURCE_ID, sanitize(row.sourceId));
        payload.put(FIELD_SOURCE_TYPE, row.sourceType);
        payload.put(FIELD_CHUNK_ID, sanitize(row.chunkId));
        payload.put(FIELD_KNOWLEDGE_ID, sanitize(row.knowledgeId));
        payload.put(FIELD_KNOWLEDGE_BASE_ID, sanitize(row.knowledgeBaseId));
        payload.put(FIELD_TAG_ID, sanitize(row.tagId));
        payload.put(FIELD_IS_ENABLED, row.isEnabled);
        return payload;
    }

    /** 对照 {@code newQdrantValueMap}：含 NUL/非法 UTF-8 的字符串先清理。 */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('\u0000') >= 0) {
            return CleanInvalidUtf8.clean(value);
        }
        return value;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(FIELD_CHUNK_ID, chunkIdList, dimension, "chunk IDs");
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension, "knowledge IDs");
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(FIELD_SOURCE_ID, sourceIdList, dimension, "source IDs");
    }

    private void deleteByField(String field, List<String> ids, int dimension, String subject) {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Qdrant] Empty {} list provided for deletion, skipping", subject);
            return;
        }
        String collection = collectionName(dimension);
        ObjectNode body = QdrantRestClient.object();
        body.set("filter", mustOnly(matchAny(field, ids)));
        try {
            // 照 Go：Delete 不带 wait（异步默认）。
            client.request("POST", "/collections/" + collection + "/points/delete", body);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to delete by {}: {}", subject, e.getMessage());
            throw new IllegalStateException(
                    "failed to delete by " + subject + ": " + e.getMessage(), e);
        }
    }

    // ── 过滤构造 ────────────────────────────────────────────────────────────

    /** 关键词集合匹配（gRPC MatchKeywords → REST match.any）。 */
    private static ObjectNode matchAny(String field, List<String> values) {
        ObjectNode cond = QdrantRestClient.object();
        cond.put("key", field);
        ObjectNode match = cond.putObject("match");
        ArrayNode any = match.putArray("any");
        values.forEach(any::add);
        return cond;
    }

    /** 单值匹配（gRPC NewMatch → REST match.value）。 */
    private static ObjectNode matchValue(String field, Object value) {
        ObjectNode cond = QdrantRestClient.object();
        cond.put("key", field);
        ObjectNode match = cond.putObject("match");
        if (value instanceof Boolean b) {
            match.put("value", b.booleanValue());
        } else if (value instanceof Number n) {
            match.put("value", n.longValue());
        } else {
            match.put("value", String.valueOf(value));
        }
        return cond;
    }

    /** 全文匹配（gRPC NewMatchText → REST match.text）。 */
    private static ObjectNode matchText(String field, String text) {
        ObjectNode cond = QdrantRestClient.object();
        cond.put("key", field);
        ObjectNode match = cond.putObject("match");
        match.put("text", text);
        return cond;
    }

    private static ObjectNode mustOnly(ObjectNode... conditions) {
        ObjectNode filter = QdrantRestClient.object();
        ArrayNode must = filter.putArray("must");
        for (ObjectNode c : conditions) {
            must.add(c);
        }
        return filter;
    }

    /** 对照 {@code getBaseFilter}：is_enabled=true 隐含 + KB/知识/标签过滤 + 排除项。 */
    static ObjectNode baseFilter(RetrieveParams params) {
        ObjectNode filter = QdrantRestClient.object();
        ArrayNode must = filter.putArray("must");
        ArrayNode mustNot = filter.putArray("must_not");
        must.add(matchValue(FIELD_IS_ENABLED, true));
        if (params != null) {
            if (params.knowledgeBaseIds != null && !params.knowledgeBaseIds.isEmpty()) {
                must.add(matchAny(FIELD_KNOWLEDGE_BASE_ID, params.knowledgeBaseIds));
            }
            if (params.knowledgeIds != null && !params.knowledgeIds.isEmpty()) {
                must.add(matchAny(FIELD_KNOWLEDGE_ID, params.knowledgeIds));
            }
            if (params.tagIds != null && !params.tagIds.isEmpty()) {
                must.add(matchAny(FIELD_TAG_ID, params.tagIds));
            }
            if (params.excludeKnowledgeIds != null && !params.excludeKnowledgeIds.isEmpty()) {
                mustNot.add(matchAny(FIELD_KNOWLEDGE_ID, params.excludeKnowledgeIds));
            }
            if (params.excludeChunkIds != null && !params.excludeChunkIds.isEmpty()) {
                mustNot.add(matchAny(FIELD_CHUNK_ID, params.excludeChunkIds));
            }
        }
        return filter;
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
                log.error("[Qdrant] invalid retriever type: {}", retrieverType);
                throw new IllegalStateException("invalid retriever type: " + retrieverType);
            }
        };
    }

    /**
     * 对照 {@code VectorRetrieve}：集合不存在 → 空结果；否则
     * {@code /points/search}（filter + limit=TopK + score_threshold + with_payload）；
     * 失败包 {@code <collection>: <err>}。
     */
    private List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        int dimension = embedding.length;
        log.info("[Qdrant] Vector retrieval: dim={}, topK={}, threshold={}",
                dimension, params.topK, params.threshold);
        String collection = collectionName(dimension);
        JsonNode existing;
        try {
            existing = client.request("GET", "/collections/" + collection, null, true);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to check collection existence: {}", e.getMessage());
            throw new IllegalStateException("failed to check collection: " + e.getMessage(), e);
        }
        if (existing == null) {
            log.warn("[Qdrant] Collection {} does not exist, returning empty results", collection);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        ObjectNode body = QdrantRestClient.object();
        ArrayNode vector = body.putArray("vector");
        for (float v : embedding) {
            vector.add(v);
        }
        body.set("filter", baseFilter(params));
        body.put("limit", params.topK);
        body.put("score_threshold", params.threshold);
        body.put("with_payload", true);
        JsonNode result;
        try {
            result = client.request("POST", "/collections/" + collection + "/points/search", body);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Vector search failed: {}", e.getMessage());
            throw new IllegalStateException(collection + ": " + e.getMessage(), e);
        }
        List<IndexWithScore> results = new ArrayList<>();
        if (result != null) {
            for (JsonNode point : result) {
                results.add(fromPoint(point, EngineTypes.MATCH_EMBEDDING,
                        point.path("score").asDouble()));
            }
        }
        if (results.isEmpty()) {
            log.warn("[Qdrant] No vector matches found that meet threshold {}", params.threshold);
        } else {
            log.info("[Qdrant] Vector retrieval found {} results", results.size());
        }
        return buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：跨集合 {@code /points/scroll}，filter 的 Should 装
     * 每个 token 的 content 全文匹配（OR）；无 token 时回落 must 里塞原 query；跨集合合并后
     * 截 TopK；score 恒 1.0；单集合失败只 WARN 继续。
     */
    private List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query;
        log.info("[Qdrant] Performing keywords retrieval with query: {}, topK: {}",
                query, params.topK);
        List<String> collections;
        try {
            collections = listCollections();
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
        List<IndexWithScore> allResults = new ArrayList<>();
        List<String> tokens = tokenizeQuery(query);
        log.debug("[Qdrant] Tokenized query into {} tokens: {}", tokens.size(), tokens);
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            ObjectNode filter = baseFilter(params);
            if (!tokens.isEmpty()) {
                ArrayNode should = filter.putArray("should");
                for (String token : tokens) {
                    should.add(matchText(FIELD_CONTENT, token));
                }
            } else {
                filter.withArray("must").add(matchText(FIELD_CONTENT, query));
            }
            ObjectNode body = QdrantRestClient.object();
            body.set("filter", filter);
            body.put("limit", params.topK);
            body.put("with_payload", true);
            JsonNode scroll;
            try {
                scroll = client.request("POST",
                        "/collections/" + collection + "/points/scroll", body);
            } catch (RuntimeException e) {
                log.warn("[Qdrant] Keywords search failed in {}: {}", collection, e.getMessage());
                continue;
            }
            JsonNode points = scroll == null ? null : scroll.get("points");
            if (points != null) {
                for (JsonNode point : points) {
                    allResults.add(fromPoint(point, EngineTypes.MATCH_KEYWORDS, 1.0));
                }
            }
        }
        int topK = Math.max(0, params.topK);
        if (allResults.size() > topK) {
            allResults = new ArrayList<>(allResults.subList(0, topK));
        }
        if (allResults.isEmpty()) {
            log.warn("[Qdrant] No keyword matches found for query: {}", query);
        } else {
            log.info("[Qdrant] Keywords retrieval found {} results", allResults.size());
        }
        return buildRetrieveResult(allResults, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /** 对照 {@code ListCollections}（REST {@code GET /collections}）。 */
    private List<String> listCollections() {
        JsonNode result = client.request("GET", "/collections", null);
        List<String> names = new ArrayList<>();
        JsonNode collections = result == null ? null : result.get("collections");
        if (collections != null) {
            for (JsonNode node : collections) {
                names.add(node.path("name").asText(""));
            }
        }
        return names;
    }

    /** 对照 Go 的集合名前缀过滤：严格长于 base 且以此为前缀。 */
    private boolean isPrefixed(String collection) {
        return collection.length() > collectionBaseName.length()
                && collection.startsWith(collectionBaseName);
    }

    /**
     * 对照 {@code tokenizeQuery}：{@code CutForSearch} → trim + 小写 →
     * 丢弃单字符/重复 token（顺序保留）。
     *
     * <p><b>降级口径</b>：gojieba 的 CutForSearch 对拉丁文本按词切分（空白自身也作词元，
     * Go 侧靠 TrimSpace + 长度过滤丢弃）；本仓的 {@link SearchTextUtil} 降级分词器把
     * 非 Han 连段整块返回，故这里对每个词元再按空白二次切分——净效果与 Go 一致
     * （英文单词成为独立 token）。真实分词器接入后此二次切分对其无副作用
     * （jieba 输出的词元本就不含空白）。</p>
     */
    static List<String> tokenizeQuery(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> words = SearchTextUtil.segmenter().cutForSearch(trimmed);
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String word : words) {
            if (word == null) {
                continue;
            }
            for (String piece : word.split("\\s+")) {
                String w = piece.trim().toLowerCase(java.util.Locale.ROOT);
                if (w.codePointCount(0, w.length()) < 2 || seen.contains(w)) {
                    continue;
                }
                seen.add(w);
                result.add(w);
            }
        }
        return result;
    }

    // ── 批量更新（跨集合 SetPayload） ──────────────────────────────────────

    /** 对照 {@code BatchUpdateChunkEnabledStatus}：按 true/false 分组 → 每集合两次 SetPayload。 */
    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Qdrant] Empty chunk status map provided, skipping");
            return;
        }
        log.info("[Qdrant] Batch updating chunk enabled status, count: {}", chunkStatusMap.size());
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
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            if (!enabledChunkIds.isEmpty()) {
                try {
                    setPayload(collection, FIELD_IS_ENABLED, true,
                            matchAny(FIELD_CHUNK_ID, enabledChunkIds));
                } catch (RuntimeException e) {
                    log.warn("[Qdrant] Failed to update enabled chunks in {}: {}",
                            collection, e.getMessage());
                }
            }
            if (!disabledChunkIds.isEmpty()) {
                try {
                    setPayload(collection, FIELD_IS_ENABLED, false,
                            matchAny(FIELD_CHUNK_ID, disabledChunkIds));
                } catch (RuntimeException e) {
                    log.warn("[Qdrant] Failed to update disabled chunks in {}: {}",
                            collection, e.getMessage());
                }
            }
        }
        log.info("[Qdrant] Batch update chunk enabled status completed");
    }

    /** 对照 {@code BatchUpdateChunkTagID}：按 tagID 分组 → 每集合逐组 SetPayload。 */
    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Qdrant] Empty chunk tag map provided, skipping");
            return;
        }
        log.info("[Qdrant] Batch updating chunk tag ID, count: {}", chunkTagMap.size());
        List<String> collections = listCollectionsOrThrow();
        Map<String, List<String>> tagGroups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            tagGroups.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(entry.getKey());
        }
        for (String collection : collections) {
            if (!isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, List<String>> group : tagGroups.entrySet()) {
                try {
                    setPayload(collection, FIELD_TAG_ID, group.getKey(),
                            matchAny(FIELD_CHUNK_ID, group.getValue()));
                } catch (RuntimeException e) {
                    log.warn("[Qdrant] Failed to update chunks with tag_id {} in {}: {}",
                            group.getKey(), collection, e.getMessage());
                }
            }
        }
        log.info("[Qdrant] Batch update chunk tag ID completed");
    }

    private List<String> listCollectionsOrThrow() {
        try {
            return listCollections();
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
    }

    /** 对照 {@code SetPayloadPoints}：单字段 payload + 选择器条件（照 Go 的批量更新调用点）。 */
    private void setPayload(String collection, String field, Object value, ObjectNode selector) {
        ObjectNode payload = QdrantRestClient.object();
        if (value instanceof Boolean b) {
            payload.put(field, b.booleanValue());
        } else if (value instanceof Number n) {
            payload.put(field, n.longValue());
        } else {
            payload.put(field, String.valueOf(value));
        }
        ObjectNode body = QdrantRestClient.object();
        body.set("payload", payload);
        body.set("filter", mustOnly(selector));
        client.request("POST", "/collections/" + collection + "/points/payload?wait=true", body);
    }

    // ── CopyIndices（照全文，含向量回搬） ─────────────────────────────────

    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("[Qdrant] Copying indices from source knowledge base {} to target knowledge base"
                        + " {}, count: {}, dimension: {}", sourceKnowledgeBaseId,
                targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size(), dimension);
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Qdrant] Empty mapping, skipping copy");
            return;
        }
        String collection = collectionName(dimension);
        ensureCollection(dimension);
        String offset = null;
        int totalCopied = 0;
        while (true) {
            ObjectNode body = QdrantRestClient.object();
            body.set("filter", mustOnly(matchValue(FIELD_KNOWLEDGE_BASE_ID,
                    sourceKnowledgeBaseId)));
            body.put("limit", COPY_PAGE_SIZE);
            if (offset != null) {
                body.put("offset", offset);
            }
            body.put("with_payload", true);
            body.put("with_vector", true);
            JsonNode scroll;
            try {
                scroll = client.request("POST", "/collections/" + collection + "/points/scroll",
                        body);
            } catch (RuntimeException e) {
                log.error("[Qdrant] Failed to query source points: {}", e.getMessage());
                throw new IllegalStateException(e.getMessage(), e);
            }
            JsonNode points = scroll == null ? null : scroll.get("points");
            int pointsCount = points == null ? 0 : points.size();
            if (pointsCount == 0) {
                break;
            }
            log.info("[Qdrant] Found {} source points in batch", pointsCount);
            List<ObjectNode> targetPoints = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                String sourceChunkId = payload.path(FIELD_CHUNK_ID).asText("");
                String sourceKnowledgeId = payload.path(FIELD_KNOWLEDGE_ID).asText("");
                String originalSourceId = payload.path(FIELD_SOURCE_ID).asText("");
                if (!sourceToTargetChunkIdMap.containsKey(sourceChunkId)) {
                    log.warn("[Qdrant] Source chunk {} not found in target mapping, skipping",
                            sourceChunkId);
                    continue;
                }
                String targetChunkId = sourceToTargetChunkIdMap.get(sourceChunkId);
                if (sourceToTargetKbIdMap == null
                        || !sourceToTargetKbIdMap.containsKey(sourceKnowledgeId)) {
                    log.warn("[Qdrant] Source knowledge {} not found in target mapping, skipping",
                            sourceKnowledgeId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap.get(sourceKnowledgeId);
                String targetSourceId = translateSourceId(originalSourceId, sourceChunkId,
                        targetChunkId);
                boolean isEnabled = !payload.has(FIELD_IS_ENABLED)
                        || payload.path(FIELD_IS_ENABLED).asBoolean(true);
                JsonNode vectorNode = point.get("vector");
                if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
                    log.warn("[Qdrant] No vectors found for source point with chunk {}, skipping",
                            sourceChunkId);
                    continue;
                }
                QdrantVectorEmbedding target = new QdrantVectorEmbedding();
                target.content = payload.path(FIELD_CONTENT).asText("");
                target.sourceId = targetSourceId;
                target.sourceType = payload.path(FIELD_SOURCE_TYPE).asInt(0);
                target.chunkId = targetChunkId;
                target.knowledgeId = targetKnowledgeId;
                target.knowledgeBaseId = targetKnowledgeBaseId;
                target.tagId = payload.path(FIELD_TAG_ID).asText("");
                target.isEnabled = isEnabled;
                target.embedding = new float[vectorNode.size()];
                for (int i = 0; i < vectorNode.size(); i++) {
                    target.embedding[i] = (float) vectorNode.get(i).asDouble();
                }
                targetPoints.add(pointBody(UUID.randomUUID().toString(), target));
            }
            if (!targetPoints.isEmpty()) {
                try {
                    client.request("PUT", "/collections/" + collection + "/points",
                            upsertBody(targetPoints));
                } catch (RuntimeException e) {
                    log.error("[Qdrant] Failed to batch upsert target points: {}", e.getMessage());
                    throw new IllegalStateException(
                            "failed to batch upsert target points during copy: "
                                    + e.getMessage(), e);
                }
                totalCopied += targetPoints.size();
                log.info("[Qdrant] Successfully copied batch, batch size: {}, total copied: {}",
                        targetPoints.size(), totalCopied);
            }
            offset = points.get(pointsCount - 1).path("id").asText();
            if (pointsCount < COPY_PAGE_SIZE) {
                break;
            }
        }
        log.info("[Qdrant] Index copy completed, total copied: {}", totalCopied);
    }

    /**
     * 对照 {@code translateSourceID} 的三态：普通 chunk（SourceID==ChunkID）→ targetChunkID；
     * 生成型问题（{@code "<chunkID>-<questionID>"}）→ 换前缀；其他 → 新 UUID。
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

    // ── move（照 move.go） ─────────────────────────────────────────────────

    /** 对照 {@code MoveKnowledgeIndices}：SetPayload（wait=true）重写 kb_id 并清 tag_id。 */
    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        ObjectNode payload = QdrantRestClient.object();
        payload.put(FIELD_KNOWLEDGE_BASE_ID, targetKb);
        payload.put(FIELD_TAG_ID, "");
        ObjectNode body = QdrantRestClient.object();
        ObjectNode payloadNode = body.putObject("payload");
        payload.properties().forEach(entry -> payloadNode.set(entry.getKey(), entry.getValue()));
        ObjectNode filter = QdrantRestClient.object();
        ArrayNode must = filter.putArray("must");
        must.add(matchValue(FIELD_KNOWLEDGE_BASE_ID, sourceKb));
        must.add(matchValue(FIELD_KNOWLEDGE_ID, knowledgeId));
        body.set("filter", filter);
        client.request("POST", "/collections/" + collectionName(dimension)
                + "/points/payload?wait=true", body);
    }

    // ── 行映射与辅助 ───────────────────────────────────────────────────────

    /**
     * 对照 {@code toQdrantVectorEmbedding}：embedding 从 {@code additionalParams["embedding"]}
     * 的 {@code Map<String, float[]>} 按 SourceID 取（缺失 → null，不单位化——Qdrant 用
     * Cosine 距离）；与 Doris 同款容错（List 形态也接受）。
     */
    static QdrantVectorEmbedding toEmbedding(IndexInfo info, Map<String, Object> additionalParams) {
        QdrantVectorEmbedding row = new QdrantVectorEmbedding();
        row.content = info.content == null ? "" : info.content;
        row.sourceId = info.sourceId == null ? "" : info.sourceId;
        row.sourceType = info.sourceType;
        row.chunkId = info.chunkId == null ? "" : info.chunkId;
        row.knowledgeId = info.knowledgeId == null ? "" : info.knowledgeId;
        row.knowledgeBaseId = info.knowledgeBaseId == null ? "" : info.knowledgeBaseId;
        row.tagId = info.tagId == null ? "" : info.tagId;
        row.isEnabled = info.isEnabled;
        if (additionalParams != null) {
            Object raw = additionalParams.get(FIELD_EMBEDDING);
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

    /** 对照 {@code fromQdrantVectorEmbedding}：payload + score → IndexWithScore（IsEnabled 不回填，照 Go）。 */
    static IndexWithScore fromPoint(JsonNode point, int matchType, double score) {
        JsonNode payload = point.path("payload");
        IndexWithScore out = new IndexWithScore();
        out.id = point.path("id").asText("");
        out.sourceId = payload.path(FIELD_SOURCE_ID).asText("");
        out.sourceType = payload.path(FIELD_SOURCE_TYPE).asInt(0);
        out.chunkId = payload.path(FIELD_CHUNK_ID).asText("");
        out.knowledgeId = payload.path(FIELD_KNOWLEDGE_ID).asText("");
        out.knowledgeBaseId = payload.path(FIELD_KNOWLEDGE_BASE_ID).asText("");
        out.tagId = payload.path(FIELD_TAG_ID).asText("");
        out.content = payload.path(FIELD_CONTENT).asText("");
        out.score = score;
        out.matchType = matchType;
        return out;
    }

    static List<RetrieveResult> buildRetrieveResult(List<IndexWithScore> results,
                                                    String retrieverType) {
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_QDRANT, retrieverType));
    }

    // ── test-connection 探针（照 vectorstore_healthcheck.go testQdrantConnection） ──

    /**
     * 连通性探针：REST {@code GET /}（等价 gRPC HealthCheck）返回 {@code version}；
     * 连不上/认证失败抛异常，由调用方折叠成通用文案。
     */
    public static String testConnection(String host, int port, String apiKey, boolean useTls,
                                        SsrfGuard guard) {
        QdrantRestClient client = new QdrantRestClient(
                QdrantRestClient.buildBaseUrl(host, port, useTls), apiKey, guard);
        try {
            QdrantRestClient.HttpProbe probe = client.rawGet("/");
            if (probe.status() / 100 != 2) {
                throw new IllegalStateException("qdrant health check HTTP " + probe.status());
            }
            return QdrantRestClient.mapper().readTree(probe.body()).path("version").asText("");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(),
                    e);
        }
    }
}
