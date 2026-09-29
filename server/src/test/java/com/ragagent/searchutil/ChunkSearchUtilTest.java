package com.ragagent.searchutil;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * ChunkSearchUtil 纯逻辑：内容图片 URL 提取、生成问题 sourceId 折叠、邻块内容拼接去重、TrimSpace 的 Unicode 空白全集。
 */
class ChunkSearchUtilTest {

    // ── 纯逻辑（searchutil / faq.go）───────────────────────────────────────

    @Test
    void imageURLsInContentCoversMarkdownAndHtmlButNotDataSrcOrUnquoted() {
        String content = "![alt](local://1/a.png)\n"
                + "<img class=\"x\" src=\"local://1/b.png\" alt=\"k\">\n"
                + "<img data-src=\"lazy.png\" src=\"local://1/c.png\">\n"
                + "<img src=unquoted.png>\n"
                + "<IMG SRC=' local://1/d.png '>";
        Set<String> urls = ChunkSearchUtil.imageURLsInContent(content);
        // Markdown 原文精确匹配；HTML src 经 TrimSpace；data-src 与无引号 src 不算
        assertThat(urls).containsExactlyInAnyOrder(
                "local://1/a.png", "local://1/b.png", "local://1/c.png", "local://1/d.png");
        assertThat(ChunkSearchUtil.imageURLsInContent("plain text")).isEmpty();
        assertThat(ChunkSearchUtil.imageURLsInContent("")).isEmpty();
    }

    @Test
    void imageURLsFromInfoParsesUrlAndOriginalAndToleratesGarbage() {
        assertThat(ChunkSearchUtil.imageURLsFromInfo(
                "[{\"url\":\"u1\",\"original_url\":\"o1\"},{\"url\":\"\",\"original_url\":\"o2\"}]"))
                .containsExactlyInAnyOrder("u1", "o1", "o2");
        assertThat(ChunkSearchUtil.imageURLsFromInfo("not json")).isEmpty();
        assertThat(ChunkSearchUtil.imageURLsFromInfo("")).isEmpty();
        assertThat(ChunkSearchUtil.imageURLsFromInfo(null)).isEmpty();
    }

    @Test
    void generatedQuestionSourceIdShortKeptLongHashed() {
        // 短 ID：历史表示原样保留
        assertThat(ChunkSearchUtil.generatedQuestionSourceId("chunk", "q1")).isEqualTo("chunk-q1");
        // 恰好 64 字节：仍是原样（36 + 1 + 27）
        String kb36 = "11111111-1111-1111-1111-111111111111";
        String q27 = "aaaaaaaaaaaaaaaaaaaaaaaaaaa";
        assertThat(kb36.length() + 1 + q27.length()).isEqualTo(64);
        assertThat(ChunkSearchUtil.generatedQuestionSourceId(kb36, q27)).isEqualTo(kb36 + "-" + q27);
        // 73 字节的 UUID 对：sha256(questionID) 前 12 字节 hex（Go 实录语料）
        String q36 = "3f2b8a1c-9d4e-4f0a-8b7c-1d2e3f4a5b6c";
        assertThat(ChunkSearchUtil.generatedQuestionSourceId(kb36, q36))
                .isEqualTo(kb36 + "-q976c5fc9daf703cb3aff0926")
                .hasSize(62);
    }

    @Test
    void joinChunkContentCollapsesContainmentOverlapAndJoins() {
        // 空 ×2
        assertThat(ChunkSearchUtil.joinChunkContent("", "next", "\n\n")).isEqualTo("next");
        assertThat(ChunkSearchUtil.joinChunkContent("acc", "", "\n\n")).isEqualTo("acc");
        // 完全包含（≥12 rune）→ 折叠
        assertThat(ChunkSearchUtil.joinChunkContent(
                "the quick brown fox jumps over", "quick brown fox", "\n\n"))
                .isEqualTo("the quick brown fox jumps over");
        // 真实后缀/前缀重叠（15 rune ≥ 12）→ 去重拼接
        assertThat(ChunkSearchUtil.joinChunkContent(
                "abcdefghijklmnopqrst", "fghijklmnopqrstuvwxy", "\n\n"))
                .isEqualTo("abcdefghijklmnopqrstuvwxy");
        // 重叠不足 12 rune（如 10）→ 不算重叠，按 separator 相连
        assertThat(ChunkSearchUtil.joinChunkContent(
                "0123456789abcdefghij", "abcdefghij0123456789", "\n\n"))
                .isEqualTo("0123456789abcdefghij\n\nabcdefghij0123456789");
    }

    @Test
    void trimSpaceMatchesUnicodeSpaceSet() {
        // Java strip() 缺 U+00A0/U+0085；Go TrimSpace 是 unicode.IsSpace 全集
        assertThat(ChunkSearchUtil.trimSpace("\u00A0\u3000 x \u2028")).isEqualTo("x");
        assertThat(ChunkSearchUtil.trimSpace("  ")).isEmpty();
        assertThat(ChunkSearchUtil.trimSpace(null)).isEmpty();
    }
}
