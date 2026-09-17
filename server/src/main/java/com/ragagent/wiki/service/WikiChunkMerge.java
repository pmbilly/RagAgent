package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ragagent.knowledge.domain.Chunk;

/**
 * 按位置 + 文本匹配重建文档正文（对照 Go internal/searchutil/chunkmerge.go 的
 * {@code MergeTextChunks} / {@code AppendWithOverlap} / {@code indexRunes}）。
 *
 * <p><b>为什么在 wiki 包里</b>：Go 的 wiki_ingest.go 通过
 * {@code reconstructContent}（L2865-2875）→ {@code searchutil.MergeTextChunks}
 * 重建正文；Java 侧 searchutil 等价模块尚未翻译，而"内容重建"是
 * {@code wiki_ingest_test.go} 明确要求覆盖的行为。为了让 wiki ingest 能独立跑起来，
 * 这里<b>只移植 {@code MergeTextChunks} 依赖到的最小闭包</b>
 * （{@code appendWithOverlap} + {@code indexRunes}），不做 searchutil 的完整搬运。
 * 若将来 searchutil 整体翻译完成，本类应被替换为对那个公共模块的调用，
 * 而不是两份实现并存。</p>
 *
 * <h2>为什么按文本匹配而不是按坐标裁剪</h2>
 * <p>（照搬 Go 的注释）历史上各处都用「按位置」的公式裁剪重叠
 * （{@code offset = len(content) - (EndAt - lastEndAt)} 之类），它默认
 * {@code len([]rune(Content)) == EndAt-StartAt}。但有两类数据会破坏这个不变式，
 * 导致拼接错位、丢字或重复：</p>
 * <ol>
 *   <li>父子分块器会给被拆开的表格「补写表头」，补进去的表头是零宽度的
 *       （{@code start == end}），位置坐标无法表达它，content 比 EndAt-StartAt 更长；</li>
 *   <li>content 里可能保留 HTML 实体（如 {@code &#34;} / {@code &gt;}），
 *       其字符数比原文区间长。</li>
 * </ol>
 * <p>因此改为「按文本」匹配重叠：在下一段开头的窗口里查找已合并文本的后缀首次出现
 * 的位置，从该位置之后接上。位置信息仅用于估算搜索窗口大小，不再用于裁剪。</p>
 *
 * <h2>码点</h2>
 * <p>Go 全程用 {@code []rune}。Java 侧一律用 {@code int[]} 码点数组
 * （{@link String#codePoints()}），避免把增补平面字符按代理对拆开——
 * 中文与 emoji 在 wiki 正文里都会出现，按 char 切会让"相等比较"与"裁剪长度"
 * 在代理对处错位。</p>
 */
final class WikiChunkMerge {

    private WikiChunkMerge() {}

    /** 对照 Go {@code minOverlapRunes}：参与匹配的最短后缀长度。太短（如表格分隔行 {@code |---|}）容易误匹配。 */
    static final int MIN_OVERLAP_RUNES = 12;

    /** 对照 Go {@code defaultSearchSpan}：搜索窗口下限，保证位置信息缺失/为 0 时也能检测到一定范围内的真实重叠。 */
    static final int DEFAULT_SEARCH_SPAN = 400;

