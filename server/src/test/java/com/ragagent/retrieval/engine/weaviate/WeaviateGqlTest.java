package com.ragagent.retrieval.engine.weaviate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Weaviate GraphQL 串的字节契约——<b>对照 Go 实录</b>：下面四条期望串录自
 * {@code weaviate-go-client v5.7.3} 的 {@code GetBuilder.Build()}（与
 * {@code filters.WhereBuilder.String()}），即 Go 驱动真正发到 {@code /v1/graphql} 的报文。
 * 任何"顺手改格式"（加空格、调参数序）都会在这里翻红。
 */
class WeaviateGqlTest {

    private static WeaviateGql.Where baseWhere() {
        return WeaviateGql.Where.and(List.of(
                WeaviateGql.Where.equal("is_enabled").valueBoolean(true),
                WeaviateGql.Where.containsAny("knowledge_base_id").valueText("kb1", "kb2"),
                WeaviateGql.Where.notEqual("knowledge_id").valueText("k9")));
    }

    // 以下四条 = Go 实录（v5 客户端 Build() 的输出，逐字节）
    private static final String GO_QUERY_VECTOR =
            "{Get {Weknora_embeddings_3 (where:{operator: And operands:[{operator: Equal "
                    + "path: [\"is_enabled\"] valueBoolean: true},{operator: ContainsAny "
                    + "path: [\"knowledge_base_id\"] valueText: [\"kb1\",\"kb2\"]},{operator: "
                    + "NotEqual path: [\"knowledge_id\"] valueText: \"k9\"}]}, "
                    + "nearVector:{certainty: 0.7 vector: [0.5,-0.25,1]}, limit: 10) "
                    + "{content source_id source_type chunk_id knowledge_id knowledge_base_id "
                    + "tag_id _additional{id certainty}}}}";

    private static final String GO_QUERY_KEYWORDS =
            "{Get {Weknora_embeddings_3 (where:{operator: And operands:[{operator: Equal "
                    + "path: [\"is_enabled\"] valueBoolean: true},{operator: ContainsAny "
                    + "path: [\"knowledge_base_id\"] valueText: [\"kb1\",\"kb2\"]},{operator: "
                    + "NotEqual path: [\"knowledge_id\"] valueText: \"k9\"}]}, "
                    + "bm25:{query: \"知识库 \\\"检索\\\"\", properties: [\"content\"]}, "
                    + "limit: 10) {content source_id source_type chunk_id knowledge_id "
                    + "knowledge_base_id tag_id _additional{id score}}}}";

    private static final String GO_QUERY_MOVE =
            "{Get {Weknora_embeddings_3 (where:{operator: And operands:[{operator: Equal "
                    + "path: [\"knowledge_base_id\"] valueString: \"srcKb\"},{operator: Equal "
                    + "path: [\"knowledge_id\"] valueString: \"k1\"}]}, limit: 100) "
                    + "{_additional{id}}}}";

    private static final String GO_QUERY_KEYWORDS_EMPTY_QUERY =
            "{Get {Weknora_embeddings_3 (where:{operator: And operands:[{operator: Equal "
                    + "path: [\"is_enabled\"] valueBoolean: true},{operator: ContainsAny "
                    + "path: [\"knowledge_base_id\"] valueText: [\"kb1\",\"kb2\"]},{operator: "
                    + "NotEqual path: [\"knowledge_id\"] valueText: \"k9\"}]}, "
                    + "bm25:{properties: [\"content\"]}, limit: 3) {content source_id "
                    + "source_type chunk_id knowledge_id knowledge_base_id tag_id "
                    + "_additional{id score}}}}";

    @Test
    @DisplayName("向量查询：与 Go 实录逐字节一致（参数序 where→nearVector→limit、certainty 在前）")
    void vectorQueryMatchesGoRecording() {
        String actual = WeaviateGql.vectorQuery("Weknora_embeddings_3", baseWhere(), 10,
                new float[] {0.5f, -0.25f, 1f}, 0.7f);
        assertThat(actual).isEqualTo(GO_QUERY_VECTOR);
    }

    @Test
    @DisplayName("关键词查询：与 Go 实录逐字节一致（bm25 的 %q 引号 + 逗号空格分隔）")
    void bm25QueryMatchesGoRecording() {
        String actual = WeaviateGql.bm25Query("Weknora_embeddings_3", baseWhere(), 10,
                "知识库 \"检索\"", List.of("content"));
        assertThat(actual).isEqualTo(GO_QUERY_KEYWORDS);
    }

