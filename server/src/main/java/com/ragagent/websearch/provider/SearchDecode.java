package com.ragagent.websearch.provider;

/** 包内小工具（Go strings.TrimSpace 的 unicode 全集表——各包同款小副本）。 */
final class SearchDecode {

    private SearchDecode() {
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
