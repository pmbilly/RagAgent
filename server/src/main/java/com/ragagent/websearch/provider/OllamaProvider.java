package com.ragagent.websearch.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * Ollama Cloud 搜索 provider。
 *
 * <p>POST {@code https://ollama.com/api/web_search}（硬编码防 SSRF）；请求体
 * 键按字母序输出：{@code max_results} 在 {@code query} 之前）；Bearer 鉴权 + 桌面 UA；
 * maxResults 缺省 5、封顶 10。</p>
 */
public final class OllamaProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://ollama.com/api/web_search";
    static final int DEFAULT_RESULTS = 5;
    static final int MAX_RESULTS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    String baseUrl = DEFAULT_URL;

    private final String apiKey;

    public OllamaProvider(WebSearchProviderParams params) {
        if (params.getApiKey().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Ollama provider");
        }
        this.apiKey = params.getApiKey();
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (query == null || query.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_RESULTS;
        }
        if (maxResults > MAX_RESULTS) {
            maxResults = MAX_RESULTS;
        }
        // 请求体键按字母序：max_results 先于 query
        var body = GoJson.object();
        body.put("max_results", maxResults);
        body.put("query", query);
        byte[] json = GoJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", BingProvider.USER_AGENT)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("ollama API returned status " + resp.status()
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
            result.setSnippet(item.path("snippet").asText(""));
            result.setContent(item.path("content").asText(""));
            result.setSource("ollama");
            results.add(result);
        }
        return results;
    }
}
