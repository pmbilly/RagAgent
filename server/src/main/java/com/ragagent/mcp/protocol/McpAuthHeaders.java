package com.ragagent.mcp.protocol;

import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 出站鉴权头的注入规则（对照 Go internal/mcp/client.go:80-152）。
 *
 * <p><b>核心不变量（Go 注释明确点名的修复）</b>：{@link #applyAuthHeaders} 只注入
 * <b>被选中的那一种</b>策略——由 {@code AuthType} 驱动，静态 API key 与 bearer
 * <b>互斥</b>。历史实现只要字段有值就两个都发，导致策略切换后出现"双重认证"
 * （服务端可能因此拒绝，或误用陈旧 token）。</p>
 *
 * <p>CustomHeaders 无论哪种策略都最后叠加，<b>可以覆盖</b>策略头。</p>
 */
public final class McpAuthHeaders {

    /** AuthType=api_key 且未指定 APIKeyHeader 时的默认头名（对照 Go 字面量）。 */
    public static final String DEFAULT_API_KEY_HEADER = "X-API-Key";

    private McpAuthHeaders() {
    }

    /**
     * 对照 Go applyAuthHeaders：把选中策略的鉴权头写进 {@code headers}（原地修改）。
     *
     * @param headers 待注入的头表（调用方通常已放入 {@code service.headers}）
     * @param ac      鉴权配置；null = 不注入任何东西（对照 Go 的 {@code if ac == nil return}）
     */
    public static void applyAuthHeaders(Map<String, String> headers, McpAuthConfig ac) {
        if (ac == null) {
            return;
        }
        McpAuthType authType = ac.getAuthType() == null ? McpAuthType.NONE : ac.getAuthType();
        switch (authType) {
            case API_KEY -> {
                if (nonEmpty(ac.getApiKey())) {
                    String name = nonEmpty(ac.getApiKeyHeader()) ? ac.getApiKeyHeader() : DEFAULT_API_KEY_HEADER;
                    headers.put(name, ac.getApiKey());
                }
            }
            case BEARER -> {
                if (nonEmpty(ac.getToken())) {
                    headers.put("Authorization", "Bearer " + ac.getToken());
                }
            }
            case NONE -> {
                // 向后兼容早于 AuthType 字段的历史行：从"恰好被设了值"的那个静态凭据推断，
                // 保留升级前的历史行为，让既有服务在升级后照常鉴权。
                // ⚠️ 这条分支是唯一允许两个头同时出现的地方——Go 就是这么写的。
                if (nonEmpty(ac.getApiKey())) {
                    headers.put(DEFAULT_API_KEY_HEADER, ac.getApiKey());
                }
                if (nonEmpty(ac.getToken())) {
                    headers.put("Authorization", "Bearer " + ac.getToken());
                }
            }
            case OAUTH -> {
                // OAuth 策略不注入静态头（由 OAuth 运行时接管，见 McpOAuthSupport）。
                // Go 的 switch 没有 oauth 分支，落到 default 什么都不做——语义一致。
            }
        }
        if (ac.getCustomHeaders() != null) {
            // 恒叠加，且可覆盖策略头（Go 用 map 赋值，同 key 覆盖）。
            headers.putAll(ac.getCustomHeaders());
        }
    }

    /**
     * 构造完整的出站头表 = {@code service.headers} 打底 + 选中策略的鉴权头。
     * 对照 Go {@code NewMCPClient} 里的 headers 装配（client.go:174-178）。
     */
    public static Map<String, String> buildHeaders(Map<String, String> serviceHeaders, McpAuthConfig ac) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (serviceHeaders != null) {
            headers.putAll(serviceHeaders);
        }
        applyAuthHeaders(headers, ac);
        return headers;
    }

    /**
     * 对照 Go {@code asOAuthRequired}（client.go:143-152）：在异常链里找 401 信号。
     *
     * <p>返回非 null 的 {@link McpOAuthRequiredException} <b>当且仅当</b>服务端广告了
     * metadata URL；裸 401 视为普通鉴权失败（比如 API key 写错），返回 null——
     * 免得把用户误导到 OAuth 上。</p>
     */
    public static McpOAuthRequiredException asOAuthRequired(Throwable err) {
        if (err == null) {
            return null;
        }
        Throwable cur = err;
        while (cur != null) {
            if (cur instanceof McpAuthorizationRequiredException auth && !auth.resourceMetadataUrl().isEmpty()) {
                return new McpOAuthRequiredException(auth.resourceMetadataUrl(), err);
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }

    /**
     * 对照 Go {@code extractResourceMetadataURL}（mcp-go streamable_http.go:347-356）：
     * 逐条 {@code WWW-Authenticate} 头，取第一个 {@code resource_metadata} 参数值。
     */
    public static String extractResourceMetadataUrl(java.util.List<String> wwwAuthenticateHeaders) {
        if (wwwAuthenticateHeaders == null) {
            return "";
        }
        for (String header : wwwAuthenticateHeaders) {
            for (String candidate : extractResourceMetadataUrls(header)) {
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return "";
    }

    /**
     * 对照 Go {@code extractResourceMetadataURLs}（streamable_http.go:367+）：
     * 一条头值里可能有多个 challenge，每个都可能带 {@code resource_metadata}；
     * 参数名按 RFC 9110 §11.2 大小写不敏感，值支持 quoted-string 与 token 两种形态。
     */
    private static java.util.List<String> extractResourceMetadataUrls(String header) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (header == null || header.isEmpty()) {
            return out;
        }
        final String target = "resource_metadata";
        int i = 0;
        int n = header.length();
        while (i < n) {
            // 前进到下一个 token 起点
            while (i < n && !isAuthTokenChar(header.charAt(i))) {
                i++;
            }
            int nameStart = i;
            while (i < n && isAuthTokenChar(header.charAt(i))) {
                i++;
            }
            String name = header.substring(nameStart, i);
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) {
                i++;
            }
            if (i >= n || header.charAt(i) != '=') {
                // 这个 token 是 scheme（如 "Bearer"）而非参数，继续
                continue;
            }
            i++; // 跳过 '='
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) {
                i++;
            }
            String value;
            if (i < n && header.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n && header.charAt(i) != '"') {
                    if (header.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    sb.append(header.charAt(i));
                    i++;
                }
                if (i < n) {
                    i++; // 收尾引号
                }
                value = sb.toString();
            } else {
                int valueStart = i;
                while (i < n && isAuthTokenChar(header.charAt(i))) {
                    i++;
                }
                value = header.substring(valueStart, i);
            }
            if (target.equalsIgnoreCase(name) && !value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /** 对照 Go isAuthTokenChar（streamable_http.go:455 附近）。 */
    private static boolean isAuthTokenChar(char c) {
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    /** 对照 Go 的 {@code ac.APIKey != ""} 判定（null 与空串等价）。 */
    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
