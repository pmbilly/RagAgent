package com.ragagent.retrieval.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.retrieval.domain.ImageInfo;

/**
 * searchutil 纯函数测试（Go 实录：chunkmerge_test.go / imageinfo_html_test.go /
 * normalize、textutil、conversion 的语义移植）。已有部分的回归随 knowledge 包套件。
 */
class SearchUtilTest {

    private static Chunk chunk(String content, String type, int startAt, int endAt, int index) {
        Chunk c = new Chunk();
        c.setContent(content);
        c.setChunkType(type);
        c.setStartAt(startAt);
        c.setEndAt(endAt);
        c.setChunkIndex(index);
        return c;
    }

    // ── chunkmerge（对照 Go chunkmerge_test.go 全部用例）──────────────

    @Test
    void appendWithOverlapContiguousNoTrim() {
        String acc = "## 第二节\n\n";
        String next = "| 列A | 列B |\n| 值1 | 含实体&#34;引号&#34;的内容 |\n";
        assertEquals(acc + next, SearchChunkMerge.appendWithOverlap(acc, next, 0));
    }

    @Test
    void appendWithOverlapPrependedTableHeaderSkipped() {
        String header = "| 列1 | 列2 | 列3 |\n|:---|:---|:---|\n";
        String overlapRows = "| 第5行 | 内容5A | 内容5B |\n| 第6行 | 内容6A | 内容6B |\n";
        String accTail = "| 第4行 | 内容4A | 内容4B |\n" + overlapRows;
        String acc = header + "| 第1行 | x | — |\n" + accTail;
        String newRows = "| 第7行 | 内容7A | 内容7B |\n| 第8行 | 内容8A | 内容8B |\n";
        String next = header + overlapRows + newRows;
        int overlapRunes = overlapRows.codePointCount(0, overlapRows.length());
        assertEquals(acc + newRows, SearchChunkMerge.appendWithOverlap(acc, next, overlapRunes));
    }

    @Test
    void appendWithOverlapPlainOverlap() {
        String acc = "abcdefghijklmnopqrstuvwxyz0123";
        String next = "klmnopqrstuvwxyz0123ABCDEFG";
        assertEquals("abcdefghijklmnopqrstuvwxyz0123ABCDEFG",
                SearchChunkMerge.appendWithOverlap(acc, next, 20));
    }

    @Test
    void appendWithOverlapNoOverlap() {
        assertEquals("hello worldcompletely different",
                SearchChunkMerge.appendWithOverlap("hello world", "completely different", 0));
    }

    @Test
    void appendWithOverlapContiguousRealContentRepeat() {
        String repeat = "The system shall maintain a complete audit trail of all transactions.";
        String acc = "3.2 Logging Requirements\n\n" + repeat;
        String next = "\n\n5.1 Security Controls\n\n* Role-based access\n* Encryption at rest"
                + "\n\n5.2 Compliance\n\n" + repeat + " This satisfies SOC 2.";
        assertEquals(acc + next, SearchChunkMerge.appendWithOverlap(acc, next, 0));
    }

    @Test
    void appendWithExactOverlapCases() {
        String overlap = "shared boundary text";
        var ok = SearchChunkMerge.appendWithExactOverlap("before " + overlap,
                overlap + " after", overlap.codePointCount(0, overlap.length()));
        assertTrue(ok.ok());
        assertEquals("before " + overlap + " after", ok.value());

        String row = "| cell | cell |\n";
        String acc = "前言\n" + row + row;
        String next = row + row + row + "结尾\n";
        var zero = SearchChunkMerge.appendWithExactOverlap(acc, next, 0);
        assertTrue(zero.ok());
        assertEquals(acc + next, zero.value());

        assertFalse(SearchChunkMerge.appendWithExactOverlap("abcdefghijkl", "XYZdefghijkl", 6).ok());
        assertFalse(SearchChunkMerge.appendWithExactOverlap("short", "shorter", 99).ok());
        assertFalse(SearchChunkMerge.appendWithExactOverlap("short", "shorter", -1).ok());
    }

