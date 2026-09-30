package com.ragagent.mcp.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.ragagent.mcp.protocol.McpContext;

/**
 * OAuth 元数据发现协作者（对照 mcp-go oauth.go 的 getServerMetadata/metadataOnce，
 * 自 {@link OAuthHandler} 机械搬出）：发现状态组（一次性缓存 + 错误 + base/resource URL）
 * 与 RFC 8414/9728 路径插入发现链、元数据 URL 校验静态工具整体随簇。
 * 持门面回引取 config/timeout 与共享 {@code MAPPER}。
 */
final class OAuthDiscovery {

    private final OAuthHandler service;

    OAuthDiscovery(OAuthHandler service) {
        this.service = service;
    }

    /**
     * 元数据发现的"只跑一次"状态（对照 Go 的 {@code metadataOnce} /
     * {@code serverMetadata} / {@code metadataFetchErr} / {@code baseURL} /
     * {@code resourceURL} 一整组，全由 {@code metadataMu} 保护）。
     */
    private final ReentrantLock metadataLock = new ReentrantLock();
    private boolean metadataFetched;
    private AuthServerMetadata serverMetadata;
    private OAuthProtocolException metadataFetchError;
    private String baseUrl = "";
    /** RFC 8707 resource indicator；由 protected-resource 元数据或 base URL 推出。 */
    private String resourceUrl = "";

    /** 对照 Go {@code SetBaseURL}。 */
    public void setBaseUrl(String value) {
        metadataLock.lock();
        try {
            this.baseUrl = value == null ? "" : value;
        } finally {
            metadataLock.unlock();
        }
    }

    /**
     * 对照 Go {@code SetProtectedResourceMetadataURL}：换 URL 时把发现结果整体作废
     * （{@code serverMetadata}/{@code metadataFetchErr}/{@code metadataOnce}/{@code resourceURL}）。
     */
    public void setProtectedResourceMetadataUrl(String url) {
        metadataLock.lock();
        try {
            service.config.setProtectedResourceMetadataUrl(url);
            this.serverMetadata = null;
            this.metadataFetchError = null;
            this.metadataFetched = false;
            this.resourceUrl = "";
        } finally {
            metadataLock.unlock();
        }
    }

    // ── 发现 ───────────────────────────────────────────────────────────

    /** 对照 Go {@code GetServerMetadata}（公开包装）。 */
    public AuthServerMetadata getServerMetadata(McpContext ctx) {
        metadataLock.lock();
        try {
            if (!metadataFetched) {
                metadataFetched = true;
                discover(ctx);
            }
            if (metadataFetchError != null) {
                throw metadataFetchError;
            }
            return serverMetadata;
        } finally {
            metadataLock.unlock();
        }
    }

    /**
     * 对照 Go {@code getServerMetadata} 的 {@code metadataOnce.Do} 函数体（oauth.go:499-663）。
     * 调用方必须已持有 {@link #metadataLock}。
     */
    private void discover(McpContext ctx) {
        // 1. 显式 metadata URL 优先，直接用它，不做任何回退
        if (!service.config.authServerMetadataUrl().isEmpty()) {
            fetchMetadataFromUrl(service.config.authServerMetadataUrl());
            if (serverMetadata == null && metadataFetchError == null) {
                // Go 在这里会返回 (nil, nil)，随后调用方解引用空指针 panic（gin recovery → 500）。
                // Java 侧改为显式报错：对外仍是 500，但只有一条清晰文案，不是 NPE 堆栈。
                metadataFetchError = OAuthProtocolException.of(
                        "failed to load authorization server metadata from "
                                + service.config.authServerMetadataUrl());
            }
            return;
        }

        String base;
        try {
            base = extractBaseUrl();
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of("failed to extract base URL: " + e.getMessage(), e);
            return;
        }

        boolean explicitMetadataUrl = !service.config.protectedResourceMetadataUrl().isEmpty();
        String protectedResourceUrl;
        if (explicitMetadataUrl) {
            protectedResourceUrl = service.config.protectedResourceMetadataUrl();
        } else {
            try {
                protectedResourceUrl = buildWellKnownUrl(base, "oauth-protected-resource");
            } catch (RuntimeException e) {
                metadataFetchError = OAuthProtocolException.of(
                        "failed to build protected resource URL: " + e.getMessage(), e);
                return;
            }
        }

        OAuthHttp.Response resp;
        try {
            resp = OAuthHttp.get(protectedResourceUrl, service.timeout);
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to send protected resource request: " + e.getMessage(), e);
            return;
        }

        if (resp.status() != 200) {
            // 显式给的 URL 失败了就<b>不</b>再回退——服务端明确指了去哪找，回退只会掩盖问题
            if (explicitMetadataUrl) {
                metadataFetchError = OAuthProtocolException.of(
                        "protected resource metadata discovery failed for explicit URL \""
                                + protectedResourceUrl + "\": status " + resp.status());
                return;
            }
            for (String u : authorizationServerMetadataUrls(base)) {
                fetchMetadataFromUrl(u);
                if (serverMetadata != null) {
                    metadataFetchError = null;
                    return;
                }
            }
            AuthServerMetadata defaults = getDefaultEndpoints(base);
            serverMetadata = defaults;
            metadataFetchError = null;
            return;
        }

        OAuthProtectedResource protectedResource;
        try {
            protectedResource = OAuthHandler.MAPPER.readValue(resp.body(), OAuthProtectedResource.class);
        } catch (Exception e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to decode protected resource response: " + e.getMessage(), e);
            return;
        }

        // RFC 9728 §3.3/§7.3：从 WWW-Authenticate 广告来的 PRM（不可信网络输入）必须自证——
        // 声明的 resource 必须与被寻址的受保护资源一致；缺 resource 字段同样拒绝。
        if (explicitMetadataUrl) {
            if (protectedResource.resource().isEmpty()) {
                metadataFetchError = OAuthProtocolException.of(
                        "advertised protected resource metadata from \"" + protectedResourceUrl
                                + "\" omits required resource field");
                return;
            }
            if (!resourceIdentifiersEqual(protectedResource.resource(), base)) {
                metadataFetchError = OAuthProtocolException.of(
                        "advertised protected resource metadata declares resource \""
                                + protectedResource.resource() + "\" which does not match base URL \"" + base + "\"");
                return;
            }
        }

        // RFC 8707：记下 resource 指示符；元数据没给就退回 base URL
        resourceUrl = protectedResource.resource().isEmpty() ? base : protectedResource.resource();

        if (!protectedResource.hasAuthorizationServers()) {
            serverMetadata = getDefaultEndpoints(base);
            metadataFetchError = null;
            return;
        }

        String authServerUrl = protectedResource.authorizationServers().get(0);
        for (String u : authorizationServerMetadataUrls(authServerUrl)) {
            fetchMetadataFromUrl(u);
            if (serverMetadata != null) {
                metadataFetchError = null;
                return;
            }
        }
        serverMetadata = getDefaultEndpoints(authServerUrl);
        metadataFetchError = null;
    }

