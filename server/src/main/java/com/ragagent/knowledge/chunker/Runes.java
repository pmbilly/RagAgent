package com.ragagent.knowledge.chunker;

/**
 * rune（Unicode code point）工具（对照 Go internal/infrastructure/chunker/splitter.go:267 runeLen）。
 *
 * <p>Go 的 {@code []rune} 切分与 {@code len([]rune(s))} 全部按 Unicode code point 计算；
 * Java 的 {@code char} 是 UTF-16 code unit，增补平面字符（如大部分 CJK 扩展、emoji）
 * 会用两个 char 表示。本类统一用 {@code int[]} code point 数组镜像 Go 的 {@code []rune}，
 * 长度一律用 {@link #len(String)}（codePointCount），杜绝 char/char 长度错位。</p>
 */
final class Runes {

    private Runes() {
    }

    /** 对照 Go {@code []rune(s)}。 */
    static int[] of(String s) {
        return s.codePoints().toArray();
    }

    /** 对照 Go {@code string(runes[start:end])}。 */
    static String str(int[] runes, int start, int end) {
        return new String(runes, start, end - start);
    }

    /** 对照 Go {@code utf8.RuneCountInString(s)}。 */
    static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** UTF-16 char 偏移 → rune 偏移（对照 Go {@code utf8.RuneCountInString(text[:byteIdx])} 的单调换算）。 */
    static int runeIndexAtChar(String s, int charIdx) {
        return s.codePointCount(0, charIdx);
    }

    /** 对照 Go {@code strings.Count(text, needle)}。 */
    static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
