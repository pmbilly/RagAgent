package com.ragagent.mcp.protocol;

import com.ragagent.common.security.LogSanitizer;

/**
 * MCP 日志辅助。
 *
 * <p>MCP 服务名、服务端自述名、host、instructions 全部来自租户可控的远端，
 * 写日志前必须脱敏（防日志注入），长度也要截断（instructions 可能很长）。</p>
 */
public final class McpLog {

    private McpLog() {
    }

    /** 脱敏（防日志注入）。 */
    public static String sanitize(String input) {
        return LogSanitizer.sanitize(input);
    }

    /**
     * <b>先脱敏，再按 Unicode 码点截断</b>，
     * 超长时追加 {@code "..."}。
     *
     * <p>三个边界：</p>
     * <ul>
     *   <li>{@code maxRunes <= 0} → 返回空串；</li>
     *   <li>长度不足 → 原样返回（<b>不加</b>省略号）；</li>
     *   <li>刚好等于上限 → 不截断（{@code len(runes) <= maxRunes}）。</li>
     * </ul>
     */
    public static String preview(String s, int maxRunes) {
        String sanitized = LogSanitizer.sanitize(s);
        if (maxRunes <= 0) {
            return "";
        }
        int[] codePoints = sanitized.codePoints().toArray();
        if (codePoints.length <= maxRunes) {
            return sanitized;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxRunes; i++) {
            sb.appendCodePoint(codePoints[i]);
        }
        return sb + "...";
    }
}
