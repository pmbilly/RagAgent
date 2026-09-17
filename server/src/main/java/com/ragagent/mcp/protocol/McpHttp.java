package com.ragagent.mcp.protocol;

import com.ragagent.common.security.SsrfGuard;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * MCP 出站 HTTP 的安全底座（对照 Go {@code secutils.NewSSRFSafeHTTPClient}
 * + {@code secutils.DefaultSSRFSafeHTTPClientConfig}，client.go:169-171）。
 *
 * <p>Go 侧靠 {@code SSRFSafeDialContext} 在<b>拨号</b>层做 SSRF 校验，JDK 的 HttpClient 不允许
 * 替换 dialer，故 Java 侧把校验放在"发送前 + 每一次重定向跳转前"——与
 * {@code LlmTransport} 同策略（项目内已有的等价实现；这里是 MCP 专用的一份，
 * 因为两者的头处理与超时语义不同：MCP 的 SSE 长连接<b>不能</b>套整体超时）。</p>
 *
 * <p>超时口径（对照 Go）：{@code clientCfg.Timeout = AdvancedConfig.timeout}（默认 30s）。
 * Java 侧把它落到每个请求的 {@code HttpRequest.timeout} 上，而不是 client 级
 * {@code connectTimeout} —— 否则 SSE 长连接会被拦腰截断。</p>
 */
public final class McpHttp {

    /** 连接建立超时（对照 Go rawHTTPTransport 的 TLSHandshakeTimeout=10s 量级）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 最多跟随的重定向跳数（对照 Go {@code MaxRedirects}）。 */
    public static final int MAX_REDIRECTS = 10;

    /** 跨域重定向时必须剥掉的凭据头（对照 Go stripRedirectSensitiveHeaders）。 */
    private static final List<String> REDIRECT_SENSITIVE_HEADERS =
            List.of("Authorization", "Cookie", "X-Auth-Token", "X-Api-Key", "Api-Key");

    private static volatile SsrfGuard ssrfGuard = new SsrfGuard();

    private McpHttp() {
    }

    public static void setSsrfGuard(SsrfGuard guard) {
        if (guard != null) {
            ssrfGuard = guard;
        }
    }

    /**
     * 进程级共享客户端。刻意固定 HTTP/1.1：MCP 的 SSE/HTTP-Streamable 服务多部署在
     * 反向代理之后，HTTP/2 的流式复用在这些实现上容易踩坑（Go 侧默认 transport 也会
     * 因无 TLS 而退回 HTTP/1.1 明文）。
     */
    public static HttpClient sharedClient() {
        return ClientHolder.INSTANCE;
    }

    private static final class ClientHolder {
        private static final HttpClient INSTANCE = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER) // 手动跟随：每一跳都要重新做 SSRF 校验
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /** 对照 Go secutils.ValidateURLForSSRF。 */
    public static void validateUrlForSsrf(String url) {
        ssrfGuard.validateURLForSSRF(url);
    }

    /** 发送并（限次）跟随重定向，响应体为流。 */
    public static HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, MAX_REDIRECTS);
    }

    /**
     * 对照 Go {@code http.Client.Do}（含 CheckRedirect）：发送前校验 URL，每一跳重新校验
     * （含 scheme），跨域跳转剥掉凭据头，跳数超限报错。
     *
     * @return 响应；打开流式 body 后<b>不会</b>自动关闭（SSE 调用方负责）
     */
    public static HttpResponse<InputStream> send(HttpRequest request, int maxRedirects)
            throws IOException, InterruptedException {
        HttpRequest current = request;
        URI originalUri = request.uri();
        int hops = 0;
        while (true) {
            validateUrlForSsrf(current.uri().toString());
            HttpResponse<InputStream> response = sharedClient().send(current, HttpResponse.BodyHandlers.ofInputStream());
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isEmpty() || location.get().isBlank()) {
                return response; // 3xx 但没 Location：不跟随，原样返回（对照 Go）
            }
            closeQuietly(response);
            if (hops >= maxRedirects) {
                throw new IOException("stopped after " + maxRedirects + " redirects");
            }
            URI target;
            try {
                target = current.uri().resolve(location.get().trim());
            } catch (IllegalArgumentException e) {
                throw new IOException("redirect blocked: invalid location " + location.get(), e);
            }
            String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IOException("redirect blocked: target URL failed SSRF validation: invalid scheme " + scheme);
            }
            try {
                validateUrlForSsrf(target.toString());
            } catch (RuntimeException e) {
                throw new IOException("redirect blocked: target URL failed SSRF validation: " + e.getMessage(), e);
            }
            boolean crossOrigin = !sameOrigin(originalUri, target);
            HttpRequest.Builder builder = HttpRequest.newBuilder(target);
            current.timeout().ifPresent(builder::timeout);
            builder.method(current.method(), current.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            current.headers().map().forEach((name, values) -> {
                if (crossOrigin && REDIRECT_SENSITIVE_HEADERS.stream().anyMatch(h -> h.equalsIgnoreCase(name))) {
                    return; // 跨域：剥掉凭据头，避免把 API key/token 泄漏给第三方
                }
                for (String value : values) {
                    builder.header(name, value);
                }
            });
            current = builder.build();
            hops++;
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI a, URI b) {
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a.getScheme()).equalsIgnoreCase(String.valueOf(b.getScheme()))
                && String.valueOf(a.getHost()).equalsIgnoreCase(String.valueOf(b.getHost()))
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** 静默关闭响应体（重定向/错误路径上避免连接泄漏）。 */
    public static void closeQuietly(HttpResponse<InputStream> response) {
        if (response == null) {
            return;
        }
        try {
            response.body().close();
        } catch (IOException ignored) {
            // 已在关闭路径上，忽略
        }
    }
}
