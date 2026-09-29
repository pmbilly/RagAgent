package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tier 2：启发式边界切分。
 * <p>面向没有 Markdown 标题、但有可识别结构线索（分页符、编号小节、多语言章节标记、
 * 视觉分隔线、全大写标题、页脚）的文档。算法：找出全部候选边界，然后贪心装箱——
 * 把边界间的块累进 chunk 直到下一块超出 ChunkSize。大于 ChunkSize 的块递归交给
 * legacy 切分器内部细分。</p>
 */
public final class HeuristicSplitter {

    record Boundary(int runeStart, int priority) {
    }

    private HeuristicSplitter() {
    }

    /**
     * @param profile 当前未使用（该层直接扫描边界），仅为签名一致性保留
     */
    public static List<ParsedChunk> splitByHeuristics(String text, SplitterConfig cfg,
            DocumentProfiler.DocProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int[] runes = CodePoints.of(text);
        int totalRunes = runes.length;
        if (totalRunes <= cfg.getChunkSize()) {
            return LegacySplitter.splitText(text, cfg);
        }

        List<Boundary> bounds = findHeuristicBoundaries(text, cfg.getLanguages());
        // 丢弃严格落在保护区域内的边界（表格 / 代码块 / LaTeX 等原子内容）；span 边缘的保留
        List<LegacySplitter.RuneSpan> prot = LegacySplitter.protectedSpansRune(
                text, LegacySplitter.protectedSpans(text));
        if (!prot.isEmpty()) {
            bounds = dropBoundsInsideSpans(bounds, prot);
        }
        if (bounds.isEmpty()) {
            return LegacySplitter.splitText(text, cfg);
        }

        // 文末哨兵，让装箱器可以 flush
        bounds = new ArrayList<>(bounds);
        bounds.add(new Boundary(totalRunes, 0));
        // 起始处没有边界则补一个 0
        if (bounds.get(0).runeStart() != 0) {
            bounds.add(0, new Boundary(0, 0));
        }

        // 贪心装箱
        List<ParsedChunk> out = new ArrayList<>();
        int seq = 0;
        int chunkStart = bounds.get(0).runeStart();
        int curEnd = chunkStart;
        int minChunkSize = cfg.getChunkSize() / 4;
        if (minChunkSize < 50) {
            minChunkSize = 50;
        }

        for (int i = 1; i < bounds.size(); i++) {
            int nextEnd = bounds.get(i).runeStart();
            int blockLen = nextEnd - curEnd;

            if (blockLen > cfg.getChunkSize()) {
                // 前后边界之间的块本身就放不进任何 chunk：flush 当前累积，然后递归细分
                if (curEnd - chunkStart > 0) {
                    out = appendChunk(out, runes, chunkStart, curEnd, seq);
                    seq = out.size();
                    chunkStart = curEnd;
                }
                out = appendOversizeBlock(out, runes, curEnd, nextEnd, cfg, seq);
                seq = out.size();
                curEnd = nextEnd;
                chunkStart = nextEnd;
                continue;
            }

            // 加入这一块会超预算？
            int accumulated = nextEnd - chunkStart;
            if (accumulated > cfg.getChunkSize() && curEnd - chunkStart >= minChunkSize) {
                // flush 累积内容为一个 chunk，从 curEnd 重启
                out = appendChunk(out, runes, chunkStart, curEnd, seq);
                seq = out.size();
                // overlap 起点吸附到最近的语义边界或换行，避免从行/词中间切开
                chunkStart = applyOverlapAligned(runes, curEnd, cfg.getChunkOverlap(), bounds);
            }
            curEnd = nextEnd;
        }

        // flush 剩余
        if (curEnd > chunkStart) {
            out = appendChunk(out, runes, chunkStart, curEnd, seq);
        }
        return out;
    }

