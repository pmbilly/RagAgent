package com.ragagent.llm.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.ragagent.common.security.SsrfGuard;

/**
 * LLM 裸 HTTP 调用的共享传输层（对照 Go internal/models/chat/transport.go 全文）。
 *
 * <p>三块内容：</p>
 * <ol>
 *   <li><b>共享客户端</b>：一个进程级复用的 {@link HttpClient}，连接层走 SSRF 校验
 *       （Go 用 {@code secutils.SSRFSafeDialContext}；JDK 的 HttpClient 不允许替换 dialer，
 *       故 Java 侧把校验放在"发送前 + 每一次重定向跳转前"，效果等价于 Go 的
 *       {@code SSRFSafeTransport + newSSRFCheckRedirect}）。</li>
 *   <li><b>兜底超时</b>：{@link #withLlmTimeout} 只在调用方没有 deadline 时套一个默认值。</li>
 *   <li><b>环境变量</b>：{@link #DEFAULT_CHAT_TIMEOUT} / {@link #DEFAULT_STREAM_TIMEOUT}。</li>
 * </ol>
 *
 * <p><b>⚠️ Go 侧注释与代码不一致，以代码为准</b>：transport.go 的注释写
 * "chat 默认 600s / stream 默认 1800s"，但代码里实际是
 * {@code 300s / 600s}（transport.go:20-21）。Java 侧按<b>代码</b>取 300 / 600。</p>
 *
 * <p><b>不设 per-request timeout 的理由（照抄 Go）</b>：超时一律通过 deadline 施加在请求上，
 * 而不是 {@code http.Client.Timeout}——后者会把流式调用提前掐断。</p>
 */
public final class LlmTransport {

    /** 非流式调用的兜底超时（环境变量 WEKNORA_LLM_CHAT_TIMEOUT_SECONDS，代码默认 300s）。 */
    public static final Duration DEFAULT_CHAT_TIMEOUT =
            envDurationSeconds("WEKNORA_LLM_CHAT_TIMEOUT_SECONDS", Duration.ofSeconds(300));

    /** 流式调用的兜底超时（环境变量 WEKNORA_LLM_STREAM_TIMEOUT_SECONDS，代码默认 600s）。 */
    public static final Duration DEFAULT_STREAM_TIMEOUT =
            envDurationSeconds("WEKNORA_LLM_STREAM_TIMEOUT_SECONDS", Duration.ofSeconds(600));

    /** 对照 Go rawHTTPClient 的 MaxRedirects=10。 */
    public static final int DEFAULT_MAX_REDIRECTS = 10;

    /** 对照 Go rawHTTPTransport 的 TLSHandshakeTimeout=10s。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 调用方的 deadline 已经过期时给出的最小超时（Go 侧 ctx 已过期 → 请求立即失败）。 */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    /** 跨域重定向时必须剥掉的凭据头（对照 Go stripRedirectSensitiveHeaders）。 */
    private static final List<String> REDIRECT_SENSITIVE_HEADERS =
            List.of("Authorization", "Cookie", "X-Auth-Token", "X-Api-Key", "Api-Key");

    private static volatile SsrfGuard ssrfGuard = new SsrfGuard();

    private LlmTransport() {
    }

    // ------------------------------------------------------------------
    // 超时（对照 transport.go:14-45）
    // ------------------------------------------------------------------

    /**
     * 对照 Go envDurationSeconds：读取以"秒"为单位的环境变量，
     * 未设置、解析失败或非正值一律回退到 fallback。
     */
    public static Duration envDurationSeconds(String key, Duration fallback) {
        return parseDurationSeconds(System.getenv(key), fallback);
    }

