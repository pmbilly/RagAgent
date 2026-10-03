package com.ragagent.common.text;

/**
 * Unicode White_Space 空白处理（Java 原生组合实现）。
 *
 * <p><b>2026-10-03（B45）</b>：收编全仓多份重复实现（原 {@code common.wiki.GoStrings}、
 * {@code gitlab.GoStrings}、{@code rss.RssUtil}、{@code MemoryScopes}、{@code MemoryText}）。
 * 定义统一为 Unicode White_Space 属性（与 Go 的 {@code unicode.IsSpace} 一致）。</p>
 *
 * <h2>为什么不能用 {@code String.trim()} / {@code String.strip()}</h2>
 * <ul>
 *   <li>{@code trim()} 只去 {@code <= 0x20}，漏掉 U+0085 / U+00A0 / U+2000 等；</li>
 *   <li>{@code strip()} = {@link Character#isWhitespace}，<b>不含</b> U+00A0 / U+2007 /
 *       U+202F / U+0085 这 4 个（Java 刻意排除）。</li>
 * </ul>
 * <p>实际影响：凭据常从网页复制、夹进 NBSP——判定"配置缺失"（保存时报错）还是拿着
 * 含 NBSP 的串发请求（同步时 401），故障点差一个数量级。</p>
 *
 * <p><b>实现选择</b>：{@link Character#isSpaceChar}（覆盖全部 Zs / Zl / Zp，含 U+00A0 /
 * U+2007 / U+202F）+ 6 个 ASCII 控制字符（09-0D、85）——与 White_Space 精确等价，
 * 且复用 JDK 的 Unicode 表（不复刻码点清单）。</p>
 */
public final class Whitespace {

    private Whitespace() {
    }

    /** Unicode White_Space 判定（与 Go 的 {@code unicode.IsSpace} 一致）。 */
    public static boolean isSpace(int cp) {
        return Character.isSpaceChar(cp)
                || cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D
                || cp == 0x85;
    }

    /** 裁掉首尾空白（定义见 {@link #isSpace}）；{@code null} → 空串。按码点推进，代理对不拆开。 */
    public static String trimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }
}
