package com.ragagent.storage.support;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在内容增量流里重写存储引用，并把"可能被切断的引用尾巴"先扣住。
 *
 * <h2>为什么需要扣留</h2>
 * <p>内容按分片送到客户端（SSE 的 answer 增量，或 IM 渠道 300ms 一批的 flush）。
 * 一条存储引用可能横跨两个分片——只看见一个分片的重写会留下一个坏掉的碎片。
 * 本类把分片尾部"可能还没写完"的引用扣住，等下一次 Push 补全后再一起发出。</p>
 *
 * <h2>键</h2>
 * <p>每条逻辑流由一个 key 标识（WeKnora 用 SSE 的 event id——也正是客户端累积用的那个键）。
 * 不同 key 的扣留缓冲互不影响，所以交错的 answer / thinking 流可以各自独立扣留。</p>
 *
 * <p>可并发使用。</p>
 *
 * <h2>⚠️ 尾部锚定必须用 {@code \z} 而不是 {@code $}</h2>
 * <p>Java 的 {@code $} 除文本末尾外还匹配**末尾换行符之前**。用 {@code $} 的话，
 * {@code "text local://abc\n"} 这种以换行结尾的分片会被判成"尾部有不完整引用"
 * 而整段扣住，本应照常发出。
 * 注意默认的 {@code \b} 就是 ASCII 词边界，这一处无需特别处理。</p>
 */
public class StreamRewriter {

    // ── 不完整引用检测 ──

    /**
     * 匹配**一直延伸到字符串末尾**的存储引用——它可能在下个分片里继续。
     * {@code \z} 见类注释。
     */
    private static final Pattern INCOMPLETE_REF_SUFFIX = Pattern.compile(
            "\\b(?:resource|storage|local|minio|s3|cos|tos|oss|obs|ks3)://[^\\s)\\]>\"]*\\z");

    /**
     * 匹配目标 URL（括号里那部分）**还没闭合**的 Markdown 图片——
     * 例如 {@code ![alt](minio://part} 或 {@code ![alt](}。
     *
     * <p>只从 {@code minio://} 起扣留是不够的：那样 {@code ![alt](} 会先被发出去，
     * 等 URL 在下个分片到达时这张图已经坏了。</p>
     *
     * <p>目标部分必须无空白，且 {@link #findIncompleteMarkdownImage} 会封顶长度：
     * URL 既不含空白也不含换行，即便是预签名 URL 也远在封顶之下。
     * 没有这两条限制，仅仅"提到"一个未闭合 {@code …](} 的正文
     * （比如一段解释 Markdown 语法的回答，或代码示例）会永远看起来没闭合，把后续流卡死。</p>
     */
    private static final Pattern INCOMPLETE_MARKDOWN_IMAGE_SUFFIX = Pattern.compile(
            "!\\[[^\\]]*\\]\\([^)\\s]*\\z");

    /**
     * 尾部文本最多被当成"没写完的 Markdown 图片"的**字节数**。超过它就不像是个链接了，直接发出去而不是缓冲。
     */
    static final int MAX_INCOMPLETE_IMAGE_BYTES = 2048;

    /**
     * 单 key 扣留缓冲的**字节数**上限。
     * 一条存储引用加上它的 Markdown alt 文本远短于此；这个封顶只用来阻止一条病态流
     * （例如永远不闭合的 {@code ![}）把整篇回答缓冲下来。
     */
    static final int MAX_HELD_BYTES = 4096;

