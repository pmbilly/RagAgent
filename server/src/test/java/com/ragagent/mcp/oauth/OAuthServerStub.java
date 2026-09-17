package com.ragagent.mcp.oauth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 极简 OAuth 授权服务器桩（对照 Go 测试里的 {@code httptest.NewServer}）。
 *
 * <p>Java 侧没有 httptest，故手写一个只实现本项目用例所需行为的桩：
 * 元数据发现、token 端点、动态注册端点，以及每个端点的"被调用次数 / 收到的表单"记录。</p>
 */
public final class OAuthServerStub implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger registerRequests = new AtomicInteger();
    private final Map<String, String> lastTokenForm = new ConcurrentHashMap<>();
    private final Map<String, String> lastRegisterBody = new ConcurrentHashMap<>();

    /** token 端点返回的 HTTP 状态。 */
    volatile int tokenStatus = 200;
    /** token 端点返回的 JSON 体。 */
    public volatile Map<String, Object> tokenBody = Map.of();
    /** 注册端点返回的 HTTP 状态。 */
    volatile int registerStatus = 201;
    /** 注册端点返回的 JSON 体。 */
    public volatile Map<String, Object> registerBody = Map.of("client_id", "dyn-client-1");
    /** 元数据端点是否返回 200（false → 404，用于探测发现链的退化行为）。 */
    volatile boolean metadataAvailable = true;
    /** 元数据里是否带 registration_endpoint（false → 模拟"不支持动态注册"的服务器）。 */
    volatile boolean includeRegistrationEndpoint = true;
    /** 授权端点路径；为 null 时不注册该端点。 */
    volatile String issuerOverride;

    public OAuthServerStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String url(String path) {
        return url() + path;
    }

    int tokenRequests() {
        return tokenRequests.get();
    }

    int registerRequests() {
        return registerRequests.get();
    }

    Map<String, String> lastTokenForm() {
        return lastTokenForm;
    }

    Map<String, String> lastRegisterBody() {
        return lastRegisterBody;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/metadata" -> {
                if (!metadataAvailable) {
                    respond(exchange, 404, Map.of());
                    return;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("issuer", url());
                metadata.put("authorization_endpoint", url("/authorize"));
                metadata.put("token_endpoint", url("/token"));
                if (includeRegistrationEndpoint) {
                    metadata.put("registration_endpoint", url("/register"));
                }
                metadata.put("response_types_supported", java.util.List.of("code"));
                metadata.put("token_endpoint_auth_methods_supported", java.util.List.of("none"));
                respond(exchange, 200, metadata);
            }
            case "/token" -> {
                tokenRequests.incrementAndGet();
                lastTokenForm.clear();
                lastTokenForm.putAll(parseForm(readBody(exchange)));
                respond(exchange, tokenStatus, tokenBody);
            }
            case "/register" -> {
                registerRequests.incrementAndGet();
                lastRegisterBody.clear();
                try {
                    Map<?, ?> parsed = MAPPER.readValue(readBody(exchange), Map.class);
                    parsed.forEach((k, v) -> lastRegisterBody.put(String.valueOf(k), String.valueOf(v)));
                } catch (Exception ignored) {
                    // 体不是 JSON 时不记录
                }
                respond(exchange, registerStatus, registerBody);
            }
            default -> respond(exchange, 404, Map.of("error", "not_found"));
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
