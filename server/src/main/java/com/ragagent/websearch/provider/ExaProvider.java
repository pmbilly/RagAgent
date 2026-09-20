package com.ragagent.websearch.provider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * Exa 搜索 provider（对照 Go {@code web_search/exa.go} 全文）。
 *
 * <p>POST {@code https://api.exa.ai/search} + {@code x-api-key}；请求体恒含
 * {@code contents:{highlights:true}}，{@code text} 仅在 extra_config
 * include_text=true 时出现（omitempty）；numResults 缺省 5、封顶 100；snippet 取
 * highlights 拼接（缺则 content 截 500 rune）；content 截 12000 rune；响应限长
 * 2MB（截断式读取，对照 Go 的 LimitReader(maxExaResponseBytes)）。</p>
 */
public final class ExaProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://api.exa.ai/search";
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final int DEFAULT_RESULTS = 5;
    static final int MAX_RESULTS = 100;
    static final int MAX_RESPONSE_BYTES = 2 << 20;
    static final int MAX_CONTENT_RUNES = 12000;

    String baseUrl = DEFAULT_URL;

    private final String apiKey;
    boolean includeText;

    public ExaProvider(WebSearchProviderParams params) {
        String key = params.getApiKey() == null ? "" : params.getApiKey().trim();
        if (key.isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Exa provider");
        }
        this.apiKey = key;
        this.includeText = parseExaBool(params.getExtraConfig(), "include_text");
    }

    @Override
    public String name() {
        return "exa";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_RESULTS;
        }
        if (maxResults > MAX_RESULTS) {
            maxResults = MAX_RESULTS;
        }

        var body = GoJson.object();
        body.put("query", q);
        body.put("numResults", maxResults);
        var contents = body.putObject("contents");
        contents.put("highlights", true);
        if (includeText) {
            contents.put("text", true);
        }
        byte[] json = GoJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        // Go：LimitReader(maxExaResponseBytes) 截断式读取（不报错）
        byte[] bodyBytes = resp.body().length > MAX_RESPONSE_BYTES
                ? java.util.Arrays.copyOf(resp.body(), MAX_RESPONSE_BYTES) : resp.body();
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new SearchHttp.SearchHttpException("exa API returned status " + resp.status()
                    + ": " + new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        JsonNode data = GoJson.parse(bodyBytes);
        if (data == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal Exa response");
        }
        String error = data.path("error").asText("");
        if (!error.isEmpty()) {
            throw new SearchHttp.SearchHttpException("exa API error: " + error);
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : data.path("results")) {
            if (results.size() >= maxResults) {
                break;
            }
            StringBuilder highlights = new StringBuilder();
            JsonNode hl = item.path("highlights");
            for (int i = 0; i < hl.size(); i++) {
                if (i > 0) {
                    highlights.append('\n');
                }
                highlights.append(hl.get(i).asText(""));
            }
            String snippet = highlights.toString().trim();
            String content = truncateExaText(item.path("text").asText("").trim(), MAX_CONTENT_RUNES);
            if (snippet.isEmpty()) {
                snippet = truncateExaText(content, 500);
            }
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("title").asText(""));
            result.setUrl(item.path("url").asText(""));
            result.setSnippet(snippet);
            result.setContent(content);
            result.setSource("exa");
            String publishedDate = item.path("publishedDate").asText("");
            if (includeDate && !publishedDate.isEmpty()) {
                OffsetDateTime t = BingProvider.parseRfc3339(publishedDate);
                if (t != null) {
                    result.setPublishedAt(t);
                }
            }
            results.add(result);
        }
        return results;
    }

    /** 对照 parseExaBool：strconv.ParseBool 语义（非 "1/t/T/true/TRUE/True" 即 false）。 */
    static boolean parseExaBool(Map<String, String> config, String key) {
        String v = config == null ? null : config.get(key);
        v = v == null ? "" : v.trim();
        if (v.isEmpty()) {
            return false;
        }
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "1", "t", "true" -> true;
            default -> false;
        };
    }

    /** 对照 truncateExaText：按 rune 截断；maxRunes<=0 → ""。 */
    static String truncateExaText(String value, int maxRunes) {
        if (maxRunes <= 0) {
            return "";
        }
        int[] runes = value.codePoints().toArray();
        if (runes.length <= maxRunes) {
            return value;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < maxRunes; i++) {
            b.appendCodePoint(runes[i]);
        }
        return b.toString();
    }
}
