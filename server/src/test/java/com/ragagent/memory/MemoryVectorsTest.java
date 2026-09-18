package com.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.ragagent.memory.domain.MemoryVectors;
import org.junit.jupiter.api.Test;

/**
 * 向量编解码与相似度（对照 Go internal/types/memory.go L1389-1451）。
 *
 * <p><b>最容易分叉的是 {@code FormatEmbeddingLiteral}</b>：Go 用的是
 * {@code strconv.FormatFloat(float64(v), 'f', -1, 32)}——定点格式、能唯一往返 float32
 * 的最少位数。Java 直接 {@code Float.toString} 会在两处不同：整数值多 {@code ".0"}、
 * {@code <1e-3} 或 {@code >=1e7} 时改用指数记法。所以断言里逐条钉住这两种情形。</p>
 */
class MemoryVectorsTest {

    // ── 编解码 ─────────────────────────────────────────────────────────────

    @Test
    void encodeEmbeddingIsLittleEndianFloat32() {
        byte[] encoded = MemoryVectors.encodeEmbedding(new float[]{1.0f, 0.5f});

        assertThat(encoded).hasSize(8);
        // 1.0f 的小端四字节
        assertThat(encoded[0]).isEqualTo((byte) 0x00);
        assertThat(encoded[1]).isEqualTo((byte) 0x00);
        assertThat(encoded[2]).isEqualTo((byte) 0x80);
        assertThat(encoded[3]).isEqualTo((byte) 0x3F);
        // 用 ByteBuffer 反读自证字节序
        assertThat(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getFloat(0))
                .isEqualTo(1.0f);
    }

    @Test
    void encodeEmbeddingReturnsNullForEmptyInput() {
        assertThat(MemoryVectors.encodeEmbedding(new float[0])).isNull();
        assertThat(MemoryVectors.encodeEmbedding(null)).isNull();
    }

    @Test
    void decodeEmbeddingRoundTrips() {
        float[] original = {1.5f, -2.25f, 0.0f, 1e-8f, Float.MAX_VALUE};
        float[] back = MemoryVectors.decodeEmbedding(MemoryVectors.encodeEmbedding(original));
        assertThat(back).containsExactly(original);
    }

    /** {@code len(raw) < 4} 回 null；尾部不足 4 字节的部分被丢弃（Go 的 {@code len/4}）。 */
    @Test
    void decodeEmbeddingHandlesShortAndRaggedInput() {
        assertThat(MemoryVectors.decodeEmbedding(null)).isNull();
        assertThat(MemoryVectors.decodeEmbedding(new byte[0])).isNull();
        assertThat(MemoryVectors.decodeEmbedding(new byte[3])).isNull();
        assertThat(MemoryVectors.decodeEmbedding(new byte[9])).hasSize(2);
    }

    // ── pgvector 字面量 ────────────────────────────────────────────────────

    /** 语料逐条来自 Go 的 {@code FormatEmbeddingLiteral} 实录。 */
    @Test
    void formatEmbeddingLiteralRendersPinnedDecimalNotExponent() {
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{1.0f}))
                .as("整数值不补 .0（Go 的 'f' 格式）")
                .isEqualTo("[1]");
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{0.5f, -0.25f}))
                .isEqualTo("[0.5,-0.25]");
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{1e30f}))
                .as("大数展开成定点，不用指数")
                .isEqualTo("[1000000000000000000000000000000]");
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{1e-8f}))
                .as("小数同样展开成定点")
                .isEqualTo("[0.00000001]");
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{3.1415927f}))
                .isEqualTo("[3.1415927]");
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{Float.MAX_VALUE}))
                .isEqualTo("[340282350000000000000000000000000000000]");
    }

    /**
     * ⚠️ **本模块自己踩到的坑**：Java 的 {@code Float.toString} 在次正规数上不是最短表示。
     *
     * <pre>
     *   Go   FormatFloat(1.4e-45, 'f', -1, 32) → 0.000000000000000000000000000000000000000000001  (1e-45)
     *   Java Float.toString(1.4e-45f)          → "1.4E-45"
     * </pre>
     * <p>照抄 {@code Float.toString} 就会在这里与 Go 分叉——与 §9 记的
     * {@code GoDoubleSerializer} 是同一个坑。</p>
     */
    @Test
    void formatEmbeddingLiteralUsesShortestRoundTripForSubnormals() {
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[]{Float.MIN_VALUE}))
                .isEqualTo("[0.000000000000000000000000000000000000000000001]");
        assertThat(MemoryVectors.formatFloat32(Float.MIN_VALUE)).isEqualTo(
                "0.000000000000000000000000000000000000000000001");
    }

    @Test
    void formatEmbeddingLiteralEmpty() {
        assertThat(MemoryVectors.formatEmbeddingLiteral(new float[0])).isEmpty();
        assertThat(MemoryVectors.formatEmbeddingLiteral(null)).isEmpty();
    }

    /** 三个非常规值逐条对齐 Go 的 {@code FormatFloat}：{@code NaN} / {@code +Inf} / {@code -Inf}。 */
    @Test
    void formatEmbeddingLiteralSpecialValuesMatchGo() {
        assertThat(MemoryVectors.formatFloat32(Float.NaN)).isEqualTo("NaN");
        assertThat(MemoryVectors.formatFloat32(Float.POSITIVE_INFINITY)).isEqualTo("+Inf");
        assertThat(MemoryVectors.formatFloat32(Float.NEGATIVE_INFINITY)).isEqualTo("-Inf");
        assertThat(MemoryVectors.formatFloat32(0.0f)).isEqualTo("0");
        assertThat(MemoryVectors.formatFloat32(-0.0f)).isEqualTo("-0");
    }

    // ── 余弦相似度 ─────────────────────────────────────────────────────────

    @Test
    void cosineSimilarityBasics() {
        assertThat(MemoryVectors.cosineSimilarity(new float[]{1, 0}, new float[]{1, 0})).isEqualTo(1.0);
        assertThat(MemoryVectors.cosineSimilarity(new float[]{1, 0}, new float[]{0, 1})).isZero();
        assertThat(MemoryVectors.cosineSimilarity(new float[]{1, 0}, new float[]{-1, 0})).isEqualTo(-1.0);
    }

    /** 长度不一致打 0 分：不同模型的向量不可比，猜比不回答更糟。 */
    @Test
    void cosineSimilarityRejectsMismatchedAndEmptyVectors() {
        assertThat(MemoryVectors.cosineSimilarity(new float[]{1}, new float[]{1, 2})).isZero();
        assertThat(MemoryVectors.cosineSimilarity(new float[0], new float[0])).isZero();
        assertThat(MemoryVectors.cosineSimilarity(null, new float[]{1})).isZero();
        assertThat(MemoryVectors.cosineSimilarity(new float[]{0, 0}, new float[]{1, 0})).isZero();
    }
}
