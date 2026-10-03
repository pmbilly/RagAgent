package com.ragagent.websearch.provider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * Tavily 搜索 provider。
 *
 * <p>POST 官方端点；api_key 放请求体（非头）；snippet 取响应的 content 字段；
 * published_date 仅 includeDate 且解析成功（RFC3339）时输出。错误带 body。</p>
 */
public final class TavilyProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://api.tavily.com/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    String baseUrl = DEFAULT_URL;

    private final String apiKey;

    public TavilyProvider(WebSearchProviderParams params) {
        if (params.getApiKey().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Tavily provider");
        }
        this.apiKey = params.getApiKey();
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (query == null || query.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        var body = GoJson.object();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("max_results", maxResults);
        byte[] json = GoJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("tavily API returned status " + resp.status()
                    + ": " + resp.bodyText());
        }
        JsonNode respData = GoJson.parse(resp.body());
        if (respData == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal response");
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : respData.path("results")) {
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("title").asText(""));
            result.setUrl(item.path("url").asText(""));
            result.setSnippet(item.path("content").asText(""));
            result.setSource("tavily");
            String publishedDate = item.path("published_date").asText("");
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
}
