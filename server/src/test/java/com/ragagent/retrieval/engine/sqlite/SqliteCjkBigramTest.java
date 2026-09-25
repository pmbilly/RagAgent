package com.ragagent.retrieval.engine.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQLite 关键词纯函数——对照 Go {@code tokenizeCJKBigram} / {@code sanitizeFTS5Query}
 * 的逐字语义（连续汉字切重叠二元组、单字保留、非 CJK 整词、标点/空白分隔）、
 * float32 小端序列化（等价 {@code sqlite_vec.SerializeFloat32}）。
 */
class SqliteCjkBigramTest {

    @Test
    @DisplayName("二元切分：连续汉字 → 重叠二元组；单字保留；非 CJK 整词；标点/空白分隔")
    void tokenize() {
        assertThat(SqliteCjkBigram.tokenize("中文检索")).isEqualTo("中文 文检 检索");
        assertThat(SqliteCjkBigram.tokenize("中")).isEqualTo("中");
        assertThat(SqliteCjkBigram.tokenize("hello world")).isEqualTo("hello world");
        assertThat(SqliteCjkBigram.tokenize("中文 hello 世界"))
                .isEqualTo("中文 hello 世界");
        assertThat(SqliteCjkBigram.tokenize("中文，检索！")).isEqualTo("中文 检索");
        assertThat(SqliteCjkBigram.tokenize("")).isEmpty();
        assertThat(SqliteCjkBigram.tokenize("A1中文")).isEqualTo("A1 中文");
        // 单字 CJK 段夹在非 CJK 中：各自成词元
        assertThat(SqliteCjkBigram.tokenize("a中b")).isEqualTo("a 中 b");
    }

    @Test
    @DisplayName("查询串：切词元后加引号并以 OR 连接；空/纯标点 → \"\"")
    void sanitizeQuery() {
        assertThat(SqliteCjkBigram.sanitizeQuery(" 中文检索 ")).isEqualTo(
                "\"中文\" OR \"文检\" OR \"检索\"");
        assertThat(SqliteCjkBigram.sanitizeQuery("hello world"))
                .isEqualTo("\"hello\" OR \"world\"");
        assertThat(SqliteCjkBigram.sanitizeQuery("   ")).isEmpty();
        assertThat(SqliteCjkBigram.sanitizeQuery("，。！")).isEmpty();
    }

    @Test
    @DisplayName("float32 序列化：小端布局（与 sqlite-vec 同格式）可往返")
    void serializeRoundTrip() {
        float[] vector = {1.5f, -2.25f, 0f};
        byte[] bytes = SqliteCjkBigram.serializeFloat32(vector);
        assertThat(bytes).hasSize(12);
        assertThat(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(0))
                .isEqualTo(1.5f);
        assertThat(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getFloat(0))
                .isNotEqualTo(1.5f); // 小端确证
        assertThat(SqliteCjkBigram.deserializeFloat32(bytes)).containsExactly(1.5f, -2.25f, 0f);
    }

    @Test
    @DisplayName("cosine 距离：同向 0、正交 1、反向 2；零向量 → 1（防守）")
    void cosineDistance() {
        assertThat(SqliteRetrieveRepository.cosineDistance(new float[] {1f, 0f},
                new float[] {2f, 0f})).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(SqliteRetrieveRepository.cosineDistance(new float[] {1f, 0f},
                new float[] {0f, 1f})).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(SqliteRetrieveRepository.cosineDistance(new float[] {1f, 0f},
                new float[] {-1f, 0f})).isCloseTo(2.0,
                org.assertj.core.api.Assertions.within(1e-9));
        assertThat(SqliteRetrieveRepository.cosineDistance(new float[] {0f, 0f},
                new float[] {1f, 0f})).isEqualTo(1.0);
        assertThat(SqliteRetrieveRepository.cosineDistance(new float[0], new float[] {1f}))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("UTF-8 清理：丢 NUL 与孤立代理项（照 common.CleanInvalidUTF8）")
    void cleanInvalidUtf8() {
        assertThat(SqliteRetrieveRepository.cleanInvalidUtf8("a\u0000b")).isEqualTo("ab");
        assertThat(SqliteRetrieveRepository.cleanInvalidUtf8("a\uD800b")).isEqualTo("ab");
        assertThat(SqliteRetrieveRepository.cleanInvalidUtf8("\uD83D\uDE00")).isEqualTo("\uD83D\uDE00");
        assertThat(SqliteRetrieveRepository.cleanInvalidUtf8(null)).isEmpty();
    }
}
