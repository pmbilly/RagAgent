package com.ragagent.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.chat.LlmTransport;

/**
 * embedding 包共享的 SSRF 安全 HTTP 设施（对照 Go
 * {@code internal/models/embedding/transport.go} + 各 embedder 的
 * {@code doRequestWithRetry}）。
 *
 * <p>Go 侧语义与 Java 对应：</p>
 * <ol>
 *   <li>{@code sharedEmbeddingHTTPTransport}（进程级共享连接池）→ JDK HttpClient
 *       连接池由 {@link LlmTransport#sharedClient()} 进程级持有（同一守卫/重定向校验）。
 *       各 embedder 仍是独立 client 语义（各自超时），对应 Go 的
 *       {@code newEmbeddingHTTPClient(timeout)} 每客户端 timeout。</li>
 *   <li>{@code validateEmbeddingBaseURL}（空 URL 放行，失败前缀
 *       {@code "base URL SSRF check failed: "}）→ {@link #validateEmbeddingBaseUrl}。</li>
 *   <li>重试：每 embedder {@code maxRetries=3}（共 4 次尝试），指数退避
 *       {@code 1<<（i-1）} 秒封顶 10s，仅传输层错误重试（HTTP 非 2xx 由调用方直接
 *       报错不重试）；等待中被打断 = Go 的 {@code ctx.Done()} 分支。</li>
 *   <li>自定义头：对照 {@code secutils.ApplyCustomHeaders}——保留头
 *       （Content-Type / Authorization 等）被跳过。</li>
 * </ol>
 */
public final class EmbeddingHttp {

    /** Go 各 embedder 的统一超时：60s（openai.go L65 等）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** 对照各 embedder 的 {@code maxRetries: 3}。 */
    private static final int MAX_RETRIES = 3;

    private EmbeddingHttp() {
    }

    /** 对照 validateEmbeddingBaseURL：空 URL 允许（调用方会套 provider 默认地址）。 */
    public static void validateEmbeddingBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        try {
            LlmTransport.validateUrlForSsrf(baseUrl);
        } catch (RuntimeException e) {
            throw new EmbeddingException("base URL SSRF check failed: " + e.getMessage(), e);
        }
    }

    /**
     * 对照 {@code doRequestWithRetry}：POST JSON，最多 {@value #MAX_RETRIES}+1 次尝试。
     * 传输层失败抛 {@link EmbeddingException}（Go 的 {@code send request: %w} 前缀由
     * 调用方补）；成功返回 {@link Result}（含非 2xx，由调用方按各自文案分支）。
     */
    public static Result postWithRetry(String url, byte[] jsonBody, String authHeaderName,
                                       String authHeaderValue, Map<String, String> customHeaders,
                                       Duration timeout) {
        RuntimeException last = null;
        for (int i = 0; i <= MAX_RETRIES; i++) {
            if (i > 0) {
                long backoffSeconds = 1L << (i - 1);
                if (backoffSeconds > 10) {
                    backoffSeconds = 10;
                }
                try {
                    Thread.sleep(backoffSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingException("context canceled", e);
                }
            }
            try {
                return doOnce(url, jsonBody, authHeaderName, authHeaderValue, customHeaders, timeout);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    last = new EmbeddingException("context canceled", ie);
                    break;
                }
                last = new EmbeddingException(e.getMessage() == null
                        ? e.getClass().getName() : e.getMessage(), e);
            }
        }
        throw last;
    }

    private static Result doOnce(String url, byte[] jsonBody, String authHeaderName,
                                 String authHeaderValue, Map<String, String> customHeaders,
                                 Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(authHeaderName, authHeaderValue)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody));
        if (customHeaders != null) {
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) {
                    continue;
                }
                if (e.getKey().equalsIgnoreCase("Content-Type")
                        || e.getKey().equalsIgnoreCase(authHeaderName)) {
                    continue;
                }
                builder.header(e.getKey(), e.getValue());
            }
        }
        HttpResponse<byte[]> resp = LlmTransport.send(builder.build(),
                LlmTransport.DEFAULT_MAX_REDIRECTS, HttpResponse.BodyHandlers.ofByteArray());
        return new Result(resp.statusCode(), goStatusLine(resp.statusCode()),
                new String(resp.body(), StandardCharsets.UTF_8));
    }

    /** Go {@code resp.Status} 形如 "200 OK"；JDK 只有 code，补常见短语。 */
    static String goStatusLine(int code) {
        return code + " " + switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 413 -> "Request Entity Too Large";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    /** 一次 HTTP 结果（Go 的 {@code (resp.StatusCode, resp.Status, body)} 三元组）。 */
    public record Result(int status, String statusLine, String bodyText) {
    }

    /** embedding 包的运行期失败（对照 Go 各构造/调用 error 返回值）。 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** SsrfGuard 注入点：与 LlmTransport 共用同一进程级白名单。 */
    public static void useGuard(SsrfGuard guard) {
        LlmTransport.setSsrfGuard(guard);
    }
}
