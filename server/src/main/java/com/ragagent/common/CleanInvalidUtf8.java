package com.ragagent.common;

/**
 * 丢弃字符串中的 NUL 字符（\x00）与无效编码单元（孤立代理项）。
 *
 * <p>处理语义：无效输入一律<b>跳过删除</b>，不替换成 U+FFFD；
 * 孤立代理项逐个删除，NUL 直接跳过。</p>
 *
 * <h2>为何是"删除"而不是"替换成 U+FFFD"</h2>
 * <p>Java String 是 UTF-16，不存在"非法字节"，唯一对位的概念是
 * <b>孤立代理项</b>（lone surrogate，UTF-16 层面的非法编码单元）。
 * 本类按"跳过"语义处理：合法字符原样保留，非法编码单元直接消失。</p>
 *
 * <p>合法代理对（high+low 成对）是合法的 UTF-16，原样保留。其余字符（含 U+FFFD
 * 本身）不受影响。</p>
 */
public final class CleanInvalidUtf8 {

    private CleanInvalidUtf8() {
    }

    public static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int len = s.length();
        StringBuilder b = new StringBuilder(len);
        for (int i = 0; i < len; ) {
            char c = s.charAt(i);
            if (c == '\u0000') {
                // NUL：跳过
                i++;
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                // 合法代理对 → 合法码点，原样保留
                b.append(c).append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
                // 孤立代理项 = UTF-16 层面的非法编码单元 → 跳过
                i++;
                continue;
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }
}
