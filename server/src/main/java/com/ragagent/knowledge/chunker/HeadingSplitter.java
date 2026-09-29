package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Tier 1：Markdown 标题感知切分。
 * <p>有正常标题结构的文档按标题边界切分，每个 chunk 通过 ContextHeader 携带
 * 活动标题面包屑（如 "# Chapter 1\n## Section 1.2"）。无可用标题结构或标题切分
 * 只产生单节时，回退到 legacy 切分器。</p>
 */
public final class HeadingSplitter {

    static final class HeadingBoundary {
        int runeStart;
        String line;

        HeadingBoundary(int runeStart, String line) {
            this.runeStart = runeStart;
            this.line = line;
        }
    }

    record SectionBreadcrumb(int runeStart, String breadcrumb) {
    }

    private HeadingSplitter() {
    }

    /**
     * @param profile 可为 null（按需自行计算）
     */
    public static List<ParsedChunk> splitByHeadings(String text, SplitterConfig cfg,
            DocumentProfiler.DocProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (profile == null) {
            profile = DocumentProfiler.profileDocument(text);
        }
        int primaryLevel = DocumentProfiler.dominantHeadingLevel(profile);
        if (primaryLevel == 0) {
            return LegacySplitter.splitText(text, cfg);
        }

        List<HeadingBoundary> bounds = findHeadingBoundaries(text, primaryLevel);
        if (bounds.size() <= 1) {
            return LegacySplitter.splitText(text, cfg);
        }

        int[] runes = Runes.of(text);
        HeadingHierarchy hierarchy = new HeadingHierarchy();

        // 预走每个标题（不只主层级），让层级反映每节开始的完整嵌套上下文
        List<ParsedChunk> out = new ArrayList<>();
        int seq = 0;

        for (int i = 0; i < bounds.size(); i++) {
            HeadingBoundary b = bounds.get(i);
            int endRune = runes.length;
            if (i + 1 < bounds.size()) {
                endRune = bounds.get(i + 1).runeStart;
            }
            if (!b.line.isEmpty()) {
                hierarchy.observe(b.line);
            }
            String breadcrumb = hierarchy.breadcrumbWithHashes();
            HeadingHierarchy sectionStart = hierarchy.copy();
            observeSubHeadings(Runes.str(runes, b.runeStart, endRune), primaryLevel, hierarchy);

            int secLen = endRune - b.runeStart;
            if (secLen == 0) {
                continue;
            }

            String sectionContent = Runes.str(runes, b.runeStart, endRune);
            int bcLen = Runes.len(breadcrumb);
            // 单 chunk 节：原样输出，面包屑走 ContextHeader（不占用 Content，保位置不变式）
            if (bcLen + 2 + secLen <= cfg.getChunkSize()) {
                ParsedChunk c = new ParsedChunk(sectionContent, breadcrumb, seq, b.runeStart, endRune);
                out.add(c);
                seq++;
                continue;
            }

            // 大节：内部细分交给 legacy；每个子 chunk 携带其开始处最深活动标题的面包屑
            int[] sectionRunes = new int[secLen];
            System.arraycopy(runes, b.runeStart, sectionRunes, 0, secLen);
            List<SectionBreadcrumb> subBreadcrumbs = sectionBreadcrumbs(sectionRunes, primaryLevel, sectionStart);
            List<ParsedChunk> subChunks = LegacySplitter.splitText(sectionContent, cfg);
            for (ParsedChunk sub : subChunks) {
                ParsedChunk c = new ParsedChunk();
                c.setContent(sub.getContent());
                c.setContextHeader(breadcrumbAtOffset(subBreadcrumbs, sub.getStart(), breadcrumb));
                c.setSeq(seq);
                c.setStart(b.runeStart + sub.getStart());
                c.setEnd(b.runeStart + sub.getEnd());
                out.add(c);
                seq++;
            }
        }

        return coalesceTinyChunks(out, cfg.getChunkSize());
    }