    @Test
    @DisplayName("空 query：省略 query 段（照 Go 的 query != \"\" 判据）")
    void bm25QueryOmitsEmptyQuery() {
        String actual = WeaviateGql.bm25Query("Weknora_embeddings_3", baseWhere(), 3, "",
                List.of("content"));
        assertThat(actual).isEqualTo(GO_QUERY_KEYWORDS_EMPTY_QUERY);
    }

    @Test
    @DisplayName("move 列举：与 Go 实录逐字节一致（And + 两个 Equal + _additional{id}）")
    void moveListQueryMatchesGoRecording() {
        WeaviateGql.Where where = WeaviateGql.Where.and(List.of(
                WeaviateGql.Where.equal("knowledge_base_id").valueString("srcKb"),
                WeaviateGql.Where.equal("knowledge_id").valueString("k1")));
        assertThat(WeaviateGql.moveListQuery("Weknora_embeddings_3", where, 100))
                .isEqualTo(GO_QUERY_MOVE);
    }

    @Test
    @DisplayName("拷贝分页（有意修正）：where+limit+offset 且取 _additional{vectors{embedding}}")
    void copyPageQueryUsesOffsetAndNamedVector() {
        WeaviateGql.Where where = WeaviateGql.Where.equal("knowledge_base_id")
                .valueString("srcKb");
        String actual = WeaviateGql.copyPageQuery("Weknora_embeddings_3", where, 64, 64);
        assertThat(actual).isEqualTo(
                "{Get {Weknora_embeddings_3 (where:{operator: Equal path: "
                        + "[\"knowledge_base_id\"] valueString: \"srcKb\"}, limit: 64, offset: 64) "
                        + "{content source_id source_type chunk_id knowledge_id "
                        + "knowledge_base_id tag_id _additional{id vectors{embedding}}}}}");
        // Go 的 where+after 组合被服务端直接拒绝（实测），本仓不再使用 after
        assertThat(actual).doesNotContain("after:");
    }

    @Test
    @DisplayName("where 的 JSON 形态（批量删除用）：Contains* 恒数组键，单值非 contains 用标量键")
    void whereJsonShape() {
        JsonNode contains = WeaviateGql.Where.containsAny("chunk_id").valueText("a").json();
        assertThat(contains.path("operator").asText()).isEqualTo("ContainsAny");
        assertThat(contains.path("valueTextArray").get(0).asText()).isEqualTo("a");
        assertThat(contains.has("valueText")).isFalse();

        JsonNode scalar = WeaviateGql.Where.equal("knowledge_base_id").valueString("kb1").json();
        assertThat(scalar.path("valueString").asText()).isEqualTo("kb1");
        assertThat(scalar.has("valueStringArray")).isFalse();

        JsonNode bool = WeaviateGql.Where.equal("is_enabled").valueBoolean(true).json();
        assertThat(bool.path("valueBoolean").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("字面量：Go %q 引号与浮点最短表示（整数不带 .0、负零保形）")
    void literals() {
        assertThat(WeaviateGql.quoteGo("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
        assertThat(WeaviateGql.quoteGo("中文")).isEqualTo("\"中文\"");
        assertThat(WeaviateGql.vectorLiteral(new float[] {1f, 0.5f, -0.25f}))
                .isEqualTo("[1,0.5,-0.25]");
        assertThat(WeaviateGql.floatGo(0.7f)).isEqualTo("0.7");
        assertThat(WeaviateGql.floatGo(1f)).isEqualTo("1");
        assertThat(WeaviateGql.floatGo(0.0000001f)).isEqualTo("1e-07");
        assertThatThrownBy(() -> WeaviateGql.floatGo(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("contains 算子的数组规则：单值 ContainsAny 也加方括号（照 formatValues）")
    void containsOperatorAlwaysArray() {
        assertThat(WeaviateGql.formatValues(List.of("x"), WeaviateGql.OP_CONTAINS_ANY))
                .isEqualTo("[\"x\"]");
        assertThat(WeaviateGql.formatValues(List.of("x"), WeaviateGql.OP_EQUAL))
                .isEqualTo("\"x\"");
        assertThat(WeaviateGql.formatValues(List.of(true), WeaviateGql.OP_EQUAL))
                .isEqualTo("true");
    }
}
