package com.ragagent.wiki.service;

import java.util.List;
import com.ragagent.common.wiki.GoStrings;

/**
 * wiki ingest 的纯文本工具：slug 化、截断、预览、XML 转义、SUMMARY 行解析与
 * LLM JSON 清洗。
 */
public final class WikiTextUtils {

    private WikiTextUtils() {}

    // ═══════════════════════════════════════════════════════════════
    // slugify / 截断 / 预览
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把任意标题压成
     * URL 友好的 slug。
     *
     * <p>保留 {@code a-z0-9-/} 与 CJK（U+4E00–U+9FFF）；空格与下划线变连字符；
     * 其余字符<b>整字丢弃</b>；折叠连续连字符、裁掉首尾连字符。</p>
     *
     * <p><b>截断语义</b>：末端按「UTF-8 字节数不超过 200」取最长前缀，且<b>不切开码点</b>
     * ——{@code String} 无法承载切在多字节字符中间的非法 UTF-8。纯 ASCII 输入
     * （UUID、拉丁标题）下等价于按字节截 200；长 CJK 标题保留的字符数会略少
     * （不会截出半个字）。</p>
     */
    public static String slugify(String s) {
        if (s == null) {
            return "";
        }
        String lower = GoStrings.trimSpace(s).toLowerCase(java.util.Locale.ROOT);

        StringBuilder mapped = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); ) {
            int r = lower.codePointAt(i);
            i += Character.charCount(r);
            if ((r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' || r == '/') {
                mapped.appendCodePoint(r);
            } else if (r == ' ' || r == '_') {
                mapped.append('-');
            } else if (r >= 0x4E00 && r <= 0x9FFF) {
                // 保留 CJK 字符
                mapped.appendCodePoint(r);
            }
            // 其余一律丢弃
        }
        String out = mapped.toString();

        // 折叠连续连字符
        while (out.contains("--")) {
            out = out.replace("--", "-");
        }
        out = trimHyphen(out);

        if (utf8Length(out) > 200) {
            out = utf8Prefix(out, 200);
        }
        return out;
    }

    /**
     * 按 <b>码点</b>截断，截断时追加 {@code "..."}。
     */
    public static String truncateString(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= maxLen) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxLen);
        return s.substring(0, end) + "...";
    }

    /**
     * 去首尾空白、换行与制表符压成空格、
     * 折叠连续双空格，再按码点截断（截断加 {@code "...(truncated)"}）。
     */
    public static String previewText(String s, int maxRunes) {
        if (s == null) {
            s = "";
        }
        s = GoStrings.trimSpace(s);
        s = s.replace("\n", " ").replace("\t", " ");
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
        int count = s.codePointCount(0, s.length());
        if (maxRunes <= 0 || count <= maxRunes) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxRunes)) + "...(truncated)";
    }

    /**
     * 把字符串列表渲染成
     * {@code [a, b, ... (+N)]} 形态的日志片段，每项先过 {@link #previewText(String, int)} 的
     * 48 码点预算。
     */
    public static String previewStringSlice(List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        if (limit <= 0) {
            limit = 1;
        }
        int n = items.size();
        List<String> head = n > limit ? items.subList(0, limit) : items;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < head.size(); i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(previewText(head.get(i), 48));
        }
        if (n > limit) {
            return "[" + joined + " ...(+" + (n - limit) + ")]";
        }
        return "[" + joined + "]";
    }

    /**
     * 只转义会破坏 XML 文本内容的最小字符集。
     * slug 是纯 ASCII，作为属性值使用时无需转义。
     */
    public static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY 行解析
    // ═══════════════════════════════════════════════════════════════

    /** (summary, content) 二元组。 */
    public record SummaryLine(String summary, String content) {}

    /**
     * 从 LLM 输出里抠出
     * {@code "SUMMARY: ..."} 行。找不到 SUMMARY 行时 summary 为空、content 为原文。
     *
     * <p>注意<b>两种冒号</b>都要认：半角 {@code SUMMARY:} 与全角 {@code SUMMARY：}
     * ——中文模型会输出全角。</p>
     */
    public static SummaryLine splitSummaryLine(String raw) {
        String value = GoStrings.trimSpace(raw);
        boolean halfWidth = value.startsWith("SUMMARY:");
        boolean fullWidth = value.startsWith("SUMMARY：");
        if (halfWidth || fullWidth) {
            int idx = value.indexOf('\n');
            if (idx < 0) {
                // 只有一行
                String line = value;
                if (line.startsWith("SUMMARY:")) {
                    line = line.substring("SUMMARY:".length());
                }
                if (line.startsWith("SUMMARY：")) {
                    line = line.substring("SUMMARY：".length());
                }
                return new SummaryLine(GoStrings.trimSpace(line), "");
            }
            String summaryLine = value.substring(0, idx);
            if (summaryLine.startsWith("SUMMARY:")) {
                summaryLine = summaryLine.substring("SUMMARY:".length());
            }
            if (summaryLine.startsWith("SUMMARY：")) {
                summaryLine = summaryLine.substring("SUMMARY：".length());
            }
            return new SummaryLine(GoStrings.trimSpace(summaryLine),
                    GoStrings.trimSpace(value.substring(idx + 1)));
        }
        return new SummaryLine("", value);
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM JSON 清洗
    // ═══════════════════════════════════════════════════════════════

    /**
     * 剥掉 markdown 代码围栏包装，
     * 并清洗控制字符，使 LLM 产出的 JSON 能被安全反序列化。
     */
    public static String cleanLLMJSON(String s) {
        if (s == null) {
            return "";
        }
        s = GoStrings.trimSpace(s);
        s = trimPrefix(s, "```json");
        s = trimPrefix(s, "```");
        s = trimSuffix(s, "```");
        s = GoStrings.trimSpace(s);
        return sanitizeJSONString(s);
    }

    /**
     * 正确转义字符串字面量内部
     * <b>未转义</b>的控制字符（尤其是换行），让 JSON 可解析。
     *
     * <p>逐字符状态机：{@code escape}（上一个字符是反斜杠）与 {@code inString}
     * 两个状态。<b>顺序敏感</b>：先处理转义态，再处理反斜杠，再处理引号，
     * 最后才是字符串内的控制字符。</p>
     *
     * <p>注意转义态里的处理：反斜杠后跟真实换行会改写成<b>字符 n</b>
     * （r、t 同理），从而把跨行的"转义序列"修成合法的 {@code \n} 转义。</p>
     */
    public static String sanitizeJSONString(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder buf = new StringBuilder(s.length());
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            i += Character.charCount(r);

            if (escape) {
                if (r == '\n') {
                    buf.append('n');
                } else if (r == '\r') {
                    buf.append('r');
                } else if (r == '\t') {
                    buf.append('t');
                } else {
                    buf.appendCodePoint(r);
                }
                escape = false;
                continue;
            }
            if (r == '\\') {
                escape = true;
                buf.appendCodePoint(r);
                continue;
            }
            if (r == '"') {
                inString = !inString;
                buf.appendCodePoint(r);
                continue;
            }
            if (inString) {
                if (r == '\n') {
                    buf.append("\\n");
                    continue;
                }
                if (r == '\r') {
                    buf.append("\\r");
                    continue;
                }
                if (r == '\t') {
                    buf.append("\\t");
                    continue;
                }
            }
            buf.appendCodePoint(r);
        }
        return buf.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════════

    /** 不存在才追加，返回同一个/新列表 */
    public static java.util.List<String> appendUnique(java.util.List<String> arr, String s) {
        if (arr == null) {
            arr = new java.util.ArrayList<>();
        }
        if (arr.contains(s)) {
            return arr;
        }
        arr.add(s);
        return arr;
    }

    /** 剥掉前缀（若有） */
    private static String trimPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    /** 剥掉后缀（若有） */
    private static String trimSuffix(String s, String suffix) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    /** 只裁首尾 ASCII 连字符 */
    private static String trimHyphen(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    /** UTF-8 编码后的字节数 */
    static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            i += Character.charCount(r);
            if (r < 0x80) {
                bytes += 1;
            } else if (r < 0x800) {
                bytes += 2;
            } else if (r < 0x10000) {
                bytes += 3;
            } else {
                bytes += 4;
            }
        }
        return bytes;
    }

    /** 取 UTF-8 字节长度不超过 maxBytes 的最长码点前缀（见 {@link #slugify} 的截断语义说明） */
    static String utf8Prefix(String s, int maxBytes) {
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int r = s.codePointAt(i);
            int size;
            if (r < 0x80) {
                size = 1;
            } else if (r < 0x800) {
                size = 2;
            } else if (r < 0x10000) {
                size = 3;
            } else {
                size = 4;
            }
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            i += Character.charCount(r);
        }
        return s.substring(0, i);
    }
}
