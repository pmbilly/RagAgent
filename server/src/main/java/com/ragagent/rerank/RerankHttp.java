package com.ragagent.rerank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.ragagent.llm.chat.LlmTransport;

/**
 * rerank 包共享的 SSRF 安全 HTTP 设施（对照 Go
 * {@code internal/models/rerank/transport.go}）。
 *
 * <p>Go：{@code sharedRerankHTTPTransport} 进程级共享连接池 + 每 client 自己的
 * timeout（大多为 0 = 不设 client 级超时，依赖 ctx；Java 用 request timeout 为
 * null 表达"不设"）。失败类型 {@link RerankException}。</p>
 */
public final class RerankHttp {

    private RerankHttp() {
    }

    /** 对照 validateRerankBaseURL：空 URL 放行；失败前缀 "base URL SSRF check failed: "。 */
    public static void validateRerankBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        try {
            LlmTransport.validateUrlForSsrf(baseUrl);
        } catch (RuntimeException e) {
            throw new RerankException("base URL SSRF check failed: " + e.getMessage(), e);
        }
    }

    /**
     * 单次 POST（Go 的 rerank 各实现<b>没有重试循环</b>——直接 client.Do 一次）。
     * timeout 传 null 表示不设（对照 Go 的 newRerankHTTPClient(0)）。
     */
    public static Result post(String url, byte[] jsonBody, String authHeaderName,
                              String authHeaderValue, Map<String, String> customHeaders,
                              Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (timeout != null) {
            builder.timeout(timeout);
        }
        builder.header("Content-Type", "application/json")
                .header(authHeaderName, authHeaderValue)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody));
        if (customHeaders != null) {
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()
                        || e.getKey().equalsIgnoreCase("Content-Type")
                        || e.getKey().equalsIgnoreCase(authHeaderName)) {
                    continue;
                }
                builder.header(e.getKey(), e.getValue());
            }
        }
        try {
            HttpResponse<byte[]> resp = LlmTransport.send(builder.build(),
                    LlmTransport.DEFAULT_MAX_REDIRECTS, HttpResponse.BodyHandlers.ofByteArray());
            return new Result(resp.statusCode(), EmbeddingStatusLine.go(resp.statusCode()),
                    new String(resp.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RerankException(e.getMessage() == null
                    ? e.getClass().getName() : e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankException("context canceled", e);
        }
    }

    /** 一次 HTTP 结果。 */
    public record Result(int status, String statusLine, String bodyText) {
    }

    /** rerank 包的运行期失败（对照 Go 各 error 返回值）。 */
    public static class RerankException extends RuntimeException {
        public RerankException(String message) {
            super(message);
        }

        public RerankException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 与 embedding 包共用的 Go resp.Status 短语表（包内小副本）。 */
    static final class EmbeddingStatusLine {
        private EmbeddingStatusLine() {
        }

        static String go(int code) {
            return code + " " + switch (code) {
                case 200 -> "OK";
                case 201 -> "Created";
                case 204 -> "No Content";
                case 301 -> "Moved Permanently";
                case 302 -> "Found";
                case 304 -> "Not Modified";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 408 -> "Request Timeout";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "";
            };
        }
    }
}