    /**
     * 对照 Go {@code fetchMetadataFromURL}：非 200 <b>静默跳过</b>（让调用方试下一个候选）；
     * 解码成功但 URL 字段非法时记录错误。
     */
    private void fetchMetadataFromUrl(String metadataUrl) {
        OAuthHttp.Response resp;
        try {
            resp = OAuthHttp.get(metadataUrl, service.timeout);
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to send metadata request: " + e.getMessage(), e);
            return;
        }
        if (resp.status() != 200) {
            return;
        }
        AuthServerMetadata metadata;
        try {
            metadata = OAuthHandler.MAPPER.readValue(resp.body(), AuthServerMetadata.class);
        } catch (Exception e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to decode metadata response: " + e.getMessage(), e);
            return;
        }
        try {
            validateAuthServerMetadataUrls(metadata);
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of(
                    "invalid authorization server metadata from " + metadataUrl + ": " + e.getMessage(), e);
            return;
        }
        serverMetadata = metadata;
    }

    /**
     * 对照 Go {@code extractBaseURL}：有 base URL 就用；否则从 redirect_uri 推 scheme://host。
     */
    private String extractBaseUrl() {
        if (!baseUrl.isEmpty()) {
            return baseUrl;
        }
        if (service.config.redirectUri().isEmpty()) {
            throw OAuthProtocolException.of("no base URL available and no redirect URI provided");
        }
        URI parsed;
        try {
            parsed = new URI(service.config.redirectUri());
        } catch (URISyntaxException e) {
            throw OAuthProtocolException.of("failed to parse redirect URI: " + e.getMessage(), e);
        }
        return parsed.getScheme() + "://" + parsed.getRawAuthority();
    }

