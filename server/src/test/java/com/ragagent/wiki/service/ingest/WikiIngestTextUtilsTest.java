package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.WikiImageMarkup;
import com.ragagent.wiki.service.page.WikiTextUtils;

/**
 * wiki ingest 的纯文本工具测试（slugify / 截断 / 去重追加 /
 * 内容重建 / 图片标记剥离 / 文本充分性 / 图片 URL 掩码与还原）。
 */
class WikiIngestTextUtilsTest {

    // ═══════════════════════════════════════════════════════════════
    // slugify
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestSlugify：11 个用例逐条对照")
    void slugify() {
        assertThat(WikiTextUtils.slugify("Hello World")).isEqualTo("hello-world");
        assertThat(WikiTextUtils.slugify("Acme Corp")).isEqualTo("acme-corp");
        assertThat(WikiTextUtils.slugify("  spaces  ")).isEqualTo("spaces");
        assertThat(WikiTextUtils.slugify("under_score")).isEqualTo("under-score");
        assertThat(WikiTextUtils.slugify("Already-Good")).isEqualTo("already-good");
        assertThat(WikiTextUtils.slugify("Special!@#Chars")).isEqualTo("specialchars");
        assertThat(WikiTextUtils.slugify("CamelCase")).isEqualTo("camelcase");
        assertThat(WikiTextUtils.slugify("")).isEmpty();
        // 保留斜杠以支持层级 slug
        assertThat(WikiTextUtils.slugify("a/b/c")).isEqualTo("a/b/c");
        // 保留 CJK
        assertThat(WikiTextUtils.slugify("中文标题")).isEqualTo("中文标题");
        // 中英混排
        assertThat(WikiTextUtils.slugify("Mix 中英文 Test")).isEqualTo("mix-中英文-test");
    }

