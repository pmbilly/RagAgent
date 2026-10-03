package com.ragagent.websearch.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * Google CSE 搜索 provider。
 *
 * <p>线格式与官方 customsearch SDK 一致：GET {@code https://www.googleapis.com/customsearch/v1}
 * {@code ?cx=&q=&num=&hl=ch-zh&key=}。<b>hl 的 "ch-zh" 是既有笔误（应为
 * zh-CN），保持不修</b>。num 缺省 5（maxResults<=0 时）。</p>
 */
public final class GoogleProvider implements WebSearchProvider {

    static final String SEARCH_URL = "https://www.googleapis.com/customsearch/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    String baseUrl = SEARCH_URL;

    private final String apiKey;
    private final String engineId;

    public GoogleProvider(WebSearchProviderParams params) {
        if (params.getApiKey().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Google provider");
        }
        if (params.getEngineId().isEmpty()) {
            throw new SearchHttp.SearchHttpException("engine ID is required for Google provider");
        }
        this.apiKey = params.getApiKey();
        this.engineId = params.getEngineId();
    }

    @Override
    public String name() {
        return "google";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (query == null || query.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        int num = maxResults > 0 ? maxResults : 5;
        // 线格式按 customsearch 官方客户端形态：参数按键字母序（alt/cx/hl/key/num/
        // prettyPrint/q），alt=json 与 prettyPrint=false 是恒发项
        String url = baseUrl
                + "?alt=json"
                + "&cx=" + URLEncoder.encode(engineId, StandardCharsets.UTF_8)
                + "&hl=ch-zh"
                + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&num=" + num
                + "&prettyPrint=false"
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var req = SearchHttp.request(url, TIMEOUT).GET().build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("google API returned status " + resp.status()
                    + ": " + resp.bodyText());
        }
        JsonNode data = GoJson.parse(resp.body());
        if (data == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal response");
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("title").asText(""));
            result.setUrl(item.path("link").asText(""));
            result.setSnippet(item.path("snippet").asText(""));
            result.setSource("google");
            results.add(result);
        }
        return results;
    }
}
