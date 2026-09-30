package com.ragagent.retrieval.engine.doris;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Apache Doris 检索引擎仓储——对照 Go {@code repository/retriever/doris/} 全包
 * （repository.go 699 + schema.go 310 + streamload.go 508 + compat.go 228 + query.go 252 +
 * move.go 41，约 2,040 行非测试）。
 *
 * <h2>通信通道（照 Go 注释）</h2>
 * <ul>
 *   <li>读写主链路：MySQL 协议（FE 默认 9030）→ {@link DorisSqlExecutor}；</li>
 *   <li>Stream Load：HTTP（FE 默认 8030）→ {@link DorisStreamLoadClient}（legacy 模式
 *       的 partial update）。</li>
 * </ul>
 *
 * <h2>表结构（按维度分表 {@code <base>_<dim>}）与兼容模式</h2>
 * <ul>
 *   <li>{@code legacy}：UNIQUE KEY(id) + cosine_distance ANN + Stream Load partial update；</li>
 *   <li>{@code inner_product_duplicate}：DUPLICATE KEY(id) + 单位化内积 +
 *       delete/insert 重写。</li>
 * </ul>
 * 模式在建表后不可互换；解析顺序 = 显式配置 → 既有表 DDL 探测 → 函数探针（照 compat.go）。
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>Go 的 {@code initializedTables sync.Map} 对应 {@link ConcurrentHashMap}；
 *       {@code sync.Once}（结果含错误都只算一次）对应双检锁 + 结果缓存。</li>
 *   <li>Go 的分组遍历（{@code map[int][]...}）无序；本仓用 {@link TreeMap} 按维度升序，
 *       结果确定。</li>
 *   <li>legacy 批量更新的 partial update body：Go 的 {@code map[string]any} 由
 *       {@code json.Marshal} 按键字母序输出；本仓显式用 {@link TreeMap} 对齐同一序。</li>
 *   <li>keywords 的截断 {@code all = all[:TopK]} 在 TopK ≤ 0 时 Go 会 panic（负下标）；
 *       本仓 clamp 到 0（防御性偏离，不改变正数 TopK 的语义）。</li>
 *   <li>Stream Load 的 Expect 头与 301/302/303 行为见 {@link DorisStreamLoadClient} 类注释。</li>
 * </ul>
 */