    /**
     * 对照 Go {@code MergeTextChunks}（chunkmerge.go L178-227）：按 {@code StartAt}
     * （并列时按 {@code ChunkIndex}）排序后，用 {@link #appendWithOverlap} 把多个 chunk
     * 的内容重建为完整文本。{@code gapSep} 用于位置不相邻（有间隙）的两段之间的分隔符。
     *
     * <p>调用方负责先做类型过滤（例如只保留文本 chunk）；<b>本函数不感知 ChunkType</b>
     * ——与 Go 一致。</p>
     */
    static String mergeTextChunks(List<Chunk> chunks, String gapSep) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        // 对照 Go L183-190：复制后**稳定**排序（不修改入参）
        List<Chunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator
                .comparingInt(Chunk::getStartAt)
                .thenComparingInt(Chunk::getChunkIndex));

        String merged = "";
        int mergedEnd = -1;
        for (Chunk c : sorted) {
            if (c == null || c.getContent() == null || c.getContent().isEmpty()) {
                continue;
            }
            if (merged.isEmpty()) {
                merged = c.getContent();
                if (c.getEndAt() > 0) {
                    mergedEnd = c.getEndAt();
                }
                continue;
            }

            // 间隙 / 位置信息缺失（EndAt==0）：作为独立段落拼接，不做重叠裁剪。
            if (c.getStartAt() > mergedEnd || c.getEndAt() == 0) {
                if (gapSep != null && !gapSep.isEmpty()) {
                    merged += gapSep;
                }
                merged += c.getContent();
                if (c.getEndAt() > 0) {
                    mergedEnd = c.getEndAt();
                }
                continue;
            }

            // 部分重叠或首尾相接：按文本匹配去重叠后拼接。
            if (c.getEndAt() > mergedEnd) {
                merged = appendWithOverlap(merged, c.getContent(), mergedEnd - c.getStartAt());
                mergedEnd = c.getEndAt();
            }
            // 否则被上一段完全覆盖，跳过。
        }

        return merged;
    }

    /**
     * 对照 Go {@code AppendWithOverlap}（chunkmerge.go L97-137）：把 next 追加到 acc
     * 之后并去除重叠。
     *
     * <p>{@code positionOverlap} 是由 StartAt/EndAt 估算的重叠量，<b>仅用于界定搜索
     * 窗口大小</b>；真正的重叠按文本匹配，能兼容补写表头与 HTML 实体长度偏差。
     * 找不到文本重叠时原样拼接（不裁剪）——宁可保留也不破坏内容。</p>
     *
     * <p>{@code positionOverlap <= 0} 时两段位置上严格相邻或不相交，没有可去重的重叠；
     * 此时若进入文本匹配，会因 headSlack 下限 320 在 next 开头窗口内误命中 acc 后缀的
     * 真实内容重复（如同一句话在文档多次出现），把 next 开头整段误判为补写表头删掉，
     * 造成<b>不可逆的内容丢失</b>。故直接拼接，补写表头重复交给调用方后处理。</p>
     */
    static String appendWithOverlap(String acc, String next, int positionOverlap) {
        if (acc == null || acc.isEmpty()) {
            return next;
        }
        if (next == null || next.isEmpty()) {
            return acc;
        }
        if (positionOverlap <= 0) {
            return acc + next;
        }

        int[] accRunes = acc.codePoints().toArray();
        int[] nextRunes = next.codePoints().toArray();
        int span = positionOverlap;

        int maxK = Math.min(accRunes.length, nextRunes.length);
        int cap = Math.max(span * 3, DEFAULT_SEARCH_SPAN);
        if (maxK > cap) {
            maxK = cap;
        }
        // 重叠内容之前最多允许跳过多少前缀（即补写的表头等合成文本）。
        int headSlack = Math.max(span * 2, 320);

        for (int k = maxK; k >= MIN_OVERLAP_RUNES; k--) {
            int[] needle = new int[k];
            System.arraycopy(accRunes, accRunes.length - k, needle, 0, k);
            int pos = indexRunes(nextRunes, needle, headSlack);
            if (pos >= 0) {
                return acc + fromCodePoints(nextRunes, pos + k);
            }
        }
        return acc + next;
    }

    /**
     * 对照 Go {@code indexRunes}（chunkmerge.go L231-250）：在 haystack 中查找 needle
     * 首次出现的码点下标，且起始位置不超过 maxStart。找不到返回 -1。
     */
    static int indexRunes(int[] haystack, int[] needle, int maxStart) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return -1;
        }
        int limit = haystack.length - needle.length;
        if (maxStart < limit) {
            limit = maxStart;
        }
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    /** 对照 Go {@code string(nextRunes[pos:])}：把码点数组的尾部还原成 String。 */
    private static String fromCodePoints(int[] runes, int from) {
        if (from >= runes.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder(runes.length - from);
        for (int i = from; i < runes.length; i++) {
            sb.appendCodePoint(runes[i]);
        }
        return sb.toString();
    }
}