    @Test
    void joinChunkContentCases() {
        String first = "first edited body with no original overlap";
        String second = "second independently edited body";
        assertEquals(first + "\n\n" + second,
                ChunkSearchUtil.joinChunkContent(first, second, "\n\n"));

        String overlap = "shared boundary text";
        assertEquals("before " + overlap + " after",
                ChunkSearchUtil.joinChunkContent(
                        "before " + overlap, overlap + " after", "\n\n"));

        String outer = "prefix complete current body suffix";
        assertEquals(outer, ChunkSearchUtil.joinChunkContent(
                outer, "complete current body", "\n\n"));
    }

    @Test
    void mergeTextChunksOrdersFiltersAndStitches() {
        String header = "| a | b |\n|:--|:--|\n";
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(chunk(header + "| r1 | x |\n| r2 | y |\n", "text", 0, 20, 0));
        chunks.add(chunk(header + "| r2 | y |\n| r3 | z |\n", "text", 10, 40, 1));
        assertEquals(header + "| r1 | x |\n| r2 | y |\n| r3 | z |\n",
                SearchChunkMerge.mergeTextChunks(chunks, "\n"));
    }

    @Test
    void mergeTextChunksGapSeparator() {
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(chunk("first", "text", 0, 5, 0));
        chunks.add(chunk("second", "text", 100, 106, 1));
        assertEquals("first\nsecond", SearchChunkMerge.mergeTextChunks(chunks, "\n"));
    }

    @Test
    void mergeTextChunksEmptyAndCovered() {
        assertTrue(SearchChunkMerge.mergeTextChunks(List.of(), "\n").isEmpty());
        // 后段被前段完全覆盖（EndAt <= mergedEnd）→ 跳过
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(chunk("abcdef", "text", 0, 6, 0));
        chunks.add(chunk("cd", "text", 2, 4, 1));
        assertEquals("abcdef", SearchChunkMerge.mergeTextChunks(chunks, "\n"));
    }

    // ── imageinfo（对照 Go imageinfo_html_test.go 语义）──────────────

    private static String img(String url, String original, String caption, String ocr) {
        return new ImageInfo() {{
            setUrl(url);
            setOriginalUrl(original);
            setCaption(caption);
            setOcrText(ocr);
        }}.getUrl().isEmpty() && false ? "" : marshalOf(url, original, caption, ocr);
    }

    private static String marshalOf(String url, String original, String caption, String ocr) {
        ImageInfo i = new ImageInfo();
        i.setUrl(url);
        i.setOriginalUrl(original);
        i.setCaption(caption);
        i.setOcrText(ocr);
        return ImageInfoMatchUtil.marshalImageInfos(List.of(i));
    }

    @Test
    void enrichContentWithImageInfoWrapsMarkdownImages() {
        String content = "before ![alt](local://1/a.png) after";
        String info = marshalOf("local://1/a.png", "", "A caption", "OCR text");
        String got = ImageInfoEnricher.enrichContentWithImageInfo(content, info);
        assertTrue(got.contains("<image url=\"local://1/a.png\">"));
        assertTrue(got.contains("<image_original>![alt](local://1/a.png)</image_original>"));
        assertTrue(got.contains("<image_caption>A caption</image_caption>"));
        assertTrue(got.contains("<image_ocr>OCR text</image_ocr>"));
        assertTrue(got.endsWith("</image> after"));
    }

    @Test
    void enrichContentWithImageInfoAppendsMissingImages() {
        String content = "no images here";
        // 无 caption/ocr 的图：BuildImageInfoXMLWithURL 内层为空 → 刻意不追加（照抄 Go）
        String noInfo = ImageInfoMatchUtil.marshalImageInfos(List.of(infoOf(
                "local://2/b.png", "", "", "")));
        assertEquals("no images here",
                ImageInfoEnricher.enrichContentWithImageInfo(content, noInfo));
        String withCaption = ImageInfoMatchUtil.marshalImageInfos(List.of(infoOf(
                "local://2/b.png", "", "cap2", "")));
        assertEquals("no images here\n<image url=\"local://2/b.png\">\n<image_caption>cap2</image_caption>\n</image>",
                ImageInfoEnricher.enrichContentWithImageInfo(content, withCaption));
    }

