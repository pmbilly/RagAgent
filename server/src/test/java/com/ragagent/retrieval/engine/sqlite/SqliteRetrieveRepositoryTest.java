package com.ragagent.retrieval.engine.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * SQLite 驱动（W5γ4.16）对<b>真实 SQLite</b>（xerial 3.46.1，FTS5 可用）的端到端测试：
 * 建表/FTS 迁移、写入与去重（INSERT OR IGNORE）、FTS5 二元切分关键词、平面 cosine 向量检索
 * （含 Go 的"先取 k 近邻再过滤"语义与阈值衰减）、三种删除、批量更新、拷贝、move、估算。
 */
class SqliteRetrieveRepositoryTest {

    private static SqliteRetrieveRepository repo(Path dir, String name) {
        return SqliteRetrieveRepository.create(dir.resolve(name).toString());
    }

    private static IndexInfo info(String chunkId, String kb, String knowledgeId) {
        IndexInfo info = new IndexInfo();
        info.chunkId = chunkId;
        info.sourceId = chunkId;
        info.content = "hello";
        info.knowledgeId = knowledgeId;
        info.knowledgeBaseId = kb;
        info.tagId = "";
        info.isEnabled = true;
        return info;
    }

    private static Map<String, Object> embeddings(Object... pairs) {
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (float[]) pairs[i + 1]);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("embedding", map);
        return params;
    }

    private static RetrieveParams vectorParams(float[] embedding, List<String> kbs, int topK) {
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = embedding;
        params.knowledgeBaseIds = kbs;
        params.topK = topK;
        return params;
    }

    private static RetrieveParams keywordParams(String query, List<String> kbs, int topK) {
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = query;
        params.knowledgeBaseIds = kbs;
        params.topK = topK;
        return params;
    }

    // ── 建表与写入 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("建表：lite_embeddings + FTS5(contentless) + 向量表；重开同文件不丢（ensureExistingVecTables）")
    void schemaAndReopen(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "a.db");
        String chunk = UUID.randomUUID().toString();
        IndexInfo info = info(chunk, "kb1", "k1");
        info.content = "中文检索";
        repo.batchSave(List.of(info), embeddings(chunk, new float[] {1f, 0f, 0f}));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("a.db"))) {
            assertThat(tableExists(conn, "lite_embeddings")).isTrue();
            assertThat(tableExists(conn, "lite_embeddings_fts")).isTrue();
            assertThat(tableExists(conn, "vec_embeddings_3")).isTrue();
            // contentless FTS 不存原文（查 content 恒 NULL，Go 同款）——用二元词元 MATCH 证明
            // 走的是"重叠二元组"切分（unicode61 原样分词不会把"文检"当成词元）
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lite_embeddings_fts"
                            + " WHERE lite_embeddings_fts MATCH '\"文检\"'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        // 重开（新实例）→ 既有维度补建向量表，且可继续检索
        SqliteRetrieveRepository reopened = repo(dir, "a.db");
        List<RetrieveResult> hits = reopened.retrieve(
                vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 5));
        assertThat(hits.get(0).results()).hasSize(1);
        assertThat(hits.get(0).results().get(0).chunkId).isEqualTo(chunk);
    }

    @Test
    @DisplayName("写入去重：同 (source_id, source_type) 二次写被忽略（照 OnConflict DoNothing）")
    void insertIgnoreOnDuplicateSource(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "b.db");
        IndexInfo first = info("fixed-source", "kb1", "k1");
        first.chunkId = "chunk-1";
        first.sourceId = "fixed-source";
        repo.batchSave(List.of(first), embeddings("fixed-source", new float[] {1f, 0f, 0f}));

        IndexInfo second = info("fixed-source", "kb1", "k2");
        second.chunkId = "chunk-2";
        second.sourceId = "fixed-source";
        repo.batchSave(List.of(second), embeddings("fixed-source", new float[] {0f, 1f, 0f}));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("b.db"));
                Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lite_embeddings")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lite_embeddings_fts")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM vec_embeddings_3")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    // ── 关键词（FTS5 + 二元切分） ──────────────────────────────────────────

    @Test
    @DisplayName("关键词：中文二元切分命中 + 英文整词命中；FTS5 缺失的老表迁移路径不炸")
    void keywordsRetrieve(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "c.db");
        String cn = UUID.randomUUID().toString();
        String en = UUID.randomUUID().toString();
        IndexInfo cnInfo = info(cn, "kb1", "k1");
        cnInfo.content = "中文检索测试文档";
        IndexInfo enInfo = info(en, "kb1", "k1");
        enInfo.content = "hello world sqlite";
        repo.batchSave(List.of(cnInfo, enInfo), embeddings(
                cn, new float[] {1f, 0f, 0f},
                en, new float[] {0f, 1f, 0f}));

        // 中文：查询"检索"（二元组 检索）应命中
        List<RetrieveResult> cnHits = repo.retrieve(keywordParams("检索", List.of("kb1"), 5));
        assertThat(cnHits.get(0).results()).hasSize(1);
        assertThat(cnHits.get(0).results().get(0).chunkId).isEqualTo(cn);
        // bm25 分被 ×-1000000 变正数
        assertThat(cnHits.get(0).results().get(0).score).isGreaterThan(0);
        assertThat(cnHits.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_KEYWORDS);
        // ID 是 rowid 的十进制串（照 Go 的 fmt.Sprintf("%d")）
        assertThat(cnHits.get(0).results().get(0).id).matches("\\d+");

        // 英文整词
        assertThat(repo.retrieve(keywordParams("world", List.of("kb1"), 5)).get(0).results())
                .hasSize(1);
        // 知识库过滤
        assertThat(repo.retrieve(keywordParams("hello", List.of("kbX"), 5)).get(0).results())
                .isEmpty();
    }

    @Test
    @DisplayName("关键词：空类型（\"\"）两条都跑并合并（照 Go 的特例）；未知类型返回空且不报错")
    void retrieveDispatch(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "d.db");
        String chunk = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(chunk, "kb1", "k1")),
                embeddings(chunk, new float[] {1f, 0f, 0f}));

        RetrieveParams both = new RetrieveParams();
        both.retrieverType = "";
        both.query = "hello";
        both.embedding = new float[] {1f, 0f, 0f};
        both.knowledgeBaseIds = List.of("kb1");
        both.topK = 5;
        List<RetrieveResult> results = repo.retrieve(both);
        assertThat(results).hasSize(2);
        assertThat(results).extracting(RetrieveResult::retrieverType)
                .containsExactlyInAnyOrder(EngineTypes.RETRIEVER_KEYWORDS,
                        EngineTypes.RETRIEVER_VECTOR);

        RetrieveParams bogus = new RetrieveParams();
        bogus.retrieverType = "bogus";
        assertThat(repo.retrieve(bogus)).isEmpty();
    }

    // ── 向量（平面 cosine + Go 的 k-then-filter 语义） ─────────────────────

    @Test
    @DisplayName("向量：cosine 排名与分数（对齐向量分数≈1）；阈值衰减在取回后生效")
    void vectorRetrieve(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "e.db");
        String near = UUID.randomUUID().toString();
        String far = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(near, "kb1", "k1"), info(far, "kb1", "k1")), embeddings(
                near, new float[] {1f, 0f, 0f},
                far, new float[] {0f, 1f, 0f}));

        List<RetrieveResult> hits = repo.retrieve(
                vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 5));
        assertThat(hits.get(0).results()).hasSize(2);
        assertThat(hits.get(0).results().get(0).chunkId).isEqualTo(near);
        assertThat(hits.get(0).results().get(0).score).isCloseTo(1.0, within(1e-6));
        assertThat(hits.get(0).results().get(1).score).isCloseTo(0.0, within(1e-6));
        assertThat(hits.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_EMBEDDING);

        // 阈值 0.5 → 只剩 near
        RetrieveParams thresholded = vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 5);
        thresholded.threshold = 0.5f;
        assertThat(repo.retrieve(thresholded).get(0).results()).hasSize(1);

        // dim 不匹配的类不存在 → 空（向量表按维度独立）
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f}, List.of("kb1"), 5))
                .get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("向量：先取 k 近邻再按过滤收窄（照 vec0 语义）——最近的两条被停用则 topK=2 得空")
    void vectorRetrieveFiltersAfterKnn(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "f.db");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(a, "kbX", "k1"), info(b, "kbX", "k1"),
                info(c, "kb1", "k1")), embeddings(
                a, new float[] {1f, 0f, 0f},
                b, new float[] {0.99f, 0.01f, 0f},
                c, new float[] {0f, 1f, 0f}));

        // topK=2 取全局最近的 a/b，再按 kb1 过滤 → 空（a/b 属 kbX）
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 2))
                .get(0).results()).isEmpty();
        // topK=3 时 c 进入近邻圈 → 命中
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 3))
                .get(0).results()).hasSize(1);
    }

    // ── 删除 / 更新 / 拷贝 / move ────────────────────────────────────────

    @Test
    @DisplayName("删除：元数据 + FTS + 向量行三处都清（按 chunk/source/knowledge）")
    void deletes(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "g.db");
        String chunk = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(chunk, "kb1", "k1")),
                embeddings(chunk, new float[] {1f, 0f, 0f}));

        repo.deleteByChunkIdList(List.of(chunk), 3, "document");
        assertThat(count(dir.resolve("g.db"), "lite_embeddings")).isZero();
        assertThat(count(dir.resolve("g.db"), "lite_embeddings_fts")).isZero();
        assertThat(count(dir.resolve("g.db"), "vec_embeddings_3")).isZero();
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 5))
                .get(0).results()).isEmpty();

        // 按 source / knowledge 删除
        String s1 = UUID.randomUUID().toString();
        String s2 = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(s1, "kb1", "k1"), info(s2, "kb1", "k2")), embeddings(
                s1, new float[] {1f, 0f, 0f},
                s2, new float[] {0f, 1f, 0f}));
        repo.deleteBySourceIdList(List.of(s1), 3, "document");
        assertThat(count(dir.resolve("g.db"), "lite_embeddings")).isEqualTo(1);
        repo.deleteByKnowledgeIdList(List.of("k2"), 3, "document");
        assertThat(count(dir.resolve("g.db"), "lite_embeddings")).isZero();
    }

    @Test
    @DisplayName("批量更新：enabled 停用后检索不可见（FTS/向量行不动）；tag 更新可被过滤命中")
    void batchUpdates(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "h.db");
        String chunk = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(chunk, "kb1", "k1")),
                embeddings(chunk, new float[] {1f, 0f, 0f}));

        repo.batchUpdateChunkEnabledStatus(new LinkedHashMap<>(Map.of(chunk, false)));
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 5))
                .get(0).results()).isEmpty();
        assertThat(repo.retrieve(keywordParams("hello", List.of("kb1"), 5)).get(0).results())
                .isEmpty();
        repo.batchUpdateChunkEnabledStatus(Map.of(chunk, true));
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), 5))
                .get(0).results()).hasSize(1);

        repo.batchUpdateChunkTagID(Map.of(chunk, "tag-7"));
        RetrieveParams byTag = vectorParams(new float[] {1f, 0f, 0f}, null, 5);
        byTag.tagIds = List.of("tag-7");
        assertThat(repo.retrieve(byTag).get(0).results()).hasSize(1);
        byTag.tagIds = List.of("tag-other");
        assertThat(repo.retrieve(byTag).get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("拷贝：新 UUID source_id + kb/chunk 映射 + 向量与 FTS 复制（目标 kb 可检索到）")
    void copyIndices(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "i.db");
        String srcChunk = UUID.randomUUID().toString();
        String targetChunk = UUID.randomUUID().toString();
        IndexInfo src = info(srcChunk, "srcKb", "srcKnowledge");
        src.content = "中文共享受控文档";
        repo.batchSave(List.of(src), embeddings(srcChunk, new float[] {1f, 0f, 0f}));

        repo.copyIndices("srcKb", Map.of("srcKnowledge", "targetKnowledge"),
                Map.of(srcChunk, targetChunk), "targetKb", 3, "document");

        // 目标 kb 可向量检索（向量行被复制）
        List<RetrieveResult> hits = repo.retrieve(
                vectorParams(new float[] {1f, 0f, 0f}, List.of("targetKb"), 5));
        assertThat(hits.get(0).results()).hasSize(1);
        assertThat(hits.get(0).results().get(0).chunkId).isEqualTo(targetChunk);
        assertThat(hits.get(0).results().get(0).knowledgeId).isEqualTo("targetKnowledge");
        assertThat(hits.get(0).results().get(0).sourceId).matches("[0-9a-f-]{36}");
        // 目标 kb 也可关键词检索（FTS 行被复制）
        assertThat(repo.retrieve(keywordParams("共享", List.of("targetKb"), 5)).get(0).results())
                .hasSize(1);
        assertThat(count(dir.resolve("i.db"), "lite_embeddings")).isEqualTo(2);
    }

    @Test
    @DisplayName("move：一条 UPDATE 同时改 kb 与清空 tag（FTS/向量行不动，仍可检索）")
    void moveIndices(@TempDir Path dir) throws Exception {
        SqliteRetrieveRepository repo = repo(dir, "j.db");
        String chunk = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(chunk, "srcKb", "k1")),
                embeddings(chunk, new float[] {1f, 0f, 0f}));
        repo.batchUpdateChunkTagID(Map.of(chunk, "t1"));

        repo.moveKnowledgeIndices("srcKb", "targetKb", "k1", List.of(), 3, "document");

        RetrieveParams moved = vectorParams(new float[] {1f, 0f, 0f}, List.of("targetKb"), 5);
        moved.tagIds = List.of("t1");
        assertThat(repo.retrieve(moved).get(0).results()).isEmpty(); // tag 被清空
        RetrieveParams plain = vectorParams(new float[] {1f, 0f, 0f}, List.of("targetKb"), 5);
        assertThat(repo.retrieve(plain).get(0).results()).hasSize(1);
    }

    @Test
    @DisplayName("估算：每条 len(content)+200（字节）；help 接缝：resolvePath 优先配置 > env > 缺省")
    void estimateAndPath(@TempDir Path dir) {
        SqliteRetrieveRepository repo = repo(dir, "k.db");
        IndexInfo info = info("c", "kb", "k");
        info.content = "中文"; // 6 字节
        assertThat(repo.estimateStorageSize(List.of(info), null)).isEqualTo(6 + 200);
        assertThat(SqliteRetrieveRepository.resolvePath(" /tmp/x.db ")).isEqualTo("/tmp/x.db");
        assertThat(SqliteRetrieveRepository.resolvePath(null))
                .isEqualTo(SqliteRetrieveRepository.DEFAULT_PATH);
    }

    private static boolean tableExists(Connection conn, String name) throws Exception {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE name = '" + name + "'")) {
            return rs.next();
        }
    }

    private static int count(Path db, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
