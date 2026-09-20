package com.ragagent.websearch.provider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * Keenable 搜索 provider（对照 Go {@code web_search/keenable.go} 全文）。
 *
 * <p><b>默认免钥</b>：无 key 走 {@code /v1/search/public}（限流）；配了 key 切
 * {@code /v1/search}（带 X-API-Key）。恒发 {@code X-Keenable-Title: WeKnora}
 * （集成流量归因）与 {@code {"query":...,"mode":"pro"}}。maxResults 缺省 5。
 * Keenable 同时返回短 description 与长 snippet：description 做 Snippet、
 * snippet 做 Content（RAG 压缩用），二者互为回落。</p>
 */
public final class KeenableProvider implements WebSearchProvider {

    static final String DEFAULT_BASE_URL = "https://api.keenable.ai";
    static final String TITLE_TAG = "WeKnora";
    static final int DEFAULT_RESULTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    String baseUrl = DEFAULT_BASE_URL;

    private final String apiKey;

    public KeenableProvider(WebSearchProviderParams params) {
        this.apiKey = params.getApiKey() == null ? "" : params.getApiKey();
    }

    @Override
    public String name() {
        return "keenable";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (query == null || query.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_RESULTS;
        }
        String path = apiKey.isEmpty() ? "/v1/search/public" : "/v1/search";
        String endpoint = baseUrl + path;

        var body = GoJson.object();
        body.put("query", query);
        body.put("mode", "pro");
        byte[] json = GoJson.marshal(body);

        var b = SearchHttp.request(endpoint, TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Keenable-Title", TITLE_TAG);
        if (!apiKey.isEmpty()) {
            b.header("X-API-Key", apiKey);
        }
        var req = b.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json)).build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("keenable API returned status " + resp.status()
                    + ": " + resp.bodyText());
        }
        JsonNode respData = GoJson.parse(resp.body());
        if (respData == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal response");
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : respData.path("results")) {
            if (maxResults > 0 && results.size() >= maxResults) {
                break;
            }
            String description = item.path("description").asText("");
            String snippet = item.path("snippet").asText("");
            String effectiveSnippet = description.isEmpty() ? snippet : description;
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("title").asText(""));
            result.setUrl(item.path("url").asText(""));
            result.setSnippet(effectiveSnippet);
            result.setContent(snippet);
            result.setSource("keenable");
            String publishedAt = item.path("published_at").asText("");
            if (includeDate && !publishedAt.isEmpty()) {
                OffsetDateTime t = BingProvider.parseRfc3339(publishedAt);
                if (t != null) {
                    result.setPublishedAt(t);
                }
            }
            results.add(result);
        }
        return results;
    }
}