    @Test
    void enrichForChatKeepsMarkdownAndInjectsBlockquote() {
        String content = "see ![pic](local://1/a.png) end";
        String info = marshalOf("local://1/a.png", "", "Caption one", "");
        String got = ImageInfoEnricher.enrichContentWithImageInfoForChat(content, info);
        assertTrue(got.contains("![pic](local://1/a.png)\n\n> **Image caption:** Caption one"), got);
        assertTrue(got.endsWith(" end"), got);
    }

    @Test
    void enrichForChatHandlesHtmlImgAndMultiLineOcr() {
        String content = "<p>x</p><img alt=\"\" src=\"  local://3/c.png  \">";
        ImageInfo i = infoOf("local://3/c.png", "", "", "line1\nline2");
        String info = ImageInfoMatchUtil.marshalImageInfos(List.of(i));
        String got = ImageInfoEnricher.enrichContentWithImageInfoForChat(content, info);
        // HTML src 先 trim 再匹配；多行 OCR 的换行接 "> " 前缀
        assertTrue(got.contains("> **Image text (OCR):** line1\n> line2"), got);
    }

    @Test
    void buildImageInfoMarkdownWithUrlEscapesAlt() {
        ImageInfo i = infoOf("", "", "a [b] c\\d", "");
        String got = ImageInfoEnricher.buildImageInfoMarkdownWithUrl("local://x/1.png", i);
        // alt 转义反斜杠/方括号；metadata 的 caption 原样
        assertEquals("![a \\[b\\] c\\\\d](local://x/1.png)\n\n> **Image caption:** a [b] c\\d",
                got);
    }

        @Test
    void buildImageInfoMarkdownEmptyCaptionFallsBackToImage() {
        ImageInfo i = infoOf("", "", "", "");
        assertEquals("![image](u)", ImageInfoEnricher.buildImageInfoMarkdownWithUrl("u", i));
    }

    @Test
    void buildImageInfoXmlVariants() {
        ImageInfo i = infoOf("u", "", "cap", "text");
        assertEquals("<image_caption>cap</image_caption>\n<image_ocr>text</image_ocr>\n",
                ImageInfoEnricher.buildImageInfoXml(i));
        assertEquals("<image url=\"u\">\n<image_caption>cap</image_caption>\n<image_ocr>text</image_ocr>\n</image>",
                ImageInfoEnricher.buildImageInfoXmlWithUrl("u", i));
        ImageInfo empty = infoOf("", "", "", "");
        assertEquals("", ImageInfoEnricher.buildImageInfoXmlWithUrl("u", empty));
    }

    @Test
    void enrichContentCaptionOnlyAndCaptionOcr() {
        String content = "![a](u1) text";
        String info = ImageInfoMatchUtil.marshalImageInfos(List.of(
                infoOf("u1", "", "cap1", "ocr1"),
                infoOf("u2", "", "cap2", "")));
        String captionOnly = ImageInfoEnricher.enrichContentCaptionOnly(content, info);
        assertEquals("![a](u1)\n<image_caption>cap1</image_caption> text\n<image_caption>cap2</image_caption>",
                captionOnly);

        String captionOcr = ImageInfoEnricher.enrichContentCaptionAndOcr(content, info);
        assertEquals("![a](u1)\n<image_caption>cap1</image_caption>\n<image_ocr>ocr1</image_ocr> text"
                        + "\n<image_caption>cap2</image_caption>",
                captionOcr);
    }

    @Test
    void mergeImageInfoJsonDedupesByUrl() {
        String a = marshalOf("u1", "", "cap-a", "");
        String b = marshalOf("u1", "orig", "cap-b", "");
        // Go 的 map 遍历无序；Java 用插入序保证确定性（先到先得的去重语义一致）
        Map<String, String> perChunk = new java.util.LinkedHashMap<>();
        perChunk.put("c1", a);
        perChunk.put("c2", b);
        perChunk.put("c3", "not-json");
        String merged = ImageInfoEnricher.mergeImageInfoJson(perChunk);
        assertTrue(merged.startsWith("[{"));
        assertTrue(merged.contains("cap-a"));
        assertFalse(merged.contains("cap-b"), "同 URL 去重（先到先得）");
    }