    @Test
    @DisplayName("slugify 边界：折叠连字符、裁首尾连字符、null 安全")
    void slugifyEdgeCases() {
        assertThat(WikiTextUtils.slugify("a  b")).isEqualTo("a-b");
        assertThat(WikiTextUtils.slugify("--a--b--")).isEqualTo("a-b");
        assertThat(WikiTextUtils.slugify("!!!")).isEmpty();
        assertThat(WikiTextUtils.slugify(null)).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // truncateString
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestTruncateString：按码点截断并追加 ...")
    void truncateString() {
        assertThat(WikiTextUtils.truncateString("hello world", 20)).isEqualTo("hello world");
        assertThat(WikiTextUtils.truncateString("hello world", 5)).isEqualTo("hello...");
        assertThat(WikiTextUtils.truncateString("", 10)).isEmpty();
        assertThat(WikiTextUtils.truncateString("abc", 3)).isEqualTo("abc");
        assertThat(WikiTextUtils.truncateString("abcd", 3)).isEqualTo("abc...");
        // CJK 按 rune 计，不按字节也不按 char
        assertThat(WikiTextUtils.truncateString("中文测试", 2)).isEqualTo("中文...");
    }

    // ═══════════════════════════════════════════════════════════════
    // appendUnique
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestAppendUnique：存在即不追加")
    void appendUnique() {
        List<String> arr = new ArrayList<>(List.of("a", "b"));
        List<String> result = WikiTextUtils.appendUnique(arr, "c");
        assertThat(result).hasSize(3);
        result = WikiTextUtils.appendUnique(result, "b");
        assertThat(result).hasSize(3);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内容重建
    // ═══════════════════════════════════════════════════════════════

    private static Chunk chunk(int index, String type, String content) {
        Chunk c = new Chunk();
        c.setChunkIndex(index);
        c.setChunkType(type);
        c.setContent(content);
        return c;
    }

    @Test
    @DisplayName("TestReconstructContent：按 ChunkIndex 排序、排除非文本 chunk（此用例是位置为空的分支）")
    void reconstructContent() {
        List<Chunk> chunks = List.of(
                chunk(2, "text", "Third paragraph."),
                chunk(0, "text", "First paragraph."),
                chunk(1, "text", "Second paragraph."),
                chunk(3, "image_ocr", "OCR text should be excluded."));
        String content = WikiIngestService.reconstructContent(chunks);
        assertThat(content).isNotEmpty();
        // 这些 chunk 没有 StartAt/EndAt（都是 0），因此 MergeTextChunks 走
        // "EndAt == 0 → 作为独立段落用 gapSep 拼接"的分支：
        // 顺序由 startAt 相同 → chunkIndex 升序决定，因此是 First/Second/Third。
        assertThat(content).isEqualTo("First paragraph.\nSecond paragraph.\nThird paragraph.");
        assertThat(content).doesNotContain("OCR");
    }

    @Test
    @DisplayName("TestReconstructContentEmpty：nil / 空列表都产出空串")
    void reconstructContentEmpty() {
        assertThat(WikiIngestService.reconstructContent(null)).isEmpty();
        assertThat(WikiIngestService.reconstructContent(List.of())).isEmpty();
    }

    @Test
    @DisplayName("reconstructContent 的重叠裁剪：按文本匹配去掉真实重叠")
    void reconstructContentTrimsOverlap() {
        // 两个 chunk 位置相接：EndAt=20 与 StartAt=20 的重叠窗口
        Chunk a = chunk(0, "text", "The quick brown fox jumps over the lazy dog");
        a.setStartAt(0);
        a.setEndAt(44);
        Chunk b = chunk(1, "text", "over the lazy dog and runs away");
        b.setStartAt(20);
        b.setEndAt(53);

        String content = WikiIngestService.reconstructContent(List.of(a, b));
        // 重叠部分 "over the lazy dog" 只出现一次
        assertThat(countOf(content, "over the lazy dog")).isEqualTo(1);
        assertThat(content).contains("and runs away");
    }

    // ═══════════════════════════════════════════════════════════════
    // 图片标记剥离
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestStripImageMarkup：7 个子用例逐条对照")
    void stripImageMarkup() {
        assertThat(WikiImageMarkup.stripImageMarkup("Hello world.")).isEqualTo("Hello world.");
        assertThat(WikiImageMarkup.stripImageMarkup("![alt](images/page_1.png)")).isEmpty();
        assertThat(WikiImageMarkup.stripImageMarkup(
                "![MX5280_page_1.png](images/MX5280_page_1.png)\n\n"
                        + "![MX5280_page_2.png](images/MX5280_page_2.png)"))
                .isEqualTo("\n\n");
        assertThat(WikiImageMarkup.stripImageMarkup(
                "Intro paragraph.\n![fig](a.png)\nConclusion."))
                .isEqualTo("Intro paragraph.\n\nConclusion.");
        assertThat(WikiImageMarkup.stripImageMarkup("Before <img src=\"x.png\" alt=\"y\"/> after"))
                .isEqualTo("Before  after");

        // 回归护栏：早期版本把整个 <image>...</image> 块删掉（含 <image_ocr> 内容），
        // 静默摧毁了成功的 VLM OCR 结果。修复必须保留内侧 OCR / caption 文本。
        String enriched = "<image url=\"images/page_1.png\">\n"
                + "<image_original>![p1](images/page_1.png)</image_original>\n"
                + "<image_caption>scanned letter on letterhead</image_caption>\n"
                + "<image_ocr>SEHR GEEHRTER HERR MUSTERMANN, ...</image_ocr>\n"
                + "</image>";
        assertThat(WikiImageMarkup.stripImageMarkup(enriched))
                .isEqualTo("\n\nscanned letter on letterhead\nSEHR GEEHRTER HERR MUSTERMANN, ...\n");

        // 空的 <image> 块（OCR 失败）退化为空白
        assertThat(WikiImageMarkup.stripImageMarkup(
                "<image url=\"x\"><image_original>![a](x)</image_original></image>"))
                .isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 文本充分性判定
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestHasSufficientTextContent：8 个子用例逐条对照（阈值 10 码点）")
    void hasSufficientTextContent() {
        assertThat(WikiImageMarkup.hasSufficientTextContent("")).isFalse();
        assertThat(WikiImageMarkup.hasSufficientTextContent("   \n\n\t  ")).isFalse();
        assertThat(WikiImageMarkup.hasSufficientTextContent(
                "![MX5280_page_1.png](images/MX5280_page_1.png)\n"
                        + "![MX5280_page_2.png](images/MX5280_page_2.png)")).isFalse();
        assertThat(WikiImageMarkup.hasSufficientTextContent("hi")).isFalse();
        assertThat(WikiImageMarkup.hasSufficientTextContent("Meeting at 3pm tomorrow.")).isTrue();

        // 图片 + 成功的 VLM OCR（修复点）
        assertThat(WikiImageMarkup.hasSufficientTextContent(
                "<image url=\"images/p1.png\">\n"
                        + "<image_original>![p1](images/p1.png)</image_original>\n"
                        + "<image_caption>scanned letter</image_caption>\n"
                        + "<image_ocr>Sehr geehrter Herr Mustermann, in der Sache 4711/2024 ...</image_ocr>\n"
                        + "</image>")).isTrue();

        // 图片 + 失败的 VLM OCR（仍拒绝）
        assertThat(WikiImageMarkup.hasSufficientTextContent(
                "<image url=\"images/p1.png\">\n"
                        + "<image_original>![p1](images/p1.png)</image_original>\n"
                        + "</image>")).isFalse();

        // 充足文本混着图片仍通过
        assertThat(WikiImageMarkup.hasSufficientTextContent(
                "![cover](cover.png)\nDie Beklagte hat die Klage anerkannt.\n![sig](sig.png)"))
                .isTrue();
    }

    // ═══════════════════════════════════════════════════════════════
    // 图片 URL 掩码
    // ═══════════════════════════════════════════════════════════════

    static final String URL_A = "minio://kb/10000/exports/4135-aaaa-bbbb-cccc/page_1.jpg";
    static final String URL_B = "local://kb/10000/exports/9999-dddd-eeee-ffff/page_2.png";

    @Test
    @DisplayName("TestMaskImageURLs/单张 Markdown 图片精确往返")
    void maskSingleMarkdownImageRoundTrips() {
        String input = "Intro ![alt text](" + URL_A + ") outro";
        WikiImageMarkup.Masked masked = WikiImageMarkup.maskImageURLs(input);

        assertThat(masked.masked()).isEqualTo("Intro ![alt text](wkimg:0001) outro");
        assertThat(masked.tokenToUrl()).containsEntry("wkimg:0001", URL_A);
        assertThat(WikiImageMarkup.unmaskImageURLs(masked.masked(), masked.tokenToUrl()))
                .isEqualTo(input);
    }

    @Test
    @DisplayName("TestMaskImageURLs/富化图片属性与原始 Markdown 共用同一 token")
    void maskEnrichedImageSharesToken() {
        String input = "<image url=\"" + URL_A + "\">\n"
                + "<image_original>![page](" + URL_A + ")</image_original>\n"
                + "<image_caption>caption text</image_caption>\n"
                + "</image>";
        WikiImageMarkup.Masked masked = WikiImageMarkup.maskImageURLs(input);

        assertThat(masked.tokenToUrl()).hasSize(1);
        assertThat(countOf(masked.masked(), "wkimg:0001")).isEqualTo(2);
        assertThat(masked.masked()).doesNotContain(URL_A);
        assertThat(WikiImageMarkup.unmaskImageURLs(masked.masked(), masked.tokenToUrl()))
                .isEqualTo(input);
    }

    @Test
    @DisplayName("TestMaskImageURLs/重复 URL 与不同 URL 的编号")
    void maskRepeatedAndDistinctUrls() {
        String input = "![a](" + URL_A + ")\n![again](" + URL_A + ")\n![b](" + URL_B + ")";
        WikiImageMarkup.Masked masked = WikiImageMarkup.maskImageURLs(input);

        assertThat(countOf(masked.masked(), "wkimg:0001")).isEqualTo(2);
        assertThat(countOf(masked.masked(), "wkimg:0002")).isEqualTo(1);
        assertThat(WikiImageMarkup.unmaskImageURLs(masked.masked(), masked.tokenToUrl()))
                .isEqualTo(input);
    }

    @Test
    @DisplayName("TestMaskImageURLs/alt 文本即使形似占位符也保留")
    void maskPreservesCaptionResemblingPlaceholder() {
        String input = "![wkimg:0001](" + URL_A + ")";
        WikiImageMarkup.Masked masked = WikiImageMarkup.maskImageURLs(input);
        assertThat(WikiImageMarkup.unmaskImageURLs(masked.masked(), masked.tokenToUrl()))
                .isEqualTo(input);
    }

    @Test
    @DisplayName("TestMaskImageURLs/非图片文本原样不改")
    void maskLeavesNonImageTextAlone() {
        String input = "slug: entity/example\nlanguage: zh";
        WikiImageMarkup.Masked masked = WikiImageMarkup.maskImageURLs(input);
        assertThat(masked.masked()).isEqualTo(input);
        assertThat(masked.tokenToUrl()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 掩码还原丢弃未知占位符
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestUnmaskImageURLsDropsUnknownPlaceholders：未知占位符整段丢弃")
    void unmaskDropsUnknownPlaceholders() {
        Map<String, String> urlMap = Map.of("wkimg:0001", "minio://kb/exports/real.jpg");
        String input = "{\"details\":\"keep ![ok](wkimg:0001) drop ![bad](wkimg:001) and wkimg:9999\"}";

        String got = WikiImageMarkup.unmaskImageURLs(input, urlMap);

        assertThat(got).contains("![ok](minio://kb/exports/real.jpg)");
        assertThat(got).doesNotContain("wkimg:");
        assertThat(got).doesNotContain("![bad]");
    }

    // ═══════════════════════════════════════════════════════════════
    // 补充：cleanLLMJSON / sanitizeJSONString / splitSummaryLine / 预览
    //（map/reduce 的输入清洗路径）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LLM JSON 清洗（对照 Go L3187-3246）")
    class JsonCleaning {

        @Test
        @DisplayName("剥掉 ```json 围栏")
        void stripsCodeFence() {
            assertThat(WikiTextUtils.cleanLLMJSON("```json\n{\"a\":1}\n```"))
                    .isEqualTo("{\"a\":1}");
            assertThat(WikiTextUtils.cleanLLMJSON("```\n{\"a\":1}\n```"))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("字符串字面量里的裸换行被转义为 \\n（否则 JSON 解析失败）")
        void escapesRawNewlinesInsideStrings() {
            String raw = "{\"details\":\"line1\nline2\"}";
            assertThat(WikiTextUtils.cleanLLMJSON(raw))
                    .isEqualTo("{\"details\":\"line1\\nline2\"}");
        }

        @Test
        @DisplayName("字符串外的换行保持原样（结构空白不该被改写）")
        void keepsStructuralNewlines() {
            String raw = "{\n\"a\":1\n}";
            assertThat(WikiTextUtils.sanitizeJSONString(raw)).isEqualTo(raw);
        }

        @Test
        @DisplayName("反斜杠后的真实换行被改写成 n（把跨行转义修成合法的 \\n）")
        void fixesSplitEscapeSequence() {
            assertThat(WikiTextUtils.sanitizeJSONString("a\\\nb")).isEqualTo("a\\nb");
        }
    }

    @Nested
    @DisplayName("SUMMARY 行解析（对照 Go splitSummaryLine L2217-2231）")
    class SummaryLine {

        @Test
        @DisplayName("半角冒号")
        void halfWidthColon() {
            WikiTextUtils.SummaryLine r =
                    WikiTextUtils.splitSummaryLine("SUMMARY: a doc about X\n# Title\nbody");
            assertThat(r.summary()).isEqualTo("a doc about X");
            assertThat(r.content()).isEqualTo("# Title\nbody");
        }

        @Test
        @DisplayName("全角冒号（中文模型会输出）")
        void fullWidthColon() {
            WikiTextUtils.SummaryLine r =
                    WikiTextUtils.splitSummaryLine("SUMMARY：关于 X 的文档\n# 标题");
            assertThat(r.summary()).isEqualTo("关于 X 的文档");
            assertThat(r.content()).isEqualTo("# 标题");
        }

        @Test
        @DisplayName("只有 SUMMARY 一行时 content 为空")
        void summaryOnly() {
            WikiTextUtils.SummaryLine r = WikiTextUtils.splitSummaryLine("SUMMARY: only");
            assertThat(r.summary()).isEqualTo("only");
            assertThat(r.content()).isEmpty();
        }

        @Test
        @DisplayName("没有 SUMMARY 行时 summary 为空、content 为原文")
        void noSummaryLine() {
            WikiTextUtils.SummaryLine r = WikiTextUtils.splitSummaryLine("# Just a page");
            assertThat(r.summary()).isEmpty();
            assertThat(r.content()).isEqualTo("# Just a page");
        }
    }

    @Nested
    @DisplayName("trace 预览（对照 Go previewExtractedItems / topCitedSlugs / previewNewSlugs）")
    class Previews {

        @Test
        @DisplayName("topCitedSlugs 按 chunk 数降序、并列按 slug 升序；空输入返回 null")
        void topCitedSlugs() {
            assertThat(WikiIngestPreviews.topCitedSlugs(Map.of(), 5)).isNull();
            assertThat(WikiIngestPreviews.topCitedSlugs(null, 5)).isNull();

            List<Map<String, Object>> out = WikiIngestPreviews.topCitedSlugs(
                    Map.of("concept/rag", List.of("c1", "c2"),
                            "entity/acme", List.of("c1"),
                            "entity/beta", List.of("c1", "c2")), 2);
            // 两条并列 2 个 chunk → 按 slug 升序：concept/rag 在 entity/beta 之前
            assertThat(out).hasSize(2);
            assertThat(out.get(0)).containsEntry("slug", "concept/rag").containsEntry("chunks", 2);
            assertThat(out.get(1)).containsEntry("slug", "entity/beta").containsEntry("chunks", 2);
        }

        @Test
        @DisplayName("previewExtractedItems 键按字母序（对照 Go map 的 encoding/json 行为）")
        void previewExtractedItemsKeyOrder() {
            ExtractedItem it = new ExtractedItem("Acme Corp", "entity/acme-corp",
                    List.of(), "a company", "details");
            List<Map<String, String>> out = WikiIngestPreviews.previewExtractedItems(List.of(it), 5);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).keySet()).containsExactly("description", "name", "slug");
        }

        @Test
        @DisplayName("previewStringSlice 截断形态")
        void previewStringSlice() {
            assertThat(WikiTextUtils.previewStringSlice(List.of(), 6)).isEqualTo("[]");
            assertThat(WikiTextUtils.previewStringSlice(List.of("a", "b"), 6)).isEqualTo("[a, b]");
            assertThat(WikiTextUtils.previewStringSlice(List.of("a", "b", "c"), 2))
                    .isEqualTo("[a, b ...(+1)]");
        }
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
