package com.ragagent.retrieval.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * EngineAwareNormalizer 对照 Go {@code retriever/normalizer.go} 的钉子：
 * Milvus 带符号余弦的重缩放、[0,1] 族的透传 + clamp、BM25 透传、未知引擎防御 clamp、
 * clamp01 的 NaN/±Inf 边界（NaN → 0 保严格弱序）。
 */
class EngineAwareNormalizerTest {

    private final EngineAwareNormalizer n = new EngineAwareNormalizer();

    @Test
    void milvusSignedCosineRescales() {
        assertThat(n.normalize(-1.0, "vector", "milvus")).isEqualTo(0.0);
        assertThat(n.normalize(0.0, "vector", "milvus")).isEqualTo(0.5);
        assertThat(n.normalize(1.0, "vector", "milvus")).isEqualTo(1.0);
        // 越界值（1.0000002）被 clamp01 拦下
        assertThat(n.normalize(1.0000002, "vector", "milvus")).isEqualTo(1.0);
        assertThat(n.normalize(-1.5, "vector", "milvus")).isEqualTo(0.0);
    }

    @Test
    void passthroughEnginesClampOnly() {
        for (String engine : new String[] {"elasticsearch", "elastic_faiss", "opensearch",
                "weaviate", "postgres", "sqlite", "qdrant", "infinity", "tencent_vectordb",
                "doris"}) {
            assertThat(n.normalize(0.7, "vector", engine)).isEqualTo(0.7);
            assertThat(n.normalize(-0.2, "vector", engine)).isEqualTo(0.0);
            assertThat(n.normalize(1.4, "vector", engine)).isEqualTo(1.0);
        }
    }

    @Test
    void unknownEngineClampsDefensively() {
        assertThat(n.normalize(0.42, "vector", "not_a_real_engine")).isEqualTo(0.42);
        assertThat(n.normalize(2.0, "vector", "")).isEqualTo(1.0);
    }

    @Test
    void keywordScoresPassThrough() {
        // BM25 无界正值 + Milvus 类型也不该被重缩放
        assertThat(n.normalize(17.35, "keywords", "postgres")).isEqualTo(17.35);
        assertThat(n.normalize(17.35, "keywords", "milvus")).isEqualTo(17.35);
    }

    @Test
    void clamp01HandlesSpecialValues() {
        // NaN → 0（否则破坏下游 sort 的严格弱序）
        assertThat(n.normalize(Double.NaN, "vector", "postgres")).isEqualTo(0.0);
        assertThat(n.normalize(Double.NEGATIVE_INFINITY, "vector", "postgres")).isEqualTo(0.0);
        assertThat(n.normalize(Double.POSITIVE_INFINITY, "vector", "postgres")).isEqualTo(1.0);
        assertThat(n.normalize(0.0, "vector", "postgres")).isEqualTo(0.0);
        assertThat(n.normalize(1.0, "vector", "postgres")).isEqualTo(1.0);
    }
}
