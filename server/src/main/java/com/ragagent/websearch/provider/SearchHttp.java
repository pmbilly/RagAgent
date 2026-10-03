package com.ragagent.websearch.provider;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.chat.LlmTransport;

/**
 * 出站搜索 HTTP 设施（代理 URL 校验 + 客户端装配）。
 *
 * <p>代理：显式 proxyURL 先过 SSRF 校验再挂 ProxySelector；否则跟随环境
 * （JDK 默认 selector）。
 * 重定向：逐跳 SSRF 校验（MaxRedirects=10）→ 复用
 * {@link LlmTransport} 的手动跟随；<b>Brave 用「不跟随」</b>。</p>
 */
public final class SearchHttp {

    private SearchHttp() {
    }

    /** 代理 URL 校验：trim 后非空才校验。 */
    public static void validateProxyUrl(SsrfGuard guard, String proxyUrl) {
        String p = proxyUrl == null ? "" : proxyUrl.trim();
        if (p.isEmpty()) {
            return;
        }
        guard.validateURLForSSRF(p);
    }

    /** 单次发送（不跟随重定向；3xx 原样返回，由调用方决定后续）。 */
    public static Result sendNoRedirect(HttpRequest request) {
        try {
            HttpResponse<byte[]> resp = LlmTransport.send(request, 0,
                    HttpResponse.BodyHandlers.ofByteArray());
            return new Result(resp.statusCode(), statusLine(resp.statusCode()), bodyBytes(resp));
        } catch (IOException e) {
            throw new SearchHttpException(e.getMessage() == null
                    ? e.getClass().getName() : e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchHttpException("context canceled", e);
        }
    }

    /**
     * 发送并跟随重定向（每跳 SSRF 校验）。explicitProxy 非空时走带代理的独立
     * HttpClient（其余场景复用 LlmTransport 的共享客户端）。
     */
    public static Result sendFollowRedirects(HttpRequest request, String explicitProxy) {
        String proxy = explicitProxy == null ? "" : explicitProxy.trim();
        if (proxy.isEmpty()) {
            try {
                HttpResponse<byte[]> resp = LlmTransport.send(request,
                        LlmTransport.DEFAULT_MAX_REDIRECTS, HttpResponse.BodyHandlers.ofByteArray());
                return new Result(resp.statusCode(), statusLine(resp.statusCode()), bodyBytes(resp));
            } catch (IOException e) {
                throw new SearchHttpException(e.getMessage() == null
                        ? e.getClass().getName() : e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchHttpException("context canceled", e);
            }
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(proxySelector(proxy))
                .build();
        try {
            HttpResponse<byte[]> resp = follow(client, request);
            return new Result(resp.statusCode(), statusLine(resp.statusCode()), bodyBytes(resp));
        } catch (IOException e) {
            throw new SearchHttpException(e.getMessage() == null
                    ? e.getClass().getName() : e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchHttpException("context canceled", e);
        }
    }

    private static HttpResponse<byte[]> follow(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        HttpRequest current = request;
        int hops = 0;
        while (true) {
            LlmTransport.validateUrlForSsrf(current.uri().toString());
            HttpResponse<byte[]> resp = client.send(current, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            if (!isRedirect(status)) {
                return resp;
            }
            Optional<String> location = resp.headers().firstValue("Location");
            if (location.isEmpty() || location.get().isBlank()) {
                return resp;
            }
            if (hops >= LlmTransport.DEFAULT_MAX_REDIRECTS) {
                throw new IOException("stopped after " + LlmTransport.DEFAULT_MAX_REDIRECTS + " redirects");
            }
            URI target = current.uri().resolve(location.get().trim());
            LlmTransport.validateUrlForSsrf(target.toString());
            HttpRequest.Builder builder = HttpRequest.newBuilder(target);
            current.timeout().ifPresent(builder::timeout);
            current.headers().map().forEach((name, values) -> {
                for (String v : values) {
                    builder.header(name, v);
                }
            });
            if (status == 301 || status == 302 || status == 303) {
                builder.GET();
            } else {
                builder.method(current.method(), current.bodyPublisher()
                        .orElse(HttpRequest.BodyPublishers.noBody()));
            }
            current = builder.build();
            hops++;
        }
    }

    private static ProxySelector proxySelector(String proxyUrl) {
        try {
            URI u = URI.create(proxyUrl);
            if (u.getScheme() == null || u.getScheme().isEmpty() || u.getHost() == null
                    || u.getHost().isEmpty()) {
                throw new SearchHttpException("invalid proxy_url: scheme and host are required");
            }
            int port = u.getPort();
            return ProxySelector.of(new java.net.InetSocketAddress(u.getHost(),
                    port == -1 ? 80 : port));
        } catch (IllegalArgumentException e) {
            throw new SearchHttpException("invalid proxy_url: " + e.getMessage(), e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] bodyBytes(HttpResponse<byte[]> resp) {
        return resp.body() == null ? new byte[0] : resp.body();
    }

    /** HTTP 状态短语表（同族小副本）。 */
    static String statusLine(int code) {
        return code + " " + switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    /** 搜索 provider 的运行期失败。 */
    public static class SearchHttpException extends RuntimeException {
        public SearchHttpException(String message) {
            super(message);
        }

        public SearchHttpException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 一次 HTTP 结果。 */
    public record Result(int status, String statusLine, byte[] body) {
        public String bodyText() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 请求构造便捷（headers + timeout）。 */
    public static HttpRequest.Builder request(String url, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (timeout != null) {
            b.timeout(timeout);
        }
        return b;
    }

    /** 逐条塞入自定义头（当前 provider 参数无 custom_headers；预留形状）。 */
    public static HttpRequest.Builder applyHeaders(HttpRequest.Builder b, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(b::header);
        }
        return b;
    }
}
