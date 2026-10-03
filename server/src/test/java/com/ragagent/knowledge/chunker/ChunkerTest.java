package com.ragagent.knowledge.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * chunker 单元测试，不依赖 Spring 上下文。
 *
 * <p>核心断言：chunk 数量、每块 content、start/end（rune 偏移，非 char/byte）。</p>
 */
class ChunkerTest {

    private static SplitterConfig cfg(int chunkSize, int chunkOverlap, List<String> separators) {
        SplitterConfig cfg = new SplitterConfig();
        cfg.setChunkSize(chunkSize);
        cfg.setChunkOverlap(chunkOverlap);
        cfg.setSeparators(separators);
        return cfg;
    }

    @Test
    void splitTextEmpty() {
        assertEquals(0, LegacySplitter.splitText("", SplitterConfig.defaultConfig()).size());
    }

    /** 单字符落在 [0,1)。 */
    @Test
    void splitTextSingleCharChinese() {
        List<ParsedChunk> chunks = LegacySplitter.splitText("你", cfg(10, 0, List.of("\n")));
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).getStart());
        assertEquals(1, chunks.get(0).getEnd());
    }

    @Test
    void splitTextChineseStartEndAreRuneOffsets() {
        String text = "你好世界这是一个测试文本用于检验分割位置";
        int runeCount = CodePoints.len(text);
        int byteCount = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertNotEquals(runeCount, byteCount, "test requires multi-byte characters");

        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(100, 0, List.of("\n")));
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).getStart());
        assertEquals(runeCount, chunks.get(0).getEnd(), "End must be rune offset, not byte offset");
    }

    @Test
    void splitTextMixedChineseAndAscii() {
        String text = "Hello你好World世界Test测试";
        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(100, 0, List.of("\n")));
        assertEquals(1, chunks.size());
        assertEquals(CodePoints.len(text), chunks.get(0).getEnd() - chunks.get(0).getStart());
    }

    /** 拼接还原原文。 */
    @Test
    void splitTextBasicAscii() {
        String text = "Hello world. This is a test.";
        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(100, 0, List.of(". ")));
        assertFalse(chunks.isEmpty());
        StringBuilder combined = new StringBuilder();
        for (ParsedChunk c : chunks) {
            combined.append(c.getContent());
        }
        assertEquals(text, combined.toString());
    }

    @Test
    void splitTextChineseMultiChunkConsistency() {
        String line = "这是一段中文内容用于测试分割功能是否正确。";
        String text = (line + "\n\n").repeat(20);
        text = trimRight(text, "\n");

        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(30, 5, List.of("\n\n", "\n", "。")));
        assertTrue(chunks.size() >= 2, "expected multiple chunks, got " + chunks.size());

        int[] textRunes = CodePoints.of(text);
        for (int i = 0; i < chunks.size(); i++) {
            ParsedChunk c = chunks.get(i);
            int contentRuneLen = CodePoints.len(c.getContent());
            int spanLen = c.getEnd() - c.getStart();
            assertEquals(contentRuneLen, spanLen,
                    "chunk[" + i + "]: End-Start must equal rune len of content");
            assertTrue(c.getStart() >= 0, "chunk[" + i + "]: negative Start");
            assertTrue(c.getEnd() <= textRunes.length, "chunk[" + i + "]: End exceeds total runes");
            assertEquals(CodePoints.str(textRunes, c.getStart(), c.getEnd()), c.getContent(),
                    "chunk[" + i + "]: rune slice must match content");
        }
    }

    @Test
    void splitTextOverlapNonNegativeStart() {
        String text = "中文测试内容，".repeat(50);
        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(20, 5, List.of("，")));
        for (int i = 0; i < chunks.size(); i++) {
            ParsedChunk c = chunks.get(i);
            assertTrue(c.getStart() >= 0, "chunk[" + i + "]: negative Start");
            assertTrue(c.getEnd() >= c.getStart(), "chunk[" + i + "]: End < Start");
        }
    }

    @Test
    void splitTextRecursiveSeparatorsNoOversizeChunks() {
        String body = "This is one fairly short line of text.\n".repeat(50);
        String text = "lead paragraph that is short.\n\n" + body;
        SplitterConfig cfg = cfg(300, 30, List.of("\n\n", "\n", ". "));
        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg);
        assertTrue(chunks.size() >= 2, "expected multiple chunks, got " + chunks.size());
        int maxAllowed = cfg.getChunkSize() * 3 / 2;
        for (int i = 0; i < chunks.size(); i++) {
            int l = CodePoints.len(chunks.get(i).getContent());
            assertTrue(l <= maxAllowed, "chunk " + i + " is " + l + " runes, > 1.5x ChunkSize");
        }
    }

    @Test
    void splitBySeparators() {
        record Case(String text, List<String> separators, int wantParts) {
        }
        List<Case> cases = List.of(
                new Case("a\n\nb\n\nc", List.of("\n\n"), 5),
                new Case("abc", List.of("\n"), 1),
                new Case("a\nb\nc", List.of("\n"), 5),
                new Case("", List.of("\n"), 1));
        for (Case tc : cases) {
            List<String> parts = LegacySplitter.splitBySeparators(tc.text(), tc.separators(), 0);
            assertEquals(tc.wantParts(), parts.size(),
                    "splitBySeparators(" + tc.text() + "): " + parts);
        }
    }

    @Test
    void findSemanticOverlapBoundaryPriorityThenEarliest() {
        record Case(String name, String text, String want) {
        }
        List<Case> cases = List.of(
                new Case("paragraph outranks earlier sentence and line",
                        "第一句。第二句\n普通换行后的内容\n\n最后一段", "最后一段"),
                new Case("earliest sentence wins within same priority",
                        "第一句。第二句。第三句", "第二句。第三句"),
                new Case("windows paragraph break", "第一段。\r\n\r\n第二段", "第二段"),
                new Case("line outranks earlier sentence", "第一句。第一行\n第二行", "第二行"),
                new Case("windows line break", "第一句。第一行\r\n第二行", "第二行"),
                new Case("chinese question mark", "第一句？第二句", "第二句"),
                new Case("chinese exclamation mark", "第一句！第二句", "第二句"),
                new Case("english period requires following space",
                        "First sentence. Second sentence", "Second sentence"),
                new Case("english question mark requires following space",
                        "Question? Answer", "Answer"),
                new Case("english exclamation mark requires following space",
                        "Warning! Continue", "Continue"));
        for (Case tc : cases) {
            LegacySplitter.BoundaryResult br = LegacySplitter.findSemanticOverlapBoundary(tc.text());
            assertTrue(br.ok(), tc.name() + ": expected semantic overlap boundary");
            int[] runes = CodePoints.of(tc.text());
            assertEquals(tc.want(), CodePoints.str(runes, br.end(), runes.length),
                    tc.name() + ": overlap tail");
        }
    }

    @Test
    void findSemanticOverlapBoundaryNoConfiguredSemanticSeparator() {
        for (String text : List.of(
                "没有任何语义分隔符的连续文本",
                "只有，逗号；分号：冒号",
                "version1.2 remains one unit",
                "address 192.168.1.1 remains one unit",
                "see https://ex.com?q=1&foo=bar")) {
            assertFalse(LegacySplitter.findSemanticOverlapBoundary(text).ok(),
                    "findSemanticOverlapBoundary(" + text + ") should find no boundary");
        }
    }

    @Test
    void findSemanticOverlapBoundaryIgnoresProtectedContent() {
        for (String text : List.of(
                "代码是 `fmt.Println(\"hello. world\")` 后续内容",
                "代码块 ```go\nfmt.Println(\"hello. world\")\n``` 后续内容",
                "公式 $$x. y$$ 后续内容")) {
            assertFalse(LegacySplitter.findSemanticOverlapBoundary(text).ok(),
                    "findSemanticOverlapBoundary(" + text + ") should ignore protected content");
        }
    }

    @Test
    void findSemanticOverlapBoundaryFiltersEligibilityBeforePriorityAndPosition() {
        record Case(String name, String text, int minEnd, String want) {
        }
        List<Case> cases = List.of(
                new Case("earlier ineligible sentence does not hide eligible sentence", "a。bc。XYZ", 4, "XYZ"),
                new Case("ineligible paragraph does not outrank eligible sentence", "\n\nx? tail", 3, "tail"));
        for (Case tc : cases) {
            LegacySplitter.BoundaryResult br =
                    LegacySplitter.findSemanticOverlapBoundaryEndingAtOrAfter(tc.text(), tc.minEnd());
            assertTrue(br.ok(), tc.name() + ": expected eligible boundary");
            int[] runes = CodePoints.of(tc.text());
            assertEquals(tc.want(), CodePoints.str(runes, br.end(), runes.length), tc.name());
        }
    }

    @Test
    void computeOverlapFindsBoundaryInsideLargeUnit() {
        String text = "abcdefgh。尾巴内容";
        LegacySplitter.SplitUnit unit = new LegacySplitter.SplitUnit(text, 100, 100 + CodePoints.len(text));

        LegacySplitter.OverlapResult ov = LegacySplitter.computeOverlap(List.of(unit), 8, 32, 8);
        assertEquals("尾巴内容", LegacySplitterUnitsText(ov.units()));
        assertEquals(CodePoints.len("尾巴内容"), ov.len());
        assertEquals(1, ov.units().size());
        assertEquals(109, ov.units().get(0).start);
        assertEquals(113, ov.units().get(0).end);
    }

    private static String LegacySplitterUnitsText(List<LegacySplitter.SplitUnit> units) {
        StringBuilder sb = new StringBuilder();
        for (LegacySplitter.SplitUnit u : units) {
            sb.append(u.text);
        }
        return sb.toString();
    }

    @Test
    void computeOverlapNoBoundaryMeansNoOverlap() {
        String text = "abcdefgh尾巴内容";
        LegacySplitter.SplitUnit unit = new LegacySplitter.SplitUnit(text, 0, CodePoints.len(text));
        LegacySplitter.OverlapResult ov = LegacySplitter.computeOverlap(List.of(unit), 8, 32, 8);
        assertEquals(0, ov.units().size());
        assertEquals(0, ov.len());
    }

    @Test
    void computeOverlapRespectsNextChunkCapacity() {
        String text = "abcdefgh。尾巴";
        LegacySplitter.SplitUnit unit = new LegacySplitter.SplitUnit(text, 0, CodePoints.len(text));
        LegacySplitter.OverlapResult ov = LegacySplitter.computeOverlap(List.of(unit), 10, 8, 5);
        assertEquals("尾巴", LegacySplitterUnitsText(ov.units()));
        assertTrue(ov.len() + 5 <= 8, "overlap plus next content exceeds chunk size");
    }

    @Test
    void computeOverlapLookbehindBoundaryEligibility() {
        record Case(String name, String text, String want) {
        }
        List<Case> cases = List.of(
                new Case("single rune separator ending at minus one is eligible", "abcd。WXYZ", "WXYZ"),
                new Case("longest separator ending at minus one is eligible", "\r\n\r\nWXYZ", "WXYZ"),
                new Case("separator ending before minus one is ineligible", "a。bcWXYZ", ""),
                new Case("separator crossing original window start is eligible", "abc. WXY", "WXY"));
        for (Case tc : cases) {
            LegacySplitter.SplitUnit unit =
                    new LegacySplitter.SplitUnit(tc.text(), 0, CodePoints.len(tc.text()));
            LegacySplitter.OverlapResult ov = LegacySplitter.computeOverlap(List.of(unit), 4, 20, 4);
            assertEquals(tc.want(), LegacySplitterUnitsText(ov.units()), tc.name());
            assertEquals(CodePoints.len(tc.want()), ov.len(), tc.name() + ": overlapLen");
            assertTrue(ov.len() <= 4, tc.name() + ": overlap exceeds configured limit");
        }
    }

    @Test
    void splitTextTableHeaderPrependedToChunks() {
        String text = "前面的文字\n\n"
                + "| 姓名 | 年龄 | 城市 |\n"
                + "| --- | --- | --- |\n"
                + "| 张三 | 25 | 北京 |\n"
                + "| 李四 | 30 | 上海 |\n"
                + "| 王五 | 28 | 广州 |\n"
                + "| 赵六 | 35 | 深圳 |\n"
                + "| 孙七 | 22 | 杭州 |\n"
                + "| 周八 | 40 | 成都 |\n"
                + "\n后面的文字";
        String tableHeader = "| 姓名 | 年龄 | 城市 |\n| --- | --- | --- |\n";

        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(60, 5, List.of("\n\n", "\n")));
        assertTrue(chunks.size() >= 3, "expected at least 3 chunks, got " + chunks.size());

        int headerPrependCount = 0;
        for (ParsedChunk c : chunks) {
            boolean hasLaterRow = c.getContent().contains("| 李四") || c.getContent().contains("| 王五")
                    || c.getContent().contains("| 赵六") || c.getContent().contains("| 孙七")
                    || c.getContent().contains("| 周八");
            if (hasLaterRow && !c.getContent().contains("| 张三")) {
                if (!c.getContent().startsWith(tableHeader)) {
                    fail("chunk (seq=" + c.getSeq() + ") has table rows but is missing prepended header:\n"
                            + c.getContent());
                } else {
                    headerPrependCount++;
                }
            }
        }
        assertTrue(headerPrependCount > 0, "expected at least one chunk with prepended table header");
    }

    @Test
    void headerTrackerBasicLifecycle() {
        HeaderTracker ht = new HeaderTracker();

        ht.update("Some regular text");
        assertEquals("", ht.getHeaders(), "expected no headers before table");

        ht.update("| A | B |\n| --- | --- |\n");
        assertFalse(ht.getHeaders().isEmpty(), "expected active header after table header unit");

        ht.update("| 1 | 2 |\n");
        assertFalse(ht.getHeaders().isEmpty(), "header should remain active during table rows");

        ht.update("\n");
        assertEquals("", ht.getHeaders(), "header should be cleared after empty line");

        ht.update("| X | Y |\n| --- | --- |\n");
        assertFalse(ht.getHeaders().isEmpty(), "expected new header to be tracked after previous table ended");
    }

    /** 位置不变式 + 原文可还原。 */
    @Test
    void splitTextRestoreTextWithTable() {
        String text = "这是文档前言部分的内容。\n\n"
                + "| 姓名 | 城市 |\n"
                + "| --- | --- |\n"
                + "| 张三 | 北京 |\n"
                + "| 李四 | 上海 |\n"
                + "| 王五 | 广州 |\n"
                + "| 赵六 | 深圳 |\n"
                + "| 孙七 | 杭州 |\n"
                + "| 周八 | 成都 |\n"
                + "| 吴九 | 武汉 |\n"
                + "| 郑十 | 南京 |\n"
                + "\n"
                + "这是表格之后的文字内容。\n"
                + "这里还有更多的普通段落。";

        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(80, 5, List.of("\n\n", "\n")));
        int[] textRunes = CodePoints.of(text);
        for (int i = 0; i < chunks.size(); i++) {
            ParsedChunk c = chunks.get(i);
            assertTrue(c.getStart() >= 0, "chunk[" + i + "]: Start < 0");
            assertTrue(c.getEnd() <= textRunes.length, "chunk[" + i + "]: End exceeds total runes");
            assertTrue(c.getEnd() >= c.getStart(), "chunk[" + i + "]: End < Start");
            // text[Start:End] 必须等于 Content 去掉前置表头后的部分
            int contentRuneLen = CodePoints.len(c.getContent());
            int headerLen = contentRuneLen - (c.getEnd() - c.getStart());
            assertTrue(headerLen >= 0, "chunk[" + i + "]: content rune len < span len");
            if (c.getEnd() <= textRunes.length) {
                String originalSlice = CodePoints.str(textRunes, c.getStart(), c.getEnd());
                String contentSuffix = CodePoints.str(CodePoints.of(c.getContent()), headerLen, contentRuneLen);
                assertEquals(originalSlice, contentSuffix, "chunk[" + i + "]: text slice != content suffix");
            }
        }
    }

    // ------------------------------------------------------------------
    // Chunker（策略入口）用例
    // ------------------------------------------------------------------

    @Test
    void splitEmptyText() {
        assertTrue(Chunker.split("", SplitterConfig.defaultConfig()).isEmpty());
    }

    @Test
    void splitLegacyStrategyMatchesSplitText() {
        String text = "Hello world.\n\n".repeat(30);
        SplitterConfig cfg = cfg(100, 20, List.of("\n\n"));
        cfg.setStrategy(Chunker.STRATEGY_LEGACY);
        List<ParsedChunk> a = Chunker.split(text, new SplitterConfig(cfg));
        List<ParsedChunk> b = LegacySplitter.splitText(text, cfg);
        assertEquals(b.size(), a.size(), "legacy strategy should match SplitText");
        for (int i = 0; i < a.size(); i++) {
            assertEquals(b.get(i).getContent(), a.get(i).getContent(), "chunk " + i + " differs");
        }
    }

    @Test
    void splitEmptyStrategyEqualsLegacy() {
        String text = "Sentence one. Sentence two.\n".repeat(20);
        SplitterConfig cfg1 = cfg(80, 10, List.of());
        List<ParsedChunk> a = Chunker.split(text, cfg1);
        SplitterConfig cfg2 = cfg(80, 10, List.of());
        cfg2.setStrategy(Chunker.STRATEGY_LEGACY);
        List<ParsedChunk> b = Chunker.split(text, cfg2);
        assertEquals(b.size(), a.size(), "empty Strategy should equal legacy");
    }

    @Test
    void splitHeadingStrategyKeepsDistinctTopLevelHeadings() {
        String doc = "# Intro\nshort intro.\n\n# Usage\nshort usage.\n\n# FAQ\nshort faq.";
        SplitterConfig cfg = cfg(500, 0, List.of());
        cfg.setStrategy(Chunker.STRATEGY_HEADING);
        List<ParsedChunk> chunks = Chunker.split(doc, cfg);
        assertEquals(3, chunks.size(), "expected one chunk per top-level heading");
        String[] headings = {"# Intro", "# Usage", "# FAQ"};
        for (int i = 0; i < headings.length; i++) {
            assertTrue(chunks.get(i).getContent().contains(headings[i]),
                    "chunk " + i + " should contain heading " + headings[i]);
        }
    }

    @Test
    void splitPreservesPositionInvariantAcrossTiers() {
        String headingTier = "# Top\nintro paragraph here.\n\n## Section A\nbody A here.\n\n"
                + "## Section B\nbody B here.\n\n## Section C\nbody C.";
        String heuristicTier = "Kapitel 1: Einleitung\n" + "Beispieltext. ".repeat(50)
                + "\n\n" + "Kapitel 2: Hauptteil\n" + "Mehr Text. ".repeat(50);
        String recursiveTier = "plain prose without structure. ".repeat(100);

        SplitterConfig cfg = cfg(300, 30, List.of("\n\n", "\n", "。", ". "));
        cfg.setStrategy(Chunker.STRATEGY_AUTO);

        for (String doc : List.of(headingTier, heuristicTier, recursiveTier)) {
            int[] runes = CodePoints.of(doc);
            List<ParsedChunk> chunks = Chunker.split(doc, new SplitterConfig(cfg));
            assertFalse(chunks.isEmpty(), "expected chunks");
            for (int i = 0; i < chunks.size(); i++) {
                ParsedChunk c = chunks.get(i);
                int contentRuneLen = CodePoints.len(c.getContent());
                int spanLen = c.getEnd() - c.getStart();
                assertEquals(contentRuneLen, spanLen, "chunk " + i + ": End-Start != content runes");
                assertTrue(c.getStart() >= 0 && c.getEnd() <= runes.length,
                        "chunk " + i + ": position out of range");
                assertEquals(CodePoints.str(runes, c.getStart(), c.getEnd()), c.getContent(),
                        "chunk " + i + ": runes[Start:End] differs from Content");
            }
        }
    }

    @Test
    void mergeBreadcrumbs() {
        record Case(String name, String parent, String child, String want) {
        }
        List<Case> cases = List.of(
                new Case("empty parent", "", "## Sub", "## Sub"),
                new Case("empty child", "# Top", "", "# Top"),
                new Case("both empty", "", "", ""),
                new Case("disjoint", "# Top", "## Other", "# Top\n## Other"),
                new Case("duplicate seam", "# Top\n## A", "## A\n### A1", "# Top\n## A\n### A1"),
                new Case("deep duplicate", "# Top", "# Top", "# Top"),
                new Case("only whitespace differs", "# Top\n## A", "  ## A  \n### A1", "# Top\n## A\n### A1"));
        for (Case tc : cases) {
            assertEquals(tc.want(), Chunker.mergeBreadcrumbs(tc.parent(), tc.child()),
                    tc.name() + ": mergeBreadcrumbs(" + tc.parent() + ", " + tc.child() + ")");
        }
    }

    @Test
    void splitParentChildLegacyStrategy() {
        String text = "This is a sentence. Another one.\n\n".repeat(50);
        SplitterConfig parentCfg = cfg(400, 40, List.of());
        parentCfg.setStrategy(Chunker.STRATEGY_LEGACY);
        SplitterConfig childCfg = cfg(100, 20, List.of());
        childCfg.setStrategy(Chunker.STRATEGY_LEGACY);
        Chunker.ParentChildResult res = Chunker.splitParentChild(text, parentCfg, childCfg);
        assertFalse(res.children().isEmpty(), "expected children chunks");
        for (int i = 0; i < res.children().size(); i++) {
            ParsedChunk c = res.children().get(i);
            if (c.getParentIndex() >= 0) {
                assertTrue(c.getParentIndex() < res.parents().size(),
                        "child[" + i + "] has invalid ParentIndex " + c.getParentIndex());
            }
        }
    }

    @Test
    void ensureDefaults() {
        SplitterConfig cfg = Chunker.ensureDefaults(new SplitterConfig());
        assertEquals(SplitterConfig.DEFAULT_CHUNK_SIZE, cfg.getChunkSize());
        assertEquals(SplitterConfig.DEFAULT_CHUNK_OVERLAP, cfg.getChunkOverlap());
        assertFalse(cfg.getSeparators().isEmpty(), "expected default separators");
    }

    /** 边界用例：chunk_size 小于 overlap 时 overlap 被截断为 chunk_size/2。 */
    @Test
    void ensureDefaultsCapsOverlapAtHalfChunkSize() {
        SplitterConfig cfg = cfg(20, 50, List.of("\n"));
        Chunker.ensureDefaults(cfg);
        assertEquals(10, cfg.getChunkOverlap(), "overlap > chunkSize/2 must be capped to chunkSize/2");
    }

    @Test
    void normalizeLineEndings() {
        assertEquals("first\nsecond\nthird\nfourth",
                TextNormalizer.normalizeLineEndings("first\r\nsecond\rthird\nfourth"));
    }

    @Test
    void deriveParentChildConfigsDefaultSizes() {
        SplitterConfig base = cfg(1000, 100, List.of("\n\n", "\n"));
        base.setStrategy(Chunker.STRATEGY_HEADING);
        Chunker.ParentChildConfigs pair = Chunker.deriveParentChildConfigs(base, 0, 0);
        assertEquals(4096, pair.parent().getChunkSize());
        assertEquals(384, pair.child().getChunkSize());
        assertEquals(Chunker.STRATEGY_HEADING, pair.parent().getStrategy());
        assertEquals(Chunker.STRATEGY_HEADING, pair.child().getStrategy());
        assertEquals(384 / 5, pair.child().getChunkOverlap());
    }

    @Test
    void validateChunks() {
        assertFalse(ChunkValidator.validate(List.of(), 1000, 500).ok(), "nil chunks should be invalid");

        List<ParsedChunk> singleLarge = List.of(chunkOf("a".repeat(5000)));
        assertFalse(ChunkValidator.validate(singleLarge, 5000, 500).ok(),
                "single 10x-too-large chunk should be invalid");

        List<ParsedChunk> reasonable = List.of(
                chunkOf("a".repeat(480)), chunkOf("b".repeat(510)), chunkOf("c".repeat(460)));
        assertTrue(ChunkValidator.validate(reasonable, 1500, 512).ok(),
                "reasonable chunks should validate");

        List<ParsedChunk> oversized = List.of(chunkOf("a".repeat(100)), chunkOf("b".repeat(5000)));
        assertFalse(ChunkValidator.validate(oversized, 5100, 1000).ok(),
                "chunk >2x size should be invalid");

        List<ParsedChunk> tinyTail = List.of(
                chunkOf("a".repeat(480)), chunkOf("b".repeat(510)), chunkOf("tail"));
        assertTrue(ChunkValidator.validate(tinyTail, 994, 512).ok(),
                "tiny last chunk should be tolerated");
    }

    private static ParsedChunk chunkOf(String content) {
        return new ParsedChunk(content, "", 0, 0, CodePoints.len(content));
    }

    // ------------------------------------------------------------------
    // HeadingSplitter 用例
    // ------------------------------------------------------------------

    @Test
    void splitByHeadingsBasicSections() {
        String body = "Lorem ipsum dolor sit amet consectetur adipiscing elit. ".repeat(4);
        String doc = "# Top\n" + body + "\n\n## Section A\n" + body
                + "\n\n## Section B\n" + body + "\n\n## Section C\n" + body;
        List<ParsedChunk> chunks = HeadingSplitter.splitByHeadings(doc, cfg(300, 0, List.of()), null);
        assertTrue(chunks.size() >= 3, "expected ≥3 chunks, got " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).getContextHeader().contains("# Top"),
                    "chunk " + i + " missing H1 in ContextHeader");
            assertTrue(chunks.get(i).embeddingContent().contains("# Top"),
                    "chunk " + i + " EmbeddingContent missing H1");
        }
        boolean found = false;
        for (ParsedChunk c : chunks) {
            if (c.getContent().contains("## Section B") && c.getContent().contains("Lorem ipsum")) {
                found = true;
            }
        }
        assertTrue(found, "no chunk contains Section B with its body");
    }

    @Test
    void splitByHeadingsFallsThroughForUnstructuredDoc() {
        String doc = "Just a plain paragraph without any headings at all in this text.";
        List<ParsedChunk> chunks = HeadingSplitter.splitByHeadings(doc, cfg(200, 0, List.of()), null);
        assertEquals(1, chunks.size(), "expected fallthrough single chunk");
    }

    @Test
    void splitByHeadingsCoalescesTinyAdjacentSections() {
        String doc = "# Install Log\n\n"
                + "## Docker镜像\n使用 daocloud 部署 v0.3.1。\n\n"
                + "## 前端老版本\n浏览器缓存了旧前端资源。\n\n"
                + "## 登录报错\nERROR: column missing.\n\n"
                + "## 解析失败\nembedding 表缺列。";
        List<ParsedChunk> chunks = HeadingSplitter.splitByHeadings(doc, cfg(500, 0, List.of()), null);
        assertFalse(chunks.isEmpty(), "expected at least one chunk");
        assertTrue(chunks.size() < 5, "expected coalesce to produce <5 chunks, got " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).getContextHeader().contains("# Install Log"),
                    "chunk " + i + " missing parent H1 in breadcrumb");
        }
        for (String h : List.of("## Docker镜像", "## 前端老版本", "## 登录报错", "## 解析失败")) {
            boolean seen = false;
            for (ParsedChunk c : chunks) {
                if (c.getContent().contains(h)) {
                    seen = true;
                    break;
                }
            }
            assertTrue(seen, "merged chunks should still contain heading " + h);
        }
    }

    @Test
    void splitByHeadingsDoesNotCoalesceDistinctTopLevelHeadings() {
        String doc = "# Intro\nshort intro.\n\n# Usage\nshort usage.\n\n# FAQ\nshort faq.";
        List<ParsedChunk> chunks = HeadingSplitter.splitByHeadings(doc, cfg(500, 0, List.of()), null);
        assertEquals(3, chunks.size(), "expected one chunk per top-level heading");
        String[] headings = {"# Intro", "# Usage", "# FAQ"};
        for (int i = 0; i < headings.length; i++) {
            assertTrue(chunks.get(i).getContent().contains(headings[i]),
                    "chunk " + i + " should contain heading " + headings[i]);
        }
    }

    @Test
    void splitByHeadingsDeepSubHeadingInLargeSection() {
        String filler = "clause body sentence that pads the section out. ".repeat(20);
        String doc = "# Standard XYZ\n"
                + "## Preface\n" + filler + "\n\n"
                + "## 5 Classification\n" + filler + "\n\n"
                + "### 5.9 Grade Nine\n" + filler + "\n\n"
                + "#### 5.9.2 Clause Series\n" + filler + "\n\n"
                + "the item users search for is MARKER_ITEM_23 graded here.\n\n"
                + "## Appendix A\n" + filler + "\n\n"
                + "## Appendix B\n" + filler + "\n\n"
                + "## Appendix C\n" + filler;

        List<ParsedChunk> chunks = HeadingSplitter.splitByHeadings(
                doc, cfg(300, 0, List.of(". ")), null);

        ParsedChunk marker = null;
        for (ParsedChunk c : chunks) {
            if (c.getContent().contains("MARKER_ITEM_23")) {
                marker = c;
                break;
            }
        }
        assertTrue(marker != null, "no chunk contains the marker line");
        assertTrue(marker.getContextHeader().contains("#### 5.9.2 Clause Series"),
                "marker chunk lost its deep sub-heading: " + marker.getContextHeader());
        assertTrue(marker.getContextHeader().contains("## 5 Classification"),
                "marker chunk lost its section heading: " + marker.getContextHeader());
    }

    // ------------------------------------------------------------------
    // HeuristicSplitter 用例
    // ------------------------------------------------------------------

    @Test
    void splitByHeuristicsFormFeedBoundary() {
        String doc = "page one body text. ".repeat(30) + "\f" + "page two body. ".repeat(30);
        List<ParsedChunk> chunks = HeuristicSplitter.splitByHeuristics(
                doc, cfg(400, 20, List.of(". ")), null);
        assertTrue(chunks.size() >= 2, "form feed should produce ≥2 chunks, got " + chunks.size());
    }

    @Test
    void splitByHeuristicsNumberedSections() {
        String body = "body sentence. ".repeat(8);
        String doc = "1. Introduction\n" + body + "\n\n2. Methods\n" + body + "\n\n3. Results\n" + body;
        List<ParsedChunk> chunks = HeuristicSplitter.splitByHeuristics(
                doc, cfg(200, 20, List.of(". ")), null);
        assertTrue(chunks.size() >= 2, "numbered sections should split: got " + chunks.size());
    }

    @Test
    void splitByHeuristicsGermanChapterMarkers() {
        String body = "Beispieltext. ".repeat(10);
        String doc = "Kapitel 1: Einführung\n" + body + "\n\nKapitel 2: Hauptteil\n" + body;
        List<ParsedChunk> chunks = HeuristicSplitter.splitByHeuristics(
                doc, cfg(200, 20, List.of(". ")), null);
        assertTrue(chunks.size() >= 2, "German chapter markers should split: got " + chunks.size());
    }

    @Test
    void splitByHeuristicsChineseChapterMarkers() {
        String body = "内容内容内容。".repeat(60);
        String doc = "第一章 引言\n" + body + "\n\n第二章 方法\n" + body;
        SplitterConfig cfg = cfg(200, 20, List.of("。"));
        cfg.setLanguages(List.of(Tokens.LANG_CHINESE));
        List<ParsedChunk> chunks = HeuristicSplitter.splitByHeuristics(doc, cfg, null);
        assertTrue(chunks.size() >= 2, "Chinese chapter markers should split: got " + chunks.size());
    }

    @Test
    void splitByHeuristicsFallsThroughForUnstructuredDoc() {
        String doc = "plain prose without structure. ".repeat(5);
        List<ParsedChunk> chunks = HeuristicSplitter.splitByHeuristics(
                doc, cfg(1000, 20, List.of()), null);
        assertEquals(1, chunks.size(), "unstructured short doc should be one chunk");
    }

    @Test
    void splitByHeuristicsOverlapActuallyOverlaps() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("\n\n");
            sb.append((char) ('0' + i % 10));
            sb.append(". ");
            sb.append("alpha beta gamma. ".repeat(4)); // ~72 chars / section
        }
        String doc = sb.toString();

        List<ParsedChunk> chunks = HeuristicSplitter.splitByHeuristics(
                doc, cfg(200, 80, List.of(". ")), null);
        assertTrue(chunks.size() >= 2, "need >=2 chunks to test overlap");

        boolean saw = false;
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).getContent().strip();
            String cur = chunks.get(i).getContent().strip();
            int match = 0;
            int maxScan = Math.min(prev.length(), cur.length());
            for (int n = 1; n <= maxScan; n++) {
                if (cur.startsWith(prev.substring(prev.length() - n))) {
                    match = n;
                }
            }
            if (match >= 20) {
                saw = true;
                break;
            }
        }
        assertTrue(saw, "expected at least one chunk pair to overlap by >=20 chars");
    }

    @Test
    void splitByHeuristicsDropsBoundariesInsideProtectedSpans() {
        String body = "filler. ".repeat(30);
        String doc = body + "\n\n$$\nx = 1\n1. equation step one\ny = 2\n$$\n\n" + body;

        List<HeuristicSplitter.Boundary> bounds = HeuristicSplitter.findHeuristicBoundaries(doc, List.of());
        List<LegacySplitter.RuneSpan> prot = LegacySplitter.protectedSpansRune(
                doc, LegacySplitter.protectedSpans(doc));
        assertFalse(prot.isEmpty(), "expected protected spans for doc");
        List<HeuristicSplitter.Boundary> filtered = HeuristicSplitter.dropBoundsInsideSpans(bounds, prot);
        for (HeuristicSplitter.Boundary b : filtered) {
            for (LegacySplitter.RuneSpan s : prot) {
                assertFalse(b.runeStart() > s.start() && b.runeStart() < s.end(),
                        "boundary " + b.runeStart() + " still inside protected span [" + s.start() + "," + s.end() + ")");
            }
        }
        assertTrue(filtered.size() < bounds.size(),
                "filter removed nothing: before=" + bounds.size() + " after=" + filtered.size());
    }

    // ------------------------------------------------------------------
    // 分隔符未命中兜底：默认分隔符对一个无任何分隔符的长文本仍须切分
    // ------------------------------------------------------------------

    /**
     * 分隔符全部不命中时的行为：单单元 2000 runes < 7500 绝对上限，legacy 不进一步强切，产出单块 [0,2000)。
     * 该输出在策略链中会被 ValidateChunks 以 "chunk exceeds 2x target size" 拒绝后兜底返回。
     */
    @Test
    void splitTextNoSeparatorHitFallback() {
        String text = "a".repeat(2000);
        List<ParsedChunk> chunks = LegacySplitter.splitText(text, cfg(512, 80, List.of("\n\n", "\n", "。")));
        assertEquals(1, chunks.size(), "Go SplitText yields a single chunk below the 7500 absolute max");
        assertEquals(0, chunks.get(0).getStart());
        assertEquals(2000, chunks.get(0).getEnd());
        assertEquals(text, chunks.get(0).getContent());
        // 策略链校验视角：超过 2x chunkSize → 无效
        assertFalse(ChunkValidator.validate(chunks, 2000, 512).ok());
    }

    private static String trimRight(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }
}