    @Test
    void clearImageInfoTextMatchingBodyVariants() {
        String info = marshalOf("u", "", "cap", "recognized text");
        String cleared = ImageInfoEnricher.clearImageInfoTextMatchingBody(
                info, "recognized text", "image_ocr");
        assertTrue(cleared.contains("\"ocr_text\":\"\""));
        assertFalse(cleared.contains("\"ocr_text\":\"recognized text\""));
        // chunk 类型不匹配 → 原样返回
        assertEquals(info, ImageInfoEnricher.clearImageInfoTextMatchingBody(
                info, "recognized text", "image_caption"));
        // 空入参
        assertEquals("", ImageInfoEnricher.clearImageInfoTextMatchingBody("", "x", "image_ocr"));
    }

    // ── imageinfo_match（对照 Go imageinfo_match_test.go 语义）────────

    @Test
    void sliceContentByDocumentRange() {
        String content = "0123456789";
        assertEquals("2345", SearchTextUtil.normalizeContent(
                ImageInfoMatchUtil.sliceContentByDocumentRange(content, 0, 2, 6)));
        assertEquals("", ImageInfoMatchUtil.sliceContentByDocumentRange(content, 0, 20, 30));
        // contentStartAt=6 → content 内相对偏移 0 起全保留
        assertEquals("0123456789", ImageInfoMatchUtil.sliceContentByDocumentRange(content, 6, 0, 100));
    }

    @Test
    void filterImageInfoByContentUrlsPrunes() {
        String content = "keep ![a](u1) only";
        String info = ImageInfoMatchUtil.marshalImageInfos(List.of(
                infoOf("u1", "", "keep", ""),
                infoOf("u2", "", "drop", "")));
        String got = ImageInfoMatchUtil.filterImageInfoByContentUrls(content, info);
        assertTrue(got.contains("keep"));
        assertFalse(got.contains("drop"));
        assertEquals("", ImageInfoMatchUtil.filterImageInfoByContentUrls(content, "not-json"));
        assertEquals("", ImageInfoMatchUtil.filterImageInfoByContentUrls("no images", info));
    }

    @Test
    void pruneMarkdownImagesByImageInfoAndOutsideRange() {
        String content = "head\n\n![a](u1)\n\n![b](u2)\n\ntail";
        String info = marshalOf("u1", "", "", "");
        String pruned = ImageInfoMatchUtil.pruneMarkdownImagesByImageInfo(content, info);
        assertTrue(pruned.contains("![a](u1)"));
        assertFalse(pruned.contains("![b](u2)"));
        // 全部不允许 → 全删
        String allGone = ImageInfoMatchUtil.pruneMarkdownImagesByImageInfo(content, "");
        assertFalse(allGone.contains("!["));
        assertTrue(allGone.contains("head"));

        // 区间裁剪：u1 在 [0,10)、u2 在 [10,30)
        String ranged = ImageInfoMatchUtil.pruneMarkdownImagesOutsideRange(content, 0, 0, 10);
        assertTrue(ranged.contains("![a](u1)"));
        assertFalse(ranged.contains("![b](u2)"));
    }

    @Test
    void filterImageInfoByMatchRange() {
        String parent = "abcdef ![a](u1) ghijkl ![b](u2) mnop";
        String info = ImageInfoMatchUtil.marshalImageInfos(List.of(
                infoOf("u1", "", "in", ""),
                infoOf("u2", "", "out", "")));
        String got = ImageInfoMatchUtil.filterImageInfoByMatchRange(parent, 0, 0, 15, info);
        assertTrue(got.contains("in"));
        assertFalse(got.contains("out"));
    }

    // ── normalize（对照 Go normalize.go 语义）─────────────────────────

