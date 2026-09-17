package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 文档画像与策略选择（对照 Go internal/infrastructure/chunker/profiler.go）。
 *
 * <p>对文档单次扫描收集结构信号，驱动 chunking 层级选择（heading 感知 /
 * heuristic 边界 / recursive）。profiling 成本低（几次正则扫描 + rune 计数）。</p>
 */
public final class DocumentProfiler {

    /** chunking 层级（对照 Go StrategyTier，profiler.go:210）。 */
    public enum StrategyTier {
        HEADING, HEURISTIC, LEGACY
    }

    /** 文档级信号（对照 Go DocProfile，profiler.go:17）。 */
    public static final class DocProfile {
        public int totalChars;
        public int totalLines;
        public double avgLineLen;
        public double stdLineLen;

        /** 层级（1..6）→ 数量。 */
        public final Map<Integer, Integer> mdHeadingCounts = new HashMap<>();
        public int mdHeadingTotal;

        public int numberedSectionCount;
        public int allCapsShortLineCount;
        public int blankParagraphBreaks;
        public int formFeedCount;
        public int visualSepCount;
        public int germanChapterCount;
        public int englishChapterCount;
        public int chineseChapterCount;
        public int repeatedFooterCount;

        public boolean hasTables;
        public boolean hasCode;
        public double codeRatio;

        public List<String> detectedLangs = new ArrayList<>();
    }

    private DocumentProfiler() {
    }

    /** 对照 Go HeadingDensity（profiler.go:48）。 */
    static double headingDensity(DocProfile p) {
        if (p.totalLines == 0) {
            return 0;
        }
        return (double) p.mdHeadingTotal / p.totalLines;
    }

    /**
     * 主导标题层级（对照 Go DominantHeadingLevel，profiler.go:63）：
     * 1. 出现 ≥3 次的最浅层级（真正的结构骨架）；
     * 2. 否则取出现过 ≥1 次的最深层级（给小文档更细的边界）。
     *
     * @return 0 表示无 Markdown 标题。
     */
    public static int dominantHeadingLevel(DocProfile p) {
        if (p.mdHeadingTotal == 0) {
            return 0;
        }
        for (int level = 1; level <= 6; level++) {
            if (p.mdHeadingCounts.getOrDefault(level, 0) >= 3) {
                return level;
            }
        }
        for (int level = 6; level >= 1; level--) {
            if (p.mdHeadingCounts.getOrDefault(level, 0) > 0) {
                return level;
            }
        }
        return 0;
    }

    /** 对照 Go HeuristicMarkerTotal（profiler.go:81）。 */
    static int heuristicMarkerTotal(DocProfile p) {
        return p.numberedSectionCount + p.germanChapterCount + p.englishChapterCount
                + p.chineseChapterCount + p.allCapsShortLineCount + p.visualSepCount + p.formFeedCount;
    }

    /** 对照 Go ProfileDocument（profiler.go:88）。 */
    public static DocProfile profileDocument(String text) {
        DocProfile p = new DocProfile();
        if (text == null || text.isEmpty()) {
            return p;
        }

        p.totalChars = Runes.len(text);
        p.formFeedCount = Runes.count(text, "\f");

        String[] lines = text.split("\n", -1);
        p.totalLines = lines.length;

        // 第一遍：逐行标记 + 行长统计
        List<Double> lengths = new ArrayList<>();
        boolean inFence = false;
        int codeChars = 0;
        for (String line : lines) {
            String trimmed = line.strip();

            // 围栏代码状态切换：用 3-反引号前缀检测而非完整正则
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                p.hasCode = true;
                continue;
            }
            if (inFence) {
                codeChars += Runes.len(line);
                continue;
            }

            lengths.add((double) Runes.len(line));

            if (matchHeading(line, p.mdHeadingCounts)) {
                p.mdHeadingTotal++;
                continue;
            }
            if (ChunkPatterns.NUMBERED_SECTION.matcher(line).find()) {
                p.numberedSectionCount++;
            }
            if (ChunkPatterns.GERMAN_CHAPTER.matcher(line).find()) {
                p.germanChapterCount++;
            }
            if (ChunkPatterns.ENGLISH_CHAPTER.matcher(line).find()) {
                p.englishChapterCount++;
            }
            if (ChunkPatterns.CHINESE_CHAPTER.matcher(line).find()) {
                p.chineseChapterCount++;
            }
            if (ChunkPatterns.ALL_CAPS_HEADING.matcher(line).find()) {
                p.allCapsShortLineCount++;
            }
            if (ChunkPatterns.VISUAL_SEPARATOR.matcher(line).find()) {
                p.visualSepCount++;
            }
            if (ChunkPatterns.PAGE_FOOTER.matcher(line).find()) {
                p.repeatedFooterCount++;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                p.hasTables = true;
            }
        }

        if (!lengths.isEmpty()) {
            double sum = 0;
            for (double l : lengths) {
                sum += l;
            }
            p.avgLineLen = sum / lengths.size();
            double variance = 0;
            for (double l : lengths) {
                double d = l - p.avgLineLen;
                variance += d * d;
            }
            variance /= lengths.size();
            p.stdLineLen = Math.sqrt(variance);
        }

        if (p.totalChars > 0) {
            p.codeRatio = (double) codeChars / p.totalChars;
        }

        p.blankParagraphBreaks = Runes.count(text, "\n\n\n");

        // 语言检测取样，避免大输入 O(N) 扫描（对照 profiler.go:178；Go 按字节截断，此处按 char，语义一致）
        String sample = text;
        if (sample.length() > 4096) {
            sample = sample.substring(0, 4096);
        }
        String lang = Tokens.detectLanguage(sample);
        p.detectedLangs = new ArrayList<>();
        if (Tokens.LANG_MIXED.equals(lang)) {
            // 提供全部三种供下游模式选择
            p.detectedLangs.add(Tokens.LANG_ENGLISH);
            p.detectedLangs.add(Tokens.LANG_GERMAN);
            p.detectedLangs.add(Tokens.LANG_CHINESE);
        } else {
            p.detectedLangs.add(lang);
        }
        return p;
    }

    /** 对照 Go matchHeading（profiler.go:194）。 */
    private static boolean matchHeading(String line, Map<Integer, Integer> counts) {
        Matcher m = ChunkPatterns.MARKDOWN_HEADING.matcher(line);
        if (!m.matches()) {
            return false;
        }
        int level = m.group(1).length();
        if (level < 1 || level > 6) {
            return false;
        }
        counts.merge(level, 1, Integer::sum);
        return true;
    }

    /**
     * 返回该文档应尝试的有序层级链（对照 Go SelectStrategy，profiler.go:220）。
     * 首层是主选择，后续是 ValidateChunks 拒绝后的回退；legacy 永远兜底。
     */
    public static List<StrategyTier> selectStrategy(DocProfile p) {
        List<StrategyTier> chain = new ArrayList<>();
        if (p == null) {
            chain.add(StrategyTier.LEGACY);
            return chain;
        }

        // Tier 1 候选：Markdown 标题感知
        if (p.mdHeadingTotal >= 3 && headingDensity(p) > 0.005 && dominantHeadingLevel(p) > 0) {
            chain.add(StrategyTier.HEADING);
        }

        // Tier 2 候选：启发式边界检测
        if (heuristicMarkerTotal(p) >= 5 || p.formFeedCount > 0
                || p.germanChapterCount + p.englishChapterCount + p.chineseChapterCount > 0) {
            chain.add(StrategyTier.HEURISTIC);
        }

        chain.add(StrategyTier.LEGACY);
        return chain;
    }
}
