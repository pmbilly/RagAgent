package com.ragagent.retrieval.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 网络搜索的国家/时效过滤。
 *
 * <p>country：空或 "ALL" 放行，其余必须两位大写字母；freshness：空 / pd / pw /
 * pm / py 放行，其余按 {@code YYYY-MM-DDtoYYYY-MM-DD} 解析且 start &lt;= end。</p>
 */
public record WebSearchFilters(String country, String freshness) {

    public WebSearchFilters {
        country = country == null ? "" : country;
        freshness = freshness == null ? "" : freshness;
    }

    public static final WebSearchFilters EMPTY = new WebSearchFilters("", "");

    /** 校验；失败抛 {@link IllegalArgumentException}。 */
    public void validate() {
        String c = country.toUpperCase(Locale.ROOT);
        if (!c.isEmpty() && !c.equals("ALL") && (c.length() != 2
                || c.charAt(0) < 'A' || c.charAt(0) > 'Z'
                || c.charAt(1) < 'A' || c.charAt(1) > 'Z')) {
            throw new IllegalArgumentException("country must be a two-letter country code or ALL");
        }
        switch (freshness) {
            case "", "pd", "pw", "pm", "py" -> {
                return;
            }
            default -> {
            }
        }
        int idx = freshness.indexOf("to");
        String start;
        String end;
        boolean ok;
        if (idx < 0) {
            start = freshness;
            end = "";
            ok = false;
        } else {
            start = freshness.substring(0, idx);
            end = freshness.substring(idx + 2);
            ok = true;
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(start);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
        try {
            to = LocalDate.parse(end);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
        if (!ok || from.isAfter(to)) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "freshness must be pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD with start <= end");
    }
}
