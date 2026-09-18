package com.ragagent.knowledge.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * chunk 编辑链路的纯逻辑辅助（对照 Go {@code internal/searchutil} 的
 * imageinfo.go / imageinfo_match.go / chunkmerge.go 与 {@code internal/types/faq.go}
 * 的 GeneratedQuestionSourceID）。
 *
 * <p><b>落点说明</b>：Go 里这些函数属 {@code searchutil} 包，被检索/富化/解析多路复用；
 * 本任务只允许改 {@code com.ragagent.knowledge.service}，故先落在服务包、命名
 * {@code ChunkSearchUtil}，后续 searchutil 波次整体落地时应迁往
 * {@code com.ragagent.searchutil}（公开方法签名保持不变即可平移）。</p>
 *
 * <p>全部为确定性纯函数（golden 依赖），<b>无降级</b>；正则与算法逐字对照 Go 源，
 * 已知差异逐条标注在成员上。</p>
 */
public final class ChunkSearchUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 对照 Go {@code MarkdownImageRegex}（imageinfo_match.go L16）：
     * {@code !\[([^\]]*)\]\(([^)]+)\)} —— Markdown 图片链接，分组 2 是 URL。
     * RE2 与 Java 的语义在这条正则上重合（无锚点、无回溯分歧），逐字照抄。
     */
    public static final Pattern MARKDOWN_IMAGE_REGEX =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /**
     * 对照 Go {@code HTMLImageSrcRegex}（imageinfo_match.go L36）：
     * {@code (?i)<img\b([^>]*?)\ssrc\s*=\s*['"]([^'"]+)['"]([^>]*)>} —— 带引号 src 的
     * HTML {@code <img>} 标签；分组 2 是 src 值（Go 的 HTMLImageSrcURLGroup = 2）。
     * src 前必须有空白（防 data-src 等连字符属性名误命中，否则会抓到懒加载占位图、
     * 漏掉真实 src）；无引号 src 与仅 srcset 的标签刻意不在范围内。
     *
     * <p><b>已知差异（保留，约定 §9 同族）</b>：Go 的 RE2 {@code \s} 是
     * {@code [\t\n\f\r ]}（不含 {@code \x0B}），Java 默认<b>含</b> {@code \x0B}——
     * URL 里出现垂直制表符不可能，保留差异并在此注释，不改成显式字符类
     * （那会离 Go 的写法更远、维护时更容易写错）。</p>
     */
    public static final Pattern HTML_IMAGE_SRC_REGEX =
            Pattern.compile("(?i)<img\\b([^>]*?)\\ssrc\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>");

    /** Go 的 HTMLImageSrcURLGroup：src 值在 HTMLImageSrcRegex 里的分组号。 */
    public static final int HTML_IMAGE_SRC_URL_GROUP = 2;

    /** Go chunkmerge.go L90：参与重叠匹配的最短后缀长度（rune）。 */
    private static final int MIN_OVERLAP_RUNES = 12;
    /** Go chunkmerge.go L93：JoinChunkContent 的后缀匹配窗口上限（rune）。 */
    private static final int DEFAULT_SEARCH_SPAN = 400;

    /** Go faq.go L34：source_id 超过该字节数时对 questionID 做 sha256 截断。 */
    private static final int MAX_GENERATED_QUESTION_SOURCE_ID_LENGTH = 64;

    private ChunkSearchUtil() {
    }

    /**
     * 对照 Go {@code ImageURLsInContent}（imageinfo_match.go L35-55）：content 里引用的
     * 图片 URL 集合，覆盖 Markdown 图片链接与带引号 src 的 HTML {@code <img>} 标签
     * （HTML 的 src 值先 {@code strings.TrimSpace}，Markdown 的按原文精确匹配）。
     * 返回保持插入序（Go 是 map、无序；多 URL 时的遍历序两侧都未定义契约，
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
                continue; // Go：len(match) < 3 || match[2] == "" 跳过
            }
            urls.add(url);
        }
        var html = HTML_IMAGE_SRC_REGEX.matcher(content);
        while (html.find()) {
            String src = html.group(HTML_IMAGE_SRC_URL_GROUP);
            if (src == null) {
                continue;
            }
            String trimmed = goTrimSpace(src);
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }

    /**
     * 对照 Go {@code ImageURLsFromInfo}（imageinfo.go L84-102）：image_info JSON
     * （{@code [{"url":...,"original_url":...}]}）里出现的全部 URL（url 与
     * original_url 都算，空串跳过）。JSON 非法/非数组时返回空集（Go 的 err 分支同款）。
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
            return urls; // Go: json.Unmarshal 失败 → 返回空 map
        }
        if (!arr.isArray()) {
            return urls; // Go: unmarshal 进 []types.ImageInfo 失败 → 空 map
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
     * 对照 Go {@code JoinChunkContent}（chunkmerge.go L15-45）：把两段当前 chunk 正文
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
        int[] accRunes = toRunes(acc);
        int[] nextRunes = toRunes(next);
        int maxOverlap = Math.min(accRunes.length, nextRunes.length);
        if (maxOverlap > DEFAULT_SEARCH_SPAN) {
            maxOverlap = DEFAULT_SEARCH_SPAN;
        }
        for (int overlap = maxOverlap; overlap >= MIN_OVERLAP_RUNES; overlap--) {
            if (runeSlicesEqual(accRunes, accRunes.length - overlap, nextRunes, 0, overlap)) {
                return acc + fromRunes(java.util.Arrays.copyOfRange(nextRunes, overlap, nextRunes.length));
            }
        }
        return acc + separator + next;
    }

    /**
     * 对照 Go {@code ContainsChunkContent}（chunkmerge.go L50-58）：完整正文是否被另一段
     * 安全包含。短于 {@code minOverlapRunes} 的子串不算包含（常见词/标点会造成误删）。
     */
    public static boolean containsChunkContent(String container, String contained) {
        if (container == null || container.isEmpty() || contained == null || contained.isEmpty()) {
            return false;
        }
        if (container.equals(contained)) {
            return true;
        }
        return runeCount(contained) >= MIN_OVERLAP_RUNES && container.contains(contained);
    }

    /**
     * 对照 Go {@code GeneratedQuestionSourceID}（types/faq.go L41-50）：生成问题的检索
     * source_id。PG 的 source_id 列是 varchar(64)，chunk UUID + "-" + question UUID 有
     * 73 字节——短 ID 保留历史表示，超长的只对 questionID 做 sha256 取前 12 字节 hex
     * （{@code chunkID + "-q" + 24 hex}，总长 62 字节，既有索引行仍可按 delete/reindex 寻址）。
     * 长度按 <b>字节</b>计（Go 的 len 是 UTF-8 字节数）。
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
     * Go 的 {@code strings.TrimSpace}（unicode.IsSpace 全集）。Java 的 {@code strip()}
     * 缺 U+0085/U+00A0，显式复刻（与 ChunkRepository.goTrimSpace 同一份表）。
     * 包内可见：ChunkService 的内容编辑 trim 与 HTML src 清洗共用。
     */
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

    /** Go unicode.IsSpace 的全集（White_Space property）。 */
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

    /** Go 的 {@code runeSlicesEqual}（等价 slice 表达式的区间比较）。 */
    private static boolean runeSlicesEqual(int[] left, int leftFrom, int[] right, int rightFrom, int len) {
        for (int i = 0; i < len; i++) {
            if (left[leftFrom + i] != right[rightFrom + i]) {
                return false;
            }
        }
        return true;
    }

    /** Go 的 {@code []rune(s)}：按 Unicode code point 切开（startAt/endAt 偏移都是 rune 计）。 */
    static int[] toRunes(String s) {
        return s == null ? new int[0] : s.codePoints().toArray();
    }

    /** Go 的 {@code string(runes)}。 */
    static String fromRunes(int[] runes) {
        StringBuilder b = new StringBuilder(runes.length);
        for (int r : runes) {
            b.appendCodePoint(r);
        }
        return b.toString();
    }

    /** Go 的 {@code len([]rune(s))}。 */
    static int runeCount(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.codePointCount(0, s.length());
    }
}
