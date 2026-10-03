package com.ragagent.websearch.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchFilters;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.common.web.ProviderJson;

/**
 * Brave 搜索 provider。
 *
 * <p>GET 官方端点 + {@code X-Subscription-Token}；<b>不跟随重定向</b>
 * （订阅令牌绝不转发给重定向目的地）。
 * country 转大写后发送（缺省不发，显式 "ALL" 照发）；freshness 原样。
 * count 缺省 5、封顶 20。结果按 age（缺则 page_age）承载相对时间——
 * 不臆造精确发布时间。</p>
 */
public final class BraveProvider implements WebSearchProvider {

    static final String SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    String baseUrl = SEARCH_URL;

    private final String apiKey;

    public BraveProvider(WebSearchProviderParams params) {
        if (params.getApiKey().trim().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Brave provider");
        }
        this.apiKey = params.getApiKey().trim();
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        return searchWithFilters(query, maxResults, includeDate, WebSearchFilters.EMPTY);
    }

    @Override
    public List<WebSearchResult> searchWithFilters(String query, int maxResults,
                                                   boolean includeDate, WebSearchFilters filters) {
        filters.validate();
        if (query == null || query.trim().isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = 5;
        }
        maxResults = Math.min(maxResults, 20);
        Map<String, String> params = new java.util.TreeMap<>();
        params.put("q", query);
        params.put("count", String.valueOf(maxResults));
        String country = filters.country().toUpperCase(Locale.ROOT);
        if (!country.isEmpty()) {
            params.put("country", country);
        }
        if (!filters.freshness().isEmpty()) {
            params.put("freshness", filters.freshness());
        }
        // 查询串按键字母序输出（count 先于 q）
        StringBuilder qs = new StringBuilder();
        params.forEach((k, v) -> {
            if (qs.length() > 0) {
                qs.append('&');
            }
            qs.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        var req = SearchHttp.request(baseUrl + "?" + qs, TIMEOUT)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();
        // 不跟随重定向：3xx 原样返回后由状态码分支报错
        SearchHttp.Result resp = SearchHttp.sendNoRedirect(req);
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new SearchHttp.SearchHttpException("brave search returned HTTP " + resp.status());
        }
        final int maxResponse = 4 << 20;
        byte[] body = resp.body();
        if (body.length > maxResponse) {
            throw new SearchHttp.SearchHttpException("brave response exceeds " + maxResponse + " bytes");
        }
        JsonNode response = ProviderJson.parse(body);
        if (response == null) {
            throw new SearchHttp.SearchHttpException("decode Brave response: invalid JSON");
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode row : response.path("web").path("results")) {
            String age = row.path("age").asText("");
            if (age.isEmpty()) {
                age = row.path("page_age").asText("");
            }
            WebSearchResult result = new WebSearchResult();
            result.setTitle(row.path("title").asText(""));
            result.setUrl(row.path("url").asText(""));
            result.setSnippet(row.path("description").asText(""));
            result.setSource(name());
            result.setAge(age);
            results.add(result);
            if (results.size() == maxResults) {
                break;
            }
        }
        return results;
    }
}
