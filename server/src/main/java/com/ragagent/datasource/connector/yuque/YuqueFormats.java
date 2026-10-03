package com.ragagent.datasource.connector.yuque;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.ragagent.common.web.GoTimeSerializer;

/**
 * 语雀连接器的**纯函数**（文档 URL 拼接、内容更新时间解析、
 * 文件名净化）。
 *
 * <p>期望值由 {@code YuqueFormatsTest} 逐条钉住。</p>
 *
 * <h2>{@code sanitizeFileName} 与飞书那份**写法不同**</h2>
 * <p>飞书的同名函数带"保留扩展名"的逻辑，语雀与 IMA 都没有；而且语雀的调用点
 * 自己拼 {@code + ".md"}。三份刻意各自独立，不要统一。</p>
 */
public final class YuqueFormats {

    private YuqueFormats() {
    }

    /**
     * 给一篇语雀文档拼浏览器 URL。
     * {@code namespace} 在某些响应里可能为空——那就只回基地址。
     */
    public static String buildDocURL(String baseUrl, String namespace, String slug) {
        if (namespace == null || namespace.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "/" + namespace + "/" + slug;
    }

    /**
     * 解析语雀的 ISO 8601 时间戳，
     * <b>解析失败一律回零值时间</b>（不是 null，也不是抛异常）。
     *
     * <p>要求带偏移、接受可选小数秒、不接受"只有日期"；
     * 对非标准写法（如 {@code +0800} 缺冒号）也拒绝。</p>
     *
     * <p>返回的瞬时保留串里带的偏移；写出去时 {@link GoTimeSerializer} 会按
     * 既有规则归一化到 JVM 默认时区（dev 上就是 {@code +08:00}，
     * 与语雀返回的偏移一致）。</p>
     */
    public static OffsetDateTime parseContentUpdatedAt(String ts) {
        if (ts == null || ts.isEmpty()) {
            return GoTimeSerializer.GO_ZERO_DATE_TIME;
        }
        try {
            return OffsetDateTime.parse(ts);
        } catch (DateTimeParseException e) {
            return GoTimeSerializer.GO_ZERO_DATE_TIME;
        }
    }

    /**
     * 剔掉文件名非法字符，并在安全的 UTF-8
     * 边界上截断到 200 <b>字节</b>。
     *
     * <p>必须按码点边界截：裸字节截断会把一个多字节
     * 码点（中文在 UTF-8 里是 3 字节）切成两半，产出非法 UTF-8，
     * 下游会以"文件名包含非法字符"拒绝整份文件。</p>
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '/': case '\\': case ':': case '*': case '?':
                case '"': case '<': case '>': case '|':
                    sb.append('_');
                    break;
                default:
                    sb.append(c);
            }
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        final int maxBytes = 200;
        if (bytes.length > maxBytes) {
            bytes = truncateToValidUtf8(bytes, maxBytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 把字节前缀切成"仍是合法 UTF-8"的最长前缀（一个码点最多 4 字节，
     * 所以要剥的尾巴不超过 3 字节）。
     */
    static byte[] truncateToValidUtf8(byte[] bytes, int maxBytes) {
        int end = Math.min(maxBytes, bytes.length);
        while (end > 0) {
            if (isValidUtf8Prefix(bytes, end)) {
                break;
            }
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(bytes, 0, out, 0, end);
        return out;
    }

    private static boolean isValidUtf8Prefix(byte[] bytes, int end) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, end));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
