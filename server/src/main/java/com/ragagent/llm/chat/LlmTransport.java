package com.ragagent.llm.chat;

import java.io.IOException;
import com.ragagent.common.deployment.AppEnvLookup;
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
 * LLM 裸 HTTP 调用的共享传输层。
 *
 * <p>三块内容：</p>
 * <ol>
 *   <li><b>共享客户端</b>：一个进程级复用的 {@link HttpClient}，连接层走 SSRF 校验
 *       （JDK 的 HttpClient 不允许替换 dialer，故校验放在"发送前 + 每一次重定向跳转前"）。</li>
 *   <li><b>兜底超时</b>：{@link #withLlmTimeout} 只在调用方没有 deadline 时套一个默认值。</li>
 *   <li><b>环境变量</b>：{@link #DEFAULT_CHAT_TIMEOUT} / {@link #DEFAULT_STREAM_TIMEOUT}。</li>
 * </ol>
 *
 * <p><b>兜底超时取值：chat 300s / stream 600s</b>（环境变量可覆盖，见下方常量）。</p>
 *
 * <p><b>不设 per-request timeout 的理由</b>：超时一律通过 deadline 施加在请求上，
 * 而不是客户端级超时——后者会把流式调用提前掐断。</p>
 */
public final class LlmTransport {

    /** 非流式调用的兜底超时（环境变量 WEKNORA_LLM_CHAT_TIMEOUT_SECONDS，代码默认 300s）。 */
    public static final Duration DEFAULT_CHAT_TIMEOUT =
            envDurationSeconds("WEKNORA_LLM_CHAT_TIMEOUT_SECONDS", Duration.ofSeconds(300));

    /** 流式调用的兜底超时（环境变量 WEKNORA_LLM_STREAM_TIMEOUT_SECONDS，代码默认 600s）。 */
    public static final Duration DEFAULT_STREAM_TIMEOUT =
            envDurationSeconds("WEKNORA_LLM_STREAM_TIMEOUT_SECONDS", Duration.ofSeconds(600));

    /** 重定向上限。 */
    public static final int DEFAULT_MAX_REDIRECTS = 10;

    /** 连接/TLS 握手超时。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 调用方的 deadline 已经过期时给出的最小超时（请求立即失败）。 */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    /** 跨域重定向时必须剥掉的凭据头。 */
    private static final List<String> REDIRECT_SENSITIVE_HEADERS =
            List.of("Authorization", "Cookie", "X-Auth-Token", "X-Api-Key", "Api-Key");

    private static volatile SsrfGuard ssrfGuard = new SsrfGuard();

    private LlmTransport() {
    }

    // ------------------------------------------------------------------
    // 超时
    // ------------------------------------------------------------------

    /**
     * 读取以"秒"为单位的环境变量，
     * 未设置、解析失败或非正值一律回退到 fallback。
     */
    public static Duration envDurationSeconds(String key, Duration fallback) {
        return parseDurationSeconds(AppEnvLookup.get(key), fallback);
    }

    /** 便于测试的纯函数版本。 */
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
     * 只在调用方未设置 deadline 时施加兜底超时；
     * 调用方若已显式设置 deadline（无论比默认更短还是更长），都原样尊重，
     * 让调用方对自己的超时策略拥有最终决定权。
     *
     * <p>以"剩余时长"形式返回：callerDeadline 为空 = 无 deadline → 用 fallback。
     * 已过期的 deadline 折算成 {@link #MIN_TIMEOUT}（JDK 的
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
    // 共享客户端
    // ------------------------------------------------------------------

    /**
     * 进程级共享的 SSRF 安全客户端。懒加载（首次真正要发请求时才建线程池）。
     *
     * <p>连接池与空闲连接回收由 JDK 客户端自行管理（无需逐项配置）；
     * 不设 client 级超时，重定向由 {@link #send} 手动跟随实现。</p>
     */
    public static HttpClient sharedClient() {
        return ClientHolder.INSTANCE;
    }

    private static final class ClientHolder {
        private static final HttpClient INSTANCE = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 重定向手动跟随：每一跳都要重新做 SSRF 校验
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

    /** SSRF 校验（含 DB 动态白名单）。 */
    public static void validateUrlForSsrf(String url) {
        ssrfGuard.validateURLForSSRF(url);
    }

    /** 发送请求并跟随重定向（默认最多 {@link #DEFAULT_MAX_REDIRECTS} 跳），响应体为流。 */
    public static HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, DEFAULT_MAX_REDIRECTS);
    }

    /** SSRF 校验 + 限次重定向跟随，响应体为流。 */
    public static HttpResponse<InputStream> send(HttpRequest request, int maxRedirects)
            throws IOException, InterruptedException {
        return send(request, maxRedirects, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
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
                // 3xx 但没有 Location 时不再跟随，原样返回
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

    /** 只跟随 301/302/303/307/308。 */
    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 301/302/303 会把非 GET/HEAD 的请求降级为 GET（并丢弃 body）。 */
    private static boolean isMethodDroppingRedirect(int status) {
        return status == 301 || status == 302 || status == 303;
    }

    private static boolean isGetOrHead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** 同源判定：scheme + host（含端口）大小写不敏感比较。 */
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
