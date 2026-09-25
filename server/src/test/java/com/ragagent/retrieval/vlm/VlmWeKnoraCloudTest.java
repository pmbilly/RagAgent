package com.ragagent.retrieval.vlm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.chat.LlmTransport;
import com.sun.net.httpserver.HttpServer;

/**
 * WeKnoraCloud 界面的 VLM（W5γ4.19 小账批补齐）——对照 Go {@code vlm/weknoracloud.go}：
 * {@code POST /api/v1/chat/completions}，multipart 内容（text + 各图 data URI）、
 * {@code max_tokens=5000}、{@code temperature=0.1}、{@code stream=false}；鉴权走
 * 六个签名头（{@code WeknoraCloudSign}，与 chat/embedding/rerank 同一份实现）；
 * {@code extra.remote_model_name} 覆盖模型名；错误族照 Go 原文。
 *
 * <p>签名校验：用**抓到的头**（request-id/timestamp/nonce）与**实际发出的 body** 独立重算
 * 一遍（Go 的算法：md5(sorted rfc3986 k=v & …)，body 先取 md5）——钉住"VLM 路径确实按
 * 该算法签名且 body 哈希覆盖的是发出去的 JSON"。</p>
 */
class VlmWeKnoraCloudTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private int status = 200;
    private String response = "{\"choices\":[{\"message\":{\"content\":\"云上识别结果\"}}]}";

    /**
     * 真实 {@link VlmHttpTransport} 走 {@code LlmTransport}（进程级 SSRF 客户端）——
     * 本地 stub 是 127.0.0.1，故临时换一个放行 loopback 的 guard（照
     * {@code ConnectorHttpTest} 的做法），收尾恢复默认实例。
     */
    @BeforeEach
    void allowLoopback() {
        LlmTransport.setSsrfGuard(new SsrfGuard() {
            @Override
            public void validateURLForSSRF(String rawURL) {
                // 测试放行（生产实例仍走真实校验）
            }
        });
    }

    @AfterEach
    void stop() {
        LlmTransport.setSsrfGuard(new SsrfGuard());
        if (server != null) {
            server.stop(0);
        }
    }

    private String startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
            captured.add(new Captured(headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static VlmClient.VlmConfig config(String baseUrl, String appId, String appSecret,
            Map<String, String> extra) {
        return new VlmClient.VlmConfig("remote", baseUrl, "cloud-vlm-model", "", "m1", "openai",
                "weknoracloud", extra, appId, appSecret);
    }

    private static VlmClient.Transport transport() {
        return new VlmHttpTransport();
    }

    @Test
    @DisplayName("请求形状 + 六个签名头（签名用抓到的头独立重算一致）+ 取 choices[0].message.content")
    void requestShapeAndSignature() throws Exception {
        String baseUrl = startStub();
        String result = VlmClient.predictWeKnoraCloud(
                config(baseUrl, "app-1", "secret-1", Map.of()), transport(),
                new byte[][] {new byte[] {1, 2, 3}}, "图里有什么？");

        assertThat(result).isEqualTo("云上识别结果");
        Captured req = captured.get(0);
        // 六个头齐备（大小写按 HTTP 头规范化）
        assertThat(req.headers()).containsKeys("X-appid", "X-api-key", "X-request-id",
                "X-timestamp", "X-nonce", "X-signature");
        assertThat(req.headers().get("X-appid")).isEqualTo("app-1");
        assertThat(req.headers().get("X-api-key")).isEqualTo("secret-1");
        assertThat(req.headers().get("X-request-id")).matches("[0-9a-f-]{36}");
        assertThat(req.headers().get("X-timestamp")).matches("\\d{10}");
        assertThat(req.headers().get("X-nonce")).matches("[A-Za-z0-9]{16}");
        // 独立重算签名（Go 算法）
        assertThat(req.headers().get("X-signature")).isEqualTo(goSignature(
                "app-1", "secret-1", req.headers().get("X-request-id"),
                req.headers().get("X-timestamp"), req.headers().get("X-nonce"), req.body()));

        JsonNode body = MAPPER.readTree(req.body());
        assertThat(body.path("model").asText()).isEqualTo("cloud-vlm-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(5000);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.path("stream").asBoolean()).isFalse();
        JsonNode message = body.path("messages").get(0);
        assertThat(message.path("role").asText()).isEqualTo("user");
        assertThat(message.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(message.path("content").get(0).path("text").asText()).isEqualTo("图里有什么？");
        JsonNode image = message.path("content").get(1);
        assertThat(image.path("type").asText()).isEqualTo("image_url");
        assertThat(image.path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    }

    @Test
    @DisplayName("remote_model_name 覆盖模型名（照 effectiveModelName）；空图丢弃")
    void remoteModelNameOverride() throws Exception {
        String baseUrl = startStub();
        VlmClient.predictWeKnoraCloud(
                config(baseUrl, "app-1", "secret-1", Map.of("remote_model_name", " qwen-vl-max ")),
                transport(), new byte[][] {new byte[0]}, "p");
        JsonNode body = MAPPER.readTree(captured.get(0).body());
        assertThat(body.path("model").asText()).isEqualTo("qwen-vl-max");
        assertThat(body.path("messages").get(0).path("content")).hasSize(1); // 只有 text
    }

    @Test
    @DisplayName("凭证缺失：AppID / AppSecret 文案照 Go")
    void missingCredentials() {
        assertThatThrownBy(() -> VlmClient.predictWeKnoraCloud(
                config("http://127.0.0.1:1", "", "s", Map.of()), transport(),
                new byte[][] {new byte[] {1}}, "p"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessage("WeKnoraCloud VLM: AppID is required");
        assertThatThrownBy(() -> VlmClient.predictWeKnoraCloud(
                config("http://127.0.0.1:1", "a", "", Map.of()), transport(),
                new byte[][] {new byte[] {1}}, "p"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessage("WeKnoraCloud VLM: AppSecret is required");
    }

    @Test
    @DisplayName("错误族：非 200 → 「weknoracloud VLM: status N: 报文」；无 choices → 「no choices」")
    void errorFamilies() throws Exception {
        String baseUrl = startStub();
        status = 500;
        response = "{\"msg\":\"boom\"}";
        assertThatThrownBy(() -> VlmClient.predictWeKnoraCloud(
                config(baseUrl, "a", "s", Map.of()), transport(),
                new byte[][] {new byte[] {1}}, "p"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessage("weknoracloud VLM: status 500: {\"msg\":\"boom\"}");

        status = 200;
        response = "{\"choices\":[]}";
        assertThatThrownBy(() -> VlmClient.predictWeKnoraCloud(
                config(baseUrl, "a", "s", Map.of()), transport(),
                new byte[][] {new byte[] {1}}, "p"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessage("WeKnoraCloud VLM: no choices in response");
    }

    @Test
    @DisplayName("predict 分派：weknoracloud 走 postWithHeaders（不落 post）；未实现该口的 stub 报错可辨")
    void predictDispatchesWeKnoraCloud() {
        List<String> calls = new ArrayList<>();
        VlmClient.Transport onlyPost = (url, apiKey, body) -> {
            calls.add("post");
            return "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}";
        };
        assertThatThrownBy(() -> VlmClient.predict(
                config("http://127.0.0.1:1", "a", "s", Map.of()), onlyPost,
                new byte[][] {new byte[] {1}}, "p"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessageContaining("postWithHeaders not supported");
        assertThat(calls).isEmpty();
    }

    /** 测试本地重算 Go 的签名（独立于生产实现）。 */
    private static String goSignature(String appId, String apiKey, String requestId,
            String timestamp, String nonce, String bodyJson) throws Exception {
        String bodyHash = md5(bodyJson == null || bodyJson.isEmpty() ? "{}" : bodyJson);
        Map<String, String> params = new java.util.TreeMap<>();
        params.put("x-appid", appId);
        params.put("x-api-key", apiKey);
        params.put("x-request-id", requestId);
        params.put("x-timestamp", timestamp);
        params.put("x-nonce", nonce);
        params.put("body", bodyHash);
        List<String> parts = new ArrayList<>();
        params.forEach((k, v) -> parts.add(encode(k) + "=" + encode(v)));
        return md5(String.join("&", parts));
    }

    private static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static String md5(String s) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
