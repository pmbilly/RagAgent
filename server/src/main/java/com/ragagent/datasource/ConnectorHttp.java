package com.ragagent.datasource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ragagent.common.security.SsrfGuard;

/**
 * 连接器的出站 HTTP 底座（对照 Go {@code internal/datasource/httpclient.go} 全文 +
 * {@code internal/utils/security.go} 里 {@code NewSSRFSafeHTTPClient} /
 * {@code newSSRFCheckRedirect} / {@code SSRFValidatingRoundTripper} 三块）。
 *
 * <h2>Go 侧有三层防护，Java 侧一一对应</h2>
 * <ol>
 *   <li><b>SSRFValidatingRoundTripper</b>：每个出站请求（含 SDK 自己想发的）都要过
 *       {@code validateURLForSSRFForOutbound} → Java 在 {@link Client#exchange} 里
 *       <b>发送前</b>调 {@link SsrfGuard#validateURLForSSRF}。</li>
 *   <li><b>SSRFSafeDialContext</b>：拨号时再校验解析出的 IP（把 DNS 答案钉死）。
 *       JDK 的 {@code HttpClient} 不允许替换 dialer（{@code LlmTransport} 里已记录同一取舍），
 *       故 Java 侧保留"发送前校验"这一层，语义上覆盖同一条 URL 策略。</li>
 *   <li><b>CheckRedirect</b>：最多 10 跳、跨域剥凭据头、每一跳都重新做 SSRF 校验
 *       （含 scheme 白名单）→ Java 在 {@link Client#exchange} 里手动跟随，逐跳照做。</li>
 * </ol>
 *
 * <h2>超时映射</h2>
 * <p>Go 的 {@code SSRFSafeHTTPClientConfig.Timeout} 是<b>整个交互</b>（含重定向与读体）
 * 的上限。Java 侧把它落成每次 {@code HttpRequest.timeout(...)}（JDK 的该超时覆盖
 * 到响应体读完），并把每跳消耗的时间从剩余预算里扣掉，效果等价。</p>
 *
 * <h2>SsrfGuard 是进程级单例，由装配代码注入</h2>
 * <p>与 {@code LlmTransport.setSsrfGuard} / {@code McpHttp.setSsrfGuard} 同一处置：
 * 未注入时用读环境变量的默认实例。<b>测试</b>要打本机 stub server（{@code 127.0.0.1}）
 * 时，必须先把白名单放行——Go 那边是 {@code t.Setenv("SSRF_WHITELIST", "127.0.0.1,::1,localhost")}，
 * Java 侧进程内改不了 env，改用 {@code new SsrfGuard()} + {@code reloadWhitelist(...)}
 * 再 {@link #setSsrfGuard}。</p>
 *
 * <h2>与 {@code llm.chat.LlmTransport} 的关系</h2>
 * <p>两者的重定向跟随逻辑刻意<b>各写一份</b>：{@code LlmTransport} 服务的是 LLM 调用
 * （它不设客户端超时、靠 deadline 施加超时），连接器这边要的是 Go 的
 * {@code SSRFSafeHTTPClientConfig.Timeout} 语义。合二为一会让两边的超时策略互相牵制。
 * 这与 §9 里 WeKnoraCloud 签名函数重复实现的取舍同族——建议后续统一到
 * {@code common} 下一个共享底座，本模块先保证行为正确。</p>
 */
public final class ConnectorHttp {

    /** 对照 Go {@code DefaultSSRFSafeHTTPClientConfig().MaxRedirects}。 */
    public static final int DEFAULT_MAX_REDIRECTS = 10;

    /** 跨域重定向时必须剥掉的凭据头（对照 Go {@code stripRedirectSensitiveHeaders}）。 */
    private static final Set<String> REDIRECT_SENSITIVE_HEADERS = Set.of(
            "Authorization", "Cookie", "X-Auth-Token", "X-Api-Key", "Api-Key");

    private static volatile SsrfGuard ssrfGuard = new SsrfGuard();

    private ConnectorHttp() {
    }

    /** 注入 Spring 管理的 {@link SsrfGuard}（对照 {@code LlmTransport.setSsrfGuard}）。 */
    public static void setSsrfGuard(SsrfGuard guard) {
        if (guard != null) {
            ssrfGuard = guard;
        }
    }

    public static SsrfGuard ssrfGuard() {
        return ssrfGuard;
    }

    // ------------------------------------------------------------------
    // httpclient.go
    // ------------------------------------------------------------------

