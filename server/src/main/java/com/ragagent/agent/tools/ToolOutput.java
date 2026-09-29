package com.ragagent.agent.tools;

/**
 * 工具输出截断。
 *
 * <p>实录锚点（20000 runes 截到 5000）：
 * {@code \n\n... [output truncated: 20000 → 5000 chars, showing first 3360 + last 1440] ...\n\n}
 * ——usable = 5000-200 = 4800，head = int(4800*0.7) = 3360，tail = 1440。
 * maxChars 太小（usable ≤ 0）时直接取前 maxChars 个 rune，<b>无 marker</b>（实录：100 个 a 截到 10
 * → 10 个 a 原样）。</p>
 */
public final class ToolOutput {

    /** 默认输出上限（rune 数，非字节——CJK 公平计账）。 */
    public static final int DEFAULT_MAX_TOOL_OUTPUT = 24000;

    /** 截断时头部占比（70% 头 / 30% 尾）。 */
    private static final double HEAD_RATIO = 0.7;

    /** 为截断 marker 本身预留的 rune 预算。 */
    public static final int TRUNCATION_MARKER_RESERVE = 200;

    private ToolOutput() {
    }

    /**
     * 超限输出截断：保头（70%）+ 保尾（30%）+ 中间 marker，防止大输出吃满 LLM 上下文窗口。
     * maxChars 按 Unicode rune 计数；maxChars ≤ 0 或未超限时原样返回。
     */
    public static String truncateToolOutput(String output, int maxChars) {
        int runeCount = output.codePointCount(0, output.length());
        if (maxChars <= 0 || runeCount <= maxChars) {
            return output;
        }

        int usable = maxChars - TRUNCATION_MARKER_RESERVE;
        if (usable <= 0) {
            return substringByRunes(output, 0, maxChars);
        }

        int headSize = (int) (usable * HEAD_RATIO);
        int tailSize = usable - headSize;
        if (tailSize <= 0) {
            tailSize = 0;
        }

        String marker = "\n\n... [output truncated: " + runeCount + " → " + maxChars
                + " chars, showing first " + headSize + " + last " + tailSize + "] ...\n\n";

        if (tailSize == 0) {
            return substringByRunes(output, 0, headSize) + marker;
        }
        return substringByRunes(output, 0, headSize) + marker
                + substringByRunes(output, runeCount - tailSize, runeCount);
    }

    /** 按 rune（code point）下标切片。 */
    private static String substringByRunes(String s, int fromRune, int toRuneExclusive) {
        int len = s.length();
        int from = Character.offsetByCodePoints(s, 0, fromRune);
        int to = from;
        for (int i = fromRune; i < toRuneExclusive && to < len; i++) {
            to += Character.charCount(s.codePointAt(to));
        }
        return s.substring(from, to);
    }
}
