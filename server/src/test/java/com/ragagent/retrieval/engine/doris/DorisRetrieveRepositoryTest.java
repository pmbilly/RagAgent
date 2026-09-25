package com.ragagent.retrieval.engine.doris;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * Doris 仓储对照 Go {@code retriever/doris} 全包（repository.go / schema.go / compat.go）：
 * 兼容模式解析（显式/探测/既有表探测/混用拒收）、惰性建表与缓存、
 * BatchSave 的分组/字面量内联/替换语义、三种删除、向量与关键词检索的 SQL 形状、
 * CopyIndices 三态改写、批量更新的整行重写、move 的模式拒收、存储估算。
 *
 * <p>桩是记账用的假 SQL 执行器：断言"发出去的 SQL / 参数序长什么样"（该批无 golden 面，
 * 逐句断言即本批的字节契约）。Stream Load 面在 {@code DorisStreamLoadTest}。</p>
 */
class DorisRetrieveRepositoryTest {

    // ── 假执行器 ───────────────────────────────────────────────────────────

    record Call(String sql, List<Object> args) {
    }

    record FakeRowSpec(List<String> columns, List<Object> values) {
    }

    interface RowsProvider {
        List<FakeRowSpec> rows(String sql, List<Object> args) throws SQLException;
    }

    interface ScalarProvider {
        Object scalar(String sql, List<Object> args) throws SQLException;
    }

    static final class FakeSql implements DorisSqlExecutor {

        final List<Call> executed = new ArrayList<>();
        final List<Call> queried = new ArrayList<>();
        final List<Call> scalars = new ArrayList<>();
        RowsProvider rowsProvider = (sql, args) -> List.of();
        ScalarProvider scalarProvider = (sql, args) -> {
            throw new SQLException("no scalar stub: " + sql);
        };

        @Override
        public int execute(String sql, List<Object> args) {
            executed.add(new Call(sql, args));
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, List<Object> args, RowMapper<T> mapper)
                throws SQLException {
            queried.add(new Call(sql, args));
            List<T> out = new ArrayList<>();
            for (FakeRowSpec spec : rowsProvider.rows(sql, args)) {
                out.add(mapper.map(new FakeRow(spec)));
            }
            return out;
        }

        @Override
        public Object scalar(String sql, List<Object> args) throws SQLException {
            scalars.add(new Call(sql, args));
            return scalarProvider.scalar(sql, args);
        }

        @Override
        public void close() {
        }
    }

    static final class FakeRow implements DorisSqlExecutor.Row {

        private final FakeRowSpec spec;

        FakeRow(FakeRowSpec spec) {
            this.spec = spec;
        }

        @Override
        public int columnCount() {
            return spec.values().size();
        }

        @Override
        public String columnName(int index) {
            return spec.columns().get(index);
        }

        @Override
        public String string(int index) {
            Object v = spec.values().get(index);
            return v == null ? null : String.valueOf(v);
        }

        @Override
        public int intValue(int index) {
            return ((Number) spec.values().get(index)).intValue();
        }

        @Override
        public double doubleValue(int index) {
            return ((Number) spec.values().get(index)).doubleValue();
        }

        @Override
        public boolean booleanValue(int index) {
            return (Boolean) spec.values().get(index);
        }
    }

    /** 缺省桩：表已存在；inner_product 探针可用；无既有 embedding 表。 */
    private static FakeSql fakeSql() {
        FakeSql sql = new FakeSql();
        sql.scalarProvider = (q, a) -> {
            if (q.startsWith("SELECT COUNT(1) FROM information_schema.tables")) {
                return 1;
            }
            if (q.startsWith("SELECT inner_product_approximate")) {
                return 1.0;
            }
            throw new SQLException("no scalar stub: " + q);
        };
        return sql;
    }

    private static DorisRetrieveRepository repo(FakeSql sql, DorisCompatMode mode) {
        return new DorisRetrieveRepository(sql,
                new DorisStreamLoadClient("http://127.0.0.1:1", "weknora", "", "", null),
                "weknora", "weknora_embeddings", 0, 0, mode);
    }

