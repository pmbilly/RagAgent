package com.ragagent.datasource.connector.rss;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RSS 连接器的**纯函数**集合（内容指纹、feed 信号指纹、条目 ID、
 * {@code firstNonEmpty}、文件名净化、游标前滚）。
 *
 * <h2>逐字节契约</h2>
 * <p>本类每个方法都有对应用例（见 {@code RssPureFunctionsTest} 的类注释）。
 * 它们是游标指纹的算法本体，
 * 必须与既有数据逐字节一致——游标要落 {@code last_sync_cursor} 这个 jsonb 列，
 * 新旧数据要能互读。</p>
 *
 * <h2>为什么把 {@code goTrim} 单列出来</h2>
 * <p>这里需要的空白集合按 {@code unicode.IsSpace} 语义：
 * 而 Java 的 {@code String.trim()} 只处理 {@code <= U+0020}、
 * {@code String.strip()} 用 {@code Character.isWhitespace}（<b>不含</b> U+00A0 / U+2007 / U+202F）。
 * 三者不同，所以显式实现（与 memory 模块的 {@code isGoSpace} 同一处置）。</p>
 */
final class RssUtil {

    /** {@code time.RFC3339} 的输出形状（无小数秒，UTC 时区写作 {@code Z}）。 */
    private static final DateTimeFormatter RFC3339_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.UTC);

    private RssUtil() {
    }

    // ── 指纹 ───────────────────────────────────────────────────────────────

    /**
     * 对最终要灌入的 Markdown 取 SHA-256，
     * 取前 16 个十六进制字符并加 {@code "h:"} 前缀（小写十六进制）。
     */
    static String contentFingerprint(String markdown) {
        return "h:" + sha256HexPrefix(markdown);
    }

    /**
     * 把 feed 里可见的字段拼成一个多行串再哈希，
     * 用来在增量同步时判断"这条 entry 在 feed 层面有没有变"——没变就<b>跳过文章页抓取</b>。
     *
     * <p>拼接顺序（逐字节契约，连空行都不能少）：
     * {@code GUID \n Link \n Title \n [updated RFC3339] \n [published RFC3339] \n feedContent}。
     * 两个时间是 UTC RFC3339，缺省时该段为空串。</p>
     */
    static String feedSignalFingerprint(FeedParser.ParsedItem item, String feedContent) {
        if (item == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(nullToEmpty(item.guid())).append('\n');
        b.append(nullToEmpty(item.link())).append('\n');
        b.append(nullToEmpty(item.title())).append('\n');
        b.append(formatRfc3339OrEmpty(item.updatedParsed())).append('\n');
        b.append(formatRfc3339OrEmpty(item.publishedParsed())).append('\n');
        b.append(nullToEmpty(feedContent));
        return "s:" + sha256HexPrefix(b.toString());
    }

    /**
     * 把条目 ID 限定在它所属 feed 之下，
     * 这样不同 feed 的相同 GUID 不会互相覆盖。
     */
    static String itemExternalID(String feedUrl, String itemId) {
        return nullToEmpty(feedUrl) + ":" + nullToEmpty(itemId);
    }

    // ── 游标前滚 ───────────────────────────────────────────────────────────

    /**
     * 每个 feed 抓取/解析失败时，把它<b>上一轮</b>的
     * 条目指纹与信号原样搬进新游标——否则一次网络抖动就会让下次同步把整个 feed
     * 当成"全新内容"重灌一遍。
     *
     * <p>两个跳过条件：源 map 为 {@code null} 或长度 0 都不搬
     * （所以空 map 不会在游标里留下一个空 feed 键）。</p>
     */
    static void copyFeedCursor(RssCursor dst, RssCursor prev, String feedUrl) {
        if (dst == null || prev == null) {
            return;
        }
        Map<String, Map<String, String>> prevItems = prev.getFeedItems();
        if (prevItems != null) {
            Map<String, String> src = prevItems.get(feedUrl);
            if (src != null && !src.isEmpty()) {
                if (dst.getFeedItems() == null) {
                    dst.setFeedItems(new LinkedHashMap<>());
                }
                dst.getFeedItems().put(feedUrl, new LinkedHashMap<>(src));
            }
        }
        Map<String, Map<String, String>> prevSignals = prev.getFeedSignals();
        if (prevSignals != null) {
            Map<String, String> src = prevSignals.get(feedUrl);
            if (src != null && !src.isEmpty()) {
                if (dst.getFeedSignals() == null) {
                    dst.setFeedSignals(new LinkedHashMap<>());
                }
                dst.getFeedSignals().put(feedUrl, new LinkedHashMap<>(src));
            }
        }
    }

    // ── 字符串 ─────────────────────────────────────────────────────────────

    /**
     * 返回第一个去空白后非空的参数
     * （<b>返回的是原值，不是去空白之后的值</b>）。
     */
    static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !goTrim(v).isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 把标题变成一个安全的文件名，并在
     * <b>UTF-8 码点边界</b>上截到 200 <b>字节</b>。
     *
     * <p>处理顺序：去空白 → 空则 {@code "untitled"} →
     * 替换 {@code / \ : * ? " < > |} 为 {@code _}、{@code \n \r \t} 为空格 →
     * 再去空白 → 空则 {@code "untitled"} → 按需截断。</p>
     *
     * <p>⚠️ {@code maxBytes} 判的是<b>字节长度</b>，
     * 所以中文标题会在 66 个字左右被截断，而不是 200 个字。截断后还要逐字节回退，
     * 直到保留最后一个"完整的" UTF-8 序列。</p>
     *
     * <p>⚠️ 本模块与语雀/飞书那份的差别：RSS 这份<b>先把 {@code \n \r \t} 换成空格再去空白</b>，
     * 于是 {@code "a\nb"} → {@code "a b"}（不是 {@code "ab"}）。别照搬别的模块。</p>
     */
    static String sanitizeFileName(String name) {
        String n = goTrim(name);
        if (n.isEmpty()) {
            return "untitled";
        }
        StringBuilder replaced = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            switch (c) {
                case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> replaced.append('_');
                case '\n', '\r', '\t' -> replaced.append(' ');
                default -> replaced.append(c);
            }
        }
        String result = goTrim(replaced.toString());
        if (result.isEmpty()) {
            return "untitled";
        }
        final int maxBytes = 200;
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return result;
        }
        return new String(truncateUtf8(bytes, maxBytes), StandardCharsets.UTF_8);
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /** 去两端的 Unicode 空白（见类注释的空白集合）。 */
    static String goTrim(String s) {
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
     * 空白判定。
     *
     * <p>{@code Character.isWhitespace} 已经覆盖 {@code \t \n \v \f \r}、U+0085 与大部分 Zs，
     * 但<b>不含</b> U+00A0 / U+2007 / U+202F（Java 视它们为"非断行空格"）。
     * 这里需要的空白集合<b>含</b>这三个。</p>
     */
    private static boolean isGoSpace(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F';
    }

    /**
     * 截到 {@code maxBytes} 之后，只要最后一个字节序列不是完整的 UTF-8 码点就再退一个字节。
     *
     * <p>逐字节后退，直到起点是一个合法首字节且序列完整；
     * 输入本身是合法 UTF-8，所以只需检查一次。</p>
     */
    private static byte[] truncateUtf8(byte[] bytes, int maxBytes) {
        int end = maxBytes;
        while (end > 0) {
            int lead = end - 1;
            while (lead >= 0 && (bytes[lead] & 0xC0) == 0x80) {
                lead--;
            }
            if (lead < 0) {
                return Arrays.copyOf(bytes, 0);
            }
            int c = bytes[lead] & 0xFF;
            int need;
            if (c < 0x80) {
                need = 1;
            } else if ((c & 0xE0) == 0xC0) {
                need = 2;
            } else if ((c & 0xF0) == 0xE0) {
                need = 3;
            } else if ((c & 0xF8) == 0xF0) {
                need = 4;
            } else {
                end = lead; // 非法首字节：丢掉它（Go 也是退 1 字节）
                continue;
            }
            if (lead + need == end) {
                break; // 末尾正好是一个完整 rune
            }
            end = lead; // 序列不完整（或被截断）：退到这个 rune 的起点之前
        }
        return Arrays.copyOf(bytes, Math.max(end, 0));
    }

    /** null map 或键缺席时回空串。 */
    static String mapGet(Map<String, String> map, String key) {
        if (map == null) {
            return "";
        }
        String v = map.get(key);
        return v == null ? "" : v;
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * "没有时间"判定：{@code null} 或零值时间
     * （{@code feedSignalFingerprint} 就是这么判的）。
     */
    static boolean isZeroTime(OffsetDateTime t) {
        return t == null || ZERO_INSTANT.equals(t.toInstant());
    }

    /** 零值时间的瞬时（{@code 0001-01-01T00:00:00Z}，即"没有时间"哨兵）。 */
    static final java.time.Instant ZERO_INSTANT =
            java.time.Instant.parse("0001-01-01T00:00:00Z");

    static String formatRfc3339OrEmpty(OffsetDateTime t) {
        if (isZeroTime(t)) {
            return "";
        }
        return RFC3339_UTC.format(t);
    }

    /** 小写十六进制 SHA-256 的前 16 个字符。 */
    private static String sha256HexPrefix(String value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
        byte[] sum = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((sum[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(sum[i] & 0xF, 16));
        }
        return hex.toString();
    }

}
