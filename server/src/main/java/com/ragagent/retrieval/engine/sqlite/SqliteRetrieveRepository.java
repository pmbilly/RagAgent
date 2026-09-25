package com.ragagent.retrieval.engine.sqlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.Function;

import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrieveEngineRepository;

/**
 * SQLite 检索引擎仓储——对照 Go {@code repository/retriever/sqlite/}
 * （repository.go 661 + move.go 17，约 680 行非测试）。
 *
 * <h2>Go 的口径</h2>
 * Go 的 SQLite 引擎<b>用的是产品库本身</b>（{@code createSQLiteEngine(_ types.VectorStore, db
 * *gorm.DB)}——忽略 store 配置，直接用 GORM 的 {@code *gorm.DB}），在库里建三张表：
 * {@code lite_embeddings}（元数据，GORM AutoMigrate）、{@code lite_embeddings_fts}
 * （<b>FTS5 contentless</b> + 手写 CJK 二元切分）、{@code vec_embeddings_<dim>}
 * （sqlite-vec 的 {@code vec0} 虚拟表，cosine）。写入是 {@code OnConflict DoNothing}
 * （靠 (source_id, source_type) 唯一索引去重）+ FTS 行 + 向量行；关键词走 FTS5 {@code MATCH}
 * 与 {@code bm25()} 打分（×-1000000 变正分数）；向量走 vec0 的 KNN（先取 k 近邻、再按
 * {@code rowid IN (过滤子查询)} 收窄）；阈值在取回后于内存里衰减。
 *
 * <h2>本仓口径（差异备案）</h2>
 * <ol>
 *   <li><b>存储介质</b>：Go 挂产品库（SQLite 形态）；本仓产品库是 PostgreSQL，
 *       "SQLite 引擎"改为一颗<b>独立的 SQLite 文件</b>（{@code SQLITE_PATH}，缺省
 *       {@code ./data/weknora-retrieval.sqlite}）——引擎名与对外语义不变，介质就近成文件；
 *       驱动用 {@code org.xerial:sqlite-jdbc}（平台 native 随 Maven 分发，仓内零二进制）。</li>
 *   <li><b>vec0 → 普通表 + Java 标量函数</b>：sqlite-vec 扩展未随包分发，改存
 *       {@code embedding BLOB}（小端 float32，与 {@code sqlite_vec.SerializeFloat32} 同格式），
 *       相似度由注册的 {@code vec_distance_cosine(blob, blob)} Java 函数算——<b>平面扫描</b>
 *       取代 ANN 索引；排序/取 k/过滤顺序与 Go 逐句一致（cosine 的 KNN 结果完全相同，
 *       仅复杂度不同）。</li>
 *   <li><b>FTS5 照用</b>：实测 xerial 3.46.1 的打包版支持 FTS5/contentless_delete/bm25
 *       → 关键词面与 Go 基本同构（含"老表非 contentless 时重建 + 用二元切分回填"的迁移）。</li>
 *   <li>事务语义：Go 的 GORM 是连接池；本仓每次操作开一条连接（SQLite 文件锁 + WAL）。</li>
 * </ol>
 */
