package com.ragagent.common.text;

/**
 * 码点序比较（对合法 UTF-8 字符串等价于 UTF-8 字节序）。
 *
 * <p><b>2026-10-03（B45）</b>：自 {@code common.wiki.GoStrings} 迁出（原为 Go
 * {@code strings.Compare} 语义的复刻）。Java 没有"按码点序比较"的标准 API，
 * 故保留自实现——它不是"复刻 Go 的字节形态"，而是"码点序"这一语义本身。</p>
 *
 * <p><b>为何不能用 {@link String#compareTo}</b>：那是 UTF-16 码元序，两者只在
 * 「增补平面字符 vs U+E000–U+FFFF 之间的 BMP 字符」上分歧（UTF-16 里代理对
 * D800–DFFF 排在 E000 之前，码点序则相反）。目录名 / slug 里出现 emoji 时，
 * 这个分歧会变成实测的顺序差异——而顺序直接影响 prompt 字节，进而影响
 * provider 前缀缓存。</p>
 */
public final class CodePointOrder {

    private CodePointOrder() {
    }

    /** 码点序比较；{@code null} 排在非 {@code null} 之前（短串在前）。 */
    public static int compare(String a, String b) {
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
}
