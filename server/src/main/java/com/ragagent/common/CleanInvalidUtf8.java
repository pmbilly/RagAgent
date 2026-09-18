package com.ragagent.common;

/**
 * 对照 Go {@code common.CleanInvalidUTF8}（internal/common/tools.go L189-210）：
 * 丢弃 NUL 字符（\x00）与非法 UTF-8 字节。
 *
 * <p>Go 的逐字节语义：{@code utf8.DecodeRuneInString} 解出
 * {@code RuneError && size == 1}（非法字节）→ <b>跳过该字节</b>（直接删除，
 * 不替换成 U+FFFD）；{@code r == 0}（NUL）→ 跳过。</p>
 *
 * <h2>Java 侧的取舍（为何是"删除"而不是"替换成 U+FFFD"）</h2>
 * <p>Java String 是 UTF-16，不存在"非法字节"——唯一对位的概念是
 * <b>孤立代理项</b>（lone surrogate，UTF-16 层面的非法编码单元，等价于 Go 字符串里的
 * 非法 UTF-8 序列）。对它有两个候选：</p>
 * <ul>
 *   <li>替换成 U+FFFD：Go 在<b>整串解码</b>的场景才是这个行为（如 {@code []byte→string}
 *       转换），本函数不是；</li>
 *   <li><b>删除（选择此项）</b>：Go 的本函数对非法字节就是"跳过"，孤立代理项按同一条
 *       规则丢弃，净效果逐字符对齐——合法字符原样保留，非法编码单元消失。</li>
 * </ul>
 *
 * <p>合法代理对（high+low 成对）是合法的 UTF-16，原样保留。其余字符（含 U+FFFD
 * 本身——Go 里它是合法 rune，会原样写回）不受影响。</p>
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
                // NUL：与 Go 同为跳过
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
                // 孤立代理项 = Java 侧对位的"非法 UTF-8 字节" → 按 Go 语义跳过
                i++;
                continue;
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }
}
