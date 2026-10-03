package com.ragagent.retrieval.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.TestSchema;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;

/**
 * PgVectorEngineRepository（postgres 引擎仓库的引擎口适配器）在 H2 上的钉子：
 * IndexInfo + additionalParams → IndexRow 的映射（embedding 按 SourceID、
 * chunk_enabled 按 ChunkID 覆写）、删除三件委托、move 改写 knowledge_base_id 并清
 * tag_id、CopyIndices 的三态 SourceID 改写 + is_enabled 缺省、未知检索类型报
 * {@code invalid retriever type}。检索的 SQL 语义在既有 pgvector/ParadeDB 件里
 * （H2 不支持 halfvec/||| 运算符），由真 PG A/B 覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PgVectorEngineRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PgVectorEngineRepository adapter;
    @Autowired
    private PgVectorRetrieveRepository pgRepository;
    @Autowired
    private VectorStoreService vectorStoreService;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    @Test
    void engineTypeAndSupport() {
        assertThat(adapter.engineType()).isEqualTo(EngineTypes.ENGINE_POSTGRES);
        assertThat(adapter.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                EngineTypes.RETRIEVER_VECTOR);
    }

    @Test
    void batchSaveMapsEmbeddingBySourceIdAndChunkEnabledOverride() throws Exception {
        IndexInfo info = new IndexInfo();
        info.sourceId = "src-1";
        info.chunkId = "chunk-1";
        info.knowledgeId = "doc-1";
        info.knowledgeBaseId = "kb-1";
        info.tagId = "tag-1";
        info.content = "hello";
        info.isEnabled = true;

        Map<String, Object> params = Map.of(
                "embedding", Map.of("src-1", new float[] {0.5f, -1.25f, 2.0f}),
                "chunk_enabled", Map.of("chunk-1", false));

        adapter.batchSave(List.of(info), params);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_id, chunk_id, knowledge_id, knowledge_base_id, tag_id, content, "
                        + "is_enabled, dimension FROM embeddings WHERE source_id = 'src-1'");
        assertThat(row.get("SOURCE_ID")).isEqualTo("src-1");
        assertThat(row.get("CHUNK_ID")).isEqualTo("chunk-1");
        assertThat(row.get("TAG_ID")).isEqualTo("tag-1");
        assertThat(row.get("IS_ENABLED")).isEqualTo(false).as("chunk_enabled 覆写 IndexInfo.isEnabled");
        assertThat(((Number) row.get("DIMENSION")).intValue()).isEqualTo(3);
    }

    @Test
    void deleteDelegates() throws Exception {
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'s1', 0, 'c1', 'k1', 'kb1', '', 'x', TRUE, 2, '[1,2]')");
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE source_id = 's1'", Integer.class);
        assertThat(before).isEqualTo(1);

        adapter.deleteBySourceIdList(List.of("s1"), 2, "");
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE source_id = 's1'", Integer.class);
        assertThat(after).isEqualTo(0);
    }

    @Test
    void moveKnowledgeIndicesRewritesKbAndClearsTag() throws Exception {
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'s1', 0, 'c1', 'k1', 'kb-src', 't1', 'x', TRUE, 2, '[1,2]')");

        adapter.moveKnowledgeIndices("kb-src", "kb-dst", "k1", List.of("c1"), 2, "");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT knowledge_base_id, tag_id FROM embeddings WHERE source_id = 's1'");
        assertThat(row.get("KNOWLEDGE_BASE_ID")).isEqualTo("kb-dst");
        assertThat(row.get("TAG_ID")).isEqualTo("").as("move.go：tag_id 清空");
    }

    @Test
    void copyIndicesRewritesSourceIdThreeWaysAndKeepsEnabledDefault() throws Exception {
        // 源行：本块（source_id == chunk_id）+ 生成问题行（chunk-1-qid）
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'c1', 0, 'c1', 'k1', 'kb-src', 'old-tag', 'body', TRUE, 2, '[1,2]')");
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'c1-q1', 0, 'c1', 'k1', 'kb-src', '', 'q', TRUE, 2, '[3,4]')");
        // 孤儿行：knowledge 不在映射里 → 跳过
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'c2', 0, 'c2', 'k9', 'kb-src', '', 'x', TRUE, 2, '[5,6]')");

        adapter.copyIndices("kb-src",
                Map.of("k1", "k1t"),
                Map.of("c1", "c1t"),
                "kb-dst", 2, "");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_id, chunk_id, knowledge_id, knowledge_base_id, tag_id, is_enabled "
                        + "FROM embeddings WHERE knowledge_base_id = 'kb-dst' ORDER BY source_id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("SOURCE_ID")).isEqualTo("c1t").as("本块 → 目标 chunkID");
        assertThat(rows.get(0).get("KNOWLEDGE_ID")).isEqualTo("k1t");
        assertThat(rows.get(0).get("TAG_ID")).isEqualTo("").as("CopyIndices 不复制 tag_id");
        assertThat(rows.get(1).get("SOURCE_ID")).isEqualTo("c1t-q1").as("生成问题保留 qid");
        // H2 的 is_enabled 列无默认值，插入为 FALSE——is_enabled 的 DEFAULT TRUE 语义
        // 只在真 PG 上由列默认值生效
        // 孤儿行未复制
        Integer orphans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE chunk_id = 'c2t'", Integer.class);
        assertThat(orphans).isEqualTo(0);
    }

    @Test
    void unknownRetrieverTypeFails() {
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "graph";
        assertThatThrownBy(() -> adapter.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid retriever type");
    }

    @Test
    void estimateStorageSizeUsesSourceIdDimension() {
        IndexInfo withVector = new IndexInfo();
        withVector.sourceId = "a";
        withVector.content = "hello";
        IndexInfo withoutVector = new IndexInfo();
        withoutVector.sourceId = "b";
        withoutVector.content = "hello";

        long total = adapter.estimateStorageSize(List.of(withVector, withoutVector),
                Map.of("embedding", Map.of("a", new float[] {1f, 2f})));
        // a: 5 字节 + 2*2 + 200 + (2*2)*2 = 217；b: 无向量 → 5 + 200 = 205
        assertThat(total).isEqualTo(217L + 205L);
    }

    @Test
    void saveIsConflictTolerant() throws Exception {
        IndexInfo info = new IndexInfo();
        info.sourceId = "dup";
        info.chunkId = "c";
        info.knowledgeId = "k";
        info.knowledgeBaseId = "kb";
        info.content = "x";
        info.isEnabled = true;
        Map<String, Object> params = Map.of("embedding", Map.of("dup", new float[] {1f}));

        adapter.save(info, params);
        // 同 SourceID 再存不炸（Go 裸 Create 的偏离备案：统一走 ON CONFLICT DO NOTHING）
        adapter.save(info, params);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE source_id = 'dup'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