    private static Map<String, Object> embeddings(Object... sourceIdAndVectorPairs) {
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < sourceIdAndVectorPairs.length; i += 2) {
            map.put((String) sourceIdAndVectorPairs[i], (float[]) sourceIdAndVectorPairs[i + 1]);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", map);
        return params;
    }

    private static IndexInfo info(String id, String sourceId, String chunkId) {
        IndexInfo info = new IndexInfo();
        info.id = id;
        info.sourceId = sourceId;
        info.chunkId = chunkId;
        info.content = "hello";
        info.knowledgeId = "k1";
        info.knowledgeBaseId = "kb1";
        info.tagId = "";
        info.isEnabled = true;
        return info;
    }

    private static String executedSql(FakeSql sql, int index) {
        return sql.executed.get(index).sql();
    }

    private static Call executedContaining(FakeSql sql, String marker) {
        return sql.executed.stream().filter(c -> c.sql().contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("no executed SQL contains " + marker
                        + ": " + sql.executed));
    }

    private static Call queriedContaining(FakeSql sql, String marker) {
        return sql.queried.stream().filter(c -> c.sql().contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("no query contains " + marker + ": "
                        + sql.queried));
    }

    private static FakeRowSpec tableRow(String name) {
        return new FakeRowSpec(List.of("TABLE_NAME"), List.of(name));
    }

    private static FakeRowSpec ddlRow(String table, String ddl) {
        return new FakeRowSpec(List.of("Table", "Create Table"), List.of(table, ddl));
    }

    // ── BatchSave ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("BatchSave：按维度分组（升序）+ DUPLICATE KEY 表 delete+insert + 向量字面量内联")
    void batchSaveGroupsByDimension() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        IndexInfo two = info("row-2", "s2", "c2");
        IndexInfo three = info("row-3", "s3", "c3");
        repo.batchSave(List.of(three, two),
                embeddings("s2", new float[] {3f, 4f}, "s3", new float[] {1f, 0f, 0f}));

