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
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.Function;

import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
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
    final ConcurrentHashMap<Integer, Boolean> vecTables = new ConcurrentHashMap<>();
    /** 内存库必须复用同一连接（否则每次连接都是新库）。 */
    private volatile Connection memoryConnection;

    final SqliteSearchOps searchOps;
    final SqliteWriteOps writeOps;

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
        this.searchOps = new SqliteSearchOps(this);
        this.writeOps = new SqliteWriteOps(this);
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
        String env = com.ragagent.retrieval.config.RetrievalEnvLookup.get(ENV_SQLITE_PATH);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return DEFAULT_PATH;
    }

    // ── 连接与 DDL ─────────────────────────────────────────────────────────

    Connection open() throws SQLException {
        if (memory) {
            Connection existing = memoryConnection;
            if (existing == null) {
                synchronized (this) {
                    if (memoryConnection == null) {
                        memoryConnection = createConnection();
                    }
                    existing = memoryConnection;
                }
            }
            // 共享连接必须不可关闭且串行：调用点全是 try-with-resources，裸共享连接
            // 会在第一次操作后被 close 掉，:memory: 库随之销毁（第二次 open 返回死连接）。
            return nonClosingSerial(existing);
        }
        return createConnection();
    }

    /** memory 模式的共享连接代理：close() no-op，其余调用在仓库锁上串行化。 */
    private Connection nonClosingSerial(Connection target) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                        return null;
                    }
                    synchronized (SqliteRetrieveRepository.this) {
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            Throwable cause = e.getCause() == null ? e : e.getCause();
                            if (cause instanceof SQLException se) {
                                throw se;
                            }
                            if (cause instanceof RuntimeException re) {
                                throw re;
                            }
                            if (cause instanceof Error err) {
                                throw err;
                            }
                            throw new SQLException(cause);
                        }
                    }
                });
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
    void ensureVecTable(Connection conn, int dim) throws SQLException {
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

    // ── 删除（照 Go：先查行 → 删向量/FTS → 删元数据） ──────────────────────

    // ── 批量更新（逐 chunk UPDATE 元数据，照 Go） ──────────────────────────

    // ── 拷贝（照 Go：逐 chunk 读源行 → 新 UUID SourceID → 复制 FTS/向量） ──

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
        return searchOps.retrieve(params);
    }


    // ── 小工具 ──────────────────────────────────────────────────────────────

    static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    static void bindStrings(PreparedStatement ps, int start, List<String> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setString(start + i, values.get(i));
        }
    }

    static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
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

    static String nullToEmpty(String s) {
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
