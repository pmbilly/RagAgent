package com.ragagent.common.security;

/**
 * 日志脱敏。
 *
 * <p>目的：<b>防日志注入</b>——攻击者把换行/控制字符塞进用户可控字段
 * （MCP 服务名、服务端 host、工具名……），伪造出额外的日志行，误导日志分析工具或掩盖恶意行为。</p>
 *
 * <p>规则：</p>
 * <ol>
 *   <li>空串直接返回空串；</li>
 *   <li>LF / CR / TAB 一律替换成空格（先各自替换，不做 CRLF 合并）；</li>
 *   <li>剔除其余控制字符（Unicode 码点 &lt; 32），保留可打印字符与常用 Unicode。</li>
 * </ol>
 *
 * <p>⚠️ 这不是 {@link InputSanitizer}（那个做 XSS 判定与清理），
 * 两者用途不同，别混用：本类只用于"写进日志的字符串"。</p>
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** 单条字符串脱敏。 */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = input.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        StringBuilder builder = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            // 只保留码点 >= 32 的字符
            if (cp >= 32) {
                builder.appendCodePoint(cp);
            }
        });
        return builder.toString();
    }

    /** 列表逐条脱敏；空/null 输入返回空列表（不是 null）。 */
    public static java.util.List<String> sanitizeAll(java.util.List<String> input) {
        if (input == null || input.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>(input.size());
        for (String item : input) {
            out.add(sanitize(item));
        }
        return out;
    }
}
