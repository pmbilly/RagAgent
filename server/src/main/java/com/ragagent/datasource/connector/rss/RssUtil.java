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
 * RSS 连接器的**纯函数**集合（对照 Go {@code internal/datasource/connector/rss/types.go}
 * 里的 {@code contentFingerprint} / {@code feedSignalFingerprint} / {@code itemExternalID} /
 * {@code firstNonEmpty} / {@code sanitizeFileName} / {@code copyFeedCursor}）。
 *
 * <h2>这些函数的期望值是 Go 实录</h2>
 * <p>本类每个方法都有对应用例，期望值是把 Go 源码<b>原样抄进</b>一个独立程序跑出来的
 * （见 {@code RssPureFunctionsTest} 的类注释）。它们是游标指纹的算法本体，
 * 跨语言必须逐字节一致——游标要落 {@code last_sync_cursor} 这个 jsonb 列，
 * Go 写 Java 读、Java 写 Go 读都要能对齐。</p>
 *
 * <h2>为什么把 {@code goTrim} 单列出来</h2>
 * <p>Go 的 {@code strings.TrimSpace} 按 {@code unicode.IsSpace} 判空白，
 * 而 Java 的 {@code String.trim()} 只处理 {@code <= U+0020}、
 * {@code String.strip()} 用 {@code Character.isWhitespace}（<b>不含</b> U+00A0 / U+2007 / U+202F）。
 * 三者不同，所以这里显式实现 Go 的语义。这与 memory 模块的 {@code isGoSpace}、
 * 约定 §9 里那条 {@code \s} 差异是同族问题。</p>
 */
final class RssUtil {

    /** {@code time.RFC3339} 的输出形状（无小数秒，UTC 时区写作 {@code Z}）。 */
    private static final DateTimeFormatter RFC3339_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.UTC);

    private RssUtil() {
    }

    // ── 指纹 ───────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code contentFingerprint}：对最终要灌入的 Markdown 取 SHA-256，
     * 取前 16 个十六进制字符并加 {@code "h:"} 前缀。
     *
     * <p>Go 是 {@code hex.EncodeToString(sum[:])[:16]} —— 小写十六进制。</p>
     */
    static String contentFingerprint(String markdown) {
        return "h:" + sha256HexPrefix(markdown);
    }

    /**
     * 对照 Go {@code feedSignalFingerprint}：把 feed 里可见的字段拼成一个多行串再哈希，
     * 用来在增量同步时判断"这条 entry 在 feed 层面有没有变"——没变就<b>跳过文章页抓取</b>。
     *
     * <p>拼接顺序（逐字节照抄 Go，连空行都不能少）：
     * {@code GUID \n Link \n Title \n [updated RFC3339] \n [published RFC3339] \n feedContent}。
     * 两个时间是 {@code .UTC().Format(time.RFC3339)}，缺省（nil 或零值）时该段为空串。</p>
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
     * 对照 Go {@code itemExternalID}：把条目 ID 限定在它所属 feed 之下，
     * 这样不同 feed 的相同 GUID 不会互相覆盖。
     */
    static String itemExternalID(String feedUrl, String itemId) {
        return nullToEmpty(feedUrl) + ":" + nullToEmpty(itemId);
    }

    // ── 游标前滚 ───────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code copyFeedCursor}：某个 feed 抓取/解析失败时，把它<b>上一轮</b>的
     * 条目指纹与信号原样搬进新游标——否则一次网络抖动就会让下次同步把整个 feed
     * 当成"全新内容"重灌一遍。
     *
     * <p>照抄 Go 的两个跳过条件：源 map 为 {@code nil} 或长度 0 都不搬
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
     * 对照 Go {@code firstNonEmpty}：返回第一个 {@code TrimSpace} 之后非空的参数
     * （<b>返回的是原值，不是 trim 之后的值</b>——Go 里是 {@code if strings.TrimSpace(v) != "" { return v }}）。
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
     * 对照 Go {@code sanitizeFileName}：把标题变成一个安全的文件名，并在
     * <b>UTF-8 rune 边界</b>上截到 200 <b>字节</b>。
     *
     * <p>顺序照抄 Go：{@code TrimSpace} → 空则 {@code "untitled"} →
     * 替换 {@code / \ : * ? " < > |} 为 {@code _}、{@code \n \r \t} 为空格 →
     * 再 {@code TrimSpace} → 空则 {@code "untitled"} → 按需截断。</p>
     *
     * <p>⚠️ {@code maxBytes} 判的是<b>字节长度</b>（{@code len(result)} 在 Go 里对 string 是字节数），
     * 所以中文标题会在 66 个字左右被截断，而不是 200 个字。截断后还要逐字节回退，
     * 直到最后一个"完整的" UTF-8 序列——照抄 Go 的 {@code utf8.DecodeLastRuneInString} 循环。</p>
     *
     * <p>⚠️ 本模块与语雀/飞书那份的差别：RSS 这份<b>先把 {@code \n \r \t} 换成空格再 TrimSpace</b>，
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

    /** 对照 Go 的 {@code strings.TrimSpace}（{@code unicode.IsSpace} 语义）。 */
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
     * 对照 Go 的 {@code unicode.IsSpace}。
     *
     * <p>{@code Character.isWhitespace} 已经覆盖 {@code \t \n \v \f \r}、U+0085 与大部分 Zs，
     * 但<b>不含</b> U+00A0 / U+2007 / U+202F（Java 视它们为"非断行空格"）。
     * Go 的 {@code unicode.IsSpace} 含这三个（它们都在 {@code unicode.White_Space} 里）。</p>
     */
    private static boolean isGoSpace(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F';
    }

    /**
     * 对照 Go 的 {@code result[:maxBytes] + utf8.DecodeLastRuneInString} 回退循环：
     * 截到 {@code maxBytes} 之后，只要最后一个字节序列不是完整的 UTF-8 rune 就再退一个字节。
     *
     * <p>Go 是逐字节后退（{@code size == 1 && r == RuneError} 就再退），
     * 落到同一个边界上；因为输入本身是合法 UTF-8，两者结果一致。</p>
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

    /** Go 的 {@code nil} map 取出来是零值 {@code ""}；Java 的 map 取出来是 {@code null}。 */
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
     * 对照 Go 的 {@code t != nil && !t.IsZero()}：Go 的零值 {@code time.Time}
     * 在这里等同于"没有值"（{@code feedSignalFingerprint} 就是这么判的）。
     */
    static boolean isGoZeroTime(OffsetDateTime t) {
        return t == null || GO_ZERO_INSTANT.equals(t.toInstant());
    }

    /** Go 零值 {@code time.Time} 的瞬时（{@code 0001-01-01T00:00:00Z}）。 */
    static final java.time.Instant GO_ZERO_INSTANT =
            java.time.Instant.parse("0001-01-01T00:00:00Z");

    static String formatRfc3339OrEmpty(OffsetDateTime t) {
        if (isGoZeroTime(t)) {
            return "";
        }
        return RFC3339_UTC.format(t);
    }

    /** 小写十六进制 SHA-256 的前 16 个字符（对照 Go 的 {@code hex.EncodeToString(sum[:])[:16]}）。 */
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
