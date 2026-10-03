package com.ragagent.llm.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ragagent.common.error.BizException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * HTTP 传输协作者（自 {@link RemoteApiChat} 拆出）：组请求头、发请求与非 200 错误文本。
 * 持门面回引用 adapter（可变，测试可替换）/鉴权三值/自定义头/模型名（日志）。
 */
final class RemoteHttpOps {

    private static final Logger log = LoggerFactory.getLogger(RemoteHttpOps.class);

    private final RemoteApiChat service;

    RemoteHttpOps(RemoteApiChat service) {
        this.service = service;
    }

    /** 不允许被用户自定义头覆盖的关键头。 */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "api-key", "x-api-key", "x-goog-api-key", "content-type",
            "content-length", "accept-encoding", "host", "connection", "transfer-encoding");

    /** 组请求头（标准头 → 鉴权 → 流式 Accept → 自定义头 → 缓存亲和头）。 */
    private HttpHeaders buildHeaders(RemoteApiChat.Outbound out, byte[] bodyBytes, boolean isStream) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        service.adapter().auth(headers, authCreds(), bodyBytes);
        if (isStream) {
            headers.set("Accept", "text/event-stream");
        }
        applyCustomHeaders(headers, service.customHeaders);
        PromptCache.attachPromptCacheHeaders(headers, out.policy(), out.sessionId());
        return headers;
    }

    /** 组装鉴权凭据。 */
    private ProviderAdapter.AuthCreds authCreds() {
        return new ProviderAdapter.AuthCreds(service.apiKey, service.appId, service.appSecret);
    }

    /** 保留头跳过，其余覆盖。 */
    private static void applyCustomHeaders(HttpHeaders headers, Map<String, String> custom) {
        if (custom == null || custom.isEmpty()) {
            return;
        }
        custom.forEach((key, value) -> {
            String name = key == null ? "" : key.trim();
            if (name.isEmpty() || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            headers.set(name, value);
        });
    }

    /** 发一次请求（先过 SSRF 校验）。 */
    HttpResponse<InputStream> sendRequest(RemoteApiChat.Outbound out, Duration timeout, boolean isStream) {
        byte[] bodyBytes = out.bodyBytes();
        try {
            LlmTransport.validateUrlForSsrf(out.endpoint());
        } catch (RuntimeException e) {
            throw BizException.internal("endpoint SSRF check failed: " + e.getMessage());
        }
        log.info("[LLM Request] Remote HTTP, endpoint={}, model={}, stream={}\n{}",
                out.endpoint(), service.modelName, isStream,
                RemoteApiChat.compactForLog(new String(bodyBytes, StandardCharsets.UTF_8)));

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(out.endpoint()))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        } catch (IllegalArgumentException e) {
            throw BizException.internal("create request: " + e.getMessage());
        }
        buildHeaders(out, bodyBytes, isStream).forEach((name, values) ->
                values.forEach(value -> builder.header(name, value)));

        try {
            return LlmTransport.send(builder.build());
        } catch (IOException e) {
            // getMessage() 可能为 null（如 EOFException），兜底用异常类名。
            throw BizException.internal("send request: " + ioDetail(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.internal("send request interrupted");
        }
    }

    private static String ioDetail(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** 读出完整响应体。非 200 的错误消息形如 "API request failed with status %d: %s"（见 {@link #statusError}）。 */
    static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.internal("read response: " + e.getMessage());
        }
    }

    static String statusError(HttpResponse<?> resp, String body) {
        return "API request failed with status " + resp.statusCode() + ": " + body;
    }

}
