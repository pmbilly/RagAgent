package com.ragagent.agent.tools;


/** wiki slug 规范化与命名空间判定。 */
public final class WikiSlugs {

    private WikiSlugs() {
    }


    // ==================== slug 工具（对照 wiki_write_page.go:252 起） ====================

    /**
     * 对照 normalizeAndValidateWikiSlug：lowercase + trim + 空格转 '-'；
     * 只许小写字母/数字/'-'/'/'/CJK（0x4E00-0x9FFF）；拒绝空、前/后/重复 '/'。
     */
    public static String normalizeAndValidateWikiSlug(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        s = s.replace(" ", "-");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("slug is required and must be non-empty");
        }
        if (s.contains("//") || s.startsWith("/") || s.endsWith("/")) {
            throw new IllegalArgumentException("invalid slug \"" + raw + "\": '/' separators are malformed");
        }
        for (int i = 0; i < s.length(); i++) {
            char r = s.charAt(i);
            boolean ok = (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' || r == '/'
                    || (r >= 0x4E00 && r <= 0x9FFF);
            if (!ok) {
                throw new IllegalArgumentException("invalid slug \"" + raw + "\": character \"" + r
                        + "\" is not allowed (use lowercase letters, digits, '-', '/', or CJK)");
            }
        }
        return s;
    }


    /** 对照 isSummaryNamespace。 */
    public static boolean isSummaryNamespace(String slug) {
        return slug != null && slug.startsWith(WIKI_PAGE_TYPE_SUMMARY + "/");
    }

    /** 对照 types.WikiPageTypeSummary。 */
    public static final String WIKI_PAGE_TYPE_SUMMARY = "summary";
}