public class DorisRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover,
        AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DorisRetrieveRepository.class);

    /** 对照 {@code defaultTableBaseName}。 */
    public static final String DEFAULT_TABLE_BASE_NAME = "weknora_embeddings";
    /** 对照 {@code envDorisTablePrefix}。 */
    public static final String ENV_DORIS_TABLE_PREFIX = "DORIS_TABLE_PREFIX";

    /** 对照 {@code schema.go} 的缺省桶/副本。 */
    static final int DEFAULT_BUCKETS_NUM = 10;
    static final int DEFAULT_REPLICATION_NUM = 1;
    /** 对照 {@code annReadyTimeout / annReadyPoll}。 */
    static final long ANN_READY_TIMEOUT_MS = 30_000L;
    static final long ANN_READY_POLL_MS = 1_000L;
    /** 对照 CopyIndices 的 {@code const pageSize = 64}。 */
    static final int COPY_PAGE_SIZE = 64;

    final DorisSqlExecutor sql;
    final DorisStreamLoadClient streamLoad;
    final String database;
    final String tableBaseName;
    final int bucketsNum;
    final int replicationNum;
    final DorisCompatMode compatModeRequested;

    /** 对照 {@code compatResolveOnce}+两个结果字段：结果（含错误）只解析一次。 */
    volatile DorisAdminOps.CompatResolution compatResolution;

    final DorisSearchOps searchOps;
    final DorisWriteOps writeOps;
    final DorisAdminOps adminOps;

    /** 对照 {@code initializedTables sync.Map}：dim -> true（已确保建过表）。 */
    final ConcurrentHashMap<Integer, Boolean> initializedTables = new ConcurrentHashMap<>();

    public DorisRetrieveRepository(DorisSqlExecutor sql, DorisStreamLoadClient streamLoad,
                                   String database, String tableBaseName, int bucketsNum,
                                   int replicationNum, DorisCompatMode compatModeRequested) {
        this.sql = sql;
        this.streamLoad = streamLoad;
        this.database = database == null ? "" : database;
        this.tableBaseName = tableBaseName == null || tableBaseName.isEmpty()
                ? DEFAULT_TABLE_BASE_NAME : tableBaseName;
        this.bucketsNum = bucketsNum;
        this.replicationNum = replicationNum;
        this.compatModeRequested = compatModeRequested;
        this.searchOps = new DorisSearchOps(this);
        this.writeOps = new DorisWriteOps(this);
        this.adminOps = new DorisAdminOps(this);
    }

    /**
     * 对照 {@code NewDorisRetrieveEngineRepository} + {@code createDorisEngine}：从连接配置
     * 建执行器与 Stream Load 客户端，并落 Go 的四条构造日志。
     */
    public static DorisRetrieveRepository create(String addr, String httpBase, String username,
                                                 String password, String database,
                                                 IndexConfig indexCfg, SsrfGuard guard) {
        DorisCompatMode.Configured configured =
                DorisCompatMode.configured(System.getenv(DorisCompatMode.ENV_KEY));
        String tableBaseName = resolveCollectionName(indexCfg);
        if (!configured.invalidRaw().isEmpty()) {
            log.warn("[Doris] Invalid {}={}, defaulting to {}",
                    DorisCompatMode.ENV_KEY, "\"" + configured.invalidRaw() + "\"",
                    DorisCompatMode.AUTO.wire());
        }
        if (System.getenv(DorisCompatMode.ENV_KEY) == null) {
            log.info("[Doris] {} not set, defaulting to {} and probing on first use",
                    DorisCompatMode.ENV_KEY, DorisCompatMode.AUTO.wire());
        }
        DorisSqlExecutor executor =
                new JdbcDorisSqlExecutor(addr, database, username, password, guard);
        DorisStreamLoadClient stream =
                new DorisStreamLoadClient(httpBase, database, username, password, guard);
        int buckets = indexCfg != null && indexCfg.bucketsNum > 0 ? indexCfg.bucketsNum : 0;
        int replication = indexCfg != null && indexCfg.replicationNum > 0
                ? indexCfg.replicationNum : 0;
        DorisRetrieveRepository repo = new DorisRetrieveRepository(executor, stream, database,
                tableBaseName, buckets, replication, configured.mode());
        log.info("[Doris] Repository initialized: db={}, base={}, fe_http={}, compat_mode={}",
                database, tableBaseName, httpBase, configured.mode().wire());
        return repo;
    }

    /** 对照 Go {@code hostFromAddr}（engine_factory.go）：从 "host:port" 拆出 host。 */
    public static String hostFromAddr(String addr) {
        if (addr == null) {
            return "";
        }
        int i = addr.lastIndexOf(':');
        return i > 0 ? addr.substring(0, i) : addr;
    }

    /** 对照 {@code types.ResolveCollectionName(indexCfg, DORIS_TABLE_PREFIX, default)}。 */
    static String resolveCollectionName(IndexConfig indexCfg) {
        if (indexCfg != null) {
            if (indexCfg.collectionPrefix != null && !indexCfg.collectionPrefix.isEmpty()) {
                return indexCfg.collectionPrefix;
            }
            if (indexCfg.collectionName != null && !indexCfg.collectionName.isEmpty()) {
                return indexCfg.collectionName;
            }
        }
        String env = System.getenv(ENV_DORIS_TABLE_PREFIX);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return DEFAULT_TABLE_BASE_NAME;
    }

    @Override
    public void close() {
        sql.close();
    }

    // ── 引擎面 ──────────────────────────────────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_DORIS;
    }

    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    /** 对照 {@code EstimateStorageSize}（按内积副本模式的单位化行计）。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null) {
            return 0;
        }
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            DorisVectorEmbedding emb = toEmbedding(info, params,
                    DorisCompatMode.INNER_PRODUCT_DUPLICATE);
            total += DorisSql.calculateStorageSize(emb);
        }
        return total;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /**
     * 对照 {@code BatchSave}：按维度分组；DUPLICATE KEY 表上用 delete + insert 保持
     * "按 id 替换"语义；空向量跳过（WARN）、非有限值拒收。
     */
    // ── 删除 ────────────────────────────────────────────────────────────────

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        return searchOps.retrieve(params);
    }


    // ── 复制与批量更新 ──────────────────────────────────────────────────────

    /**
     * 对照 {@code CopyIndices}（与 Qdrant 实现完全镜像）：分页扫描源表 → chunk_id /
     * knowledge_id 映射改写 → SourceID 三态改写 → 新 UUID 主键写回同一张表。
     */
    /** 对照 {@code BatchUpdateChunkEnabledStatus}：按模式分派 partial update / 整行重写。 */
    /** 对照 {@code BatchUpdateChunkTagID}。 */
    // ── 迁移（KnowledgeIndexMover） ────────────────────────────────────────

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
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                            int dimension, String knowledgeType) throws Exception {
        writeOps.copyIndices(sourceKnowledgeBaseId, sourceToTargetKbIdMap,
                sourceToTargetChunkIdMap, targetKnowledgeBaseId, dimension, knowledgeType);
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
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        writeOps.moveKnowledgeIndices(sourceKb, targetKb, knowledgeId, chunkIds, dimension,
                knowledgeType);
    }




    /** 对照 {@code getTableName}：{@code <base>_<dim>}。 */
    String getTableName(int dimension) {
        return tableBaseName + "_" + dimension;
    }

    DorisCompatMode resolveCompatModeOrThrow() {
        return adminOps.resolveCompatModeOrThrow();
    }

    void ensureTable(int dimension) {
        adminOps.ensureTable(dimension);
    }

    boolean tableExists(String tableName) {
        return adminOps.tableExists(tableName);
    }

    List<String> listEmbeddingTables() {
        return adminOps.listEmbeddingTables();
    }


    // ── 行映射与辅助 ───────────────────────────────────────────────────────

    /**
     * 对照 {@code toDorisVectorEmbedding}：embedding 从
     * {@code additionalParams["embedding"]} 的 {@code Map<String, float[]>} 按 SourceID 取；
     * 非 legacy 模式先单位化。
     */
    static DorisVectorEmbedding toEmbedding(IndexInfo info, Map<String, Object> additionalParams,
                                            DorisCompatMode compatMode) {
        DorisVectorEmbedding emb = new DorisVectorEmbedding();
        emb.id = info.id == null ? "" : info.id;
        emb.content = info.content == null ? "" : info.content;
        emb.sourceId = info.sourceId == null ? "" : info.sourceId;
        emb.sourceType = info.sourceType;
        emb.chunkId = info.chunkId == null ? "" : info.chunkId;
        emb.knowledgeId = info.knowledgeId == null ? "" : info.knowledgeId;
        emb.knowledgeBaseId = info.knowledgeBaseId == null ? "" : info.knowledgeBaseId;
        emb.tagId = info.tagId == null ? "" : info.tagId;
        emb.isEnabled = info.isEnabled;
        if (additionalParams != null) {
            Object raw = additionalParams.get(DorisSql.FIELD_EMBEDDING);
            if (raw instanceof Map<?, ?> map) {
                Object vector = map.get(emb.sourceId);
                if (vector instanceof float[] f) {
                    emb.embedding = f.clone();
                } else if (vector instanceof List<?> list) {
                    // 容错（照 OpenSearch 驱动的 lookupEmbedding）：JSON 形态的向量列表。
                    float[] out = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
                    }
                    emb.embedding = out;
                }
                if (emb.embedding != null && compatMode.normalizeEmbeddings()) {
                    emb.embedding = DorisSql.normalizeEmbedding(emb.embedding);
                }
            }
        }
        return emb;
    }

    /** 对照 {@code scanCopyRows}：embedding 列是 {@code ARRAY<FLOAT>} 的字面量字符串。 */
    static DorisVectorEmbedding scanCopyRow(DorisSqlExecutor.Row row) throws SQLException {
        DorisVectorEmbedding out = new DorisVectorEmbedding();
        out.id = row.string(0);
        out.content = row.string(1);
        out.sourceId = row.string(2);
        out.sourceType = row.intValue(3);
        out.chunkId = row.string(4);
        out.knowledgeId = row.string(5);
        out.knowledgeBaseId = row.string(6);
        out.tagId = row.string(7);
        out.isEnabled = row.booleanValue(8);
        try {
            out.embedding = DorisSql.parseEmbeddingLiteral(row.string(9));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("parse embedding: " + e.getMessage(), e);
        }
        return out;
    }

    /** Go 的 {@code %w} 形态：错误文本取底层 message（为空时取类名）。 */
    static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ── test-connection 探针（照 vectorstore_healthcheck.go testDorisConnection） ──

    /**
     * 连通性探针：MySQL 协议连接（database 空则用 information_schema）+ Ping 语义 +
     * {@code SELECT @@version}（失败只 WARN，返回 ""）；版本串剥 {@code "Doris-"} 前缀
     * （{@code "5.7.99 Doris-4.1.0"} → {@code "4.1.0"}）。
     *
     * @throws SQLException 连接/认证失败（调用方折叠成通用文案）
     */
    public static String testConnection(String addr, String database, String username,
                                        String password) throws SQLException {
        String db = database == null || database.isEmpty() ? "information_schema" : database;
        try (Connection conn = DriverManager.getConnection(
                JdbcDorisSqlExecutor.jdbcUrl(addr, db, 5_000),
                username == null ? "" : username,
                password == null ? "" : password)) {
            String version = "";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@version")) {
                if (rs.next()) {
                    version = rs.getString(1) == null ? "" : rs.getString(1);
                }
            } catch (SQLException e) {
                log.warn("[Doris] Doris version detection failed: {}", e.getMessage());
                return "";
            }
            return stripDorisVersionPrefix(version);
        }
    }

    /**
     * 对照 Go 的剥前缀逻辑（testDorisConnection L323-326）：{@code @@version} 形如
     * {@code "5.7.99 Doris-4.1.0"}——{@code "Doris-"} 之后才是真实版本号。
     */
    static String stripDorisVersionPrefix(String version) {
        if (version == null) {
            return "";
        }
        int i = version.indexOf("Doris-");
        return i >= 0 ? version.substring(i + "Doris-".length()).trim() : version;
    }
}