    /** 便于测试的纯函数版本（Java 无法像 Go 的 t.Setenv 那样改环境变量）。 */
    static Duration parseDurationSeconds(String rawValue, Duration fallback) {
        String v = rawValue == null ? "" : rawValue.trim();
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(v);
            if (n <= 0) {
                return fallback;
            }
            return Duration.ofSeconds(n);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 对照 Go withLLMTimeout：只在调用方未设置 deadline 时施加兜底超时；
     * 调用方若已显式设置 deadline（无论比默认更短还是更长），都原样尊重，
     * 让调用方对自己的超时策略拥有最终决定权。
     *
     * <p>Java 侧以"剩余时长"形式返回（Go 返回的是带 deadline 的 ctx，语义等值——
     * callerDeadline 为空 = 无 deadline → 用 fallback）。已过期的 deadline 折算成
     * {@link #MIN_TIMEOUT}（对应 Go 里 ctx 已过期 → 请求立即失败；JDK 的
     * {@code HttpRequest.timeout} 不接受非正数）。</p>
     *
     * @param callerDeadline 调用方下发的截止时刻；null = 未设置
     * @param fallback       兜底超时（{@link #DEFAULT_CHAT_TIMEOUT} / {@link #DEFAULT_STREAM_TIMEOUT}）
     */
    public static Duration withLlmTimeout(Instant callerDeadline, Duration fallback) {
        if (callerDeadline == null) {
            return fallback;
        }
        Duration remaining = Duration.between(Instant.now(), callerDeadline);
        return remaining.isNegative() || remaining.isZero() ? MIN_TIMEOUT : remaining;
    }

    // ------------------------------------------------------------------
    // 共享客户端（对照 transport.go:47-62）
    // ------------------------------------------------------------------

    /**
     * 进程级共享的 SSRF 安全客户端。懒加载（首次真正要发请求时才建线程池）。
     *
     * <p>Go 侧：{@code Transport{Proxy: ProxyFromEnvironment, DialContext: SSRFSafeDialContext,
     * TLSHandshakeTimeout: 10s, IdleConnTimeout: 90s, MaxIdleConnsPerHost: 5}} +
     * {@code SSRFSafeHTTPClientWithTransport({Timeout: 0, MaxRedirects: 10})}。
     * Java 侧连接池与空闲连接回收由 JDK 客户端自行管理（无需逐项配置），
     * {@code Timeout: 0}（不设 client 级超时）与 {@code MaxRedirects} 由
     * {@link #send} 手动跟随实现。</p>
     */
    public static HttpClient sharedClient() {
        return ClientHolder.INSTANCE;
    }

    private static final class ClientHolder {
        private static final HttpClient INSTANCE = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 重定向手动跟随：每一跳都要重新做 SSRF 校验（对照 newSSRFCheckRedirect）
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * 注入 Spring 管理的 {@link SsrfGuard}（含运行时由 SystemSettingService 推送的
     * DB 白名单）。未注入时用读环境变量的默认实例。
     */
    public static void setSsrfGuard(SsrfGuard guard) {
        if (guard != null) {
            ssrfGuard = guard;
        }
    }

    /** 对照 Go secutils.ValidateURLForSSRF。 */
    public static void validateUrlForSsrf(String url) {
        ssrfGuard.validateURLForSSRF(url);
    }

    /** 发送请求并跟随重定向（默认最多 {@link #DEFAULT_MAX_REDIRECTS} 跳），响应体为流。 */
    public static HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, DEFAULT_MAX_REDIRECTS);
    }

    /** 对照 Go http.Client.Do：SSRF 校验 + 限次重定向跟随，响应体为流。 */
    public static HttpResponse<InputStream> send(HttpRequest request, int maxRedirects)
            throws IOException, InterruptedException {
        return send(request, maxRedirects, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 对照 Go http.Client 的 {@code Do}（含 {@code CheckRedirect}）：
     * 发送前先校验 URL，随后每一跳都重新校验（含 scheme），跨域跳转剥掉凭据头，
     * 跳数超限抛错。
     */
    public static <T> HttpResponse<T> send(HttpRequest request, int maxRedirects,
                                           HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest current = request;
        URI originalUri = request.uri();
        int hops = 0;
        while (true) {
            validateUrlForSsrf(current.uri().toString());
            HttpResponse<T> response = sharedClient().send(current, handler);
            int status = response.statusCode();
            if (!isRedirect(status)) {
                return response;
            }
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isEmpty() || location.get().isBlank()) {
                // Go：3xx 但没有 Location 时不再跟随，原样返回
                return response;
            }
            if (hops >= maxRedirects) {
                closeQuietly(response);
                throw new IOException("stopped after " + maxRedirects + " redirects");
            }

            URI target;
            try {
                target = current.uri().resolve(location.get().trim());
            } catch (IllegalArgumentException e) {
                closeQuietly(response);
                throw new IOException("redirect blocked: invalid location " + location.get(), e);
            }
            String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                closeQuietly(response);
                throw new IOException("redirect blocked: target URL failed SSRF validation: invalid scheme " + scheme);
            }
            try {
                validateUrlForSsrf(target.toString());
            } catch (RuntimeException e) {
                closeQuietly(response);
                throw new IOException("redirect blocked: target URL failed SSRF validation: " + e.getMessage(), e);
            }

            boolean crossOrigin = !sameHttpOrigin(originalUri, target);
            boolean dropBody = isMethodDroppingRedirect(status) && !isGetOrHead(current.method());
            HttpRequest.Builder builder = HttpRequest.newBuilder(target);
            current.timeout().ifPresent(builder::timeout);
            if (dropBody) {
                builder.method("GET", HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(current.method(),
                        current.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            }
            current.headers().map().forEach((name, values) -> {
                if (crossOrigin && REDIRECT_SENSITIVE_HEADERS.stream()
                        .anyMatch(h -> h.equalsIgnoreCase(name))) {
                    return; // 跨域：剥掉凭据头，避免泄漏给第三方
                }
                for (String value : values) {
                    builder.header(name, value);
                }
            });

            closeQuietly(response);
            current = builder.build();
            hops++;
        }
    }

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

    private static void closeQuietly(HttpResponse<?> response) {
        if (response.body() instanceof InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 中间跳的响应体，关不掉也无所谓
            }
        }
    }
}
