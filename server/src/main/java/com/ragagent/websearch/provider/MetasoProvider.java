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
 * Metaso（秘塔）搜索 provider。
 *
 * <p>POST {@code https://metaso.cn/api/v1/search}；scope 缺省 webpage（白名单校验，
 * 非法值构造报错）；恒发 includeSummary:true / includeRawContent:false /
 * conciseSnippet:true；snippet 取 summary（缺则 snippet）；maxResults 缺省 10、
 * 封顶 50；响应 4MB 上限。</p>
 */
public final class MetasoProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://metaso.cn/api/v1/search";
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_RESULTS = 10;
    static final int MAX_RESULTS = 50;
    static final int MAX_RESPONSE_BYTES = 4 << 20;
    static final String DEFAULT_SCOPE = "webpage";
    private static final Set<String> VALID_SCOPES =
            Set.of("webpage", "document", "scholar", "podcast", "video", "image");

    String baseUrl = DEFAULT_URL;

    private final String apiKey;
    String scope;

    public MetasoProvider(WebSearchProviderParams params) {
        validateParameters(params);
        this.apiKey = params.getApiKey().trim();
        this.scope = metasoScope(params.getExtraConfig());
    }

    /** 入参校验：api_key 必填；scope 必须在白名单内。 */
    public static void validateParameters(WebSearchProviderParams params) {
        if (params.getApiKey().trim().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Metaso provider");
        }
        String scope = metasoScope(params.getExtraConfig());
        if (!VALID_SCOPES.contains(scope)) {
            throw new SearchHttp.SearchHttpException("invalid Metaso search scope: " + scope);
        }
    }

    static String metasoScope(Map<String, String> extraConfig) {
        String scope = extraConfig == null ? "" : extraConfig.getOrDefault("scope", "");
        scope = scope == null ? "" : scope.trim();
        if (!scope.isEmpty()) {
            return scope;
        }
        return DEFAULT_SCOPE;
    }

    @Override
    public String name() {
        return "metaso";
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

        var body = ProviderJson.object();
        body.put("q", q);
        body.put("scope", scope);
        body.put("size", maxResults);
        body.put("includeSummary", true);
        body.put("includeRawContent", false);
        body.put("conciseSnippet", true);
        byte[] json = ProviderJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.body().length > MAX_RESPONSE_BYTES) {
            throw new SearchHttp.SearchHttpException(
                    "Metaso response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        if (resp.status() != 200) {
            throw metasoHttpError(resp.status(), resp.body());
        }
        JsonNode response = ProviderJson.parse(resp.body());
        if (response == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal Metaso response");
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : response.path("webpages")) {
            if (item.path("title").asText("").trim().isEmpty()
                    && item.path("link").asText("").trim().isEmpty()) {
                continue;
            }
            String snippet = item.path("summary").asText("").trim();
            if (snippet.isEmpty()) {
                snippet = item.path("snippet").asText("").trim();
            }
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("title").asText(""));
            result.setUrl(item.path("link").asText(""));
            result.setSnippet(snippet);
            result.setContent(item.path("rawContent").asText(""));
            result.setSource("metaso");
            if (includeDate) {
                OffsetDateTime publishedAt = BochaProvider.parseFlexibleDate(
                        item.path("date").asText(""), false);
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

    /** 错误体解析：message/error 字段优先，body 截 4096。 */
    static SearchHttp.SearchHttpException metasoHttpError(int statusCode, byte[] body) {
        JsonNode apiError = ProviderJson.parse(body);
        if (apiError != null && apiError.isObject()) {
            String detail = apiError.path("message").asText("").trim();
            if (detail.isEmpty()) {
                detail = apiError.path("error").asText("").trim();
            }
            if (!detail.isEmpty()) {
                return new SearchHttp.SearchHttpException(
                        "Metaso API returned status " + statusCode + ": " + detail);
            }
        }
        String detail = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (detail.length() > 4096) {
            detail = detail.substring(0, 4096);
        }
        if (detail.isEmpty()) {
            return new SearchHttp.SearchHttpException("Metaso API returned status " + statusCode);
        }
        return new SearchHttp.SearchHttpException(
                "Metaso API returned status " + statusCode + ": " + detail);
    }
}