    @Test
    void normalizeKeywordScoresTable() {
        // 单条 → 1
        List<Double> single = new ArrayList<>(List.of(0.3));
        KeywordScoreNormalizer.normalizeKeywordScores(single, d -> true, d -> d, (d, v) -> single.set(0, v), null);
        assertEquals(1.0, single.get(0));

        // 无方差 → 全 1 + OnNoVariance
        List<Double> flat = new ArrayList<>(List.of(0.5, 0.5, 0.5));
        double[] noVar = {0, 0};
        KeywordScoreNormalizer.normalizeKeywordScores(flat, d -> true, d -> d,
                (d, v) -> flat.set(flat.indexOf(d), v),
                new KeywordScoreNormalizer.Callbacks() {
                    @Override
                    public void onNoVariance(int count, double score) {
                        noVar[0] = count;
                        noVar[1] = score;
                    }
                });
        flat.forEach(v -> assertEquals(1.0, v));
        assertEquals(3, noVar[0]);
        assertEquals(0.5, noVar[1]);

        // 常规 min-max 归一
        List<Double> spread = new ArrayList<>(List.of(1.0, 2.0, 3.0));
        KeywordScoreNormalizer.normalizeKeywordScores(spread, d -> true, d -> d,
                (d, v) -> spread.set(spread.indexOf(d), v), null);
        assertEquals(0.0, spread.get(0));
        assertEquals(0.5, spread.get(1));
        assertEquals(1.0, spread.get(2));

        // 混合类型只归一 keyword
        List<String> mixed = new ArrayList<>(List.of("k:1", "v:skip", "k:3"));
        KeywordScoreNormalizer.normalizeKeywordScores(mixed, s -> s.startsWith("k"),
                s -> Double.parseDouble(s.substring(2)),
                (s, v) -> mixed.set(mixed.indexOf(s), "k:" + v), null);
        assertEquals("k:0.0", mixed.get(0));
        assertEquals("v:skip", mixed.get(1));
        assertEquals("k:1.0", mixed.get(2));
    }

