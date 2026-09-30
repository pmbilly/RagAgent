package com.ragagent.retrieval.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索侧文本工具（对照 Go {@code internal/searchutil/textutil.go} 全文）。
 *
 * <h2>⚠️ 唯一实质降级：中文分词（jieba）</h2>
 * <p>Go 的 {@code TokenizeSimple} 对含中文的文本用 gojieba 的
 * {@code CutForSearch}（search 模式细粒度切分）。本项目不允许新增依赖，且
 * <b>Java 侧没有等价分词器</b>——与波 0 RSS 的 readability 降级同类，这里做成接缝：
 * {@link Segmenter} 接口的默认实现 {@link UnavailableSegmenter} 退化为
 * 「按空白切 + 把连续 CJK 段切成二字滑窗」。<b>与 Go 的 jieba 分词结果不逐词一致</b>；
 * 去重/Jaccard 的相对语义保留（同为集合、同样过滤单 rune 与纯标点），但具体分词
 * 边界会分叉。内容签名/包含判断等与分词无关的函数不受影响。</p>
 */
public final class SearchTextUtil {

    private SearchTextUtil() {
    }

    /**
     * 对照 BuildContentSignature：小写 → trim → 空白折叠 → 全文 MD5 hex。
     * 空内容返回 ""。
     */
    public static String buildContentSignature(String content) {
        String c = goTrimSpace(content == null ? "" : content.toLowerCase(java.util.Locale.ROOT));
        if (c.isEmpty()) {
            return "";
        }
        c = String.join(" ", goFields(c));
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(c.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 containsChinese：是否含 CJK 统一表意文字（unicode.Han）。 */
    static boolean containsChinese(String text) {
        return text.codePoints().anyMatch(SearchTextUtil::isHan);
    }

    private static boolean isHan(int r) {
        // unicode.Han（Go rangeTable）：CJK 统一表意及其扩展区 + 兼容表意 + 计数符
        return (r >= 0x2E80 && r <= 0x2EF3)      // CJK 部首/笔画（Han 范围起）
                || (r >= 0x2F00 && r <= 0x2FD5)
                || (r >= 0x3005 && r <= 0x3005)
                || (r >= 0x3007 && r <= 0x3007)
                || (r >= 0x3021 && r <= 0x3029)
                || (r >= 0x3038 && r <= 0x303B)
                || (r >= 0x3400 && r <= 0x4DBF)
                || (r >= 0x4E00 && r <= 0x9FFF)
                || (r >= 0xF900 && r <= 0xFAFF)
                || (r >= 0x20000 && r <= 0x2FA1F);
    }

    /** 中文分词接缝（默认实现降级，见类注释）。 */
    public interface Segmenter {
        /** 对照 jieba.CutForSearch(text, true)：search 模式切词。 */
        java.util.List<String> cutForSearch(String text);
    }

    /** 默认分词实现（jieba 不可用 → 二字滑窗近似）。 */
    public static final class UnavailableSegmenter implements Segmenter {
        @Override
        public java.util.List<String> cutForSearch(String text) {
            java.util.List<String> out = new java.util.ArrayList<>();
            int[] runes = text.codePoints().toArray();
            int i = 0;
            while (i < runes.length) {
                if (isHan(runes[i])) {
                    // CJK 连段：二字滑窗（末尾单字也算一段，交由调用方过滤）
                    int j = i;
                    while (j < runes.length && isHan(runes[j])) {
                        j++;
                    }
                    for (int k = i; k < j; k++) {
                        StringBuilder b = new StringBuilder();
                        b.appendCodePoint(runes[k]);
                        if (k + 1 < j) {
                            b.appendCodePoint(runes[k + 1]);
                        }
                        out.add(b.toString());
                    }
                    i = j;
                } else {
                    int j = i;
                    StringBuilder b = new StringBuilder();
                    while (j < runes.length && !isHan(runes[j])) {
                        b.appendCodePoint(runes[j]);
                        j++;
                    }
                    out.add(b.toString());
                    i = j;
                }
            }
            return out;
        }
    }

    private static volatile Segmenter jieba = new UnavailableSegmenter();

    /** 注入真实分词器（后续接入 ANSJ/HanLP 等时的唯一改点）。 */
    public static void setSegmenter(Segmenter s) {
        if (s != null) {
            jieba = s;
        }
    }

    /**
     * 分词接缝的读取口——Qdrant 驱动的 {@code tokenizeQuery} 需要
     * {@code CutForSearch} 的<b>原始词序列</b>（Go 侧直接调 {@code types.Jieba.CutForSearch}，
     * 之后自己做 trim/lower/长度过滤/去重），套 {@code tokenizeSimple} 的归一化会丢序/多滤。
     */
    public static Segmenter segmenter() {
        return jieba;
    }

    /**
     * 对照 TokenizeSimple：小写 trim 后分词（中文走 jieba 接缝，否则按空白），
     * 过滤单 rune 与纯标点/空白 token，返回唯一 token 集。空文本返回空集
     * （Go 返回 nil——Java 侧空集等价）。
     */
    public static Set<String> tokenizeSimple(String text) {
        String t = goTrimSpace((text == null ? "" : text).toLowerCase(java.util.Locale.ROOT));
        if (t.isEmpty()) {
            return new LinkedHashSet<>();
        }
        java.util.List<String> words = containsChinese(t)
                ? jieba.cutForSearch(t)
                : goFields(t);
        Set<String> set = new LinkedHashSet<>();
        for (String w : words) {
            w = goTrimSpace(w);
            if (w.codePointCount(0, w.length()) > 1 && !isAllPunct(w)) {
                set.add(w);
            }
        }
        return set;
    }

    /** 对照 isAllPunct：全部是标点/空白/符号。 */
    static boolean isAllPunct(String s) {
        for (int r : s.codePoints().toArray()) {
            if (!isPunct(r) && !Character.isWhitespace(r) && !isSymbol(r)) {
                return false;
            }
        }
        return true;
    }

    /** unicode.IsPunct 的近似（ASCII + 常见 Unicode 标点块）。 */
    private static boolean isPunct(int r) {
        byte type = (byte) Character.getType(r);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION
                || type == Character.START_PUNCTUATION;
    }

    private static boolean isSymbol(int r) {
        byte type = (byte) Character.getType(r);
        return type == Character.CURRENCY_SYMBOL || type == Character.MATH_SYMBOL
                || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL;
    }

    /** 对照 Jaccard：两个 token 集的 Jaccard 相似度（双空集 → 0）。 */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.size() > b.size()) {
            return jaccard(b, a);
        }
        int inter = 0;
        for (String k : a) {
            if (b.contains(k)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        if (union == 0) {
            return 0;
        }
        return (double) inter / (double) union;
    }

    /** 对照 NormalizeContent：小写 + trim + 空白折叠。 */
    public static String normalizeContent(String s) {
        return buildContentSignaturePrefix(s);
    }

    private static String buildContentSignaturePrefix(String s) {
        String c = goTrimSpace((s == null ? "" : s).toLowerCase(java.util.Locale.ROOT));
        if (c.isEmpty()) {
            return "";
        }
        return String.join(" ", goFields(c));
    }

    /**
     * 对照 IsContentContained：normalizedShort 是否为 normalizedLong 的子串。
     * 两个入参必须先经 {@link #normalizeContent}。
     */
    public static boolean isContentContained(String normalizedShort, String normalizedLong) {
        if (normalizedShort == null || normalizedShort.isEmpty()
                || normalizedLong == null || normalizedLong.isEmpty()) {
            return false;
        }
        if (normalizedShort.length() > normalizedLong.length()) {
            return false;
        }
        return normalizedLong.contains(normalizedShort);
    }

    /**
     * 对照 ContentOverlapRatio：overlap 系数 |交|/|较小集|；任一空集 → 0。
     */
    public static double contentOverlapRatio(String a, String b) {
        Set<String> tokA = tokenizeSimple(a);
        Set<String> tokB = tokenizeSimple(b);
        if (tokA.isEmpty() || tokB.isEmpty()) {
            return 0;
        }
        Set<String> small = tokA.size() > tokB.size() ? tokB : tokA;
        Set<String> large = small == tokA ? tokB : tokA;
        int inter = 0;
        for (String k : small) {
            if (large.contains(k)) {
                inter++;
            }
        }
        return (double) inter / (double) small.size();
    }

    /** 对照 ClampFloat。 */
    public static double clampFloat(double v, double minV, double maxV) {
        if (v < minV) {
            return minV;
        }
        if (v > maxV) {
            return maxV;
        }
        return v;
    }

    /** Go strings.Fields（unicode 空白切分）。 */
    static java.util.List<String> goFields(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && isGoSpace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !isGoSpace(s.charAt(i))) {
                i++;
            }
            if (start < i) {
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    private static boolean isGoSpace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000B': case '\f': case '\r':
            case ' ': case '\u0085': case '\u00A0': case '\u1680':
            case '\u2028': case '\u2029': case '\u202F': case '\u205F': case '\u3000':
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A';
        }
    }

    static String goTrimSpace(String s) {
        if (s == null) {
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
}
