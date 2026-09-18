package com.ragagent.memory.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量的编解码、pgvector 字面量与余弦相似度
 * （对照 Go internal/types/memory.go L1389-1451）。
 */
public final class MemoryVectors {

    private MemoryVectors() {}

    /**
     * 对照 Go {@code EncodeEmbedding}：把小端 float32 打包成字节串。
     *
     * <p>空向量回 {@code null}（Go 回 nil 切片 → 列写 SQL NULL）。</p>
     */
    public static byte[] encodeEmbedding(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * 对照 Go {@code DecodeEmbedding}：解开 {@link #encodeEmbedding} 写出的字节串。
     *
     * <p>{@code len(raw) < 4} 回 {@code null}（Go 回 nil）；
     * 尾部不足 4 字节的部分**被丢弃**（{@code len(raw)/4} 向下取整）。</p>
     */
    public static float[] decodeEmbedding(byte[] raw) {
        if (raw == null || raw.length < 4) {
            return null;
        }
        int count = raw.length / 4;
        float[] out = new float[count];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    /**
     * 对照 Go {@code FormatEmbeddingLiteral}：按 pgvector 能解析的方式渲染向量，
     * 让数据库自己算距离，而不是把每条已存向量都搬到应用里打分。
     *
     * <p><b>{@code strconv.FormatFloat(v, 'f', -1, 32)} 的 Java 等价物</b>：
     * {@code 'f'} = 定点（**绝不**用指数），{@code -1} = 能唯一往返 float32 的最少位数。
     * 具体做法见 {@link #formatFloat32(float)}——它比"直接调 {@code Float.toString}"
     * 多一层有效位数压缩，因为 Java 在次正规数上不给最短表示。</p>
     *
     * <p>Go 实录（逐条对照）：</p>
     * <pre>
     *   1.0        → [1]                                    （整数值不补 .0）
     *   0.5,-0.25  → [0.5,-0.25]
     *   1e30       → [1000000000000000000000000000000]       （'f' 不用指数）
     *   1e-8       → [0.00000001]
     *   0, -0.0    → [0,-0]
     *   3.1415927  → [3.1415927]
     *   最小次正规 → [0.000000000000000000000000000000000000000000001]
     *   MaxFloat32 → [340282350000000000000000000000000000000]
     *   NaN/+Inf/-Inf → [NaN] / [+Inf] / [-Inf]              （不是 Java 的 "Infinity"）
     * </pre>
     */
    public static String formatEmbeddingLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder(vector.length * 8);
        b.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(formatFloat32(vector[i]));
        }
        b.append(']');
        return b.toString();
    }

    /**
     * 对照 Go {@code strconv.FormatFloat(float64(v), 'f', -1, 32)}。
     *
     * <p>⚠️ <b>不能直接用 {@code Float.toString}</b>：Java 的最短表示在**次正规数**上
     * 并不最短。实测（Go 实录）：</p>
     * <pre>
     *   Go   FormatFloat(1.4e-45, 'f', -1, 32) → "0.000000000000000000000000000000000000000000001"
     *   Java Float.toString(1.4e-45f)          → "1.4E-45"
     * </pre>
     * <p>Go 给的是 {@code 1e-45}（1 位有效数字），Java 给的是 {@code 1.4E-45}（2 位）。
     * 这与 §9 记的 {@code GoDoubleSerializer} 是**同一个坑**，修法也一样：
     * 在 Java 结果上再做一轮"有效位数递减"，用 {@code Float.parseFloat} 校验往返
     * ——这只会朝 Go 移动。</p>
     */
    public static String formatFloat32(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        if (Float.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        if (value == 0.0f) {
            // -0.0 与 +0.0：Go 的 'f' 分别给 "-0" 与 "0"（Java 会多出 ".0"）
            return Float.floatToRawIntBits(value) < 0 ? "-0" : "0";
        }
        return shortestRoundTrip(value).stripTrailingZeros().toPlainString();
    }

    /**
     * 最短能唯一往返到该 float32 的十进制——先取 {@code Float.toString}，
     * 再尝试压缩有效位数（同 {@code GoDoubleSerializer.shortestRoundTrip} 的模子，
     * 只是这里用 {@code Float.parseFloat} 做往返校验）。
     */
    private static BigDecimal shortestRoundTrip(float value) {
        BigDecimal stripped = new BigDecimal(Float.toString(value)).stripTrailingZeros();
        int jdkPrecision = stripped.precision();
        if (jdkPrecision <= 1) {
            return stripped;
        }
        // 精确的二进制展开：四舍五入到 k 位有效数字时的基准。
        BigDecimal exact = new BigDecimal((double) value);
        for (int precision = 1; precision < jdkPrecision; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Float.parseFloat(candidate.toString()) == value) {
                return candidate.stripTrailingZeros();
            }
        }
        return stripped;
    }

    /**
     * 对照 Go {@code CosineSimilarity}：在 [-1, 1] 上给两个向量打分。
     *
     * <p>长度不一致就打 0 分：不同模型产生的向量不可比，猜比不回答更糟。</p>
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