    /**
     * 对照 Go {@code ValidateConnectorBaseURL}：把连接器的 {@code base_url} 过一遍 SSRF 策略。
     *
     * <p>空串放行（调用方会在发请求前套上自己的默认值）；没有 scheme 时补 {@code https://}
     * 之后再校验——这一步很关键：{@code evil.internal} 这种裸主机会被补成
     * {@code https://evil.internal} 再被解析，否则 {@code url.Parse} 会把整串当 path。</p>
     *
     * @throws ConnectorException 消息为 {@code "base_url SSRF validation failed: <SsrfGuard 原文>"}
     */
    public static void validateConnectorBaseUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            return;
        }
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        try {
            ssrfGuard.validateURLForSSRF(url);
        } catch (SsrfGuard.SsrfException e) {
            throw new ConnectorException("base_url SSRF validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 对照 Go {@code NewConnectorHTTPClient(timeout)}：返回一个带 SSRF 防护、
     * 限次重定向跟随、以及 {@code timeout} 上限的客户端。
     */
    public static Client newConnectorHttpClient(Duration timeout) {
        return new Client(timeout);
    }

    // ------------------------------------------------------------------
    // Client / Response
    // ------------------------------------------------------------------

    /**
     * 对照 Go 的 {@code *http.Client}。一个实例一个超时预算，可复用（JDK 内部池化连接）。
     */
    public static final class Client {

        private static final HttpClient SHARED = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // 手动跟随：每一跳都要重新做 SSRF 校验（对照 newSSRFCheckRedirect）
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        private final Duration timeout;

        Client(Duration timeout) {
            this.timeout = timeout == null ? Duration.ZERO : timeout;
        }

        public Duration timeout() {
            return timeout;
        }

        /**
         * 对照 Go 的 {@code client.Do(req)}：发送请求、跟随重定向、读完全部响应体。
         *
         * <p>连接器侧的失败形态照抄 Go：<b>非 2xx 是正常返回</b>（由调用方判
         * {@link Response#status()}），只有传输层失败（连不上 / 超时 / 被 SSRF 拒绝）
         * 才抛 {@link ConnectorException}。</p>
         *
         * @param method  {@code "GET"} / {@code "POST"} …
         * @param url     已含 scheme 的完整 URL
         * @param headers 请求头；null 视为无
         * @param body    请求体；null 表示无体（GET 用 null）
         */
        public Response exchange(String method, String url, Map<String, String> headers, byte[] body) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
            long deadlineNanos = System.nanoTime()
                    + (timeout.isZero() || timeout.isNegative() ? Long.MAX_VALUE : timeout.toNanos());
            applyTimeout(builder, deadlineNanos);
            if (body != null) {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            if (headers != null) {
                headers.forEach((k, v) -> {
                    if (k != null && v != null) {
                        builder.header(k, v);
                    }
                });
            }

            HttpRequest current = builder.build();
            URI originalUri = current.uri();
            int hops = 0;
            while (true) {
                try {
                    ssrfGuard.validateURLForSSRF(current.uri().toString());
                } catch (SsrfGuard.SsrfException e) {
                    throw new ConnectorException(
                            "outbound request blocked by SSRF policy: " + e.getMessage(), e);
                }

                HttpResponse<byte[]> response;
                try {
                    response = SHARED.send(current, HttpResponse.BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    throw new ConnectorException("execute request: " + e.getMessage(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ConnectorException("request interrupted", e);
                }

                int status = response.statusCode();
                if (!isRedirect(status)) {
                    return new Response(status, statusText(status), response.body(),
                            new LinkedHashMap<>(response.headers().map()));
                }
                Optional<String> location = response.headers().firstValue("Location");
                if (location.isEmpty() || location.get().isBlank()) {
                    return new Response(status, statusText(status), response.body(),
                            new LinkedHashMap<>(response.headers().map()));
                }
                if (hops >= DEFAULT_MAX_REDIRECTS) {
                    throw new ConnectorException(
                            "execute request: stopped after " + DEFAULT_MAX_REDIRECTS + " redirects");
                }
                URI target;
                try {
                    target = current.uri().resolve(location.get().trim());
                } catch (IllegalArgumentException e) {
                    throw new ConnectorException(
                            "redirect blocked: invalid location " + location.get(), e);
                }
                String scheme = target.getScheme() == null
                        ? "" : target.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new ConnectorException("redirect blocked: target URL failed SSRF "
                            + "validation: invalid scheme " + scheme);
                }
                try {
                    ssrfGuard.validateURLForSSRF(target.toString());
                } catch (SsrfGuard.SsrfException e) {
                    throw new ConnectorException("redirect blocked: target URL failed SSRF "
                            + "validation: " + e.getMessage(), e);
                }

                boolean crossOrigin = !sameHttpOrigin(originalUri, target);
                boolean dropBody = isMethodDroppingRedirect(status) && !isGetOrHead(current.method());

                HttpRequest.Builder next = HttpRequest.newBuilder(target);
                applyTimeout(next, deadlineNanos);
                if (dropBody) {
                    next.method("GET", HttpRequest.BodyPublishers.noBody());
                } else {
                    next.method(current.method(), current.bodyPublisher()
                            .orElse(HttpRequest.BodyPublishers.noBody()));
                }
                current.headers().map().forEach((name, values) -> {
                    if (crossOrigin && REDIRECT_SENSITIVE_HEADERS.stream()
                            .anyMatch(h -> h.equalsIgnoreCase(name))) {
                        return; // 跨域：剥掉凭据头，避免泄漏给第三方
                    }
                    for (String value : values) {
                        next.header(name, value);
                    }
                });

                current = next.build();
                hops++;
            }
        }

        /** 便利方法：GET 无体。 */
        public Response get(String url, Map<String, String> headers) {
            return exchange("GET", url, headers, null);
        }

        /** 便利方法：POST 带体。 */
        public Response post(String url, Map<String, String> headers, byte[] body) {
            return exchange("POST", url, headers, body);
        }

        private void applyTimeout(HttpRequest.Builder builder, long deadlineNanos) {
            if (timeout.isZero() || timeout.isNegative()) {
                return; // 不设超时（对照 Go 的 Timeout: 0）
            }
            long remaining = deadlineNanos - System.nanoTime();
            // JDK 不接受非正的 timeout；已经过期就压到 1ms（等价 Go 的 deadline 已过 → 立刻失败）
            builder.timeout(Duration.ofNanos(Math.max(remaining, 1_000_000L)));
        }
    }

    /**
     * 对照 Go 的 {@code *http.Response}（只保留连接器真正用到的部分：
     * 状态码、状态行原文、响应头、响应体）。
     */
    public record Response(int status, String statusText, byte[] body,
                           Map<String, List<String>> headers) {

        /**
         * 对照 Go 的 {@code resp.Status}（如 {@code "429 Too Many Requests"}）。
         *
         * <p>方法名刻意不叫 {@code status()}——那是 record 组件 {@code status}（int）
         * 的访问器，同名不同返回类型在 Java 里是编译错误。</p>
         */
        public String statusLine() {
            return status + " " + statusText;
        }

        /** 对照 Go 的 {@code resp.Header.Get(name)}：取首个值，缺席回空串。 */
        public String header(String name) {
            List<String> values = headerValues(name);
            return values.isEmpty() ? "" : values.get(0);
        }

        public List<String> headerValues(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return List.of();
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String bodyAsString() {
            return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** 对照 Go {@code truncate(s, n)}：超长时截断并补 {@code "..."}。 */
        public String truncatedBody(int maxLen) {
            String s = bodyAsString();
            if (s.length() <= maxLen) {
                return s;
            }
            return s.substring(0, maxLen) + "...";
        }
    }

    // ------------------------------------------------------------------
    // 与 Go 逐行对应的私有工具
    // ------------------------------------------------------------------

    /** 对照 Go 的 3xx 判定（Go 只跟随 301/302/303/307/308）。 */
    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 对照 Go：301/302/303 会把非 GET/HEAD 的请求降级为 GET（并丢弃 body）。 */
    private static boolean isMethodDroppingRedirect(int status) {
        return status == 301 || status == 302 || status == 303;
    }

    private static boolean isGetOrHead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** 对照 Go sameHTTPOrigin：scheme + host（含端口）大小写不敏感比较。 */
    private static boolean sameHttpOrigin(URI a, URI b) {
        if (a == null || b == null) {
            return false;
        }
        return equalsIgnoreCase(a.getScheme(), b.getScheme())
                && equalsIgnoreCase(a.getRawAuthority(), b.getRawAuthority());
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equalsIgnoreCase(b);
    }

    /**
     * 对照 Go 的状态行原因短语表（{@code net/http.StatusText} 的子集 + 兜底）。
     *
     * <p><b>已知差异</b>：Go 的 {@code resp.Status} 直接抄服务端发回的状态行原文
     * （非标准原因短语会原样保留），JDK 不暴露原因短语，Java 侧只能查标准表。
     * 真实服务端（含本项目用的 stub server）发的都是标准短语，所以线上等价。</p>
     */
    static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 303: return "See Other";
            case 304: return "Not Modified";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 413: return "Request Entity Too Large";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "";
        }
    }
}
