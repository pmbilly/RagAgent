package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.IntPredicate;

/**
 * Tier 3 legacy（递归字符）切分器。
 * <p>递归文本切分：先找保护区域
 * （LaTeX / 图片 / 链接 / 表格 / 代码块），其余文本按分隔符优先级递归切分为
 * splitUnit，最后带 overlap 地合并为 chunk，并在表格边界前置活动表头。</p>
 */
public final class LegacySplitter {

    record CharSpan(int start, int end) {
    }

    /** rune 偏移的 span。 */
    record RuneSpan(int start, int end) {
    }

    static final class SplitUnit {
        String text;
        int start;
        int end;

        SplitUnit(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private static final List<Pattern> PROTECTED_PATTERNS = List.of(
            Pattern.compile("(?s)\\$\\$.*?\\$\\$"),                                    // LaTeX 块级公式
            Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)"),                             // Markdown 图片
            Pattern.compile("\\[[^\\]]*\\]\\([^)]+\\)"),                              // Markdown 链接
            Pattern.compile("(?m)[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|[\\r\\n]+"), // 表头+分隔行
            Pattern.compile("(?m)[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+"),                  // 表格行
            Pattern.compile("(?s)```(?:\\w+)?[\\r\\n].*?```"),                        // 围栏代码块
            Pattern.compile("`[^`\\r\\n]+`"));                                        // 行内代码

    /** 语义 overlap 回察看长 = 最长分隔符 "\r\n\r\n"。 */
    private static final int SEMANTIC_OVERLAP_LOOKBEHIND = 4;

    private static final ConcurrentHashMap<String, Pattern> SEP_PATTERN_CACHE = new ConcurrentHashMap<>();

    private LegacySplitter() {
    }

