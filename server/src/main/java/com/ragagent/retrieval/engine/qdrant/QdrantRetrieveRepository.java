package com.ragagent.retrieval.engine.qdrant;

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
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.retrieval.support.SearchTextUtil;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Qdrant 检索引擎仓储。
 *
 * <h2>协议口径</h2>
 * 本仓<b>自持 HTTP/JSON</b>（REST），逐方法等价的端点映射见 {@link QdrantRestClient} 与各方法注释
 * （{@code Query} → REST {@code /points/search}、{@code Scroll} → {@code /points/scroll}、
 * {@code SetPayload} → {@code /points/payload}、{@code CreateFieldIndex} → {@code /index}）。
 *
 * <h2>语义要点</h2>
 * <ul>
 *   <li><b>按维度分集合</b> {@code <base>_<dim>}；collection 名由
 *       {@code ResolveCollectionName(indexCfg, QDRANT_COLLECTION, "weknora_embeddings")} 决定；</li>
 *   <li>建集合时带 keyword 索引（chunk/knowledge/kb/source）+ bool 索引（is_enabled）+
 *       content 的 multilingual text 索引（lowercase=true）——索引创建失败只 WARN；</li>
 *   <li>点 ID 恒为新 UUID（Qdrant 不承载业务主键）；payload 字符串过
 *       {@link CleanInvalidUtf8}（NUL/非法编码单元丢弃）；</li>
 *   <li>关键词检索是 {@code should(or) + content match text} 的 Scroll，跨集合合并后截 TopK、
 *       score 恒 1.0；单集合失败只 WARN 继续；</li>
 *   <li>批量按 100 分片 upsert；CopyIndices 每页 64 并带向量回搬。</li>
 * </ul>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li>传输 REST（语义等价面已逐条对齐；gRPC 专有字段不适用）；</li>
 *   <li>Delete/Upsert/SetPayload(批量更新) 都不带 {@code wait}（异步默认）；
 *       只有 Move 的 SetPayload 带 {@code wait=true}；</li>
 *   <li>分词走本仓独有的 {@link SearchTextUtil#segmenter()} 接缝（jieba 默认降级为二字滑窗，
 *       与标准 jieba 词表不逐词一致——这是既有的文档化降级，Qdrant 驱动只是复用）；</li>
 *   <li>TopK ≤ 0 时截断 clamp 到 0（防御性处理，正数语义不变）。</li>
 * </ul>
 */
public class QdrantRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover,
        AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QdrantRetrieveRepository.class);

    /** 缺省 collection 名。 */
    public static final String DEFAULT_COLLECTION_NAME = "weknora_embeddings";
    /** collection 名环境键。 */
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

    /** BatchSave 分片大小。 */
    static final int UPSERT_BATCH_SIZE = 100;
    /** CopyIndices 分页大小。 */
    static final int COPY_PAGE_SIZE = 64;

    final QdrantRestClient client;
    final String collectionBaseName;
    private final int shardNumber;
    private final int replicationFactor;

    final QdrantSearchOps searchOps;
    final QdrantWriteOps writeOps;

    /** 已初始化集合表：dim -> true。 */
    private final ConcurrentHashMap<Integer, Boolean> initializedCollections =
            new ConcurrentHashMap<>();

    public QdrantRetrieveRepository(QdrantRestClient client, String collectionBaseName,
                                    int shardNumber, int replicationFactor) {
        this.client = client;
        this.collectionBaseName = collectionBaseName == null || collectionBaseName.isEmpty()
                ? DEFAULT_COLLECTION_NAME : collectionBaseName;
        this.shardNumber = shardNumber;
        this.replicationFactor = replicationFactor;
        this.searchOps = new QdrantSearchOps(this);
        this.writeOps = new QdrantWriteOps(this);
    }

    /** 构造入口：建 client + 解析 collection 名。 */
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

    /** collection 名解析：indexCfg 前缀/名称 > env {@code QDRANT_COLLECTION} > 缺省。 */
    static String resolveCollectionName(IndexConfig indexCfg) {
        if (indexCfg != null) {
            if (indexCfg.collectionPrefix != null && !indexCfg.collectionPrefix.isEmpty()) {
                return indexCfg.collectionPrefix;
            }
            if (indexCfg.collectionName != null && !indexCfg.collectionName.isEmpty()) {
                return indexCfg.collectionName;
            }
        }
        String env = com.ragagent.retrieval.config.RetrievalEnvLookup.get(ENV_QDRANT_COLLECTION);
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

    /** 存储估算（HNSW M=16；payload 不含 tag_id）。 */
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

    /** 单点存储估算（参照 qdrant-sizing-calculator）。 */
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

    // ── 集合管理 ────────────────────────────────────────────────────────────

    String collectionName(int dimension) {
        return collectionBaseName + "_" + dimension;
    }

    /**
     * 集合就绪：存在性探测（GET，404 视为不存在）→ 建集合
     * （size/distance=Cosine + 可选的 shard/replication）→ payload 索引（keyword×4 +
     * bool + text）；索引失败只 WARN；结果按维度缓存。
     */
    void ensureCollection(int dimension) {
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


    /** payload 字符串清理：含 NUL/非法 UTF-8 的先清理。 */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('\u0000') >= 0) {
            return CleanInvalidUtf8.clean(value);
        }
        return value;
    }


    // ── 过滤构造 ────────────────────────────────────────────────────────────

    /** 关键词集合匹配（gRPC MatchKeywords → REST match.any）。 */
    static ObjectNode matchAny(String field, List<String> values) {
        ObjectNode cond = QdrantRestClient.object();
        cond.put("key", field);
        ObjectNode match = cond.putObject("match");
        ArrayNode any = match.putArray("any");
        values.forEach(any::add);
        return cond;
    }

    /** 单值匹配（gRPC NewMatch → REST match.value）。 */
    static ObjectNode matchValue(String field, Object value) {
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
    static ObjectNode matchText(String field, String text) {
        ObjectNode cond = QdrantRestClient.object();
        cond.put("key", field);
        ObjectNode match = cond.putObject("match");
        match.put("text", text);
        return cond;
    }

    static ObjectNode mustOnly(ObjectNode... conditions) {
        ObjectNode filter = QdrantRestClient.object();
        ArrayNode must = filter.putArray("must");
        for (ObjectNode c : conditions) {
            must.add(c);
        }
        return filter;
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** 列举集合（REST {@code GET /collections}）。 */
    List<String> listCollections() {
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

    /** 集合名前缀过滤：严格长于 base 且以此为前缀。 */
    boolean isPrefixed(String collection) {
        return collection.length() > collectionBaseName.length()
                && collection.startsWith(collectionBaseName);
    }

    /**
     * 查询分词：{@code CutForSearch} → trim + 小写 →
     * 丢弃单字符/重复 token（顺序保留）。
     *
     * <p><b>降级口径</b>：标准 jieba 的 CutForSearch 对拉丁文本按词切分（空白自身也作词元，
     * 靠 trim + 长度过滤丢弃）；本仓的 {@link SearchTextUtil} 降级分词器把
     * 非 Han 连段整块返回，故这里对每个词元再按空白二次切分——净效果与标准 jieba 一致
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

    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return searchOps.retrieve(params);
    }



    /**
     * SourceID 三态改写：普通 chunk（SourceID==ChunkID）→ targetChunkID；
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


    // ── move ────────────────────────────────────────────────────────────────

    /** SetPayload（wait=true）重写 kb_id 并清 tag_id。 */
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
     * IndexInfo → 行模型：embedding 从 {@code additionalParams["embedding"]}
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

    // ── test-connection 探针 ────────────────────────────────────────────────

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
