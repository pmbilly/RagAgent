package com.ragagent.wiki.service;

/**
 * 字符串工具，只收录 Java 标准方法与 wiki 所需语义<b>不一致</b>的那几个函数。
 *
 * <p>Java 的 {@code String.trim()} / {@code strip()} 与本类的空白定义并不等价：</p>
 * <ul>
 *   <li>Java {@code strip()} 用 {@link Character#isWhitespace}，它<b>不含</b>
 *       U+00A0（NBSP）、U+2007、U+202F；</li>
 *   <li>Java {@code strip()} 用 {@link Character#isWhitespace}，它<b>含</b>
 *       U+001C–U+001F 这些不应视为空白的控制字符；</li>
 *   <li>本类的空白定义 = ASCII 空白 + Unicode White_Space 属性码点，
 *       含 U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028/2029、U+202F、U+205F、U+3000。</li>
 * </ul>
 *
 * <p>这些差异在 ASCII 场景下不可见，但 wiki prompt / slug 会处理中文与 PDF 抽取文本，
 * NBSP 与全角空格都不罕见，故这里按上述定义<b>逐码点</b>实现。</p>
 */
final class GoStrings {

    private GoStrings() {}

    /**
     * 按<b>码点序</b>比较，对合法 UTF-8 字符串等价于 UTF-8 字节序。
     *
     * <p>对合法的 UTF-8 字符串，字节序等价于<b>码点序</b>——因此 Java 侧用码点比较
     * 即可精确复刻，而<b>不能</b>用 {@link String#compareTo}（那是 UTF-16 码元序）。
     * 两者只在"增补平面字符 vs U+E000–U+FFFF 之间的 BMP 字符"上分歧
     * （UTF-16 里代理对 D800–DFFF 排在 E000 之前，码点序则相反）。
     * 目录名 / slug 里出现 emoji 时这个分歧就会变成实测的顺序差异，
     * 而顺序直接影响 prompt 字节，进而影响 provider 前缀缓存——所以按码点比。</p>
     */
    static int compareByCodePoints(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * 本类的空白定义（ASCII 空白 + Unicode White_Space 属性码点）是否命中。
     *
     * <p>刻意不使用 {@code Character.isWhitespace}/{@code isSpaceChar} 的组合——
     * 二者并集与该定义不一致（多出 U+001C–U+001F），也与 JDK 版本演进的
     * 模糊地带纠缠；显式列举反而稳定。</p>
     */
    static boolean isSpace(int cp) {
        return cp == '\t' || cp == '\n' || cp == 0x0B || cp == '\f' || cp == '\r'
                || cp == ' '
                || cp == 0x85   // NEL
                || cp == 0xA0   // NBSP
                || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A)
                || cp == 0x2028 || cp == 0x2029
                || cp == 0x202F || cp == 0x205F
                || cp == 0x3000;
    }

    /**
     * 裁掉首尾的空白（定义见 {@link #isSpace}）。
     * 按<b>码点</b>推进，代理对不会被拆开。
     */
    static String trimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
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