    static List<CharSpan> protectedSpans(String text) {
        List<CharSpan> all = new ArrayList<>();
        for (Pattern pat : PROTECTED_PATTERNS) {
            Matcher m = pat.matcher(text);
            while (m.find()) {
                if (m.end() - m.start() > 0) {
                    all.add(new CharSpan(m.start(), m.end()));
                }
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        // 排序：start 升序，等 start 时长度降序
        all.sort(Comparator.comparingInt((CharSpan s) -> s.start())
                .thenComparing(Comparator.comparingInt((CharSpan s) -> s.end() - s.start()).reversed()));
        List<CharSpan> result = new ArrayList<>();
        int lastEnd = 0;
        for (CharSpan s : all) {
            if (s.start() >= lastEnd) {
                result.add(s);
                lastEnd = s.end();
            }
        }
        return result;
    }

    static List<RuneSpan> protectedSpansRune(String text, List<CharSpan> charSpans) {
        if (charSpans.isEmpty()) {
            return List.of();
        }
        List<RuneSpan> out = new ArrayList<>(charSpans.size());
        for (CharSpan s : charSpans) {
            out.add(new RuneSpan(CodePoints.runeIndexAtChar(text, s.start()), CodePoints.runeIndexAtChar(text, s.end())));
        }
        return out;
    }

    /**
     * 某分隔符切出的片段仍大于 chunkSize 时，用剩余（低优先级）分隔符在该片段内递归。
     * chunkSize == 0 关闭大小守卫。
     */
    static List<String> splitBySeparators(String text, List<String> separators, int chunkSize) {
        if (text.isEmpty() || separators.isEmpty()) {
            return List.of(text);
        }
        if (chunkSize > 0 && CodePoints.len(text) <= chunkSize) {
            return List.of(text);
        }

        for (int i = 0; i < separators.size(); i++) {
            String sep = separators.get(i);
            if (sep.isEmpty()) {
                continue;
            }
            Pattern re = SEP_PATTERN_CACHE.computeIfAbsent(
                    sep, s -> Pattern.compile("(" + Pattern.quote(s) + ")"));
            Matcher m = re.matcher(text);
            List<String> splits = new ArrayList<>();
            List<String> matches = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                splits.add(text.substring(last, m.start()));
                matches.add(m.group());
                last = m.end();
            }
            if (matches.isEmpty()) {
                continue;
            }
            splits.add(text.substring(last));

            List<String> pieces = new ArrayList<>();
            for (int j = 0; j < splits.size(); j++) {
                if (!splits.get(j).isEmpty()) {
                    pieces.add(splits.get(j));
                }
                if (j < matches.size() && !matches.get(j).isEmpty()) {
                    pieces.add(matches.get(j));
                }
            }
            if (pieces.size() <= 1) {
                continue;
            }

            List<String> out = new ArrayList<>();
            List<String> remaining = separators.subList(i + 1, separators.size());
            for (String p : pieces) {
                if (chunkSize > 0 && CodePoints.len(p) > chunkSize && !remaining.isEmpty()) {
                    out.addAll(splitBySeparators(p, remaining, chunkSize));
                } else {
                    out.add(p);
                }
            }
            return out;
        }
        return List.of(text);
    }

    static List<SplitUnit> buildUnitsWithProtection(String text, List<CharSpan> protectedSpans,
            List<String> separators, int chunkSize) {
        final int maxProtectedSize = 7500; // 保护单元上限（留余量给标题等）

        List<SplitUnit> units = new ArrayList<>();
        int charPos = 0;
        int runePos = 0;

        for (CharSpan p : protectedSpans) {
            if (p.start() > charPos) {
                String pre = text.substring(charPos, p.start());
                List<String> parts = splitBySeparators(pre, separators, chunkSize);
                int runeOffset = runePos;
                for (String part : parts) {
                    int partRuneLen = CodePoints.len(part);
                    units.add(new SplitUnit(part, runeOffset, runeOffset + partRuneLen));
                    runeOffset += partRuneLen;
                }
                runePos += CodePoints.len(pre);
            }

            String protText = text.substring(p.start(), p.end());
            int protRuneLen = CodePoints.len(protText);

            // 保护内容过大时强制切分，防止下游（embedding API）拿到超大 chunk
            if (protRuneLen > maxProtectedSize) {
                int[] runes = CodePoints.of(protText);
                int offset = 0;
                while (offset < runes.length) {
                    int chunkEnd = offset + maxProtectedSize;
                    if (chunkEnd > runes.length) {
                        chunkEnd = runes.length;
                    } else {
                        // 优先在换行或空格处断开
                        for (int i = chunkEnd - 1; i > offset && i > chunkEnd - 200; i--) {
                            if (runes[i] == '\n' || runes[i] == ' ') {
                                chunkEnd = i + 1;
                                break;
                            }
                        }
                    }
                    units.add(new SplitUnit(CodePoints.str(runes, offset, chunkEnd),
                            runePos + offset, runePos + chunkEnd));
                    offset = chunkEnd;
                }
            } else {
                units.add(new SplitUnit(protText, runePos, runePos + protRuneLen));
            }
            runePos += protRuneLen;
            charPos = p.end();
        }

        if (charPos < text.length()) {
            String remaining = text.substring(charPos);
            List<String> parts = splitBySeparators(remaining, separators, chunkSize);
            int runeOffset = runePos;
            for (String part : parts) {
                int partRuneLen = CodePoints.len(part);
                units.add(new SplitUnit(part, runeOffset, runeOffset + partRuneLen));
                runeOffset += partRuneLen;
            }
        }
        return units;
    }

    /**
     * 这是策略链的最终兜底（Tier 3 legacy）。
     */
    public static List<ParsedChunk> splitText(String text, SplitterConfig cfg) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        int chunkSize = cfg.getChunkSize();
        int chunkOverlap = cfg.getChunkOverlap();
        List<String> separators = cfg.getSeparators();

        if (chunkSize <= 0) {
            chunkSize = 512;
        }
        if (chunkOverlap < 0) {
            chunkOverlap = 0;
        }

        List<CharSpan> protectedSpans = protectedSpans(text);
        List<SplitUnit> units = buildUnitsWithProtection(text, protectedSpans, separators, chunkSize);
        return mergeUnits(units, chunkSize, chunkOverlap);
    }

