package com.ragagent.websearch.provider;

import java.net.URI;

import com.ragagent.common.security.SsrfGuard;

/**
 * SearxngBaseURL 的共享校验（服务层参数
 * 校验与 provider 构造器共用，save/use 永不分歧）。四段拒绝文案 + SSRF 检查。
 */
final class SearxngValidation {

    private SearxngValidation() {
    }

    static void validateSearxngBaseUrl(String rawUrl) {
        String base = rawUrl == null ? "" : rawUrl.trim();
        if (base.isEmpty()) {
            throw new SearchHttp.SearchHttpException("base_url is required for SearXNG provider");
        }
        URI parsed;
        try {
            parsed = URI.create(base);
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed == null || parsed.getScheme() == null || parsed.getScheme().isEmpty()
                || parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw new SearchHttp.SearchHttpException(
                    "invalid SearXNG base_url: must be an absolute http(s) URL");
        }
        String scheme = parsed.getScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SearchHttp.SearchHttpException("invalid SearXNG base_url scheme: " + scheme);
        }
        if ((parsed.getRawQuery() != null && !parsed.getRawQuery().isEmpty())
                || (parsed.getRawFragment() != null && !parsed.getRawFragment().isEmpty())) {
            throw new SearchHttp.SearchHttpException(
                    "invalid SearXNG base_url: must not contain query or fragment");
        }
        try {
            new SsrfGuard().validateURLForSSRF(base);
        } catch (RuntimeException e) {
            throw new SearchHttp.SearchHttpException("invalid SearXNG base_url: " + e.getMessage());
        }
    }
}