    /**
     * 返回 {@code s} 尾部"可能被截断的存储引用"的偏移，没有则返回 -1。
     *
     * <p>返回值是 <b>Java 字符下标</b>——Java 的 String 是 UTF-16，
     * 用字符下标才是自洽的；这个偏移不对外泄漏，只用于本类的内部切分。</p>
     */
    public static int findIncompleteRef(String s) {
        Matcher m = INCOMPLETE_REF_SUFFIX.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /**
     * 返回 {@code s} 尾部未闭合的 {@code ![alt](url} 的偏移，没有则返回 -1。
     */
    public static int findIncompleteMarkdownImage(String s) {
        // 优先把尾部的引用碎片与它前面最近的 `![…](` 配对，这样 alt 文本本身
        // 可以含 ']'（例如 `![a[b]](minio://part`）。
        int urlIdx = findIncompleteRef(s);
        if (urlIdx >= 0) {
            int imgIdx = s.substring(0, urlIdx).lastIndexOf("![");
            if (imgIdx >= 0 && s.substring(imgIdx, urlIdx).contains("](")) {
                return imgIdx;
            }
        }
        Matcher m = INCOMPLETE_MARKDOWN_IMAGE_SUFFIX.matcher(s);
        if (!m.find()) {
            return -1;
        }
        int locStart = m.start();
        if (utf8Length(s.substring(locStart)) > MAX_INCOMPLETE_IMAGE_BYTES) {
            return -1;
        }
        return locStart;
    }

    /**
     * 返回"该分片从哪个偏移起就不再安全"的位置；整段可发时返回 {@code s.length()}。
     */
    public static int holdbackCutoff(String s) {
        int cutoff = s.length();
        int imgIdx = findIncompleteMarkdownImage(s);
        if (imgIdx >= 0 && imgIdx < cutoff) {
            return imgIdx;
        }
        int refIdx = findIncompleteRef(s);
        if (refIdx >= 0 && refIdx < cutoff) {
            return refIdx;
        }
        return cutoff;
    }

    // ── 扣留缓冲 ──

    /** 一条被释放的尾巴：重写后的内容，以及最后一次贡献它的 Push 带进来的元数据。 */
    public record Held(String content, Object meta) {
    }

    /** 被扣住的尾巴，连同它来自的那个事件的元数据。 */
    private record HeldContent(String content, Object meta) {
    }

    private final Rewriter rewriter;
    private final Object mu = new Object();
    private final Map<String, HeldContent> held = new HashMap<>();

    /** 用按流的扣留状态包住 {@code rewriter}。 */
    public StreamRewriter(Rewriter rewriter) {
        this.rewriter = rewriter;
    }

    /** 底层重写器是否启用。 */
    public boolean enabled() {
        return rewriter != null && rewriter.enabled();
    }

    /**
     * 暴露底层 Rewriter，给那些**整块到达、
     * 不需要扣留**的流字段用（references、metadata）。
     */
    public Rewriter rewriter() {
        return rewriter;
    }

    /**
     * 喂入 {@code key} 这条流的下一个分片，返回已可发出的重写结果。
     *
     * <p>{@code flush} 在流的**终止分片**上置位，用来释放扣住的尾巴。
     * {@code meta} 不透明地与尾巴一起保留，并由 {@link #flushAll()} 原样交回，
     * 这样延迟释放的尾巴能带上与它被切下来时那个事件相同的元数据。</p>
     */
    public String push(String key, String chunk, boolean flush, Object meta) {
        if (!enabled()) {
            return chunk;
        }
        String emit;
        synchronized (mu) {
            HeldContent previous = held.get(key);
            String pending = (previous == null ? "" : previous.content()) + chunk;
            int cutoff = pending.length();
            if (!flush) {
                cutoff = holdbackCutoff(pending);
                // 绝不无界缓冲：超出上限就释放掉多余部分，即便它可能含一条残缺引用——
                // 那也正是"完全不重写"时会展示给用户的东西。
                int remainderBytes = utf8Length(pending.substring(cutoff));
                if (remainderBytes > MAX_HELD_BYTES) {
                    cutoff = charIndexAtByteOffset(pending, utf8Length(pending) - MAX_HELD_BYTES);
                }
            }
            emit = pending.substring(0, cutoff);
            String remainder = pending.substring(cutoff);
            if (remainder.isEmpty()) {
                held.remove(key);
            } else {
                held.put(key, new HeldContent(remainder, meta));
            }
        }
        // 重写在锁外做——
        // Rewriter 有自己的锁，两把锁不该套在一起。
        return rewriter.rewrite(emit);
    }

    /**
     * 释放所有被扣住的尾巴（重写后），按流 key 返回。
     *
     * <p>调用方在"流结束了却没有终止分片"时（例如客户端断开）用它，
     * 免得缓冲下来的内容被悄悄丢掉。</p>
     */
    public Map<String, Held> flushAll() {
        if (!enabled()) {
            return null;
        }
        Map<String, HeldContent> pending;
        synchronized (mu) {
            if (held.isEmpty()) {
                return null;
            }
            pending = new LinkedHashMap<>(held);
            held.clear();
        }
        Map<String, Held> out = new LinkedHashMap<>();
        for (Map.Entry<String, HeldContent> entry : pending.entrySet()) {
            HeldContent value = entry.getValue();
            out.put(entry.getKey(), new Held(rewriter.rewrite(value.content()), value.meta()));
        }
        return out;
    }

    // ── 字节 / 字符换算 ──

    /**
     * 字符串的 UTF-8 字节长度。
     *
     * <p>本类的两个上限都是**字节**数，而 {@code String} 按 UTF-16 计长。
     * 用字符数当字节数会让中文尾巴的上限放宽 3 倍——所以要真的数字节。</p>
     */
    static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            bytes += utf8Length(cp);
            i += Character.charCount(cp);
        }
        return bytes;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /**
     * 找出最大的字符下标 {@code i}，使得 {@code s[0:i]} 的 UTF-8 字节数 ≤ {@code byteOffset}
     * （即向前取整到字符边界）。
     *
     * <p>一个 4 字节的增补字符占 2 个 Java 字符，若字节偏移落在它内部，
     * 这里会落到它的**高位代理**下标上——正好是那个增补字符的起点。</p>
     */
    static int charIndexAtByteOffset(String s, int byteOffset) {
        if (byteOffset <= 0) {
            return 0;
        }
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpBytes = utf8Length(cp);
            if (bytes + cpBytes > byteOffset) {
                break;
            }
            bytes += cpBytes;
            i += Character.charCount(cp);
        }
        return i;
    }
}