    @Test
    void normalizeKeywordScoresPercentileBoundsForTenPlus() {
        List<Double> scores = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            scores.add((double) i);
        }
        KeywordScoreNormalizer.normalizeKeywordScores(scores, d -> true, d -> d,
                (d, v) -> scores.set(scores.indexOf(d), v), null);
        // p5 = scores[1] = 2，p95 = scores[19] = 20：2 → 0，20 → 1，1 被钳为 0
        assertEquals(0.0, scores.get(1));
        assertEquals(1.0, scores.get(19));
        assertEquals(0.0, scores.get(0));
    }

    // ── textutil（对照 Go textutil.go 语义）───────────────────────────

    @Test
    void buildContentSignatureNormalizesCaseAndWhitespace() {
        String a = SearchTextUtil.buildContentSignature("Hello   WORLD\n\tfoo  ");
        String b = SearchTextUtil.buildContentSignature("hello world foo");
        assertEquals(a, b);
        assertEquals(32, a.length());
        assertEquals("", SearchTextUtil.buildContentSignature("   "));
    }

    @Test
    void tokenizeSimpleFiltersSingleRunesAndPunctuation() {
        Set<String> tokens = SearchTextUtil.tokenizeSimple("Hello, world! Hello");
        // "Hello," 归一后 "hello," 分词（whitespace 路径）：逗号尾随不剥离——对照 Go 的
        // strings.Fields 行为（只按空白切）；单 rune/纯标点 token 被过滤
        assertTrue(tokens.contains("hello,"));
        assertTrue(tokens.contains("world!"));
        assertFalse(tokens.contains("x"));

        assertTrue(SearchTextUtil.tokenizeSimple("").isEmpty());
        assertTrue(SearchTextUtil.tokenizeSimple("!!!").isEmpty(), "纯标点全被过滤");
    }

    @Test
    void jaccardAndOverlapRatio() {
        assertEquals(0.0, SearchTextUtil.jaccard(Set.of(), Set.of()));
        Set<String> a = Set.of("x", "y");
        Set<String> b = Set.of("y", "z");
        assertEquals(1.0 / 3.0, SearchTextUtil.jaccard(a, b), 1e-12);
        assertEquals(0.0, SearchTextUtil.contentOverlapRatio("", "abc"));
        assertEquals(1.0, SearchTextUtil.contentOverlapRatio("alpha beta", "beta alpha"), 1e-12);
    }

    @Test
    void normalizeContentAndContainment() {
        assertEquals("a b c", SearchTextUtil.normalizeContent("  A   B\nC "));
        assertTrue(SearchTextUtil.isContentContained("a b", "x a b y"));
        assertFalse(SearchTextUtil.isContentContained("", "x"));
        assertFalse(SearchTextUtil.isContentContained("longer string", "short"));
        assertEquals(0.5, SearchTextUtil.clampFloat(0.5, 0, 1));
        assertEquals(1.0, SearchTextUtil.clampFloat(2, 0, 1));
    }

    @Test
    void tokenizeSimpleChineseUsesSegmenterSeam() {
        // 降级接缝：二字滑窗（非 jieba）——分词边界与 Go 分叉，但集合语义保留
        Set<String> tokens = SearchTextUtil.tokenizeSimple("知识库检索");
        assertFalse(tokens.isEmpty());
        tokens.forEach(t -> assertTrue(t.codePointCount(0, t.length()) > 1));
    }

    // ── conversion（对照 Go conversion.go 语义）───────────────────────

    @Test
    void convertWebSearchResultsDefaultsAndSeqOverride() {
        List<com.ragagent.retrieval.domain.WebSearchResult> web = new ArrayList<>();
        com.ragagent.retrieval.domain.WebSearchResult r1 =
                new com.ragagent.retrieval.domain.WebSearchResult();
        r1.setTitle("Title");
        r1.setUrl("https://e/1");
        r1.setSnippet("Snip");
        r1.setContent("Body");
        r1.setSource("exa");
        web.add(r1);
        web.add(null);
        com.ragagent.retrieval.domain.WebSearchResult r2 =
                new com.ragagent.retrieval.domain.WebSearchResult();
        r2.setTitle("NoURL");
        web.add(r2);

        // 缺省 seqFunc 恒 1
        List<com.ragagent.retrieval.domain.SearchResult> results =
                WebResultConverter.convert(web);
        assertEquals(2, results.size());
        var first = results.get(0);
        assertEquals("https://e/1", first.getId());
        assertEquals("https://e/1", first.getKnowledgeId());
        assertEquals("Title\n\nSnip\n\nBody", first.getContent());
        assertEquals(0.6, first.getScore());
        assertEquals(7, first.getMatchType(), "MatchTypeWebSearch = iota 7");
        assertEquals("web_search", first.getChunkType());
        assertEquals("web_search", first.getKnowledgeSource());
        assertEquals("https://e/1", first.getMetadata().get("url"));
        assertEquals(1, first.getSeq());
        // 空 URL → web_search_%d（用原始下标）
        assertEquals("web_search_2", results.get(1).getId());
        // EndAt = content 的 rune 数
        assertEquals(first.getContent().codePointCount(0, first.getContent().length()), first.getEndAt());

        // seq 覆盖（service 层传 idx）
        List<com.ragagent.retrieval.domain.SearchResult> withSeq =
                WebResultConverter.convert(web, idx -> idx);
        assertEquals(0, withSeq.get(0).getSeq());
    }

    @Test
    void convertWebSearchResultsPublishedAtRfc3339() {
        com.ragagent.retrieval.domain.WebSearchResult r =
                new com.ragagent.retrieval.domain.WebSearchResult();
        r.setTitle("t");
        r.setUrl("u");
        r.setPublishedAt(java.time.OffsetDateTime.parse("2026-08-09T16:18:30+08:00"));
        var got = WebResultConverter.convert(List.of(r));
        assertEquals("2026-08-09T08:18:30Z", got.get(0).getMetadata().get("published_at"));
    }

    private static ImageInfo infoOf(String url, String original, String caption, String ocr) {
        ImageInfo i = new ImageInfo();
        i.setUrl(url);
        i.setOriginalUrl(original);
        i.setCaption(caption);
        i.setOcrText(ocr);
        return i;
    }
}
