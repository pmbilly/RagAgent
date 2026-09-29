package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/** wiki 工具共享文本处理：JSON 字段解析、截断、按查询摘要、码点切片。 */
public final class WikiTexts {

    private WikiTexts() {
    }


    /** JSON 字符串或字符串数组 → 非空字符串列表。 */
    public static List<String> parseStringOrArray(JsonNode val) {
        List<String> result = new ArrayList<>();
        if (val == null || val.isNull()) {
            return result;
        }
        if (val.isTextual()) {
            String s = val.asText();
            if (!s.isEmpty()) {
                result.add(s);
            }
            return result;
        }
        if (val.isArray()) {
            for (JsonNode item : val) {
                if (item.isTextual()) {
                    String s = item.asText();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
        }
        return result;
    }


    /** 摘要截断：首段（去 #/## 前缀），超长按 rune 截 + "..."。 */
    public static String truncateForSummary(String content, int maxLen) {
        String first = content == null ? "" : content;
        int para = first.indexOf("\n\n");
        if (para >= 0) {
            first = first.substring(0, para);
        }
        String summary = first.trim();
        if (summary.startsWith("# ")) {
            summary = summary.substring(2);
        } else if (summary.startsWith("## ")) {
            summary = summary.substring(3);
        }
        int runes = summary.codePointCount(0, summary.length());
        if (runes > maxLen) {
            return summary.substring(0, summary.offsetByCodePoints(0, maxLen)) + "...";
        }
        return summary;
    }


    /**
     * 摘要片段：(?i)+query 的首个匹配前后各 60 runes、匹配自身至多 100
     * runes，压缩空白后以 "... ... ..." 包裹。正则编译失败返回 ""（正则方言
     * 差异为已知差异点）。
     */
    public static String extractSnippet(String content, String query) {
        if (content == null || content.isEmpty() || query == null || query.isEmpty()) {
            return "";
        }
        final java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile("(?i)" + query);
        } catch (java.util.regex.PatternSyntaxException e) {
            return "";
        }
        java.util.regex.Matcher m = pattern.matcher(content);
        if (!m.find()) {
            return "";
        }
        String matchStr = m.group();
        String before = content.substring(0, m.start());
        String after = content.substring(m.end());

        String beforePart = lastRunes(before, 60);
        String afterPart = firstRunes(after, 60);
        String matchPart = firstRunes(matchStr, 100);

        String snippet = beforePart + matchPart + afterPart;
        snippet = snippet.replace("\n", " ");
        while (snippet.contains("  ")) {
            snippet = snippet.replace("  ", " ");
        }
        return "... " + snippet.trim() + " ...";
    }


    /** 前 n runes。 */
    static String firstRunes(String s, int n) {
        if (s == null) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }


    /** 后 n runes。 */
    static String lastRunes(String s, int n) {
        if (s == null) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= n) {
            return s;
        }
        return s.substring(s.offsetByCodePoints(0, count - n));
    }
}
