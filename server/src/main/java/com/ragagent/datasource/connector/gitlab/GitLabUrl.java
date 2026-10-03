package com.ragagent.datasource.connector.gitlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitLab 出站 URL 的转义原语：{@code PathEscape} / {@code PathUnescape} /
 * {@code Values.Encode}。
 *
 * <p><b>2026-10-03（B47）</b>：原 {@code GoUrl} 改名——转义集合源自 Go {@code net/url}
 * 的历史对齐；B46 已用 95 位可打印 ASCII 全表实测裁决<b>保留行为</b>（与 JDK/Spring
 * 原生编码器有 7+2 处语义差异，替换会改出站 URL 字节）。</p>
 *
 * <h2>为什么不直接用 {@code java.net.URLEncoder} / {@code URI}</h2>
 * <p>连接器的请求 URL 是<b>发往 GitLab 的线上字节</b>，而 GitLab 侧对
 * {@code repository/files/<path>} 这一段的解码按同一套转义规则。JDK 的
 * {@code URLEncoder} 是 {@code application/x-www-form-urlencoded} 语义
 * （空格写 {@code +}、{@code ~} 被转义、不区分 path/query 模式），
 * {@code URI} 的构造又会自己做一次规范化。任何一处分叉都会让
 * {@code group%2Fproject} 变成 {@code group%252Fproject} 之类的双重转义——
 * GitLab 会 404，而这正是本项目最贵的一类 bug（只在真集成时暴露）。
 * 所以这里按 {@code shouldEscape(c, mode)} 的规则表自己算。</p>
 *
 * <h2>三个原语</h2>
 * <ul>
 *   <li>{@link #pathEscape}（path segment 模式）：
 *       只保留字母数字与 {@code - _ . ~}，另外把 {@code $ & + : @} 也保留
 *       （RFC 3986 §2.2 允许它们在 path segment 里出现）；
 *       而 {@code / ; , ?} 必须转义——{@code /} 要转义正是"路径分隔"的用法。</li>
 *   <li>{@link #pathUnescape}：只解 {@code %XX}，
 *       <b>不</b>把 {@code +} 当空格（那是 query 模式才有的规则）；
 *       非法转义抛 {@link InvalidEscapeException}，调用方（{@code projectPath}）会保留原串。</li>
 *   <li>{@link #valuesEncode}：键<b>排序</b>后
 *       {@code k=v} 用 {@code &} 连接，键值都走 {@code QueryEscape}
 *       （空格写 {@code +}，其余按 query 模式全转义）。</li>
 * </ul>
 *
 * <p><b>内部工具，不是契约</b>：它不落 jsonb、也不进任何 HTTP 响应体，
 * 只决定出站请求的 URL 字节。转义表由 {@code GitLabCompatTest}
 * 用对 0x20-0x7E 全量打印的 95 位标记串逐字符钉住。</p>
 */
final class GitLabUrl {

    private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();

    private GitLabUrl() {
    }

    /** 路径段转义。 */
    static String pathEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() * 3);
        for (byte c : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            if (shouldEscapePathSegment(c)) {
                b.append('%').append(UPPER_HEX[(c >> 4) & 0x0F]).append(UPPER_HEX[c & 0x0F]);
            } else {
                b.append((char) (c & 0xFF));
            }
        }
        return b.toString();
    }

    /**
     * 解码 {@code %XX} 转义。
     *
     * @throws InvalidEscapeException 出现 {@code %XX} 之外的 {@code %}
     *                                （消息：{@code invalid URL escape "%zz"}）
     */
    static String pathUnescape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        byte[] src = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[src.length];
        int w = 0;
        for (int i = 0; i < src.length; ) {
            if (src[i] != '%') {
                // path segment 模式下不把 '+' 当空格
                out[w++] = src[i++];
                continue;
            }
            if (i + 2 >= src.length || !isHex(src[i + 1]) || !isHex(src[i + 2])) {
                // 错误里带的是从 '%' 起最多 3 字节的原文
                int end = Math.min(i + 3, src.length);
                throw new InvalidEscapeException(
                        new String(src, i, end - i, java.nio.charset.StandardCharsets.UTF_8));
            }
            out[w++] = (byte) ((unhex(src[i + 1]) << 4) | unhex(src[i + 2]));
            i += 3;
        }
        return new String(out, 0, w, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 查询串转义。 */
    static String queryEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() * 3);
        for (byte c : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            if (c == ' ') {
                b.append('+');
            } else if (shouldEscapeQueryComponent(c)) {
                b.append('%').append(UPPER_HEX[(c >> 4) & 0x0F]).append(UPPER_HEX[c & 0x0F]);
            } else {
                b.append((char) (c & 0xFF));
            }
        }
        return b.toString();
    }

    /**
     * 键按<b>字节序</b>排序，
     * {@code QueryEscape(k) + "=" + QueryEscape(v)} 用 {@code &} 连接。
     *
     * <p>本连接器只用单值，故签名收窄成 {@code Map<String,String>}；多值展开本模块不可达。</p>
     */
    static String valuesEncode(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>(values.keySet());
        // 键都是连接器自己写的 ASCII 字面量，String.compareTo 即字节序
        keys.sort(String::compareTo);
        StringBuilder b = new StringBuilder();
        for (String key : keys) {
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(queryEscape(key)).append('=').append(queryEscape(values.get(key)));
        }
        return b.toString();
    }

    // ── shouldEscape 的规则表 ───────────────────────────────────────────

    /**
     * {@code shouldEscape(c, encodePathSegment)} 的规则：
     *
     * <pre>
     *     alnum / '-' '_' '.' '~'                 → false
     *     '$' '&' '+' ',' '/' ':' ';' '=' '?' '@' → 仅 c=='/' || c==';' || c==',' || c=='?' 时 true
     *     其余                                     → true
     * </pre>
     */
    private static boolean shouldEscapePathSegment(byte c) {
        if (isAlnum(c)) {
            return false;
        }
        switch (c) {
            case '-':
            case '_':
            case '.':
            case '~':
                return false;
            case '$':
            case '&':
            case '+':
            case ':':
            case '=':
            case '@':
                return false;
            default:
                return true;
        }
    }

    /**
     * {@code shouldEscape(c, encodeQueryComponent)} 的规则：保留集只有
     * alnum 与 {@code - _ . ~}，其余（含全部 reserved）都转义；
     * 空格由调用方（{@link #queryEscape}）单独写成 {@code +}。
     */
    private static boolean shouldEscapeQueryComponent(byte c) {
        if (isAlnum(c)) {
            return false;
        }
        switch (c) {
            case '-':
            case '_':
            case '.':
            case '~':
                return false;
            default:
                return true;
        }
    }

    private static boolean isAlnum(byte c) {
        int v = c & 0xFF;
        return (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9');
    }

    private static boolean isHex(byte c) {
        return unhex(c) >= 0;
    }

    private static int unhex(byte c) {
        int v = c & 0xFF;
        if (v >= '0' && v <= '9') {
            return v - '0';
        }
        if (v >= 'a' && v <= 'f') {
            return v - 'a' + 10;
        }
        if (v >= 'A' && v <= 'F') {
            return v - 'A' + 10;
        }
        return -1;
    }

    /** 非法转义异常（消息体只用于内部诊断）。 */
    static class InvalidEscapeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidEscapeException(String fragment) {
            super("invalid URL escape \"" + fragment + "\"");
        }
    }
}
