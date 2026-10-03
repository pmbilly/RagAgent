package com.ragagent.websearch.provider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.common.web.ProviderJson;

/**
 * 智谱独立 Web 搜索 provider。
 *
 * <p>POST {@code https://open.bigmodel.cn/api/paas/v4/web_search}；search_engine
 * 缺省 search_std、content_size 缺省 medium（白名单校验）；query 按码点截到
 * 70；search_intent 恒 false；maxResults 缺省 10、封顶 50。错误分支：
 * 非 200 走 {@code Zhipu API returned status %d (%s): %s}；200 但 error 字段非空
 * 走 {@code Zhipu API error (%s): %s}。响应 2MB 上限。</p>
 */
public final class ZhipuProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4/web_search";
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final int DEFAULT_RESULTS = 10;
    static final int MAX_RESULTS = 50;
    static final int MAX_QUERY_RUNES = 70;
    static final int MAX_RESPONSE_BYTES = 2 << 20;
    static final String DEFAULT_SEARCH_ENGINE = "search_std";
    static final String DEFAULT_CONTENT_SIZE = "medium";
    private static final Set<String> VALID_ENGINES =
            Set.of("search_std", "search_pro", "search_pro_sogou", "search_pro_quark");
    private static final Set<String> VALID_SIZES = Set.of("medium", "high");

    String baseUrl = DEFAULT_URL;

    private final String apiKey;
    String searchEngine;
    String contentSize;

    public ZhipuProvider(WebSearchProviderParams params) {
        validateParameters(params);
        this.apiKey = params.getApiKey().trim();
        String[] options = zhipuOptions(params.getExtraConfig());
        this.searchEngine = options[0];
        this.contentSize = options[1];
    }

    /** 入参校验：api_key 必填；search_engine/content_size 必须在白名单内。 */
    public static void validateParameters(WebSearchProviderParams params) {
        if (params.getApiKey().trim().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Zhipu provider");
        }
        String[] options = zhipuOptions(params.getExtraConfig());
        if (!VALID_ENGINES.contains(options[0])) {
            throw new SearchHttp.SearchHttpException("invalid Zhipu search engine: " + options[0]);
        }
        if (!VALID_SIZES.contains(options[1])) {
            throw new SearchHttp.SearchHttpException("invalid Zhipu content size: " + options[1]);
        }
    }

    static String[] zhipuOptions(Map<String, String> extraConfig) {
        String engine = DEFAULT_SEARCH_ENGINE;
        String size = DEFAULT_CONTENT_SIZE;
        if (extraConfig != null) {
            String v = extraConfig.getOrDefault("search_engine", "");
            if (v != null && !v.trim().isEmpty()) {
                engine = v.trim();
            }
            v = extraConfig.getOrDefault("content_size", "");
            if (v != null && !v.trim().isEmpty()) {
                size = v.trim();
            }
        }
        return new String[] {engine, size};
    }

    @Override
    public String name() {
        return "zhipu";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        String preparedQuery = normalizeZhipuQuery(query);
        if (preparedQuery.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_RESULTS;
        }
        if (maxResults > MAX_RESULTS) {
            maxResults = MAX_RESULTS;
        }

        var body = ProviderJson.object();
        body.put("search_query", preparedQuery);
        body.put("search_engine", searchEngine);
        body.put("search_intent", false);
        body.put("count", maxResults);
        body.put("content_size", contentSize);
        byte[] json = ProviderJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.body().length > MAX_RESPONSE_BYTES) {
            throw new SearchHttp.SearchHttpException(
                    "Zhipu response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        if (resp.status() != 200) {
            throw zhipuHttpError(resp.status(), resp.body());
        }
        JsonNode response = ProviderJson.parse(resp.body());
        if (response == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal Zhipu response");
        }
        String errorCode = response.path("error").path("code").asText("");
        String errorMessage = response.path("error").path("message").asText("");
        if (!errorMessage.isEmpty() || !errorCode.isEmpty()) {
            throw new SearchHttp.SearchHttpException("Zhipu API error (" + errorCode + "): "
                    + errorMessage);
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : response.path("search_result")) {
            if (item.path("title").asText("").trim().isEmpty()
                    && item.path("link").asText("").trim().isEmpty()) {
                continue;
            }
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("title").asText(""));
            result.setUrl(item.path("link").asText(""));
            result.setSnippet(item.path("content").asText(""));
            result.setSource("zhipu");
            if (includeDate) {
                OffsetDateTime publishedAt = parseZhipuDate(item.path("publish_date").asText(""));
                if (publishedAt != null) {
                    result.setPublishedAt(publishedAt);
                }
            }
            results.add(result);
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }

    /** 按码点截到 70。 */
    static String normalizeZhipuQuery(String query) {
        String q = query == null ? "" : query.trim();
        if (q.codePointCount(0, q.length()) <= MAX_QUERY_RUNES) {
            return q;
        }
        int[] runes = q.codePoints().toArray();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < MAX_QUERY_RUNES; i++) {
            b.appendCodePoint(runes[i]);
        }
        return b.toString();
    }

    /** 四个时间格式依序尝试（带偏移 RFC3339/日期时间/日期分钟/日期）。 */
    static OffsetDateTime parseZhipuDate(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (RuntimeException ignored) {
            // next
        }
        for (String layout : new String[] {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"}) {
            try {
                return java.time.LocalDateTime.parse(v,
                        java.time.format.DateTimeFormatter.ofPattern(layout))
                        .atOffset(java.time.ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                // next
            }
        }
        try {
            return java.time.LocalDate.parse(v).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 错误体解析：error 结构优先，body 截 4096。 */
    static SearchHttp.SearchHttpException zhipuHttpError(int statusCode, byte[] body) {
        JsonNode response = ProviderJson.parse(body);
        if (response != null && response.isObject()) {
            String code = response.path("error").path("code").asText("");
            String message = response.path("error").path("message").asText("");
            if (!code.isEmpty() || !message.isEmpty()) {
                return new SearchHttp.SearchHttpException("Zhipu API returned status " + statusCode
                        + " (" + code + "): " + message);
            }
        }
        String detail = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (detail.length() > 4096) {
            detail = detail.substring(0, 4096);
        }
        if (detail.isEmpty()) {
            return new SearchHttp.SearchHttpException("Zhipu API returned status " + statusCode);
        }
        return new SearchHttp.SearchHttpException(
                "Zhipu API returned status " + statusCode + ": " + detail);
    }
}
