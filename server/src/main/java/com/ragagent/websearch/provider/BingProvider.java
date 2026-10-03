package com.ragagent.websearch.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.common.web.ProviderJson;

/**
 * Bing 搜索 provider。
 *
 * <p>GET 官方端点 + {@code Ocp-Apim-Subscription-Key} + 桌面 UA；count 原样传递
 * （不做默认值/上限钳制）；结果恒带 dateLastCrawled
 * （解析失败为 null）。</p>
 */
public final class BingProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://api.bing.microsoft.com/v7.0/search";
    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    String baseUrl = DEFAULT_URL;

    private final String apiKey;

    public BingProvider(WebSearchProviderParams params) {
        if (params.getApiKey().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Bing provider");
        }
        this.apiKey = params.getApiKey();
    }

    @Override
    public String name() {
        return "bing";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (query == null || query.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        String url = baseUrl + "?count=" + maxResults
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var req = SearchHttp.request(url, TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .GET()
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("bing API returned status " + resp.status()
                    + ": " + resp.bodyText());
        }
        JsonNode respData = ProviderJson.parse(resp.body());
        if (respData == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal response");
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : respData.path("webPages").path("value")) {
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("name").asText(""));
            result.setUrl(item.path("url").asText(""));
            result.setSnippet(item.path("snippet").asText(""));
            result.setSource("bing");
            // dateLastCrawled 恒承载（零值也是值）；解析失败置 null，不外显
            OffsetDateTime crawled = parseRfc3339(item.path("dateLastCrawled").asText(""));
            result.setPublishedAt(crawled);
            results.add(result);
        }
        return results;
    }

    static OffsetDateTime parseRfc3339(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
