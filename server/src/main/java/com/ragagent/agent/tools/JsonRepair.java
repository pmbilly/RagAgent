package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 输出容错 JSON 修复（全程按 code point 处理——含代理对的内容不会被劈开）。
 *
 * <p>修复项（实测钉死）：</p>
 * <ul>
 *   <li>截断 JSON（缺闭合括号/花括号）→ balanceBrackets 补齐；</li>
 *   <li>闭合括号前的尾逗号 → 剥掉；</li>
 *   <li>字符串里的非法反斜杠转义（{@code "\+" "\d"} 等正则元字符）→ 改写成字面反斜杠序列
 *       （{@code \\+}），合法转义（反斜杠接 " \ / b f n r t u）原样保留；</li>
 *   <li>顶层值之后的残渣（{@code {}""}、{@code {"a":1}{"a":1}}）→ 掐掉；</li>
 *   <li>缺外层花括号的 {@code key=value} / 带 {@code :} 文本 → 补花括号包裹。</li>
 * </ul>
 *
 * <p><b>刻意不修</b>（实录钉死）：单引号键值（{@code {'a':1}} 原样返回）、
 * markdown 代码围栏（{@code ```json ... ```} 会被当作普通文本包进花括号——保持既有怪异行为）、
 * {@code //} 注释（不识别，但顶层值截断常能顺带修好）。</p>
 *
 * <p>修不动时原样返回（调用方自行处理解析失败）。空输入返回 {@code "{}"}。</p>
 */
public final class JsonRepair {

    private JsonRepair() {
    }

    /** 修复常见 LLM JSON 畸形；修不动原样返回。 */
    public static String repairJson(String s) {
        return repairJsonDetail(s).repaired();
    }

    /** 修复结果： repaired JSON + 是否发生了"截断收尾"（字符串未闭合或括号不平衡）。 */
    public record RepairResult(String repaired, boolean truncated) {
    }

    /**
     * 同 {@link #repairJson(String)}，并报告是否必须"闭合"才能解析——那只发生在 provider
     * 在参数中途停发时（值能解析但只是半截：{@code content} 半个文件、{@code query} 半句话），
     * 调用方必须拒绝这种调用而不是执行它。转义与尾逗号修复是普通畸形，不置位。
     */
    public static RepairResult repairJsonDetail(String s) {
        s = s.strip();
        if (s.isEmpty()) {
            return new RepairResult("{}", false);
        }

        // 对象必须以 { 开头
        if (s.charAt(0) != '{') {
            // 可能是没带花括号的 key=value 对
            if (s.contains(":") || s.contains("=")) {
                s = "{" + s + "}";
            } else {
                return new RepairResult(s, false);
            }
        }

        // 先修非法反斜杠转义——不闭合的字符串会干扰下面的逗号/括号追踪。
        s = fixInvalidEscapes(s);

        // 掐掉第一个完整顶层值之后的一切
        s = trimAfterTopLevelValue(s);

        // 修尾逗号：,} 或 ,]
        s = fixTrailingCommas(s);

        // 平衡括号/花括号
        return balanceBrackets(s);
    }

    /**
     * 把字符串里非法的 JSON 转义改写成字面反斜杠序列。JSON 只允许反斜杠接
     * {@code " \ / b f n r t u}；字符串里的其他反斜杠（如 {@code \+ \d \w \. \|}）都是解析错误。
     * LLM 传正则时经常忘了双重转义——把 "\+" 改写为 "\\+" 正好还原 LLM 的本意
     * （解析后的字符串变回 "\+"，正则引擎要的就是它），且对已合法的 JSON 幂等。
     */
    static String fixInvalidEscapes(String s) {
        int[] cps = s.codePoints().toArray();
        StringBuilder out = new StringBuilder(s.length() + 8);
        boolean inString = false;
        int i = 0;
        while (i < cps.length) {
            int r = cps[i];
            if (!inString) {
                out.appendCodePoint(r);
                if (r == '"') {
                    inString = true;
                }
                i++;
                continue;
            }
            if (r == '"') {
                out.appendCodePoint(r);
                inString = false;
                i++;
                continue;
            }
            if (r != '\\') {
                out.appendCodePoint(r);
                i++;
                continue;
            }
            // 字符串里遇到反斜杠。看下一个 rune 决定是不是合法 JSON 转义。
            if (i + 1 >= cps.length) {
                // EOF 悬空反斜杠——转义成 \\，别让字符串停在"等待转义"状态。
                out.append("\\\\");
                i++;
                continue;
            }
            int next = cps[i + 1];
            if (next == '"' || next == '\\' || next == '/' || next == 'b' || next == 'f'
                    || next == 'n' || next == 'r' || next == 't' || next == 'u') {
                // 合法 JSON 转义——原样通过。
                out.appendCodePoint(r);
                out.appendCodePoint(next);
                i += 2;
            } else {
                // 非法转义——多半是忘了双重转义的正则元字符。输出字面反斜杠 + 下一 rune。
                out.append("\\\\");
                out.appendCodePoint(next);
                i += 2;
            }
        }
        return out.toString();
    }

    /**
     * 掐掉第一个平衡的顶层对象/数组之后的一切。流式 provider 偶尔在参数已完整后又吐一段
     * 残渣——孤立的 {@code ""} 或整个 payload 的重复——拼接结果（{@code {}""}）解析失败，
     * 但前导值本身是好的。顶层值从未闭合的输入原样返回，留给 balanceBrackets 收尾。
     */
    static String trimAfterTopLevelValue(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        int i = 0;
        int n = s.length();
        while (i < n) {
            int r = s.codePointAt(i);
            int w = Character.charCount(r);
            if (escaped) {
                escaped = false;
                i += w;
                continue;
            }
            if (inString) {
                if (r == '\\') {
                    escaped = true;
                } else if (r == '"') {
                    inString = false;
                }
                i += w;
                continue;
            }
            if (r == '"') {
                inString = true;
            } else if (r == '{' || r == '[') {
                depth++;
            } else if (r == '}' || r == ']') {
                depth--;
                if (depth == 0) {
                    int end = i + w;
                    if (s.substring(end).strip().isEmpty()) {
                        return s;
                    }
                    return s.substring(0, end);
                }
            }
            i += w;
        }
        return s;
    }

    /** 剥掉闭合括号/花括号前的尾逗号。 */
    static String fixTrailingCommas(String s) {
        int[] runes = s.codePoints().toArray();
        StringBuilder result = new StringBuilder(s.length());
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < runes.length; i++) {
            int r = runes[i];
            if (escaped) {
                escaped = false;
                result.appendCodePoint(r);
                continue;
            }
            if (r == '\\' && inString) {
                escaped = true;
                result.appendCodePoint(r);
                continue;
            }
            if (r == '"') {
                inString = !inString;
                result.appendCodePoint(r);
                continue;
            }
            if (inString) {
                result.appendCodePoint(r);
                continue;
            }

            // 字符串外：检查尾逗号
            if (r == ',') {
                // 向前看闭合括号/花括号（跳过空白）
                int nextNonSpace = findNextNonSpace(runes, i + 1);
                if (nextNonSpace >= 0 && (runes[nextNonSpace] == '}' || runes[nextNonSpace] == ']')) {
                    continue; // 丢弃这个逗号
                }
            }
            result.appendCodePoint(r);
        }

        return result.toString();
    }

    /** 从 start 起找下一个非空白 rune 的下标；没有返回 -1。 */
    private static int findNextNonSpace(int[] runes, int start) {
        for (int i = start; i < runes.length; i++) {
            if (!isGoSpace(runes[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 空白判定（\t \n \v \f \r 空格 U+0085 U+00A0 及 Unicode 空白）。 */
    private static boolean isGoSpace(int r) {
        switch (r) {
            case '\t', '\n', 0x0B, 0x0C, '\r', ' ', 0x85, 0xA0:
                return true;
            default:
                return Character.isWhitespace(r);
        }
    }

    /** 补齐缺失的闭合括号/花括号，并报告是否发生了补齐。 */
    static RepairResult balanceBrackets(String s) {
        List<Character> stack = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;

        int i = 0;
        int n = s.length();
        while (i < n) {
            int r = s.codePointAt(i);
            i += Character.charCount(r);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (r == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (r == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }

            if (r == '{') {
                stack.add('}');
            } else if (r == '[') {
                stack.add(']');
            } else if (r == '}' || r == ']') {
                if (!stack.isEmpty() && stack.get(stack.size() - 1) == r) {
                    stack.remove(stack.size() - 1);
                }
            }
        }

        boolean closed = false;

        // 未闭合字符串先补引号
        if (inString) {
            s = s + "\"";
            closed = true;
        }

        // 逆序补齐缺失的闭合符
        for (int k = stack.size() - 1; k >= 0; k--) {
            s = s + (char) (int) stack.get(k);
            closed = true;
        }

        return new RepairResult(s, closed);
    }
}
