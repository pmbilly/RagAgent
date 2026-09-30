package com.ragagent.retrieval.support;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * chunk 编辑链路的纯逻辑辅助。
 * 本任务只允许改 {@code com.ragagent.knowledge.service}，故先落在服务包、命名
 * {@code ChunkSearchUtil}，后续 searchutil 波次整体落地时应迁往
 * {@code com.ragagent.retrieval.support}（公开方法签名保持不变即可平移）。</p>
 * 已知差异逐条标注在成员上。</p>
 */
public final class ChunkSearchUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * * {@code !\[([^\]]*)\]\(([^)]+)\)} —— Markdown 图片链接，分组 2 是 URL。
     * RE2 与 Java 的语义在这条正则上重合（无锚点、无回溯分歧），逐字照抄。
     */
    public static final Pattern MARKDOWN_IMAGE_REGEX =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /**
     * * {@code (?i)<img\b([^>]*?)\ssrc\s*=\s*['"]([^'"]+)['"]([^>]*)>} —— 带引号 src 的
     * src 前必须有空白（防 data-src 等连字符属性名误命中，否则会抓到懒加载占位图、
     * 漏掉真实 src）；无引号 src 与仅 srcset 的标签刻意不在范围内。
     * {@code [\t\n\f\r ]}（不含 {@code \x0B}），Java 默认<b>含</b> {@code \x0B}——
     * URL 里出现垂直制表符不可能，保留差异并在此注释，不改成显式字符类
     */
    public static final Pattern HTML_IMAGE_SRC_REGEX =
            Pattern.compile("(?i)<img\\b([^>]*?)\\ssrc\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>");

    public static final int HTML_IMAGE_SRC_URL_GROUP = 2;

    private static final int MIN_OVERLAP_CODE_POINTS = 12;
    private static final int DEFAULT_SEARCH_SPAN = 400;

    private static final int MAX_GENERATED_QUESTION_SOURCE_ID_LENGTH = 64;

    private ChunkSearchUtil() {
    }

    /**
     * content 里引用的
     * 图片 URL 集合，覆盖 Markdown 图片链接与带引号 src 的 HTML {@code <img>} 标签
     * （HTML 的 src 值先 {@code strings.TrimSpace}，Markdown 的按原文精确匹配）。
     * LinkedHashSet 取"扫描序"是确定性的超集）。
     */
    public static Set<String> imageURLsInContent(String content) {
        Set<String> urls = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return urls;
        }
        var md = MARKDOWN_IMAGE_REGEX.matcher(content);
        while (md.find()) {
            String url = md.group(2);
            if (url == null || url.isEmpty()) {
                continue; // 匹配组不足或 URL 为空的段跳过
            }
            urls.add(url);
        }
        var html = HTML_IMAGE_SRC_REGEX.matcher(content);
        while (html.find()) {
            String src = html.group(HTML_IMAGE_SRC_URL_GROUP);
            if (src == null) {
                continue;
            }
            String trimmed = trimSpace(src);
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }

    /**
     * image_info JSON
     * （{@code [{"url":...,"original_url":...}]}）里出现的全部 URL（url 与
     */
    public static Set<String> imageURLsFromInfo(String imageInfoJson) {
        Set<String> urls = new LinkedHashSet<>();
        if (imageInfoJson == null || imageInfoJson.isEmpty()) {
            return urls;
        }
        JsonNode arr;
        try {
            arr = MAPPER.readTree(imageInfoJson);
        } catch (Exception e) {
            return urls; // 解析失败返回空 map
        }
        if (!arr.isArray()) {
            return urls; // 结构不匹配返回空 map
        }
        for (JsonNode info : arr) {
            String url = info.path("url").asText("");
            if (!url.isEmpty()) {
                urls.add(url);
            }
            String original = info.path("original_url").asText("");
            if (!original.isEmpty()) {
                urls.add(original);
            }
        }
        return urls;
    }

    /**
     * 把两段当前 chunk 正文
     * 拼起来——完全包含则折叠、真实后缀/前缀重叠则去重、否则以 separator 相连。
     * 保守回退刻意宁可少量重复也不静默丢内容。重叠窗口上限
     * {@code defaultSearchSpan}（400 rune），防止 200KB 级编辑把匹配变成平方级
     * （parser 重叠窗口通常远低于该上限）。
     */
    public static String joinChunkContent(String acc, String next, String separator) {
        if (acc == null || acc.isEmpty()) {
            return next;
        }
        if (next == null || next.isEmpty()) {
            return acc;
        }
        if (containsChunkContent(acc, next)) {
            return acc;
        }
        if (containsChunkContent(next, acc)) {
            return next;
        }
        int[] accCodePoints = toCodePoints(acc);
        int[] nextCodePoints = toCodePoints(next);
        int maxOverlap = Math.min(accCodePoints.length, nextCodePoints.length);
        if (maxOverlap > DEFAULT_SEARCH_SPAN) {
            maxOverlap = DEFAULT_SEARCH_SPAN;
        }
        for (int overlap = maxOverlap; overlap >= MIN_OVERLAP_CODE_POINTS; overlap--) {
            if (codePointSlicesEqual(accCodePoints, accCodePoints.length - overlap, nextCodePoints, 0, overlap)) {
                return acc + fromCodePoints(java.util.Arrays.copyOfRange(nextCodePoints, overlap, nextCodePoints.length));
            }
        }
        return acc + separator + next;
    }

    /**
     * 完整正文是否被另一段
     * 安全包含。短于 {@code minOverlapRunes} 的子串不算包含（常见词/标点会造成误删）。
     */
    public static boolean containsChunkContent(String container, String contained) {
        if (container == null || container.isEmpty() || contained == null || contained.isEmpty()) {
            return false;
        }
        if (container.equals(contained)) {
            return true;
        }
        return codePointCount(contained) >= MIN_OVERLAP_CODE_POINTS && container.contains(contained);
    }

    /**
     * 生成问题的检索
     * source_id。PG 的 source_id 列是 varchar(64)，chunk UUID + "-" + question UUID 有
     * 73 字节——短 ID 保留历史表示，超长的只对 questionID 做 sha256 取前 12 字节 hex
     * （{@code chunkID + "-q" + 24 hex}，总长 62 字节，既有索引行仍可按 delete/reindex 寻址）。
     */
    public static String generatedQuestionSourceId(String chunkId, String questionId) {
        String candidate = chunkId + "-" + questionId;
        if (candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= MAX_GENERATED_QUESTION_SOURCE_ID_LENGTH) {
            return candidate;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(questionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return chunkId + "-q" + hex;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // JLS：SHA-256 恒存在
        }
    }

    /**
     * 缺 U+0085/U+00A0，显式复刻（与 ChunkRepository.trimSpace 同一份表）。
     * 包内可见：ChunkService 的内容编辑 trim 与 HTML src 清洗共用。
     */
    public static String trimSpace(String s) {
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

    private static boolean codePointSlicesEqual(int[] left, int leftFrom, int[] right, int rightFrom, int len) {
        for (int i = 0; i < len; i++) {
            if (left[leftFrom + i] != right[rightFrom + i]) {
                return false;
            }
        }
        return true;
    }

    public static int[] toCodePoints(String s) {
        return s == null ? new int[0] : s.codePoints().toArray();
    }

    public static String fromCodePoints(int[] codePoints) {
        StringBuilder b = new StringBuilder(codePoints.length);
        for (int r : codePoints) {
            b.appendCodePoint(r);
        }
        return b.toString();
    }

    static int codePointCount(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.codePointCount(0, s.length());
    }
}
