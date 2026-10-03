package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管线结构化日志。
 *
 * <p>行格式：{@code [PIPELINE] stage=X action=Y k1=v1 k2=v2}，字段按 key 字母序；
 * string 值做 JSON 风格引号转义，值截到 300 个 Unicode 码点追加 "..."，
 * sanitizeForLog 把 \n\r\t 折成空格。日志不是字节契约，但保持固定形状便于排障。
 * 签名不含 ctx 参数（上下文由 TenantContext/MDC/ThreadLocal 承担）。</p>
 */
public final class PipelineLog {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineLog.class);

    private static final int VALUE_MAX_RUNE = 300;
    private static final String DEFAULT_STAGE = "PIPELINE";
    private static final String DEFAULT_ACTION = "info";
    private static final String PREFIX = "[PIPELINE]";
    private static final String ELLIPSIS = "...";

    private PipelineLog() {}

    /** 构建 log 行。 */
    public static String format(String stage, String action, Map<String, Object> fields) {
        String st = stage == null || stage.isEmpty() ? DEFAULT_STAGE : stage;
        String ac = action == null || action.isEmpty() ? DEFAULT_ACTION : action;
        StringBuilder b = new StringBuilder(128);
        b.append(PREFIX).append(" stage=").append(st).append(" action=").append(ac);
        if (fields != null && !fields.isEmpty()) {
            Map<String, Object> sorted = new TreeMap<>(fields);
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                b.append(' ').append(e.getKey()).append('=')
                        .append(sanitizeForLog(formatValue(e.getValue())));
            }
        }
        return b.toString();
    }

    public static void info(String stage, String action, Map<String, Object> fields) {
        LOG.info(format(stage, action, fields));
    }

    public static void warn(String stage, String action, Map<String, Object> fields) {
        LOG.warn(format(stage, action, fields));
    }

    public static void error(String stage, String action, Map<String, Object> fields) {
        LOG.error(format(stage, action, fields));
    }

    /** string 值转义+截断；其余转字符串形态。 */
    private static String formatValue(Object value) {
        if (value instanceof String s) {
            return quote(truncate(s));
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) {
                parts.add(o == null ? "<nil>" : String.valueOf(o));
            }
            return parts.toString();
        }
        return value == null ? "<nil>" : String.valueOf(value);
    }

    /** 换行转字面 \\n，300 码点截断。 */
    private static String truncate(String content) {
        String c = content.replace("\n", "\\n");
        if (runeLength(c) <= VALUE_MAX_RUNE) {
            return c;
        }
        return runeSubstring(c, VALUE_MAX_RUNE) + ELLIPSIS;
    }

    /** JSON 风格引号转义（控制符转 \\uXXXX，非字节契约）。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** \n\r\t → 空格、其余 C0 控制符移除。 */
    private static String sanitizeForLog(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                sb.append(' ');
            } else if (ch >= 0x20) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    public static int runeLength(String s) {
        return s.codePointCount(0, s.length());
    }

    static String runeSubstring(String s, int runes) {
        int end = s.offsetByCodePoints(0, Math.min(runes, runeLength(s)));
        return s.substring(0, end);
    }
}
