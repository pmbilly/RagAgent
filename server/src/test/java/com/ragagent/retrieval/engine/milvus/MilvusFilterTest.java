package com.ragagent.retrieval.engine.milvus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Milvus 纯函数面——过滤器表达式（算子/括号/转义形态，
 * 值内联：REST v2 无模板参数）、度量类型解析、类名解析、in 过滤、存储估算、SourceID 三态。
 */
class MilvusFilterTest {

    @Test
    @DisplayName("比较表达式：字段 == 字面量（字符串转义 \\\" 、布尔 true/false、数值原样）")
    void comparisonExpressions() {
        assertThat(MilvusFilter.expr(MilvusFilter.Condition.equal("is_enabled", true)))
                .isEqualTo("is_enabled == true");
        assertThat(MilvusFilter.expr(MilvusFilter.Condition.equal("knowledge_base_id", "kb1")))
                .isEqualTo("knowledge_base_id == \"kb1\"");
        assertThat(MilvusFilter.expr(MilvusFilter.Condition.notEqual("tag_id", "t\"9")))
                .isEqualTo("tag_id != \"t\\\"9\"");
        assertThat(MilvusFilter.expr(MilvusFilter.Condition.comparison("source_type",
                MilvusFilter.OP_GTE, 2)))
                .isEqualTo("source_type >= 2");
    }

    @Test
    @DisplayName("逻辑表达式：左结合括号（照 Go 的 ({a}) and ({b})）")
    void logicalExpressions() {
        String expr = MilvusFilter.expr(MilvusFilter.Condition.and(List.of(
                MilvusFilter.Condition.equal("is_enabled", true),
                MilvusFilter.Condition.in("knowledge_base_id", List.of("kb1", "kb2")),
                MilvusFilter.Condition.notIn("chunk_id", List.of("c9")))));
        assertThat(expr).isEqualTo(
                "((is_enabled == true) and (knowledge_base_id in [\"kb1\",\"kb2\"])) "
                        + "and (chunk_id not in [\"c9\"])");
    }

    @Test
    @DisplayName("between：field >= a and field <= b")
    void betweenExpression() {
        assertThat(MilvusFilter.expr(MilvusFilter.Condition.comparison("source_type",
                MilvusFilter.OP_BETWEEN, List.of(1, 5))))
                .isEqualTo("source_type >= 1 and source_type <= 5");
    }

    @Test
    @DisplayName("失败文案照 Go（nil 条件 / 未知算子 / 空 in 值 / between 元素数）")
    void errorMessages() {
        assertThatThrownBy(() -> MilvusFilter.expr(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("milvus filter condition is nil");
        assertThatThrownBy(() -> MilvusFilter.expr(
                MilvusFilter.Condition.comparison("f", "bogus", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported operator: bogus");
        assertThatThrownBy(() -> MilvusFilter.expr(
                MilvusFilter.Condition.in("chunk_id", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in operator value must be a slice with at least one value");
        assertThatThrownBy(() -> MilvusFilter.expr(
                MilvusFilter.Condition.comparison("f", MilvusFilter.OP_BETWEEN, List.of(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between operator value must be a slice with two elements");
    }

    @Test
    @DisplayName("基础过滤：KB/知识/标签 in、排除项 not in、is_enabled 恒在（照 getBaseFilterForQuery）")
    void baseFilter() {
        RetrieveParams params = new RetrieveParams();
        params.knowledgeBaseIds = List.of("kb1");
        params.knowledgeIds = List.of("k1", "k2");
        params.tagIds = List.of("t1");
        params.excludeKnowledgeIds = List.of("k9");
        params.excludeChunkIds = List.of("c9");
        // 左结合全括号（照 Go：每一步把已有结果整体加括号再与下一项连接）
        assertThat(MilvusRetrieveRepository.baseFilter(params)).isEqualTo(
                "(((((knowledge_base_id in [\"kb1\"]) and (knowledge_id in [\"k1\",\"k2\"])) "
                        + "and (tag_id in [\"t1\"])) and (knowledge_id not in [\"k9\"])) "
                        + "and (chunk_id not in [\"c9\"])) and (is_enabled == true)");
    }

    @Test
    @DisplayName("in 过滤（照 SDK WithStringIDs）：field in [\"a\",\"b\"]，不转义")
    void inFilterMatchesSdkShape() {
        assertThat(MilvusRetrieveRepository.inFilter("chunk_id", List.of("a", "b")))
                .isEqualTo("chunk_id in [\"a\",\"b\"]");
    }

    @Test
    @DisplayName("度量类型解析：缺省 IP、COSINE/L2 大小写不敏感、未知回落 IP")
    void metricTypeResolution() {
        assertThat(MilvusRetrieveRepository.resolveMetricType(null)).isEqualTo("IP");
        assertThat(MilvusRetrieveRepository.resolveMetricType("")).isEqualTo("IP");
        assertThat(MilvusRetrieveRepository.resolveMetricType("cosine")).isEqualTo("COSINE");
        assertThat(MilvusRetrieveRepository.resolveMetricType("L2")).isEqualTo("L2");
        assertThat(MilvusRetrieveRepository.resolveMetricType("junk")).isEqualTo("IP");
    }

    @Test
    @DisplayName("类名解析：prefix > name > 缺省 weknora_embeddings")
    void collectionNameResolution() {
        IndexConfig withPrefix = new IndexConfig();
        withPrefix.collectionPrefix = "pref";
        withPrefix.collectionName = "name";
        assertThat(MilvusRetrieveRepository.resolveCollectionName(withPrefix)).isEqualTo("pref");
        IndexConfig withName = new IndexConfig();
        withName.collectionName = "name";
        assertThat(MilvusRetrieveRepository.resolveCollectionName(withName)).isEqualTo("name");
        assertThat(MilvusRetrieveRepository.resolveCollectionName(null))
                .isEqualTo("weknora_embeddings");
    }

    @Test
    @DisplayName("存储估算：payload + 向量 + (向量+16) + 32；nil 向量只算元数据")
    void storageEstimate() {
        MilvusVectorEmbedding row = new MilvusVectorEmbedding();
        row.content = "中";
        row.sourceId = "s";
        row.chunkId = "c";
        row.knowledgeId = "k";
        row.knowledgeBaseId = "kb";
        row.embedding = new float[] {1f, 2f};
        // payload = 3+1+1+1+2+8 = 16；vector = 8；index = 8+16 = 24；meta = 32
        assertThat(MilvusRetrieveRepository.calculateStorageSize(row))
                .isEqualTo(16 + 8 + 24 + 32);

        MilvusVectorEmbedding noVector = new MilvusVectorEmbedding();
        assertThat(MilvusRetrieveRepository.calculateStorageSize(noVector)).isEqualTo(8 + 32);
    }

    @Test
    @DisplayName("SourceID 三态改写")
    void translateSourceId() {
        assertThat(MilvusRetrieveRepository.translateSourceId("c1", "c1", "tc1"))
                .isEqualTo("tc1");
        assertThat(MilvusRetrieveRepository.translateSourceId("c1-q7", "c1", "tc1"))
                .isEqualTo("tc1-q7");
        assertThat(MilvusRetrieveRepository.translateSourceId("other", "c1", "tc1"))
                .matches("[0-9a-f-]{36}");
    }
}
