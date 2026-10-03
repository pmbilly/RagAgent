package com.ragagent.retrieval.engine.doris;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Doris 驱动纯函数族：
 * 兼容模式解析、embedding 字面量与解析、校验与单位化、SourceID 三态改写、
 * 建表 DDL 形状、存储估算（UTF-8 字节）、Stream Load 拆批。
 */
class DorisPureFunctionsTest {

    // ── DorisCompatMode ─────────────────────────────────────────────────────

    @Test
    @DisplayName("configured：空→auto；三种别名归一到 inner_product_duplicate；非法值回落 auto 并带回原值")
    void configuredCompatMode() {
        assertThat(DorisCompatMode.configured(null).mode()).isEqualTo(DorisCompatMode.AUTO);
        assertThat(DorisCompatMode.configured("  ").mode()).isEqualTo(DorisCompatMode.AUTO);
        assertThat(DorisCompatMode.configured("auto").mode()).isEqualTo(DorisCompatMode.AUTO);
        assertThat(DorisCompatMode.configured("LEGACY").mode()).isEqualTo(DorisCompatMode.LEGACY);
        for (String alias : List.of("inner_product_duplicate", "INNER-PRODUCT-DUPLICATE",
                "inner_product", "inner-product")) {
            assertThat(DorisCompatMode.configured(alias).mode())
                    .isEqualTo(DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        }
        DorisCompatMode.Configured invalid = DorisCompatMode.configured(" bogus ");
        assertThat(invalid.mode()).isEqualTo(DorisCompatMode.AUTO);
        assertThat(invalid.invalidRaw()).isEqualTo("bogus");
    }

    @Test
    @DisplayName("fromDdl：duplicate key( → 内积副本；unique key( → legacy；其余报 Go 原文")
    void compatModeFromDdl() {
        assertThat(DorisCompatMode.fromDdl("CREATE TABLE ... DUPLICATE KEY(id) ..."))
                .isEqualTo(DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        assertThat(DorisCompatMode.fromDdl("create table x unique key(`id`)"))
                .isEqualTo(DorisCompatMode.LEGACY);
        assertThatThrownBy(() -> DorisCompatMode.fromDdl("CREATE TABLE x (id int)"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unsupported table definition; expected UNIQUE KEY or DUPLICATE KEY");
    }

    @Test
    @DisplayName("模式语义：legacy 三项全 false；其余模式全 true")
    void compatModeSemantics() {
        assertThat(DorisCompatMode.LEGACY.normalizeEmbeddings()).isFalse();
        assertThat(DorisCompatMode.LEGACY.usesReplaceWrite()).isFalse();
        assertThat(DorisCompatMode.LEGACY.usesRewriteChunkUpdates()).isFalse();
        for (DorisCompatMode mode : List.of(DorisCompatMode.AUTO,
                DorisCompatMode.INNER_PRODUCT_DUPLICATE)) {
            assertThat(mode.normalizeEmbeddings()).isTrue();
            assertThat(mode.usesReplaceWrite()).isTrue();
            assertThat(mode.usesRewriteChunkUpdates()).isTrue();
        }
    }

    // ── embedding 字面量 ────────────────────────────────────────────────────

    @Test
    @DisplayName("embeddingLiteral：Go 'g' 形态——去尾随 .0、指数形态、NaN/±Inf/-0 拼写")
    void embeddingLiteralShape() {
        assertThat(DorisSql.embeddingLiteral(new float[] {1f, -2.5f, 0.0001f, 1e7f}))
                .isEqualTo("[1,-2.5,0.0001,1e+07]");
        assertThat(DorisSql.embeddingLiteral(new float[] {0.5f, 0.25f}))
                .isEqualTo("[0.5,0.25]");
        assertThat(DorisSql.embeddingLiteral(new float[0])).isEqualTo("[]");
        assertThat(DorisSql.embeddingLiteral(null)).isEqualTo("[]");
        assertThat(DorisSql.embeddingLiteral(new float[] {1e-5f})).isEqualTo("[1e-05]");
        assertThat(DorisSql.embeddingLiteral(new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, -0f})).isEqualTo("[NaN,+Inf,-Inf,-0]");
    }

    @Test
    @DisplayName("parseEmbeddingLiteral：[] 可选、空段跳过、非法数字报 Go strconv 原文")
    void parseEmbeddingLiteral() {
        assertThat(DorisSql.parseEmbeddingLiteral("[1,2.5]")).containsExactly(1f, 2.5f);
        assertThat(DorisSql.parseEmbeddingLiteral(" 3 , 4 ")).containsExactly(3f, 4f);
        assertThat(DorisSql.parseEmbeddingLiteral("[1,,2]")).containsExactly(1f, 2f);
        assertThat(DorisSql.parseEmbeddingLiteral("[]")).isNull();
        assertThat(DorisSql.parseEmbeddingLiteral("")).isNull();
        assertThat(DorisSql.parseEmbeddingLiteral(null)).isNull();
        assertThatThrownBy(() -> DorisSql.parseEmbeddingLiteral("[abc]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("strconv.ParseFloat: parsing \"abc\": invalid syntax");
    }

    @Test
    @DisplayName("validateEmbedding：非有限值带上标下标与 Go 拼写")
    void validateEmbedding() {
        DorisSql.validateEmbedding(null);
        DorisSql.validateEmbedding(new float[] {0f, -1.5f});
        assertThatThrownBy(() -> DorisSql.validateEmbedding(new float[] {0f, Float.NaN}))
                .isInstanceOf(DorisSql.InvalidEmbeddingException.class)
                .hasMessage("doris: embedding[1] is not finite: NaN");
        assertThatThrownBy(() -> DorisSql.validateEmbedding(new float[] {Float.POSITIVE_INFINITY}))
                .hasMessage("doris: embedding[0] is not finite: +Inf");
        assertThatThrownBy(() -> DorisSql.validateEmbedding(new float[] {Float.NEGATIVE_INFINITY}))
                .hasMessage("doris: embedding[0] is not finite: -Inf");
    }

    @Test
    @DisplayName("normalizeEmbedding：单位化副本；零向量原样副本；空向量 null")
    void normalizeEmbedding() {
        float[] unit = DorisSql.normalizeEmbedding(new float[] {3f, 4f});
        assertThat(unit[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(unit[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
        float[] zeros = {0f, 0f};
        assertThat(DorisSql.normalizeEmbedding(zeros)).isNotSameAs(zeros)
                .containsExactly(0f, 0f);
        assertThat(DorisSql.normalizeEmbedding(new float[0])).isNull();
        assertThat(DorisSql.normalizeEmbedding(null)).isNull();
    }

    // ── SourceID 三态改写 ───────────────────────────────────────────────────

    @Test
    @DisplayName("translateSourceId：普通 chunk / 生成型问题 / 其他（新 UUID）")
    void translateSourceId() {
        assertThat(DorisSql.translateSourceId("chunk-1", "chunk-1", "chunk-2"))
                .isEqualTo("chunk-2");
        assertThat(DorisSql.translateSourceId("chunk-1-q9", "chunk-1", "chunk-2"))
                .isEqualTo("chunk-2-q9");
        String generated = DorisSql.translateSourceId("something-else", "chunk-1", "chunk-2");
        assertThat(generated).matches("[0-9a-f-]{36}").isNotEqualTo("chunk-2");
    }

    // ── 建表 DDL ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildCreateTableDdl：内积副本模式（DUPLICATE KEY + inner_product）")
    void createTableDdlInnerProduct() {
        String ddl = DorisSql.buildCreateTableDdl("weknora_embeddings_768", 768, 10, 1,
                DorisCompatMode.INNER_PRODUCT_DUPLICATE);
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS `weknora_embeddings_768` (");
        assertThat(ddl).contains("DUPLICATE KEY(id)");
        assertThat(ddl).contains("\"metric_type\"=\"inner_product\"");
        assertThat(ddl).contains("\"dim\"=\"768\"");
        assertThat(ddl).contains("DISTRIBUTED BY HASH(id) BUCKETS 10");
        // 模板的 PROPERTIES( 行本身带一个制表符，properties 串再带一个（照 Go 原文）
        assertThat(ddl).contains("PROPERTIES(\n\t\t\"replication_num\"=\"1\"\n);");
        assertThat(ddl).contains("INDEX idx_content  (content)           USING INVERTED"
                + " PROPERTIES(\"parser\"=\"chinese\",\"support_phrase\"=\"true\")");
        assertThat(ddl).doesNotContain("enable_unique_key_merge_on_write");
    }

    @Test
    @DisplayName("buildCreateTableDdl：legacy 模式（UNIQUE KEY + cosine_distance + MoW）")
    void createTableDdlLegacy() {
        String ddl = DorisSql.buildCreateTableDdl("weknora_embeddings_3", 3, 4, 2,
                DorisCompatMode.LEGACY);
        assertThat(ddl).contains("UNIQUE KEY(id)");
        assertThat(ddl).contains("\"metric_type\"=\"cosine_distance\"");
        assertThat(ddl).contains("BUCKETS 4");
        assertThat(ddl).contains("\t\t\"replication_num\"=\"2\",\n"
                + "\t\"enable_unique_key_merge_on_write\"=\"true\"");
    }

    // ── 存储估算与集合名解析 ───────────────────────────────────────────────

    @Test
    @DisplayName("calculateStorageSize：UTF-8 字节 + 向量 dim*4 + HNSW 512 + 元数据 24")
    void calculateStorageSize() {
        DorisVectorEmbedding emb = new DorisVectorEmbedding();
        emb.content = "中";
        emb.sourceId = "s";
        emb.chunkId = "c";
        emb.knowledgeId = "k";
        emb.knowledgeBaseId = "kb";
        emb.tagId = "t";
        emb.embedding = new float[] {1f, 2f};
        // payload = 3 + 1 + 1 + 1 + 2 + 1 + 8 = 17；vec = 8；hnsw = 512；meta = 24
        assertThat(DorisSql.calculateStorageSize(emb)).isEqualTo(17 + 8 + 512 + 24);

        DorisVectorEmbedding noVec = new DorisVectorEmbedding();
        assertThat(DorisSql.calculateStorageSize(noVec)).isEqualTo(8 + 24);
    }

    @Test
    @DisplayName("resolveCollectionName：collectionPrefix > collectionName > 缺省")
    void resolveCollectionName() {
        IndexConfig withPrefix = new IndexConfig();
        withPrefix.collectionPrefix = "pref";
        withPrefix.collectionName = "name";
        assertThat(DorisRetrieveRepository.resolveCollectionName(withPrefix)).isEqualTo("pref");

        IndexConfig withName = new IndexConfig();
        withName.collectionName = "name";
        assertThat(DorisRetrieveRepository.resolveCollectionName(withName)).isEqualTo("name");

        assertThat(DorisRetrieveRepository.resolveCollectionName(null))
                .isEqualTo(DorisRetrieveRepository.DEFAULT_TABLE_BASE_NAME);
    }

    @Test
    @DisplayName("hostFromAddr：host:port → host；无冒号整段当 host")
    void hostFromAddr() {
        assertThat(DorisRetrieveRepository.hostFromAddr("doris-fe:9030")).isEqualTo("doris-fe");
        assertThat(DorisRetrieveRepository.hostFromAddr("doris-fe")).isEqualTo("doris-fe");
        assertThat(DorisRetrieveRepository.hostFromAddr("")).isEmpty();
        assertThat(DorisRetrieveRepository.hostFromAddr(null)).isEmpty();
    }

    // ── Stream Load 拆批 ────────────────────────────────────────────────────

    @Test
    @DisplayName("chunkRows：按累积 JSON 体大小拆批（1 MiB），单行超限仍自成一批")
    void chunkRows() {
        Map<String, Object> big = new HashMap<>();
        big.put("id", "x".repeat(700 * 1024));
        big.put("is_enabled", true);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(big, big, deepCopy(big), big));
        List<List<Map<String, Object>>> chunks =
                DorisStreamLoadClient.chunkRows(rows, DorisStreamLoadClient.MAX_BATCH_BYTES);
        assertThat(chunks).hasSize(4); // 单行 ~700KB，两行即超 1 MiB

        Map<String, Object> small = Map.of("id", "a");
        List<List<Map<String, Object>>> single =
                DorisStreamLoadClient.chunkRows(List.of(small, small), 1 << 20);
        assertThat(single).hasSize(1).allSatisfy(batch -> assertThat(batch).hasSize(2));
    }

    private static Map<String, Object> deepCopy(Map<String, Object> row) {
        return new HashMap<>(row);
    }
}
