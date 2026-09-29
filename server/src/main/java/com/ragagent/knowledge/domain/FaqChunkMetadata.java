package com.ragagent.knowledge.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.common.text.TextConv;

/**
 * FAQ 条目在 chunks.metadata 中的结构。
 * <p><b>JSON 是契约</b>（落库 + 由 {@code FAQEntry}/导出面逐字段搬运）：
 * answer_strategy / version / source} 带 omitempty（空省略，{@code NON_DEFAULT}——
 * 空列表与 0/"" 都省，nil 列表同样省）；{@code standard_question} 恒输出。</p>
 * {@code Sanitize/Normalize} 翻成 {@link #sanitize()} / {@link #normalize()}——
 * 不带 get/is 前缀，Jackson 不会当属性吐进 jsonb（历史上两次在此踩坑，
 * UnrecognizedPropertyException）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class FaqChunkMetadata {

    public static final String ANSWER_STRATEGY_ALL = "all";
    public static final String ANSWER_STRATEGY_RANDOM = "random";

    /**
     * 本仓约定 第 6 条——历史行/新增字段不能让整行读不出来）。
     */
    public static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    /** 从 chunks.metadata 的 JsonNode 解析；null/空 → null。 */
    public static FaqChunkMetadata fromJson(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        try {
            return JSON.treeToValue(node, FaqChunkMetadata.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    public com.fasterxml.jackson.databind.node.ObjectNode toJsonNode() {
        return JSON.valueToTree(this);
    }

    public String standardQuestion = "";

    public List<String> similarQuestions;

    public List<String> negativeQuestions;

    public List<String> answers;

    public String answerStrategy = "";

    public int version;

    public String source = "";

    /** TrimSpace + 列表去空去重，version 兜底 1。 */
    public void sanitize() {
        standardQuestion = trimSpace(standardQuestion);
        similarQuestions = sanitizeStrings(similarQuestions);
        negativeQuestions = sanitizeStrings(negativeQuestions);
        answers = sanitizeStrings(answers);
        if (version <= 0) {
            version = 1;
        }
    }

    /** 返回归一化副本（原对象不变）。 */
    public FaqChunkMetadata normalize() {
        FaqChunkMetadata copy = new FaqChunkMetadata();
        copy.standardQuestion = normalizeQuestion(standardQuestion);
        copy.similarQuestions = normalizeQuestionStrings(similarQuestions);
        copy.negativeQuestions = normalizeQuestionStrings(negativeQuestions);
        copy.answers = sanitizeStrings(answers);
        copy.answerStrategy = answerStrategy;
        copy.version = version;
        copy.source = source;
        return copy;
    }

    // ── 纯函数 ─────────────────────────────

    /** TrimSpace + 去空 + 去重；空输入/全空 → null。 */
    public static List<String> sanitizeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            String trimmed = v == null ? "" : trimSpace(v);
            if (trimmed.isEmpty()) {
                continue;
            }
            seen.add(trimmed);
        }
        if (seen.isEmpty()) {
            return null;
        }
        return new ArrayList<>(seen);
    }

    /** 逐条 NormalizeQuestion 后去重；空 → null。 */
    public static List<String> normalizeQuestionStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            String normalized = normalizeQuestion(v);
            if (normalized.isEmpty()) {
                continue;
            }
            seen.add(normalized);
        }
        if (seen.isEmpty()) {
            return null;
        }
        return new ArrayList<>(seen);
    }

    /**
     * hash 基于 标准问 + 相似问（排序后）+
     * 反例（排序后）+ 答案（排序后），SHA256 hex。
     */
    public static String calculateContentHash(FaqChunkMetadata meta) {
        if (meta == null) {
            return "";
        }
        FaqChunkMetadata normalized = meta.normalize();
        List<String> similar = sortedCopy(normalized.similarQuestions);
        List<String> negative = sortedCopy(normalized.negativeQuestions);
        List<String> answers = sortedCopy(normalized.answers);

        String joined = normalized.standardQuestion
                + "|" + String.join(",", similar)
                + "|" + String.join(",", negative)
                + "|" + String.join(",", answers);
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> sortedCopy(List<String> values) {
        List<String> copy = values == null ? new ArrayList<>() : new ArrayList<>(values);
        copy.sort(null);
        return copy;
    }

    /**
     * 去首尾空白 → 移除 URL → 转小写 → 去首尾标点 → 繁转简 → 全角转半角 → 智能空格。
     */
    public static String normalizeQuestion(String q) {
        if (q == null) {
            return "";
        }
        q = trimSpace(q);
        if (q.isEmpty()) {
            return "";
        }
        q = trimUrl(q);
        q = q.toLowerCase(java.util.Locale.ROOT);
        // cutset 逐字节？。，；、：+ ASCII 双引号 x2 + ！?.,;!:' + ASCII 双引号 x2
        q = trimCutset(q, "？。，；、：\"\"！?.,;!:'\"\"");
        q = TextConv.toSimplified(q);
        q = toHalfWidth(q);
        q = normalizeSpaces(q);
        return trimSpace(q);
    }

    /** 全角空格 + 全角 ASCII（U+FF01..U+FF5E → U+0021..U+007E）。 */
    public static String toHalfWidth(String s) {
        StringBuilder builder = new StringBuilder(s.length());
        s.codePoints().forEach(r -> {
            if (r == 0x3000) {
                builder.append(' ');
            } else if (r >= 0xFF01 && r <= 0xFF5E) {
                builder.appendCodePoint(r - 0xFF01 + 0x21);
            } else {
                builder.appendCodePoint(r);
            }
        });
        return builder.toString();
    }

    /** 前后都是 ASCII 字母/数字的空格保留，其余去除。 */
    public static String normalizeSpaces(String s) {
        s = s.replaceAll("\\s+", " ");
        int[] runes = s.codePoints().toArray();
        StringBuilder builder = new StringBuilder(s.length());
        for (int i = 0; i < runes.length; i++) {
            int r = runes[i];
            if (r != ' ') {
                builder.appendCodePoint(r);
                continue;
            }
            int prevRune = i > 0 ? runes[i - 1] : 0;
            int nextRune = 0;
            for (int j = i + 1; j < runes.length; j++) {
                if (runes[j] != ' ') {
                    nextRune = runes[j];
                    break;
                }
            }
            if (isAsciiAlphaNum(prevRune) && isAsciiAlphaNum(nextRune)) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    private static boolean isAsciiAlphaNum(int r) {
        return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9');
    }

    public static String trimUrl(String s) {
        return s.replaceAll("https?://[^\\s]+", "");
    }

    private static String trimCutset(String s, String cutset) {
        Set<Integer> cut = new LinkedHashSet<>();
        cutset.codePoints().forEach(cut::add);
        int start = 0;
        int end = s.length();
        while (start < end && cut.contains(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start) {
            int prev = s.codePointBefore(end);
            if (!cut.contains(prev)) {
                break;
            }
            end -= Character.charCount(prev);
        }
        return s.substring(start, end);
    }

    /**
     * 差 U+0085/U+00A0，与 ChunkRepository.trimSpace 同款显式复刻。
     */
    @JsonIgnore
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
}