        // dim 2 先于 dim 3（TreeMap 升序）：DELETE + INSERT
        assertThat(executedSql(sql, 0)).isEqualTo(
                "DELETE FROM `weknora_embeddings_2` WHERE id IN (?)");
        assertThat(executedSql(sql, 1)).isEqualTo("INSERT INTO `weknora_embeddings_2` "
                + "(id, content, source_id, source_type, chunk_id, knowledge_id, knowledge_base_id,"
                + " tag_id, is_enabled, embedding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, [0.6,0.8])");
        assertThat(sql.executed.get(1).args()).containsExactly("row-2", "hello", "s2", 0,
                "c2", "k1", "kb1", "", true);
        assertThat(executedSql(sql, 3)).isEqualTo("INSERT INTO `weknora_embeddings_3` "
                + "(id, content, source_id, source_type, chunk_id, knowledge_id, "
                + "knowledge_base_id, tag_id, is_enabled, embedding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, [1,0,0])");
    }

    @Test
    @DisplayName("BatchSave：空向量跳过（WARN）；非有限值按 Go 文案拒收；id 兜底 sourceId→UUID")
    void batchSaveValidationAndIdFallback() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        repo.batchSave(List.of(info("r1", "s1", "c1")), embeddings()); // 无向量 → 跳过
        assertThat(sql.executed).isEmpty();

        assertThatThrownBy(() -> repo.batchSave(List.of(info("r1", "s1", "c1")),
                embeddings("s1", new float[] {Float.NaN})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid embedding for chunk c1")
                .hasMessageContaining("doris: embedding[0] is not finite: NaN");

        FakeSql sql2 = fakeSql();
        DorisRetrieveRepository repo2 = repo(sql2, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        repo2.batchSave(List.of(info("", "s1", "c1")), embeddings("s1", new float[] {1f}));
        assertThat(executedContaining(sql2, "INSERT").args().get(0)).isEqualTo("s1");

        FakeSql sql3 = fakeSql();
        DorisRetrieveRepository repo3 = repo(sql3, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        // id 与 sourceId 都空：embedding 仍按 SourceID（空串）取到 → 主键兜底 UUID
        repo3.batchSave(List.of(info("", "", "c1")), embeddings("", new float[] {1f}));
        assertThat((String) executedContaining(sql3, "INSERT").args().get(0))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("legacy 模式：不单位化、只 INSERT（无 delete+insert）")
    void legacyModeInsertsWithoutReplace() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.LEGACY);
        repo.batchSave(List.of(info("r1", "s1", "c1")), embeddings("s1", new float[] {3f, 4f}));
        assertThat(sql.executed).hasSize(1);
        assertThat(executedSql(sql, 0)).contains("INSERT INTO").contains("[3,4]");
    }

    @Test
    @DisplayName("兼容模式 auto：探针选中内积副本 / legacy；两者都不支持 → Go 原文报错")
    void autoProbeSelectsMode() throws Exception {
        // 表不存在（count=0）→ 建表 DDL 直接显形
        FakeSql innerOnly = fakeSql();
        innerOnly.scalarProvider = (q, a) -> {
            if (q.startsWith("SELECT COUNT(1) FROM information_schema.tables")) {
                return 0;
            }
            if (q.startsWith("SELECT inner_product_approximate")) {
                return 1.0;
            }
            throw new SQLException("unsupported");
        };
        DorisRetrieveRepository repoInner = repo(innerOnly, DorisCompatMode.AUTO);
        repoInner.batchSave(List.of(info("r1", "s1", "c1")), embeddings("s1", new float[] {1f}));
        assertThat(executedSql(innerOnly, 0)).contains("CREATE TABLE")
                .contains("DUPLICATE KEY(id)")
                .contains("\"metric_type\"=\"inner_product\"");
        assertThat(innerOnly.scalars.stream().map(Call::sql))
                .anyMatch(q -> q.contains("inner_product_approximate([1.0],[1.0])"));

        FakeSql cosineOnly = fakeSql();
        cosineOnly.scalarProvider = (q, a) -> {
            if (q.startsWith("SELECT COUNT(1) FROM information_schema.tables")) {
                return 0;
            }
            if (q.startsWith("SELECT cosine_distance_approximate")) {
                return 1.0;
            }
            throw new SQLException("unsupported");
        };
        DorisRetrieveRepository repoCosine = repo(cosineOnly, DorisCompatMode.AUTO);
        repoCosine.batchSave(List.of(info("r1", "s1", "c1")), embeddings("s1", new float[] {1f}));
        assertThat(executedSql(cosineOnly, 0)).contains("UNIQUE KEY(id)")
                .contains("\"metric_type\"=\"cosine_distance\"");

        FakeSql neither = fakeSql();
        neither.scalarProvider = (q, a) -> {
            if (q.startsWith("SELECT COUNT(1) FROM information_schema.tables")) {
                return 0;
            }
            throw new SQLException("unsupported");
        };
        DorisRetrieveRepository repoNeither = repo(neither, DorisCompatMode.AUTO);
        assertThatThrownBy(() -> repoNeither.batchSave(List.of(info("r1", "s1", "c1")),
                embeddings("s1", new float[] {1f})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Doris compatibility auto-detection could not find a "
                        + "supported vector function")
                .hasMessageContaining("Set DORIS_COMPAT_MODE=inner_product_duplicate or "
                        + "DORIS_COMPAT_MODE=legacy explicitly");
    }

    @Test
    @DisplayName("既有表探测：显式配置不匹配 → Go 原文；混用模式 → 拒收")
    void existingTableDetection() {
        FakeSql mismatch = fakeSql();
        mismatch.rowsProvider = (q, a) -> {
            if (q.startsWith("SELECT TABLE_NAME FROM information_schema.tables")) {
                return List.of(tableRow("weknora_embeddings_3"));
            }
            if (q.startsWith("SHOW CREATE TABLE")) {
                return List.of(ddlRow("weknora_embeddings_3", "CREATE TABLE x UNIQUE KEY(id)"));
            }
            return List.of();
        };
        DorisRetrieveRepository repo = repo(mismatch, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        assertThatThrownBy(() -> repo.batchSave(List.of(info("r1", "s1", "c1")),
                embeddings("s1", new float[] {1f})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Doris compat mode \"inner_product_duplicate\" does not match existing"
                        + " embedding tables (detected \"legacy\" from weknora_embeddings_3)."
                        + " DORIS_COMPAT_MODE is not interchangeable after weknora_embeddings_*"
                        + " tables are created. Recreate the existing weknora_embeddings_* tables"
                        + " before switching modes, or set DORIS_COMPAT_MODE=legacy");

        FakeSql mixed = fakeSql();
        mixed.rowsProvider = (q, a) -> {
            if (q.startsWith("SELECT TABLE_NAME FROM information_schema.tables")) {
                return List.of(tableRow("weknora_embeddings_2"), tableRow("weknora_embeddings_3"));
            }
            if (q.startsWith("SHOW CREATE TABLE `weknora_embeddings_2`")) {
                return List.of(ddlRow("weknora_embeddings_2", "DUPLICATE KEY(id)"));
            }
            if (q.startsWith("SHOW CREATE TABLE `weknora_embeddings_3`")) {
                return List.of(ddlRow("weknora_embeddings_3", "UNIQUE KEY(id)"));
            }
            return List.of();
        };
        DorisRetrieveRepository mixedRepo = repo(mixed, DorisCompatMode.AUTO);
        assertThatThrownBy(() -> mixedRepo.batchSave(List.of(info("r1", "s1", "c1")),
                embeddings("s1", new float[] {1f})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing Doris embedding tables use mixed compat modes "
                        + "(inner_product_duplicate and legacy)");
    }

    @Test
    @DisplayName("ensureTable 缓存：同维度第二次写入不再判存/建表")
    void ensureTableCached() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        repo.batchSave(List.of(info("r1", "s1", "c1")), embeddings("s1", new float[] {1f}));
        repo.batchSave(List.of(info("r2", "s2", "c2")), embeddings("s2", new float[] {1f}));
        assertThat(sql.scalars.stream()
                .filter(c -> c.sql().startsWith("SELECT COUNT(1) FROM information_schema")))
                .hasSize(1);
    }

    // ── 删除与检索 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("三种删除：IN 子句 + 空列表短路")
    void deleteLists() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        repo.deleteByChunkIdList(List.of("c1", "c2"), 2, "document");
        assertThat(executedSql(sql, 0))
                .isEqualTo("DELETE FROM `weknora_embeddings_2` WHERE chunk_id IN (?, ?)");
        assertThat(sql.executed.get(0).args()).containsExactly("c1", "c2");

        repo.deleteByKnowledgeIdList(List.of("k1"), 2, "document");
        assertThat(executedSql(sql, 1)).isEqualTo(
                "DELETE FROM `weknora_embeddings_2` WHERE knowledge_id IN (?)");

        repo.deleteBySourceIdList(List.of("s1"), 3, "faq");
        assertThat(executedSql(sql, 2)).isEqualTo(
                "DELETE FROM `weknora_embeddings_3` WHERE source_id IN (?)");

        repo.deleteByChunkIdList(List.of(), 2, "document");
        assertThat(sql.executed).hasSize(3);
    }

    @Test
    @DisplayName("向量检索 SQL 形状：单位化查询字面量 + 隐含 is_enabled + 过滤 + HAVING/ORDER/LIMIT")
    void vectorRetrieveSqlShape() throws Exception {
        FakeSql sql = fakeSql();
        sql.rowsProvider = (q, a) -> {
            if (q.contains("inner_product_approximate")) {
                return List.of(new FakeRowSpec(List.of(), List.of("row-1", "hello", "s1", 0,
                        "c1", "k1", "kb1", "t1", true, 0.91)));
            }
            return List.of();
        };
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {3f, 4f};
        params.knowledgeBaseIds = List.of("kb1", "kb2");
        params.excludeChunkIds = List.of("c9");
        params.topK = 10;
        params.threshold = 0.7;

        List<RetrieveResult> results = repo.retrieve(params);

        Call query = queriedContaining(sql, "inner_product_approximate");
        assertThat(query.sql()).isEqualTo(
                "SELECT id, content, source_id, source_type, chunk_id, knowledge_id, "
                        + "knowledge_base_id, tag_id, is_enabled, "
                        + "inner_product_approximate(`embedding`, [0.6,0.8]) AS score "
                        + "FROM `weknora_embeddings_2` WHERE is_enabled = ? "
                        + "AND knowledge_base_id IN (?, ?) AND chunk_id NOT IN (?) "
                        + "HAVING score >= ? ORDER BY score DESC LIMIT 10");
        assertThat(query.args()).containsExactly(true, "kb1", "kb2", "c9", 0.7);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).retrieverEngineType()).isEqualTo(EngineTypes.ENGINE_DORIS);
        IndexWithScore hit = results.get(0).results().get(0);
        assertThat(hit.score).isEqualTo(0.91);
        assertThat(hit.matchType).isEqualTo(EngineTypes.MATCH_EMBEDDING);
        assertThat(hit.sourceId).isEqualTo("s1");
    }

    @Test
    @DisplayName("向量检索 legacy：cosine 表达式且不单位化；表不存在 → 空结果（不发检索查询）")
    void vectorRetrieveLegacyAndMissingTable() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.LEGACY);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {3f, 4f};
        params.topK = 5;
        repo.retrieve(params);
        assertThat(queriedContaining(sql, "cosine_distance_approximate").sql())
                .contains("(1 - cosine_distance_approximate(`embedding`, [3,4])) AS score");

        FakeSql missing = fakeSql();
        missing.scalarProvider = (q, a) -> 0; // 表不存在
        DorisRetrieveRepository repoMissing = repo(missing, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        List<RetrieveResult> empty = repoMissing.retrieve(params);
        assertThat(empty.get(0).results()).isEmpty();
        assertThat(missing.queried.stream().map(Call::sql))
                .noneMatch(q -> q.contains("inner_product_approximate"));
        assertThat(missing.scalars.stream().map(Call::sql))
                .anyMatch(q -> q.contains("SELECT COUNT(1) FROM information_schema"));
    }

    @Test
    @DisplayName("关键词检索：跨表 MATCH_ANY、score 恒 1.0、topK 截断、单表失败跳过")
    void keywordsRetrieve() throws Exception {
        FakeSql sql = fakeSql();
        sql.rowsProvider = (q, a) -> {
            if (q.startsWith("SELECT TABLE_NAME FROM information_schema.tables")) {
                return List.of(tableRow("weknora_embeddings_2"), tableRow("weknora_embeddings_3"));
            }
            if (q.contains("`weknora_embeddings_2`")) {
                return List.of(keywordRow("h1"), keywordRow("h2"), keywordRow("h3"));
            }
            if (q.contains("`weknora_embeddings_3`")) {
                throw new SQLException("table gone");
            }
            return List.of();
        };
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "  知识库  ";
        params.topK = 2;
        List<RetrieveResult> results = repo.retrieve(params);

        Call query = queriedContaining(sql, "MATCH_ANY");
        assertThat(query.sql()).isEqualTo(
                "SELECT id, content, source_id, source_type, chunk_id, knowledge_id, "
                        + "knowledge_base_id, tag_id, is_enabled "
                        + "FROM `weknora_embeddings_2` WHERE is_enabled = ? "
                        + "AND content MATCH_ANY ? LIMIT 2");
        assertThat(query.args()).containsExactly(true, "知识库");
        assertThat(results.get(0).results()).hasSize(2); // topK 截断（第 3 条丢弃）
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_KEYWORDS);
        assertThat(results.get(0).results().get(0).score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("关键词检索：空 query / 无表 → 空结果且零检索查询")
    void keywordsShortCircuits() throws Exception {
        FakeSql sql = fakeSql();
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "   ";
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
        assertThat(sql.queried).isEmpty();

        params.query = "x";
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
        assertThat(sql.queried.stream().map(Call::sql))
                .anyMatch(q -> q.startsWith("SELECT TABLE_NAME FROM information_schema.tables"));
    }

    @Test
    @DisplayName("retrieve：未知检索类型 → Go 原文")
    void retrieveInvalidType() {
        DorisRetrieveRepository repo = repo(fakeSql(), DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "bogus";
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid retriever type: bogus");
    }

    private static FakeRowSpec keywordRow(String id) {
        return new FakeRowSpec(List.of(), List.of(id, "content", "s-" + id, 0,
                "c-" + id, "k1", "kb1", "", true));
    }

    // ── CopyIndices ───────────────────────────────────────────────────────

    @Test
    @DisplayName("CopyIndices：分页扫描 + 三态 SourceID 改写 + 新 UUID 主键 + 未映射行跳过")
    void copyIndices() throws Exception {
        FakeSql sql = fakeSql();
        sql.rowsProvider = (q, a) -> {
            if (q.startsWith("SELECT id, content, source_id") && q.contains("knowledge_base_id = ?")) {
                return List.of(
                        copyRow("src-1", "c1", "k1", "c1", "[1,2]"),
                        copyRow("src-2", "c2", "k2", "c2-q7", "[3,4]"),
                        copyRow("src-3", "c3", "k3", "zzz", "[5,6]"));
            }
            return List.of();
        };
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        Map<String, String> kbMap = Map.of("k1", "tk1", "k2", "tk2");
        Map<String, String> chunkMap = Map.of("c1", "tc1", "c2", "tc2");
        repo.copyIndices("srcKb", kbMap, chunkMap, "targetKb", 2, "document");

        Call scan = queriedContaining(sql, "ORDER BY id LIMIT 64 OFFSET 0");
        assertThat(scan.sql()).isEqualTo(
                "SELECT id, content, source_id, source_type, chunk_id, knowledge_id, "
                        + "knowledge_base_id, tag_id, is_enabled, embedding "
                        + "FROM `weknora_embeddings_2` WHERE knowledge_base_id = ? "
                        + "ORDER BY id LIMIT 64 OFFSET 0");
        assertThat(scan.args()).containsExactly("srcKb");

        Call insert = executedContaining(sql, "INSERT");
        assertThat(insert.sql()).contains("[1,2]").contains("[3,4]");
        assertThat(insert.args()).hasSize(18);
        assertThat((String) insert.args().get(0)).matches("[0-9a-f-]{36}");
        assertThat(insert.args()).containsExactly(
                insert.args().get(0), "content", "tc1", 0, "tc1", "tk1", "targetKb", "", true,
                insert.args().get(9), "content", "tc2-q7", 0, "tc2", "tk2", "targetKb", "", true);

        // 空 chunk 映射 → 短路
        FakeSql untouched = fakeSql();
        repo(untouched, DorisCompatMode.INNER_PRODUCT_DUPLICATE)
                .copyIndices("srcKb", kbMap, Map.of(), "targetKb", 2, "document");
        assertThat(untouched.executed).isEmpty();
        assertThat(untouched.queried).isEmpty();
    }

    private static FakeRowSpec copyRow(String id, String chunkId, String knowledgeId,
                                       String sourceId, String embedding) {
        return new FakeRowSpec(List.of(), List.of(id, "content", sourceId, 0, chunkId,
                knowledgeId, "srcKb", "", true, embedding));
    }

    // ── 批量更新（整行重写） ───────────────────────────────────────────────

    @Test
    @DisplayName("批量更新（内积副本）：读整行 → 变异 → delete+insert 回写")
    void batchUpdateRewritesRows() throws Exception {
        FakeSql sql = fakeSql();
        sql.rowsProvider = (q, a) -> {
            if (q.startsWith("SELECT TABLE_NAME FROM information_schema.tables")) {
                return List.of(tableRow("weknora_embeddings_2"));
            }
            if (q.startsWith("SHOW CREATE TABLE")) {
                return List.of(ddlRow("weknora_embeddings_2", "DUPLICATE KEY(id)"));
            }
            if (q.startsWith("SELECT id, content, source_id") && q.contains("chunk_id IN")) {
                return List.of(copyRow("r1", "c1", "k1", "s1", "[1]"),
                        copyRow("r2", "c2", "k1", "s2", "[2]"));
            }
            return List.of();
        };
        DorisRetrieveRepository repo = repo(sql, DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        repo.batchUpdateChunkEnabledStatus(Map.of("c1", false));

        assertThat(queriedContaining(sql, "chunk_id IN").sql()).isEqualTo(
                "SELECT id, content, source_id, source_type, chunk_id, knowledge_id, "
                        + "knowledge_base_id, tag_id, is_enabled, embedding "
                        + "FROM `weknora_embeddings_2` WHERE chunk_id IN (?)");
        assertThat(executedSql(sql, 0)).isEqualTo(
                "DELETE FROM `weknora_embeddings_2` WHERE id IN (?)");
        assertThat(sql.executed.get(0).args()).containsExactly("r1");
        Call insert = executedContaining(sql, "INSERT");
        assertThat(insert.args()).containsExactly("r1", "content", "s1", 0, "c1", "k1",
                "srcKb", "", false);

        repo.batchUpdateChunkTagID(Map.of("c2", "t9"));
        Call tagInsert = sql.executed.get(sql.executed.size() - 1);
        assertThat(tagInsert.sql()).contains("INSERT");
        assertThat(tagInsert.args()).containsExactly("r2", "content", "s2", 0, "c2", "k1",
                "srcKb", "t9", true);
    }

    // ── move ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("move：内积副本拒 reuse_vectors；legacy 走 UPDATE；非法维度拒收")
    void moveIndices() throws Exception {
        DorisRetrieveRepository dup = repo(fakeSql(), DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        assertThatThrownBy(() -> dup.moveKnowledgeIndices("srcKb", "targetKb", "k1",
                List.of(), 2, "document"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reuse_vectors move is not supported by Doris ANN tables; "
                        + "use reparse mode");

        FakeSql legacySql = fakeSql();
        DorisRetrieveRepository legacy = repo(legacySql, DorisCompatMode.LEGACY);
        legacy.moveKnowledgeIndices("srcKb", "targetKb", "k1", List.of(), 2, "document");
        assertThat(executedSql(legacySql, 0)).isEqualTo("UPDATE `weknora_embeddings_2` SET "
                + "knowledge_base_id = ?, tag_id = '' WHERE knowledge_base_id = ? "
                + "AND knowledge_id = ?");
        assertThat(legacySql.executed.get(0).args())
                .containsExactly("targetKb", "srcKb", "k1");

        assertThatThrownBy(() -> legacy.moveKnowledgeIndices("srcKb", "targetKb", "k1",
                List.of(), 0, "document"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid embedding dimension");
    }

    // ── test-connection 探针（拨号与版本剥离） ──────────────────────────────

    @Test
    @DisplayName("test-connection：MySQL 协议驱动在类路径（连不上 → SQLException，而非 No suitable driver）")
    void testConnectionUsesMysqlDriver() {
        assertThatThrownBy(() -> DorisRetrieveRepository.testConnection("127.0.0.1:1", "",
                "root", "pw"))
                .isInstanceOf(SQLException.class)
                .hasMessageNotContaining("No suitable driver");
    }

    @Test
    @DisplayName("版本剥离：\"5.7.99 Doris-4.1.0\" → \"4.1.0\"；无前缀原样；空 → \"\"")
    void stripDorisVersionPrefix() {
        assertThat(DorisRetrieveRepository.stripDorisVersionPrefix("5.7.99 Doris-4.1.0"))
                .isEqualTo("4.1.0");
        assertThat(DorisRetrieveRepository.stripDorisVersionPrefix("8.0.30")).isEqualTo("8.0.30");
        assertThat(DorisRetrieveRepository.stripDorisVersionPrefix(null)).isEmpty();
    }

    // ── 存储估算 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("estimateStorageSize：逐行按 payload+向量+HNSW+元数据求和")
    void estimateStorageSize() {
        DorisRetrieveRepository repo = repo(fakeSql(), DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        IndexInfo a = info("r1", "s1", "c1");
        IndexInfo b = info("r2", "s2", "c2");
        long size = repo.estimateStorageSize(List.of(a, b),
                embeddings("s1", new float[] {1f, 2f}, "s2", new float[] {1f, 2f}));
        // 每行：payload = 5(hello)+2+2+2+3+0+8 = 22；vec = 8；hnsw = 512；meta = 24
        assertThat(size).isEqualTo(2L * (22 + 8 + 512 + 24));
    }
}
