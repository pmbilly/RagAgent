package com.ragagent.retrieval.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * chunk 内容的重叠拼接（对照 Go {@code internal/searchutil/chunkmerge.go} 的
 * <b>波 2 未翻部分</b>：{@code AppendWithOverlap / AppendWithExactOverlap /
 * MergeTextChunks / indexRunes}。{@code JoinChunkContent / ContainsChunkContent}
 * 已在 {@code knowledge.service.ChunkSearchUtil} 落地（本包不重复，见该类注释的
 * 迁移备案）。
 *
 * <p>历史背景（Go 注释逐条保留）：按位置的裁剪公式默认
 * {@code len([]rune(Content)) == EndAt-StartAt}，两类数据会破坏它——
 * 父子分块器给拆开的表格补写零宽度表头；content 保留 HTML 实体导致字符数偏长。
 * 因此按<b>文本</b>匹配重叠，位置信息仅用于估算搜索窗口。</p>
 */
public final class SearchChunkMerge {

    /** 参与匹配的最短后缀长度（太短如表格分隔行 |---| 容易误匹配）。 */
    static final int MIN_OVERLAP_RUNES = 12;
    /** 搜索窗口的下限（位置信息缺失/为 0 时也能检测一定范围内的真实重叠）。 */
    static final int DEFAULT_SEARCH_SPAN = 400;

    private SearchChunkMerge() {
    }

    /**
     * 把 next 追加到 acc 之后并去除重叠。positionOverlap 由 StartAt/EndAt 估算
     * （lastEnd - curStart），仅界定搜索窗口；找不到文本重叠则原样拼接（宁可保留）。
     *
     * <p>positionOverlap &lt;= 0 时直接拼接：此时进入文本匹配会因 headSlack 下限
     * 320 在 next 开头窗口内误命中 acc 后缀的真实内容重复，把 next 开头整段误判
     * 为补写表头删掉（不可逆内容丢失——Go 回归测试
     * TestAppendWithOverlap_ContiguousRealContentRepeat 钉住）。</p>
     */
    public static String appendWithOverlap(String acc, String next, int positionOverlap) {
        if (acc.isEmpty()) {
            return next;
        }
        if (next.isEmpty()) {
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
        // 重叠内容之前最多允许跳过的前缀（补写的表头等合成文本）
        int headSlack = Math.max(span * 2, 320);

        for (int k = maxK; k >= MIN_OVERLAP_RUNES; k--) {
            int[] needle = new int[k];
            System.arraycopy(accRunes, accRunes.length - k, needle, 0, k);
            int pos = indexRunes(nextRunes, needle, headSlack);
            if (pos >= 0) {
                return acc + fromRunes(nextRunes, pos + k);
            }
        }
        return acc + next;
    }

    /**
     * 坐标可信时按已知精确重叠量拼接：校验 acc 末 overlap 个字符与 next 前 overlap
     * 个字符逐字相等，相等则精确裁剪；overlap 为 0 直接拼接。
     *
     * @return result + ok；ok=false 时调用方回退 {@link #appendWithOverlap}
     *         （对照 Go 的 {@code (string, bool)}）
     */
    public record ExactResult(String value, boolean ok) {
    }

    public static ExactResult appendWithExactOverlap(String acc, String next, int overlap) {
        if (acc.isEmpty()) {
            return new ExactResult(next, true);
        }
        if (next.isEmpty()) {
            return new ExactResult(acc, true);
        }
        if (overlap < 0) {
            return new ExactResult("", false);
        }
        if (overlap == 0) {
            return new ExactResult(acc + next, true);
        }
        int[] accRunes = acc.codePoints().toArray();
        int[] nextRunes = next.codePoints().toArray();
        if (overlap > accRunes.length || overlap > nextRunes.length) {
            return new ExactResult("", false);
        }
        for (int i = 0; i < overlap; i++) {
            if (accRunes[accRunes.length - overlap + i] != nextRunes[i]) {
                return new ExactResult("", false);
            }
        }
        return new ExactResult(acc + fromRunes(nextRunes, overlap), true);
    }

    /**
     * 按 StartAt（并列时按 ChunkIndex）排序后用 {@link #appendWithOverlap} 重建全文。
     * gapSep 用于位置不相邻（有间隙）或 EndAt==0 的两段之间；传空串直接拼接。
     * 调用方负责先做类型过滤（本函数不感知 ChunkType）。
     */
    public static String mergeTextChunks(List<com.ragagent.knowledge.domain.Chunk> chunks,
                                         String gapSep) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        List<com.ragagent.knowledge.domain.Chunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator
                .comparingInt(com.ragagent.knowledge.domain.Chunk::getStartAt)
                .thenComparingInt(com.ragagent.knowledge.domain.Chunk::getChunkIndex));

        String merged = "";
        int mergedEnd = -1;
        for (var c : sorted) {
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
            // 间隙 / 位置信息缺失（EndAt==0）：独立段落拼接，不做重叠裁剪
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
            // 部分重叠或首尾相接：按文本匹配去重叠后拼接
            if (c.getEndAt() > mergedEnd) {
                merged = appendWithOverlap(merged, c.getContent(), mergedEnd - c.getStartAt());
                mergedEnd = c.getEndAt();
            }
            // 否则被上一段完全覆盖，跳过
        }
        return merged;
    }

    /** 在 haystack 中查找 needle 首次出现的 rune 下标，起始位置不超过 maxStart；找不到 -1。 */
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

    static String fromRunes(int[] runes, int from) {
        StringBuilder b = new StringBuilder(runes.length - from);
        for (int i = from; i < runes.length; i++) {
            b.appendCodePoint(runes[i]);
        }
        return b.toString();
    }
}