public class SqliteRetrieveRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(SqliteRetrieveRepository.class);

    public static final String ENV_SQLITE_PATH = "SQLITE_PATH";
    public static final String DEFAULT_PATH = "./data/weknora-retrieval.sqlite";

    static final String TABLE_EMBEDDINGS = "lite_embeddings";
    static final String TABLE_FTS = "lite_embeddings_fts";

    private final String dbPath;
    private final boolean memory;
    private final ConcurrentHashMap<Integer, Boolean> vecTables = new ConcurrentHashMap<>();
    /** 内存库必须复用同一连接（否则每次连接都是新库）。 */
    private volatile Connection memoryConnection;

    public SqliteRetrieveRepository(String dbPath) {
        String path = dbPath == null || dbPath.trim().isEmpty() ? DEFAULT_PATH : dbPath.trim();
        this.memory = path.equals(":memory:");
        this.dbPath = path;
        if (!memory) {
            try {
                Path parent = Path.of(path).toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (Exception e) {
                throw new IllegalStateException("[SQLite] cannot prepare data dir for " + path
                        + ": " + e.getMessage(), e);
            }
        }
        migrate();
        ensureExistingVecTables();
    }

    /** 照 {@code NewSQLiteRetrieveEngineRepository}（AutoMigrate + initFTS5 + 既有向量表）。 */
    public static SqliteRetrieveRepository create(String dbPath) {
        log.info("[SQLite] Initializing SQLite retriever engine repository with sqlite-vec");
        return new SqliteRetrieveRepository(dbPath);
    }

    /** 系统属性口（测试/运维便利；优先级在 store 配置之后、env 之前）。 */
    public static final String PROP_SQLITE_PATH = "weknora.sqlite.path";

    /**
     * 路径解析：store 配置（{@code connection_config.addr}，若像路径）→ 系统属性
     * {@code weknora.sqlite.path} → env {@code SQLITE_PATH} → 缺省
     * {@code ./data/weknora-retrieval.sqlite}。
     */
    public static String resolvePath(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String property = System.getProperty(PROP_SQLITE_PATH);
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String env = System.getenv(ENV_SQLITE_PATH);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return DEFAULT_PATH;
    }

    // ── 连接与 DDL ─────────────────────────────────────────────────────────

    private Connection open() throws SQLException {
        if (memory) {
            Connection existing = memoryConnection;
            if (existing != null) {
                return existing;
            }
            synchronized (this) {
                if (memoryConnection == null) {
                    memoryConnection = createConnection();
                }
                return memoryConnection;
            }
        }
        return createConnection();
    }

    private Connection createConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        // vec0 的 cosine 距离在 Java 侧算（平面扫描；同 sqlite-vec 的 1-cos 定义）
        Function.create(conn, "vec_distance_cosine", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                byte[] a = value_blob(0);
                byte[] b = value_blob(1);
                result(cosineDistance(SqliteCjkBigram.deserializeFloat32(a),
                        SqliteCjkBigram.deserializeFloat32(b)));
            }
        });
        return conn;
    }

    static double cosineDistance(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) {
            return 1.0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 1.0;
        }
        return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private void migrate() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE_EMBEDDINGS + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "created_at DATETIME,"
                    + "updated_at DATETIME,"
                    + "source_id TEXT NOT NULL,"
                    + "source_type INTEGER NOT NULL,"
                    + "chunk_id TEXT,"
                    + "knowledge_id TEXT,"
                    + "knowledge_base_id TEXT,"
                    + "tag_id TEXT,"
                    + "content TEXT NOT NULL,"
                    + "dimension INTEGER NOT NULL,"
                    + "is_enabled INTEGER DEFAULT 1)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_sqlite_emb_source ON "
                    + TABLE_EMBEDDINGS + "(source_id, source_type)");
            for (String column : List.of("chunk_id", "knowledge_id", "knowledge_base_id",
                    "tag_id", "is_enabled")) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_sqlite_emb_" + column + " ON "
                        + TABLE_EMBEDDINGS + "(" + column + ")");
            }
        } catch (SQLException e) {
            log.error("[SQLite] Failed to auto-migrate {}: {}", TABLE_EMBEDDINGS, e.getMessage());
            throw new IllegalStateException("sqlite migrate failed: " + e.getMessage(), e);
        }
        initFts();
    }

    /** 照 {@code initFTS5}：老表非 contentless → 重建并用二元切分回填。 */
    private void initFts() {
        try (Connection conn = open()) {
            String existing = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
                ps.setString(1, TABLE_FTS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existing = rs.getString(1);
                    }
                }
            }
            boolean legacy = existing != null && existing.contains("content='lite_embeddings'");
            if (legacy) {
                log.info("[SQLite] Migrating FTS5 table to contentless table with manual bigram"
                        + " tokenization");
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS " + TABLE_FTS);
                }
                existing = null;
            }
            if (existing == null) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS " + TABLE_FTS + " USING fts5("
                            + "content, source_id, chunk_id, knowledge_id, knowledge_base_id,"
                            + "content='',"
                            + "contentless_delete=1,"
                            + "tokenize='unicode61')");
                }
                log.info("[SQLite] Populating contentless FTS5 table from {} with bigrams",
                        TABLE_EMBEDDINGS);
                List<Object[]> rows = new ArrayList<>();
                try (Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery("SELECT id, content, source_id, chunk_id,"
                                + " knowledge_id, knowledge_base_id FROM " + TABLE_EMBEDDINGS)) {
                    while (rs.next()) {
                        rows.add(new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6)});
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE_FTS
                        + "(rowid, content, source_id, chunk_id, knowledge_id, knowledge_base_id)"
                        + " VALUES(?, ?, ?, ?, ?, ?)")) {
                    for (Object[] row : rows) {
                        ps.setLong(1, (Long) row[0]);
                        ps.setString(2, SqliteCjkBigram.tokenize((String) row[1]));
                        for (int i = 2; i < 6; i++) {
                            ps.setString(i + 1, (String) row[i]);
                        }
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[SQLite] Failed to create FTS5 table: {}", e.getMessage());
        }
    }

    /** 照 {@code ensureExistingVecTables}：按元数据里的既有维度补建向量表。 */
    private void ensureExistingVecTables() {
        List<Integer> dims = new ArrayList<>();
        try (Connection conn = open();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT DISTINCT dimension FROM "
                        + TABLE_EMBEDDINGS + " WHERE dimension > 0")) {
            while (rs.next()) {
                dims.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            log.warn("[SQLite] Failed to scan existing dimensions: {}", e.getMessage());
            return;
        }
        for (int dim : dims) {
            ensureVecTable(dim);
        }
    }

    /** 照 {@code ensureVecTable}：向量表一次性建（普通表 + BLOB；vec0 的等价面）。 */
    void ensureVecTable(int dim) {
        if (dim <= 0 || vecTables.containsKey(dim)) {
            return;
        }
        try (Connection conn = open()) {
            ensureVecTable(conn, dim);
        } catch (SQLException e) {
            log.error("[SQLite] Failed to open connection for vec table dim {}: {}", dim,
                    e.getMessage());
        }
    }

    /**
     * 连接内建表（写路径必需）：WAL 下"另一条连接建的表"在已开启事务的快照里不可见——
     * 故写事务里必须用<b>同一条连接</b>建向量表（SQLite 允许事务内 DDL）。
     */
    private void ensureVecTable(Connection conn, int dim) throws SQLException {
        if (dim <= 0 || vecTables.containsKey(dim)) {
            return;
        }
        String table = vecTableName(dim);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table
                    + " (rowid INTEGER PRIMARY KEY, embedding BLOB)");
            vecTables.put(dim, true);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                vecTables.put(dim, true);
                return;
            }
            log.error("[SQLite] Failed to create vec table for dim {}: {}", dim, e.getMessage());
            throw e;
        }
    }

    static String vecTableName(int dim) {
        return "vec_embeddings_" + dim;
    }

    // ── 引擎面 ──────────────────────────────────────────────────────────────

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_SQLITE;
    }

    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    /** 照 {@code EstimateStorageSize}：每条 {@code len(content)+200}（字节长度）。 */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        if (indexInfoList == null) {
            return 0;
        }
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            total += byteLength(info.content) + 200;
        }
        return total;
    }

    static long byteLength(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ── 写入（照 Save/BatchSave：INSERT OR IGNORE + FTS + 向量） ───────────

    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        batchSave(List.of(indexInfo), params);
    }

    @Override
    public void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params)
            throws Exception {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                List<long[]> newIds = new ArrayList<>();
                List<float[]> vectors = new ArrayList<>();
                for (IndexInfo info : indexInfoList) {
                    float[] emb = extractEmbedding(params, info.sourceId);
                    Row row = toRow(info, emb.length);
                    long id = insertIgnore(conn, row);
                    if (id > 0) {
                        syncFtsInsert(conn, id, row);
                        newIds.add(new long[] {id, emb.length});
                        vectors.add(emb);
                    }
                }
                for (int i = 0; i < newIds.size(); i++) {
                    long id = newIds.get(i)[0];
                    int dim = (int) newIds.get(i)[1];
                    float[] emb = vectors.get(i);
                    if (dim > 0 && id > 0) {
                        insertVec(conn, id, dim, emb);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 行模型（照 {@code sqliteEmbedding}）。 */
    static final class Row {

        long id;
        String sourceId = "";
        int sourceType;
        String chunkId = "";
        String knowledgeId = "";
        String knowledgeBaseId = "";
        String tagId = "";
        String content = "";
        int dimension;
        boolean isEnabled = true;
    }

    /** 照 {@code toSQLiteEmbedding}：content 过 CleanInvalidUTF8；is_enabled 恒有值。 */
    static Row toRow(IndexInfo info, int dimension) {
        Row row = new Row();
        row.sourceId = info.sourceId == null ? "" : info.sourceId;
        row.sourceType = info.sourceType;
        row.chunkId = info.chunkId == null ? "" : info.chunkId;
        row.knowledgeId = info.knowledgeId == null ? "" : info.knowledgeId;
        row.knowledgeBaseId = info.knowledgeBaseId == null ? "" : info.knowledgeBaseId;
        row.tagId = info.tagId == null ? "" : info.tagId;
        row.content = cleanInvalidUtf8(info.content);
        row.dimension = dimension;
        row.isEnabled = info.isEnabled;
        return row;
    }

    /** 照 {@code extractEmbedding}：params["embedding"] 是 sourceID→向量的表。 */
    static float[] extractEmbedding(Map<String, Object> params, String sourceId) {
        if (params == null) {
            return new float[0];
        }
        Object raw = params.get("embedding");
        if (!(raw instanceof Map<?, ?> map)) {
            return new float[0];
        }
        Object value = map.get(sourceId);
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

    /** 照 GORM 的 {@code OnConflict DoNothing}：唯一索引冲突 → 忽略并返回 0。 */
    private long insertIgnore(Connection conn, Row row) throws SQLException {
        String sql = "INSERT OR IGNORE INTO " + TABLE_EMBEDDINGS + "(created_at, updated_at,"
                + " source_id, source_type, chunk_id, knowledge_id, knowledge_base_id, tag_id,"
                + " content, dimension, is_enabled) VALUES(datetime('now'), datetime('now'),"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setRowParams(ps, row);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                return 0;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return 0;
    }

    private static void setRowParams(PreparedStatement ps, Row row) throws SQLException {
        ps.setString(1, row.sourceId);
        ps.setInt(2, row.sourceType);
        ps.setString(3, row.chunkId);
        ps.setString(4, row.knowledgeId);
        ps.setString(5, row.knowledgeBaseId);
        ps.setString(6, row.tagId);
        ps.setString(7, row.content);
        ps.setInt(8, row.dimension);
        ps.setInt(9, row.isEnabled ? 1 : 0);
    }

    /** 照 {@code syncFTS5Insert}：内容先过二元切分再进 FTS5。 */
    private void syncFtsInsert(Connection conn, long id, Row row) throws SQLException {
        if (id == 0) {
            return;
        }
        String tokenized = SqliteCjkBigram.tokenize(row.content);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE_FTS
                + "(rowid, content, source_id, chunk_id, knowledge_id, knowledge_base_id)"
                + " VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, tokenized);
            ps.setString(3, row.sourceId);
            ps.setString(4, row.chunkId);
            ps.setString(5, row.knowledgeId);
            ps.setString(6, row.knowledgeBaseId);
            ps.executeUpdate();
        }
    }

    private void insertVec(Connection conn, long rowId, int dim, float[] emb) throws SQLException {
        ensureVecTable(conn, dim);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + vecTableName(dim)
                + "(rowid, embedding) VALUES (?, ?)")) {
            ps.setLong(1, rowId);
            ps.setBytes(2, SqliteCjkBigram.serializeFloat32(emb));
            ps.executeUpdate();
        }
    }

    // ── 删除（照 Go：先查行 → 删向量/FTS → 删元数据） ──────────────────────

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteBy("chunk_id", chunkIdList);
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteBy("source_id", sourceIdList);
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteBy("knowledge_id", knowledgeIdList);
    }

    private void deleteBy(String column, List<String> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            List<Row> rows = findRows(conn, column, values);
            deleteRowsAndVecs(conn, rows);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + TABLE_EMBEDDINGS
                    + " WHERE " + column + " IN (" + placeholders(values.size()) + ")")) {
                bindStrings(ps, 1, values);
                ps.executeUpdate();
            }
        }
    }

    private List<Row> findRows(Connection conn, String column, List<String> values)
            throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, dimension FROM "
                + TABLE_EMBEDDINGS + " WHERE " + column + " IN ("
                + placeholders(values.size()) + ")")) {
            bindStrings(ps, 1, values);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row row = new Row();
                    row.id = rs.getLong(1);
                    row.dimension = rs.getInt(2);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 照 {@code deleteRowsAndVecs}：只动"已建向量表"的维度，再删 FTS 行。 */
    private void deleteRowsAndVecs(Connection conn, List<Row> rows) throws SQLException {
        for (Row row : rows) {
            if (row.dimension > 0 && vecTables.containsKey(row.dimension)) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM "
                        + vecTableName(row.dimension) + " WHERE rowid = ?")) {
                    ps.setLong(1, row.id);
                    ps.executeUpdate();
                }
            }
        }
        for (Row row : rows) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + TABLE_FTS + " WHERE rowid = ?")) {
                ps.setLong(1, row.id);
                ps.executeUpdate();
            }
        }
    }

    // ── 批量更新（逐 chunk UPDATE 元数据，照 Go） ──────────────────────────

    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + TABLE_EMBEDDINGS + " SET is_enabled = ?, updated_at = datetime('now')"
                        + " WHERE chunk_id = ?")) {
            for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
                ps.setInt(1, Boolean.TRUE.equals(entry.getValue()) ? 1 : 0);
                ps.setString(2, entry.getKey());
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + TABLE_EMBEDDINGS + " SET tag_id = ?, updated_at = datetime('now')"
                        + " WHERE chunk_id = ?")) {
            for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
                ps.setString(1, entry.getValue() == null ? "" : entry.getValue());
                ps.setString(2, entry.getKey());
                ps.executeUpdate();
            }
        }
    }

    // ── 拷贝（照 Go：逐 chunk 读源行 → 新 UUID SourceID → 复制 FTS/向量） ──

    @Override
    public void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<String, String> entry : sourceToTargetChunkIdMap.entrySet()) {
                    String sourceChunkId = entry.getKey();
                    String targetChunkId = entry.getValue();
                    Row src = findByChunkId(conn, sourceChunkId);
                    if (src == null) {
                        continue;
                    }
                    Row target = new Row();
                    target.sourceId = UUID.randomUUID().toString();
                    target.sourceType = src.sourceType;
                    target.chunkId = targetChunkId;
                    target.knowledgeId = sourceToTargetKbIdMap == null ? ""
                            : nullToEmpty(sourceToTargetKbIdMap.get(src.knowledgeId));
                    target.knowledgeBaseId = targetKnowledgeBaseId;
                    target.tagId = src.tagId;
                    target.content = src.content;
                    target.dimension = src.dimension;
                    target.isEnabled = src.isEnabled;
                    long newId = insertIgnore(conn, target);
                    if (newId <= 0) {
                        log.warn("[SQLite] CopyIndices: failed to copy chunk {}", sourceChunkId);
                        continue;
                    }
                    syncFtsInsert(conn, newId, target);
                    if (src.dimension > 0 && newId > 0) {
                        copyVec(conn, src.id, newId, src.dimension);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private Row findByChunkId(Connection conn, String chunkId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, source_type, chunk_id,"
                + " knowledge_id, knowledge_base_id, tag_id, content, dimension, is_enabled"
                + " FROM " + TABLE_EMBEDDINGS + " WHERE chunk_id = ? LIMIT 1")) {
            ps.setString(1, chunkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Row row = new Row();
                row.id = rs.getLong(1);
                row.sourceType = rs.getInt(2);
                row.chunkId = nullToEmpty(rs.getString(3));
                row.knowledgeId = nullToEmpty(rs.getString(4));
                row.knowledgeBaseId = nullToEmpty(rs.getString(5));
                row.tagId = nullToEmpty(rs.getString(6));
                row.content = nullToEmpty(rs.getString(7));
                row.dimension = rs.getInt(8);
                row.isEnabled = rs.getInt(9) != 0;
                return row;
            }
        }
    }

    /** 照 {@code copyVec}：向量行整行复制（同一维度表内）。 */
    private void copyVec(Connection conn, long srcId, long dstId, int dim) throws SQLException {
        if (!vecTables.containsKey(dim)) {
            return;
        }
        ensureVecTable(conn, dim);
        String table = vecTableName(dim);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + table
                + "(rowid, embedding) SELECT ?, embedding FROM " + table + " WHERE rowid = ?")) {
            ps.setLong(1, dstId);
            ps.setLong(2, srcId);
            ps.executeUpdate();
        }
    }

    // ── move（照 move.go：一条 UPDATE，FTS/向量行靠 rowid 关联不动） ───────

    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + TABLE_EMBEDDINGS + " SET knowledge_base_id = ?, tag_id = '',"
                        + " updated_at = datetime('now')"
                        + " WHERE knowledge_base_id = ? AND knowledge_id = ?")) {
            ps.setString(1, targetKb);
            ps.setString(2, sourceKb);
            ps.setString(3, knowledgeId);
            ps.executeUpdate();
        }
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /**
     * 照 Go 的分派：{@code keywords} 或<b>空类型</b>跑关键词、{@code vector} 或<b>空类型</b>
     * 跑向量——空类型会<b>两条都跑</b>并合并（其他店是"未知类型报错"，此店是特例）。
     */
    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        List<RetrieveResult> results = new ArrayList<>();
        String type = params == null || params.retrieverType == null ? "" : params.retrieverType;
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(type) || type.isEmpty()) {
            results.addAll(keywordsRetrieve(params));
        }
        if (EngineTypes.RETRIEVER_VECTOR.equals(type) || type.isEmpty()) {
            results.addAll(vectorRetrieve(params));
        }
        // 照 Go：未知类型<b>不报错</b>，返回空列表（其他店的"invalid retriever type"是特例）
        return results;
    }

    private List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws SQLException {
        String query = params.query == null ? "" : params.query;
        if (query.isEmpty()) {
            return List.of();
        }
        String ftsQuery = SqliteCjkBigram.sanitizeQuery(query);
        StringBuilder sql = new StringBuilder("SELECT e.id, e.source_id, e.source_type, e.chunk_id,"
                + " e.knowledge_id, e.knowledge_base_id, e.tag_id, e.content,"
                + " (bm25(" + TABLE_FTS + ") * -1000000.0) AS score"
                + " FROM " + TABLE_FTS + " JOIN " + TABLE_EMBEDDINGS
                + " e ON e.id = " + TABLE_FTS + ".rowid"
                + " WHERE " + TABLE_FTS + " MATCH ?"
                + " AND (e.is_enabled IS NULL OR e.is_enabled = 1)");
        List<Object> args = new ArrayList<>();
        args.add(ftsQuery);
        for (FilterWhere wp : buildFilterWhere(params, "e")) {
            sql.append(" AND ").append(wp.clause());
            args.addAll(wp.args());
        }
        sql.append(" ORDER BY score DESC LIMIT ?");
        args.add(Math.max(params.topK, 0));

        List<IndexWithScore> items = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexWithScore item = readIndex(rs, false);
                    item.matchType = EngineTypes.MATCH_KEYWORDS;
                    items.add(item);
                }
            }
        } catch (SQLException e) {
            throw new SQLException("FTS5 query failed: " + e.getMessage(), e);
        }
        log.info("[SQLite] keywordsRetrieve: query={}, ftsQuery={}, matched={} rows", query,
                ftsQuery, items.size());
        return List.of(new RetrieveResult(items, EngineTypes.ENGINE_SQLITE,
                EngineTypes.RETRIEVER_KEYWORDS));
    }

    /**
     * 照 Go 的 vec0 查询形状：<b>先取 k 近邻，再用 {@code rowid IN (过滤子查询)} 收窄</b>
     * （因此结果可能少于 TopK——这是 Go 的既有语义，别"顺手"改成先过滤）；阈值在取回后于
     * 内存里衰减（{@code score < threshold → 跳过}）。
     */
    private List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws SQLException {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        if (embedding.length == 0) {
            return List.of();
        }
        int dim = embedding.length;
        ensureVecTable(dim);
        String table = vecTableName(dim);
        String filterSql = "SELECT filtered.id FROM " + TABLE_EMBEDDINGS + " filtered"
                + " WHERE (filtered.is_enabled IS NULL OR filtered.is_enabled = 1)";
        List<Object> args = new ArrayList<>();
        args.add(SqliteCjkBigram.serializeFloat32(embedding));
        args.add(Math.max(params.topK, 0));
        List<String> clauses = new ArrayList<>();
        List<Object> filterArgs = new ArrayList<>();
        for (FilterWhere wp : buildFilterWhere(params, "filtered")) {
            clauses.add(wp.clause());
            filterArgs.addAll(wp.args());
        }
        if (!clauses.isEmpty()) {
            filterSql += " AND " + String.join(" AND ", clauses);
        }
        String sql = "SELECT v.rowid, v.distance, e.source_id, e.source_type, e.chunk_id,"
                + " e.knowledge_id, e.knowledge_base_id, e.tag_id, e.content"
                + " FROM (SELECT rowid, vec_distance_cosine(embedding, ?) AS distance FROM "
                + table + " ORDER BY distance ASC LIMIT ?) v"
                + " JOIN " + TABLE_EMBEDDINGS + " e ON e.id = v.rowid"
                + " WHERE v.rowid IN (" + filterSql + ")"
                + " ORDER BY v.distance ASC";
        args.addAll(filterArgs);

        List<IndexWithScore> items = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double distance = rs.getDouble("distance");
                    double score = 1 - distance;
                    if (params.threshold > 0 && score < params.threshold) {
                        continue;
                    }
                    IndexWithScore item = readIndex(rs, true);
                    item.score = score;
                    item.matchType = EngineTypes.MATCH_EMBEDDING;
                    items.add(item);
                }
            }
        } catch (SQLException e) {
            throw new SQLException("sqlite-vec query failed: " + e.getMessage(), e);
        }
        log.info("[SQLite] vectorRetrieve: query_dim={}, threshold={}, matched={} rows", dim,
                params.threshold, items.size());
        return List.of(new RetrieveResult(items, EngineTypes.ENGINE_SQLITE,
                EngineTypes.RETRIEVER_VECTOR));
    }

    /** 行读取：{@code id} 用 rowid 的十进制串（照 Go 的 {@code fmt.Sprintf("%d", row.ID)}）。 */
    private static IndexWithScore readIndex(ResultSet rs, boolean withRowid) throws SQLException {
        IndexWithScore item = new IndexWithScore();
        item.id = String.valueOf(withRowid ? rs.getLong("rowid") : rs.getLong("id"));
        item.sourceId = nullToEmpty(rs.getString("source_id"));
        item.sourceType = rs.getInt("source_type");
        item.chunkId = nullToEmpty(rs.getString("chunk_id"));
        item.knowledgeId = nullToEmpty(rs.getString("knowledge_id"));
        item.knowledgeBaseId = nullToEmpty(rs.getString("knowledge_base_id"));
        item.tagId = nullToEmpty(rs.getString("tag_id"));
        item.content = nullToEmpty(rs.getString("content"));
        if (!withRowid) {
            item.score = rs.getDouble("score");
        }
        return item;
    }

    // ── 过滤（照 buildFilterWhere：只有 KB/知识/标签三个 IN，无排除项） ────

    record FilterWhere(String clause, List<Object> args) {
    }

    static List<FilterWhere> buildFilterWhere(RetrieveParams params, String alias) {
        List<FilterWhere> parts = new ArrayList<>();
        if (params == null) {
            return parts;
        }
        addFilter(parts, alias + ".knowledge_base_id", params.knowledgeBaseIds);
        addFilter(parts, alias + ".knowledge_id", params.knowledgeIds);
        addFilter(parts, alias + ".tag_id", params.tagIds);
        return parts;
    }

    private static void addFilter(List<FilterWhere> parts, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        parts.add(new FilterWhere(column + " IN (" + placeholders(values.size()) + ")",
                new ArrayList<>(values)));
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void bindStrings(PreparedStatement ps, int start, List<String> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setString(start + i, values.get(i));
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (arg instanceof Long n) {
                ps.setLong(i + 1, n);
            } else if (arg instanceof byte[] bytes) {
                ps.setBytes(i + 1, bytes);
            } else {
                ps.setString(i + 1, String.valueOf(arg));
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 对照 Go {@code common.CleanInvalidUTF8}（丢 NUL 与孤立代理项）。 */
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

}
