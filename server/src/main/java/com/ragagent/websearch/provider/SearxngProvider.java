package com.ragagent.websearch.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * 自托管 SearXNG provider（对照 Go {@code web_search/searxng.go} 全文）。
 *
 * <p>实例 URL 由租户提供（base_url 参数），SSRF 校验在构造与服务层参数校验共用
 * {@code ValidateSearxngBaseURL}（save 与 use 永不分歧）。GET
 * {@code /search?q=&format=json&language=all}（"all" 是 SearXNG 文档的
 * 「无语言过滤」值；safesearch 刻意不设，让实例 settings.yml 生效）。
 * 空结果 + unresponsive_engines 时给出可诊断说明。</p>
 */
public final class SearxngProvider implements WebSearchProvider {

    /** 略高于 SearXNG 默认 outgoing.max_request_timeout(10s)，慢引擎先在服务端暴露。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    String baseUrl;
    List<List<String>> lastUnresponsive = new ArrayList<>();

    public SearxngProvider(WebSearchProviderParams params) {
        String base = params.getBaseUrl() == null ? "" : params.getBaseUrl().trim();
        com.ragagent.websearch.provider.SearxngValidation.validateSearxngBaseUrl(base);
        this.baseUrl = base.replaceAll("/+$", "");
    }

    @Override
    public String name() {
        return "searxng";
    }

    /** 对照 EmptyResultDiagnostics。 */
    @Override
    public String emptyResultDiagnostics() {
        String detail = formatUnresponsiveEngines(lastUnresponsive);
        if (!detail.isEmpty()) {
            return detail + "; check that upstream search engines can reach the internet";
        }
        return "verify the instance URL is reachable and JSON format is enabled in settings.yml";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (query == null || query.trim().isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = 5;
        }
        String url = baseUrl + "/search?format=json&language=all&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var req = SearchHttp.request(url, TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "WeKnora/1.0")
                .GET()
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            byte[] body = resp.body().length > 1024
                    ? java.util.Arrays.copyOf(resp.body(), 1024) : resp.body();
            throw new SearchHttp.SearchHttpException("searxng returned status " + resp.status()
                    + ": " + new String(body, StandardCharsets.UTF_8));
        }
        JsonNode data = GoJson.parse(resp.body());
        if (data == null) {
            lastUnresponsive = new ArrayList<>();
            throw new SearchHttp.SearchHttpException(
                    "failed to decode SearXNG response (ensure JSON format is enabled in settings.yml): invalid JSON");
        }
        lastUnresponsive = new ArrayList<>();
        for (JsonNode e : data.path("unresponsive_engines")) {
            List<String> tuple = new ArrayList<>();
            for (JsonNode x : e) {
                tuple.add(x.asText(""));
            }
            lastUnresponsive.add(tuple);
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode r : data.path("results")) {
            if (results.size() >= maxResults) {
                break;
            }
            String urlValue = r.path("url").asText("");
            String title = r.path("title").asText("");
            if (urlValue.isEmpty() || title.isEmpty()) {
                continue;
            }
            WebSearchResult item = new WebSearchResult();
            item.setTitle(title);
            item.setUrl(urlValue);
            item.setSnippet(r.path("content").asText(""));
            item.setSource("searxng");
            String publishedDate = r.path("publishedDate").asText("");
            if (includeDate && !publishedDate.isEmpty()) {
                OffsetDateTime t = parseSearxngDate(publishedDate);
                if (t != null) {
                    item.setPublishedAt(t);
                }
            }
            results.add(item);
        }
        return results;
    }

    /**
     * 对照 searxngDateLayouts（六个 layout，首个命中即用；RFC3339 已含纳秒形态，
     * RFC3339Nano 刻意不在列）。
     */
    static OffsetDateTime parseSearxngDate(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) {
            return null;
        }
        // RFC3339（带偏移；含纳秒）
        try {
            return OffsetDateTime.parse(v);
        } catch (RuntimeException ignored) {
            // next
        }
        // 2006-01-02T15:04:05 / 2006-01-02 15:04:05 → UTC 墙钟
        for (String layout : new String[] {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"}) {
            try {
                return java.time.LocalDateTime.parse(v, DateTimeFormatter.ofPattern(layout))
                        .atOffset(java.time.ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                // next
            }
        }
        // 2006-01-02
        try {
            return java.time.LocalDate.parse(v).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // next
        }
        // RFC1123Z / RFC1123
        try {
            return java.time.ZonedDateTime.parse(v,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 对照 formatUnresponsiveEngines：[[engine, reason]] → "unresponsive engines: a (x), b"。 */
    static String formatUnresponsiveEngines(List<List<String>> engines) {
        if (engines == null || engines.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (List<String> e : engines) {
            if (e == null || e.isEmpty()) {
                continue;
            }
            if (e.size() == 1) {
                parts.add(e.get(0));
                continue;
            }
            parts.add(e.get(0) + " (" + e.get(1) + ")");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "unresponsive engines: " + String.join(", ", parts);
    }
}