    /**
     * 对照 Go {@code getDefaultEndpoints}：丢掉 path，用 {@code <scheme>://<host>} 拼默认端点。
     */
    private static AuthServerMetadata getDefaultEndpoints(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw OAuthProtocolException.of("failed to parse base URL: " + e.getMessage(), e);
        }
        if (isBlank(parsed.getScheme()) || isBlank(parsed.getRawAuthority())) {
            throw OAuthProtocolException.of("invalid base URL: missing scheme or host in \"" + url + "\"");
        }
        String authBaseUrl = parsed.getScheme() + "://" + parsed.getRawAuthority();
        return new AuthServerMetadata(authBaseUrl, authBaseUrl + "/authorize",
                authBaseUrl + "/token", authBaseUrl + "/register");
    }

    /** 对照 Go {@code getResourceURL}。 */
    public String getResourceUrl() {
        metadataLock.lock();
        try {
            return resourceUrl;
        } finally {
            metadataLock.unlock();
        }
    }
    // ── 静态工具（对照 Go 的包级函数） ───────────────────────────────────

    /**
     * 对照 Go {@code buildWellKnownURL}：well-known 段<b>插在 authority 与 path 之间</b>
     * （RFC 8414 §3 / RFC 9728 的路径插入语义），不是简单拼接。
     */
    static String buildWellKnownUrl(String baseUrl, String suffix) {
        URI parsed;
        try {
            parsed = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw OAuthProtocolException.of("failed to parse base URL: " + e.getMessage(), e);
        }
        if (isBlank(parsed.getScheme()) || isBlank(parsed.getRawAuthority())) {
            throw OAuthProtocolException.of(
                    "invalid base URL: missing scheme or host in \"" + baseUrl + "\"");
        }
        String path = trimTrailingSlash(parsed.getRawPath() == null ? "" : parsed.getRawPath());
        String root = parsed.getScheme() + "://" + parsed.getRawAuthority();
        if (path.isEmpty() || "/".equals(path)) {
            return root + "/.well-known/" + suffix;
        }
        return root + "/.well-known/" + suffix + path;
    }

    /**
     * 对照 Go {@code authorizationServerMetadataURLs}：给定 issuer 的候选发现地址有序列表。
     * issuer 无路径时两个候选（RFC 8414 + OIDC）；有路径时三个（含 OIDC 的
     * {@code <path>/.well-known/openid-configuration} 变体）。
     */
    static List<String> authorizationServerMetadataUrls(String issuerUrl) {
        List<String> urls = new ArrayList<>();
        URI parsed;
        try {
            parsed = new URI(issuerUrl);
        } catch (URISyntaxException e) {
            return urls;
        }
        if (isBlank(parsed.getScheme()) || isBlank(parsed.getRawAuthority())) {
            return urls;
        }
        String root = parsed.getScheme() + "://" + parsed.getRawAuthority();
        String originalPath = trimSlashes(parsed.getPath() == null ? "" : parsed.getPath());
        if (originalPath.isEmpty()) {
            urls.add(root + "/.well-known/oauth-authorization-server");
            urls.add(root + "/.well-known/openid-configuration");
            return urls;
        }
        urls.add(root + "/.well-known/oauth-authorization-server/" + originalPath);
        urls.add(root + "/.well-known/openid-configuration/" + originalPath);
        urls.add(root + "/" + originalPath + "/.well-known/openid-configuration");
        return urls;
    }

    /**
     * 对照 Go {@code validateAuthServerMetadataURLs}：每个 URL 型字段必须 http/https 且带 host。
     * 空的可选字段放行。
     */
    static void validateAuthServerMetadataUrls(AuthServerMetadata m) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("issuer", m.issuer());
        fields.put("authorization_endpoint", m.authorizationEndpoint());
        fields.put("token_endpoint", m.tokenEndpoint());
        fields.put("jwks_uri", m.jwksUri());
        fields.put("registration_endpoint", m.registrationEndpoint());
        fields.put("service_documentation", m.serviceDocumentation());
        fields.put("op_policy_uri", m.opPolicyUri());
        fields.put("op_tos_uri", m.opTosUri());
        fields.put("revocation_endpoint", m.revocationEndpoint());
        fields.put("introspection_endpoint", m.introspectionEndpoint());
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            URI u;
            try {
                u = new URI(e.getValue());
            } catch (URISyntaxException ex) {
                throw OAuthProtocolException.of(e.getKey() + ": " + ex.getMessage());
            }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw OAuthProtocolException.of(
                        e.getKey() + " has disallowed scheme \"" + u.getScheme() + "\"");
            }
            if (isBlank(u.getRawAuthority())) {
                throw OAuthProtocolException.of(e.getKey() + " is missing host");
            }
        }
    }

    /**
     * 对照 Go {@code resourceIdentifiersEqual}：scheme/host 大小写不敏感；
     * 路径比 {@code EscapedPath}（保留百分号编码语义）；<b>两侧各去掉一个尾部斜杠</b>
     * （真实部署常带或不带，判不等会误杀合法服务器）；query/fragment/userinfo 参与比较。
     * 不可解析时退回字符串精确比较。
     */
    static boolean resourceIdentifiersEqual(String a, String b) {
        URI ua;
        URI ub;
        try {
            ua = new URI(a);
            ub = new URI(b);
        } catch (URISyntaxException e) {
            return a.equals(b);
        }
        if (!equalsIgnoreCase(ua.getScheme(), ub.getScheme())) {
            return false;
        }
        if (!equalsIgnoreCase(ua.getRawAuthority(), ub.getRawAuthority())) {
            return false;
        }
        String pathA = ua.getRawPath() == null ? "" : ua.getRawPath();
        String pathB = ub.getRawPath() == null ? "" : ub.getRawPath();
        if (!trimTrailingSlash(pathA).equals(trimTrailingSlash(pathB))) {
            return false;
        }
        if (!equalsNn(ua.getRawQuery(), ub.getRawQuery())) {
            return false;
        }
        if (!equalsNn(ua.getRawFragment(), ub.getRawFragment())) {
            return false;
        }
        return equalsNn(ua.getRawUserInfo(), ub.getRawUserInfo());
    }
    /** 对照 Go {@code strings.TrimSuffix(p, "/")}：<b>最多</b>去掉一个尾部斜杠。 */
    private static String trimTrailingSlash(String s) {
        if (s.length() > 0 && s.charAt(s.length() - 1) == '/') {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String trimSlashes(String s) {
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.equalsIgnoreCase(y);
    }

    private static boolean equalsNn(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
