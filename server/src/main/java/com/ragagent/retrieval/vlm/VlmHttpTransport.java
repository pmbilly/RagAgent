package com.ragagent.retrieval.vlm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agentm.service.AsrTranscriber;
import com.ragagent.llm.chat.LlmTransport;

/**
 * {@link VlmClient.Transport} 的生产实现（对照 Go vlm/transport.go 的
 * newVLMHTTPClient + go-openai 客户端的可观察行为）。
 *
 * <ul>
 *   <li>超时：{@code VLM_HTTP_TIMEOUT_SECONDS}（正整数秒）缺省 180s
 *       （对照 vlmHTTPTimeout，remote_api.go:32-40）；</li>
 *   <li>鉴权：{@code Authorization: Bearer <apiKey>}；</li>
 *   <li>非 2xx 错误文案复用 go-openai 仿真
 *       （{@link AsrTranscriber.OpenAiAsrTranscriber#goOpenAiError}）——
 *       与 ASR 共用同一套字节契约；</li>
 *   <li>网络层错误：Go 的 url.Error 形态（{@code Post "<url>": <cause>}）。</li>
 * </ul>
 */
public class VlmHttpTransport implements VlmClient.Transport {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 带自定义头的 POST（WeKnoraCloud 签名头）——**不带** Authorization；非 200 抛
     * {@link VlmClient.HttpStatusException}（让调用方按 Go 的文案报 status + 响应体）。
     */
    @Override
    public String postWithHeaders(String url, java.util.Map<String, String> headers,
            Object jsonBody) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(jsonBody);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(timeout());
        if (headers != null) {
            headers.forEach(builder::header);
        }
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> resp;
        try {
            resp = LlmTransport.send(builder.build());
        } catch (IOException e) {
            throw new RuntimeException("Post \"" + url + "\": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Post \"" + url + "\": interrupted");
        }
        byte[] respBody;
        try (InputStream stream = resp.body()) {
            respBody = stream == null ? new byte[0] : stream.readAllBytes();
        }
        if (resp.statusCode() != 200) {
            throw new VlmClient.HttpStatusException(resp.statusCode(),
                    new String(respBody, StandardCharsets.UTF_8));
        }
        return new String(respBody, StandardCharsets.UTF_8);
    }

    @Override
    public String post(String url, String apiKey, Object jsonBody) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(jsonBody);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .timeout(timeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = LlmTransport.send(request);
        } catch (IOException e) {
            throw new RuntimeException("Post \"" + url + "\": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Post \"" + url + "\": interrupted");
        }
        byte[] respBody;
        try (InputStream stream = resp.body()) {
            respBody = stream == null ? new byte[0] : stream.readAllBytes();
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                    AsrTranscriber.OpenAiAsrTranscriber.goOpenAiError(resp.statusCode(), respBody));
        }
        return new String(respBody, StandardCharsets.UTF_8);
    }

    /** 对照 vlmHTTPTimeout：env 正整数秒生效，否则 180s。 */
    static Duration timeout() {
        String raw = System.getenv("VLM_HTTP_TIMEOUT_SECONDS");
        if (raw != null && !raw.isBlank()) {
            try {
                int secs = Integer.parseInt(raw.trim());
                if (secs > 0) {
                    return Duration.ofSeconds(secs);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TIMEOUT;
    }
}