    /**
     * 避免 FAQ / 安装日志类文档触发校验器 "too many tiny chunks" 一路回退到 legacy。
     * 只在 cur.End == next.Start 时合并（legacy 子 chunk 因 overlap 可能不邻接，天然跳过）。
     */
    static List<ParsedChunk> coalesceTinyChunks(List<ParsedChunk> in, int chunkSize) {
        if (in.size() <= 1 || chunkSize <= 0) {
            return in;
        }
        int target = chunkSize / 2;
        if (target < 200) {
            target = 200;
        }

        List<ParsedChunk> out = new ArrayList<>(in.size());
        ParsedChunk cur = in.get(0);
        int curLen = Runes.len(cur.getContent());

        for (int i = 1; i < in.size(); i++) {
            ParsedChunk next = in.get(i);
            int nextLen = Runes.len(next.getContent());
            String sharedHeader = commonHeadingPrefix(cur.getContextHeader(), next.getContextHeader());
            // 邻接 + 当前仍小 + 合并不超预算 → 合并
            if (!sharedHeader.isEmpty() && cur.getEnd() == next.getStart()
                    && curLen < target && curLen + nextLen <= chunkSize) {
                cur.setContent(cur.getContent() + next.getContent());
                cur.setContextHeader(sharedHeader);
                cur.setEnd(next.getEnd());
                curLen += nextLen;
                continue;
            }
            out.add(cur);
            cur = next;
            curLen = nextLen;
        }
        out.add(cur);

        // 重排序号：下游期望 Seq 是 0..N-1 稠密区间
        for (int i = 0; i < out.size(); i++) {
            out.get(i).setSeq(i);
        }
        return out;
    }

    /**
     * 最长公共前缀；行内部分截断会破坏面包屑，故只按整行比较。
     */
    static String commonHeadingPrefix(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        String[] la = a.split("\n", -1);
        String[] lb = b.split("\n", -1);
        int n = Math.min(la.length, lb.length);
        int common = 0;
        for (int i = 0; i < n; i++) {
            if (!la[i].equals(lb[i])) {
                break;
            }
            common = i + 1;
        }
        if (common == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < common; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(la[i]);
        }
        return sb.toString();
    }

    /**
     * 每个围栏代码块之外、层级 ≤ primaryLevel 的标题一个边界。
     */
    static List<HeadingBoundary> findHeadingBoundaries(String text, int primaryLevel) {
        List<HeadingBoundary> bounds = new ArrayList<>();
        bounds.add(new HeadingBoundary(0, ""));
        if (text.isEmpty()) {
            return bounds;
        }

        int pos = 0;
        boolean inFence = false;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                pos += Runes.len(line);
                if (i < lines.length - 1) {
                    pos++; // 换行
                }
                continue;
            }
            if (!inFence) {
                Matcher m = ChunkPatterns.MARKDOWN_HEADING.matcher(line);
                if (m.matches()) {
                    int level = m.group(1).length();
                    if (level >= 1 && level <= primaryLevel && pos > 0) {
                        bounds.add(new HeadingBoundary(pos, line));
                    }
                    if (level >= 1 && level <= primaryLevel && pos == 0) {
                        // 首行即标题 —— 替换前置边界的 line
                        bounds.get(0).line = line;
                    }
                }
            }
            pos += Runes.len(line);
            if (i < lines.length - 1) {
                pos++; // strings.Split 去掉的 \n
            }
        }
        return bounds;
    }

    static void observeSubHeadings(String sectionText, int primaryLevel, HeadingHierarchy h) {
        if (sectionText.isEmpty()) {
            return;
        }
        boolean inFence = false;
        for (String line : sectionText.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher m = ChunkPatterns.MARKDOWN_HEADING.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int level = m.group(1).length();
            if (level > primaryLevel) {
                h.observe(line);
            }
        }
    }

    /**
     * rune 偏移与面包屑；返回序列以偏移 0 的种子面包屑开头。
     */
    static List<SectionBreadcrumb> sectionBreadcrumbs(int[] sectionRunes, int primaryLevel,
            HeadingHierarchy seed) {
        HeadingHierarchy h = seed.copy();
        List<SectionBreadcrumb> result = new ArrayList<>();
        result.add(new SectionBreadcrumb(0, h.breadcrumbWithHashes()));
        int pos = 0;
        boolean inFence = false;
        String text = new String(sectionRunes, 0, sectionRunes.length);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                pos += Runes.len(line);
                if (i < lines.length - 1) {
                    pos++;
                }
                continue;
            }
            if (!inFence) {
                Matcher m = ChunkPatterns.MARKDOWN_HEADING.matcher(line);
                if (m.matches() && m.group(1).length() > primaryLevel) {
                    h.observe(line);
                    result.add(new SectionBreadcrumb(pos, h.breadcrumbWithHashes()));
                }
            }
            pos += Runes.len(line);
            if (i < lines.length - 1) {
                pos++;
            }
        }
        return result;
    }

    static String breadcrumbAtOffset(List<SectionBreadcrumb> bcs, int offset, String fallback) {
        String bc = fallback;
        for (SectionBreadcrumb e : bcs) {
            if (e.runeStart() > offset) {
                break;
            }
            bc = e.breadcrumb();
        }
        return bc;
    }
}