    /**
     * 活动上下文表头（如 Markdown 表头）会前置到新 chunk，使每个 chunk 自带表头上下文。
     */
    static List<ParsedChunk> mergeUnits(List<SplitUnit> units, int chunkSize, int chunkOverlap) {
        if (units.isEmpty()) {
            return List.of();
        }

        final int absoluteMaxSize = 7500;

        HeaderTracker ht = new HeaderTracker();

        List<ParsedChunk> chunks = new ArrayList<>();
        List<SplitUnit> current = new ArrayList<>();
        int curLen = 0;

        for (SplitUnit u : units) {
            int uLen = CodePoints.len(u.text);

            // 单个单元超过绝对上限 → 进一步强制切分
            if (uLen > absoluteMaxSize) {
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, chunks.size()));
                    current = new ArrayList<>();
                    curLen = 0;
                }

                // 超大单元也更新表头状态
                ht.update(u.text);

                int[] runes = CodePoints.of(u.text);
                int offset = 0;
                while (offset < runes.length) {
                    int chunkEnd = offset + absoluteMaxSize;
                    if (chunkEnd > runes.length) {
                        chunkEnd = runes.length;
                    } else {
                        for (int i = chunkEnd - 1; i > offset && i > chunkEnd - 200; i--) {
                            if (runes[i] == '\n' || runes[i] == ' ') {
                                chunkEnd = i + 1;
                                break;
                            }
                        }
                    }
                    ParsedChunk c = new ParsedChunk();
                    c.setContent(CodePoints.str(runes, offset, chunkEnd));
                    c.setSeq(chunks.size());
                    c.setStart(u.start + offset);
                    c.setEnd(u.start + chunkEnd);
                    chunks.add(c);
                    offset = chunkEnd;
                }
                continue;
            }

