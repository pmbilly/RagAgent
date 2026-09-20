package com.ragagent.websearch.provider;

/**
 * 连通性测试的空结果错误（对照 Go {@code web_search/test_errors.go} 全文）。
 *
 * <p>文案逐字对照；searxng 的 detail 由
 * {@link SearxngProvider#emptyResultDiagnostics()} 提供。</p>
 */
public final class EmptyTestResults {

    private EmptyTestResults() {
    }

    /** 对照 EmptyTestResultsError：返回的异常消息 = Go 的 error 文案。 */
    public static SearchHttp.SearchHttpException emptyTestResultsError(String providerType,
                                                                       WebSearchProvider provider) {
        String detail = provider == null ? "" : provider.emptyResultDiagnostics();
        return switch (providerType == null ? "" : providerType) {
            case "searxng" -> !detail.isEmpty()
                    ? new SearchHttp.SearchHttpException("searxng returned 0 results: " + detail)
                    : new SearchHttp.SearchHttpException(
                            "searxng returned 0 results; verify the instance URL, JSON format "
                            + "in settings.yml, and upstream search engine connectivity")
                            ;
            case "duckduckgo" -> new SearchHttp.SearchHttpException(
                    "duckduckgo returned 0 results; verify network connectivity and proxy settings");
            case "keenable" -> new SearchHttp.SearchHttpException(
                    "keenable returned 0 results; keyless requests are rate-limited, so verify "
                    + "network connectivity or set an API key to lift the cap");
            case "exa" -> new SearchHttp.SearchHttpException(
                    "exa returned 0 results; verify the API key, account quota, network "
                    + "connectivity, and proxy settings");
            default -> new SearchHttp.SearchHttpException(
                    "search returned 0 results, please verify your API key and configuration");
        };
    }
}
