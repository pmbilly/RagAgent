package com.ragagent.datasource.connector.gitlab;

/**
 * 按 Go 标准库 {@code strings.TrimSpace} 语义实现的去空白工具。
 *
 * <h2>为什么不直接用 {@code String.trim()} / {@code String.strip()}</h2>
 * <p>这三个函数对"什么算空白"的答案<b>都不一样</b>，而本连接器有一处语义
 * 完全建立在这个答案上：{@code access_token} 去空白后为空 → 直接报
 * {@code "GitLab platform configuration is missing"}。凭据经常是从网页/文档里
 * 复制粘贴的，夹进 U+00A0（不换行空格）完全可能——此时这里判定"token 是空的"
 * （这是一个明确的配置错误），而 {@code String.trim()} 会认为 token 非空、
 * 拿着含 NBSP 的串去请求，最终得到一个 401。故障点从"保存时"漂移到"同步时"，
 * 排查成本差一个数量级。</p>
 *
 * <pre>
 *   Go  strings.TrimSpace  → unicode.IsSpace：' \t\n\v\f\r' + U+0085 + U+00A0
 *                            + U+1680 + U+2000–U+200A + U+2028 + U+2029
 *                            + U+202F + U+205F + U+3000
 *    Java String.trim()     → 只去 &lt;= 0x20 的字符（漏掉 U+0085 / U+00A0 / U+2000…）
 *    Java String.strip()    → Character.isWhitespace：覆盖了上面的绝大多数，
 *                             但<b>不含</b> U+00A0 / U+2007 / U+202F / U+0085
 *                             这 4 个（Unicode 的 White_Space 有它们，Java 的实现刻意排除）
 *   JDK 21 的 {@code String.strip()} 因此仍与 Go 差这 4 个字符。
 * </pre>
 * <p>做法是 {@code Character.isWhitespace} 打底、再补上那 4 个缺口
 * （与 memory 模块的 {@code isGoSpace} 同一处置）。</p>
 *
 * <p><b>内部工具，不是契约</b>：只影响配置解析与 URL 归一，不落 jsonb、不进响应体。</p>
 */
final class GoStrings {

    private GoStrings() {
    }

    /**
     * 去掉两端的 Unicode 空白。
     *
     * @param s 允许为 {@code null}（回空串）
     */
    static String trimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 去掉两端指定的单字符——只支持单字符 cutset
     * （本模块只用于去掉首尾 {@code '/'}）。
     */
    static String trim(String s, char cutset) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == cutset) {
            start++;
        }
        while (end > start && s.charAt(end - 1) == cutset) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 空白判定（= Unicode White_Space 属性）。 */
    static boolean isGoSpace(char c) {
        return Character.isWhitespace(c)
                || c == '\u00A0'   // NO-BREAK SPACE
                || c == '\u2007'   // FIGURE SPACE
                || c == '\u202F'   // NARROW NO-BREAK SPACE
                || c == '\u0085';  // NEXT LINE（Java 归类为控制字符，不算 whitespace）
    }
}