            // 表头追踪
            ht.update(u.text);
            // 表格边界 flush，避免新表被并入仍带着上一表表头的 chunk
            if (ht.isHeaderEndedThisUnit() && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));
                current = new ArrayList<>();
                curLen = 0;
            }
            String headers = ht.getHeaders();
            int headersLen = CodePoints.len(headers);
            if (headersLen > chunkSize) {
                headers = "";
                headersLen = 0;
            }

            // 加入该单元（并给后续 chunk 预留表头空间）会超预算 → flush 当前 chunk
            if (curLen + uLen + headersLen > chunkSize && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));

                // 保留当前末尾作为 overlap
                OverlapResult ov = computeOverlap(current, chunkOverlap, chunkSize, uLen);
                current = new ArrayList<>(ov.units());
                curLen = ov.len();

                // 空间不足时继续收缩 overlap，以容纳表头 + 下一单元
                if (!headers.isEmpty() && headersLen + uLen <= chunkSize) {
                    while (!current.isEmpty() && curLen + uLen + headersLen > chunkSize) {
                        curLen -= CodePoints.len(current.get(0).text);
                        current.remove(0);
                    }

                    // overlap 或下一单元中已有列名上下文时不重复前置
                    String overlapText = unitsText(current);
                    if (!HeaderTracker.headerAlreadyPresent(headers, overlapText, u.text)
                            && !HeaderTracker.headerColumnMismatch(headers, u.text)) {
                        int startPos = u.start;
                        if (!current.isEmpty()) {
                            startPos = current.get(0).start;
                        }
                        SplitUnit hUnit = new SplitUnit(headers, startPos, startPos);
                        current.add(0, hUnit);
                        curLen += headersLen;
                    }
                }
            }

            // 绝对上限二次检查
            if (curLen + uLen > absoluteMaxSize) {
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, chunks.size()));
                    current = new ArrayList<>();
                    curLen = 0;
                }
            }

            current.add(u);
            curLen += uLen;
        }

        // flush 剩余
        if (!current.isEmpty()) {
            chunks.add(buildChunk(current, chunks.size()));
        }
        return chunks;
    }

    private static String unitsText(List<SplitUnit> units) {
        StringBuilder sb = new StringBuilder();
        for (SplitUnit u : units) {
            sb.append(u.text);
        }
        return sb.toString();
    }

    private static ParsedChunk buildChunk(List<SplitUnit> units, int seq) {
        StringBuilder sb = new StringBuilder();
        for (SplitUnit u : units) {
            sb.append(u.text);
        }
        ParsedChunk c = new ParsedChunk();
        c.setContent(sb.toString());
        c.setSeq(seq);
        c.setStart(units.get(0).start);
        c.setEnd(units.get(units.size() - 1).end);
        return c;
    }

    record OverlapResult(List<SplitUnit> units, int len) {
    }

    /**
     * 边界检测会额外回看 4 个 rune（最长分隔符 "\r\n\r\n"），使被窗口切断的分隔符仍可见。
     * 优先级：段落 > 行 > 句末；同级取最早边界。无有效语义边界则不留 overlap。
     */
    static OverlapResult computeOverlap(List<SplitUnit> current, int chunkOverlap, int chunkSize, int nextLen) {
        if (chunkOverlap <= 0) {
            return new OverlapResult(List.of(), 0);
        }

        // overlap 占用下一 chunk 的预算；下一单元已接近预算时收缩窗口
        int maxOverlap = chunkOverlap;
        int remaining = chunkSize - nextLen;
        if (remaining < maxOverlap) {
            maxOverlap = remaining;
        }
        if (maxOverlap <= 0) {
            return new OverlapResult(List.of(), 0);
        }

        List<SplitUnit> window = semanticOverlapWindow(current, maxOverlap + SEMANTIC_OVERLAP_LOOKBEHIND);
        if (window.isEmpty()) {
            return new OverlapResult(List.of(), 0);
        }

        String windowText = unitsText(window);
        // originalWindowStart 之前是回看点；end-exclusive 语义下 boundaryEnd >= originalWindowStart
        // 等价于分隔符最后一个 rune 位于 -1 或更后
        int originalWindowStart = CodePoints.len(windowText) - maxOverlap;
        if (originalWindowStart < 0) {
            originalWindowStart = 0;
        }
        BoundaryResult br = findSemanticOverlapBoundaryEndingAtOrAfter(windowText, originalWindowStart);
        if (!br.ok()) {
            return new OverlapResult(List.of(), 0);
        }

        List<SplitUnit> overlap = trimUnitsPrefix(window, br.end());
        int overlapLen = 0;
        for (SplitUnit u : overlap) {
            overlapLen += CodePoints.len(u.text);
        }
        if (overlapLen <= 0 || overlapLen > maxOverlap || unitsText(overlap).strip().isEmpty()) {
            return new OverlapResult(List.of(), 0);
        }
        return new OverlapResult(overlap, overlapLen);
    }

    /**
     * 有原文出处的 rune；必要时可在首个保留单元内部切片。零宽合成单元（如重复表头）是硬屏障。
     */
    static List<SplitUnit> semanticOverlapWindow(List<SplitUnit> current, int maxLen) {
        if (maxLen <= 0 || current.isEmpty()) {
            return List.of();
        }

        int remaining = maxLen;
        List<SplitUnit> reversed = new ArrayList<>(current.size());
        for (int i = current.size() - 1; i >= 0 && remaining > 0; i--) {
            SplitUnit u = current.get(i);
            int uLen = CodePoints.len(u.text);
            if (uLen == 0) {
                continue;
            }
            // 表头标记是合成文本，不占原文范围：不纳入、不穿越
            if (u.start == u.end || u.end - u.start != uLen) {
                break;
            }

            if (uLen <= remaining) {
                reversed.add(u);
                remaining -= uLen;
                continue;
            }

            int[] runes = CodePoints.of(u.text);
            int start = uLen - remaining;
            reversed.add(new SplitUnit(CodePoints.str(runes, start, uLen), u.start + start, u.end));
            remaining = 0;
        }

        if (reversed.isEmpty()) {
            return List.of();
        }
        List<SplitUnit> window = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            window.add(reversed.get(i));
        }
        return window;
    }

    record BoundaryResult(int end, boolean ok) {
    }

    static BoundaryResult findSemanticOverlapBoundary(String text) {
        return findSemanticOverlapBoundaryEndingAtOrAfter(text, 0);
    }

    /**
     * 仅对 end-exclusive rune 偏移 ≥ minEnd 的候选应用优先级与最早位置规则；
     * 先过滤再比较，避免更早但不合格的回看分隔符遮蔽更晚的合格边界。
     */
    static BoundaryResult findSemanticOverlapBoundaryEndingAtOrAfter(String text, int minEnd) {
        int[] runes = CodePoints.of(text);
        if (runes.length == 0) {
            return new BoundaryResult(0, false);
        }
        if (minEnd < 0) {
            minEnd = 0;
        }
        if (minEnd > runes.length) {
            return new BoundaryResult(0, false);
        }

        List<RuneSpan> protectedSpans = protectedSpansRune(text, protectedSpans(text));
        // spans 按 start 升序
        final List<RuneSpan> prot = protectedSpans;
        IntPredicate insideProtectedFn = pos -> {
            for (RuneSpan p : prot) {
                if (pos < p.start()) {
                    return false;
                }
                if (pos >= p.start() && pos < p.end()) {
                    return true;
                }
            }
            return false;
        };

        int[] best = {-1, -1, Integer.MAX_VALUE}; // start, end, priority
        boolean[] found = {false};
        int finalMinEnd = minEnd;
        class Consider {
            void apply(int start, int end, int priority) {
                if (start < 0 || end <= start || end < finalMinEnd || end > runes.length
                        || insideProtectedFn.test(start) || !hasMeaningfulTail(end)) {
                    return;
                }
                if (!found[0] || priority < best[2]
                        || (priority == best[2] && start < best[0])) {
                    best[0] = start;
                    best[1] = end;
                    best[2] = priority;
                    found[0] = true;
                }
            }

            boolean hasMeaningfulTail(int end) {
                return end >= 0 && end < runes.length && !CodePoints.str(runes, end, runes.length).strip().isEmpty();
            }
        }
        Consider consider = new Consider();

        // 标记段落分隔 rune，其组成换行不再作为低优先级行分隔候选
        boolean[] paragraphRune = new boolean[runes.length];
        for (int i = 0; i < runes.length; i++) {
            if (i + 3 < runes.length && runes[i] == '\r' && runes[i + 1] == '\n'
                    && runes[i + 2] == '\r' && runes[i + 3] == '\n') {
                consider.apply(i, i + 4, 1);
                for (int j = i; j < i + 4; j++) {
                    paragraphRune[j] = true;
                }
                i += 3;
            } else if (i + 1 < runes.length && runes[i] == '\n' && runes[i + 1] == '\n') {
                consider.apply(i, i + 2, 1);
                paragraphRune[i] = paragraphRune[i + 1] = true;
                i++;
            }
        }

        for (int i = 0; i < runes.length; i++) {
            if (paragraphRune[i]) {
                continue;
            }
            if (runes[i] == '\r' && i + 1 < runes.length && runes[i + 1] == '\n' && !paragraphRune[i + 1]) {
                consider.apply(i, i + 2, 2);
                i++;
                continue;
            }
            if (runes[i] == '\n') {
                consider.apply(i, i + 1, 2);
            }
        }

        for (int i = 0; i < runes.length; i++) {
            switch (runes[i]) {
                case '。', '？', '！' -> consider.apply(i, i + 1, 3);
                case '.', '?', '!' -> {
                    if (i + 1 < runes.length && runes[i + 1] == ' ') {
                        consider.apply(i, i + 2, 3);
                    }
                }
                default -> {
                }
            }
        }

        if (!found[0]) {
            return new BoundaryResult(0, false);
        }
        return new BoundaryResult(best[1], true);
    }

    /**
     * 保留剩余单元的原始位置。
     */
    static List<SplitUnit> trimUnitsPrefix(List<SplitUnit> units, int prefixLen) {
        if (prefixLen <= 0) {
            return new ArrayList<>(units);
        }

        int remaining = prefixLen;
        List<SplitUnit> out = new ArrayList<>(units.size());
        for (SplitUnit u : units) {
            int uLen = CodePoints.len(u.text);
            if (remaining >= uLen) {
                remaining -= uLen;
                continue;
            }
            if (remaining > 0) {
                int[] runes = CodePoints.of(u.text);
                u = new SplitUnit(CodePoints.str(runes, remaining, uLen), u.start + remaining, u.end);
                remaining = 0;
            }
            out.add(u);
        }
        return out;
    }
}