    static List<Boundary> findHeuristicBoundaries(String text, List<String> langs) {
        List<Boundary> bounds = new ArrayList<>();

        // 分页符 —— 最强单字符边界
        for (int idx : allRuneIndices(text, "\f")) {
            bounds.add(new Boundary(idx, ChunkPatterns.PRIO_FORM_FEED));
        }

        // 逐行模式：一次性按行走
        String[] lines = text.split("\n", -1);
        List<Pattern> chapterPatterns = ChunkPatterns.chapterPatternsForLangs(langs);
        int pos = 0;
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                int runeStart = pos;
                boolean added = false;
                for (Pattern pat : chapterPatterns) {
                    if (pat.matcher(line).find()) {
                        bounds.add(new Boundary(runeStart, ChunkPatterns.PRIO_CHAPTER_MARKER));
                        added = true;
                        break;
                    }
                }
                if (!added && ChunkPatterns.NUMBERED_SECTION.matcher(line).find()) {
                    bounds.add(new Boundary(runeStart, ChunkPatterns.PRIO_NUMBERED_HEAD));
                    added = true;
                }
                if (!added && ChunkPatterns.ALL_CAPS_HEADING.matcher(line).find()) {
                    bounds.add(new Boundary(runeStart, ChunkPatterns.PRIO_ALL_CAPS_HEADING));
                    added = true;
                }
                if (!added && ChunkPatterns.VISUAL_SEPARATOR.matcher(line).find()) {
                    bounds.add(new Boundary(runeStart, ChunkPatterns.PRIO_VISUAL_SEP));
                    added = true;
                }
                if (!added && ChunkPatterns.PAGE_FOOTER.matcher(line).find()) {
                    bounds.add(new Boundary(runeStart, ChunkPatterns.PRIO_PAGE_FOOTER));
                }
            }
            pos += CodePoints.len(line);
            if (i < lines.length - 1) {
                pos++; // \n
            }
        }

        // 连续空行块（\n{3,}）。匹配 run 的 *末尾*，干净地落入下一段
        Matcher m = ChunkPatterns.EXCESSIVE_BLANKS.matcher(text);
        while (m.find()) {
            int runeStart = CodePoints.runeIndexAtChar(text, m.end());
            bounds.add(new Boundary(runeStart, ChunkPatterns.PRIO_BLANK_BLOCK));
        }

        if (bounds.isEmpty()) {
            return List.of();
        }

        // 按位置排序；同偏移保留最高优先级
        bounds.sort(Comparator.comparingInt(Boundary::runeStart)
                .thenComparing(Comparator.comparingInt(Boundary::priority).reversed()));
        List<Boundary> deduped = new ArrayList<>(bounds.size());
        int prev = -1;
        for (Boundary b : bounds) {
            if (b.runeStart() != prev) {
                deduped.add(b);
                prev = b.runeStart();
            }
        }
        return deduped;
    }

    /**
     * （rune 偏移）保护 span 内的边界；span 起点/终点的边界保留。
     */
    static List<Boundary> dropBoundsInsideSpans(List<Boundary> bounds, List<LegacySplitter.RuneSpan> spans) {
        if (spans.isEmpty()) {
            return bounds;
        }
        List<Boundary> out = new ArrayList<>(bounds.size());
        boundLoop:
        for (Boundary b : bounds) {
            for (LegacySplitter.RuneSpan s : spans) {
                if (s.start() >= b.runeStart()) {
                    break; // 剩余 span 起点在 b 处或之后，不可能包含 b
                }
                if (b.runeStart() < s.end()) {
                    continue boundLoop;
                }
            }
            out.add(b);
        }
        return out;
    }

    static List<Integer> allRuneIndices(String text, String needle) {
        List<Integer> out = new ArrayList<>();
        if (needle.isEmpty()) {
            return out;
        }
        int pos = 0;
        for (int cp : CodePoints.of(text)) {
            if (CodePoints.str(new int[]{cp}, 0, 1).equals(needle)) {
                out.add(pos);
            }
            pos++;
        }
        return out;
    }

    /**
     * Content 是原始切片，Start/End rune 偏移必须与 content 的 rune 长度一致。
     */
    private static List<ParsedChunk> appendChunk(List<ParsedChunk> out, int[] runes,
            int start, int end, int seq) {
        if (end <= start) {
            return out;
        }
        String raw = CodePoints.str(runes, start, end);
        if (raw.strip().isEmpty()) {
            return out;
        }
        out.add(new ParsedChunk(raw, "", seq, start, end));
        return out;
    }

    private static List<ParsedChunk> appendOversizeBlock(List<ParsedChunk> out, int[] runes,
            int start, int end, SplitterConfig cfg, int seq) {
        if (end <= start) {
            return out;
        }
        String subText = CodePoints.str(runes, start, end);
        List<ParsedChunk> subs = LegacySplitter.splitText(subText, cfg);
        for (ParsedChunk s : subs) {
            out.add(new ParsedChunk(s.getContent(), "", seq, start + s.getStart(), start + s.getEnd()));
            seq++;
        }
        return out;
    }

    /**
     * 优先吸附窗口 [curEnd-2*overlap, curEnd) 内最近的边界（不含 curEnd 自身，否则 overlap 为 0），
     * 否则回退到前一个换行，最后才用原始目标。
     */
    static int applyOverlapAligned(int[] runes, int curEnd, int overlap, List<Boundary> bounds) {
        if (overlap <= 0) {
            return curEnd;
        }
        int target = curEnd - overlap;
        if (target < 0) {
            target = 0;
        }
        int windowStart = curEnd - 2 * overlap;
        if (windowStart < 0) {
            windowStart = 0;
        }

        // 优先窗口内的语义边界
        int bestBound = -1;
        for (Boundary b : bounds) {
            if (b.runeStart() >= windowStart && b.runeStart() < curEnd && b.runeStart() > bestBound) {
                bestBound = b.runeStart();
            }
        }
        if (bestBound >= 0) {
            return bestBound;
        }

        // 回退：从 target 向前找换行，但不越过 windowStart
        for (int i = target; i > windowStart && i < runes.length; i--) {
            if (runes[i] == '\n') {
                return i + 1;
            }
        }
        return target;
    }
}
