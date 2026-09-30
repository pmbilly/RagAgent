package com.ragagent.wiki.domain;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code source_refs} 的匹配谓词构造。
 *
 * <p>{@code source_refs} 是 JSON 数组列，元素有两种历史形态：{@code "knowledgeID"} 与
 * {@code "knowledgeID|title"}。匹配用「jsonb 包含 OR 文本 LIKE」两条分支同时覆盖。</p>
 *
 * <p>把 JSON 编码与 LIKE 转义放在一起，是为了让 needle 与 pattern 出自同一处，
 * 避免注入：任意 knowledge id（可能含引号、反斜杠、{@code %}、{@code _}）都不能
 * 逃出引号或变成通配符。</p>
 */
public final class WikiSourceRefs {

    private WikiSourceRefs() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 转义 LIKE / ILIKE 元字符，
     * 使结果可以安全地用 {@code %} 包裹。顺序重要：先反斜杠，再通配符。
     */
    public static String escapeLikePattern(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** JSON 编码一个字符串，失败时抛 IllegalStateException */
    private static String jsonString(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException("marshal source ref failed", e);
        }
    }

    /**
     * 构造一个 knowledge id 的三种匹配形式。
     *
     * @param kid 源文档 id（不可为空；空 id 由调用方跳过）
     */
    public static SourceRefNeedle of(String kid) {
        String id = kid == null ? "" : kid;
        // PG 包含分支的操作数：["id"]
        String needle = "[" + jsonString(id) + "]";
        // 非 PG 分支（H2）：数组里"恰好等于该 id"的元素，即 JSON 编码后的 "id"
        String exactLike = "%" + escapeLikePattern(jsonString(id)) + "%";
        // 历史 "id|title" 形态：jsonString(id + "|") 去掉尾部引号后转义
        String prefixJson = jsonString(id + "|");
        String prefixStr = prefixJson.endsWith("\"")
                ? prefixJson.substring(0, prefixJson.length() - 1)
                : prefixJson;
        return new SourceRefNeedle(needle, exactLike, "%" + escapeLikePattern(prefixStr) + "%");
    }
}
