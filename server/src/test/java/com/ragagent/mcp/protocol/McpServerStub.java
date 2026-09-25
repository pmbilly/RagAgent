package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 MCP 服务端桩（HTTP Streamable 传输）。
 *
 * <p>Java 侧没有 mcp-go 那样的服务端 SDK，故手写一个只实现本项目用例所需行为的桩：
 * initialize（可挂起）+ {@code notifications/initialized} + 各方法的可编程响应。</p>
 */
final class McpServerStub implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;

    /** 路径 → initialize 之前的阻塞钩子（用来把建连挂住）。 */
    final Map<String, Runnable> initializeGates = new ConcurrentHashMap<>();
    /** 路径 → initialize 次数。 */
    final Map<String, AtomicInteger> initializeCounts = new ConcurrentHashMap<>();
    /** 收到的 JSON-RPC 方法名（按顺序）。 */
    final List<String> methods = new CopyOnWriteArrayList<>();
    /** 收到的请求体原文（按顺序）——用于钉线上键序。 */
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    /** 收到的 Mcp-Session-Id 头（按顺序）。 */
    final List<String> sessionIdsSeen = new CopyOnWriteArrayList<>();
    /** 收到的 X-API-Key 头（按顺序）。 */
    final List<String> apiKeyHeaders = new CopyOnWriteArrayList<>();
    /** 收到的 Authorization 头（按顺序）。 */
    final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    /** 是否收到了 notifications/initialized。 */
    volatile boolean initializedNotificationSeen;
    /** 服务端下发的会话 ID；空串 = 无状态（不下发）。 */
    volatile String sessionId = "stub-session-1";
    /** initialize 返回的 instructions。 */
    volatile String instructions = "full server instructions";
    /** 方法名 → 自定义 result 构造（返回 null 表示回 202）。 */
    final Map<String, java.util.function.BiFunction<JsonNode, String, JsonNode>> responders = new ConcurrentHashMap<>();
    /** 非 200 的强制响应：方法名 → HTTP 状态（用于 404/405/401 用例）。 */
    final Map<String, Integer> forcedStatus = new ConcurrentHashMap<>();
    /** 用 SSE（text/event-stream）而非 JSON 回包的方法名。 */
    final java.util.Set<String> sseMethods = ConcurrentHashMap.newKeySet();
    /** 方法名 → JSON-RPC error 文案（HTTP 200 + error 对象，对照"服务端业务错误"）。 */
    final Map<String, String> errorMessages = new ConcurrentHashMap<>();
    /** 401 时下发的 WWW-Authenticate 头内容。 */
    volatile String unauthorizedHeader = "";

    McpServerStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    int initializeCount(String path) {
        AtomicInteger counter = initializeCounts.get(path);
        return counter == null ? 0 : counter.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            byte[] raw = readAll(exchange.getRequestBody());
            if (raw.length > 0) {
                requestBodies.add(new String(raw, StandardCharsets.UTF_8));
            }
            JsonNode request = raw.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
            String method = request.path("method").asText("");
            String sessionHeader = exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_SESSION_ID);
            methods.add(method);
            if (sessionHeader != null) {
                sessionIdsSeen.add(sessionHeader);
            } else {
                sessionIdsSeen.add("");
            }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (apiKey != null) {
                apiKeyHeaders.add(apiKey);
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizationHeaders.add(authorization);
            }

            if (McpProtocol.METHOD_INITIALIZE.equals(method)) {
                initializeCounts.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                Runnable gate = initializeGates.get(path);
                if (gate != null) {
                    gate.run();
                }
            }
            if (McpProtocol.METHOD_INITIALIZED_NOTIFICATION.equals(method)) {
                initializedNotificationSeen = true;
            }

            Integer forced = forcedStatus.get(method);
            if (forced != null) {
                if (forced == 401 && !unauthorizedHeader.isEmpty()) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", unauthorizedHeader);
                }
                respondEmpty(exchange, forced);
                return;
            }

            if (!request.has("id") || request.get("id").isNull()) {
                respondEmpty(exchange, 202); // 通知：Accepted
                return;
            }

            java.util.function.BiFunction<JsonNode, String, JsonNode> custom = responders.get(method);
            JsonNode result;
            if (custom != null) {
                result = custom.apply(request, path);
                if (result == null) {
                    respondEmpty(exchange, 202);
                    return;
                }
            } else if (McpProtocol.METHOD_INITIALIZE.equals(method)) {
                result = initializeResult();
            } else if (McpProtocol.METHOD_TOOLS_LIST.equals(method)) {
                result = MAPPER.readTree("{\"tools\":[]}");
            } else {
                result = MAPPER.createObjectNode();
            }

            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", request.get("id"));
            String errorMessage = errorMessages.get(method);
            if (errorMessage != null) {
                ObjectNode error = MAPPER.createObjectNode();
                error.put("code", -32000);
                error.put("message", errorMessage);
                envelope.set("error", error);
            } else {
                envelope.set("result", result);
            }
            String responseSession = McpProtocol.METHOD_INITIALIZE.equals(method) ? sessionId : "";
            if (sseMethods.contains(method)) {
                // 对照 MCP：单端点 POST 的响应也可以是 SSE 流
                byte[] stream = ("event: message\ndata: " + MAPPER.writeValueAsString(envelope) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                respondWith(exchange, 200, "text/event-stream", stream, responseSession);
                return;
            }
            respond(exchange, 200, MAPPER.writeValueAsBytes(envelope), responseSession);
        } catch (RuntimeException e) {
            respondEmpty(exchange, 500);
        }
    }

    private JsonNode initializeResult() throws IOException {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        result.set("capabilities", MAPPER.createObjectNode());
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "stub-server");
        info.put("version", "9.9.9");
        info.put("title", "Stub");
        info.put("description", "stub description");
        result.set("serverInfo", info);
        result.put("instructions", instructions);
        return result;
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private void respond(HttpExchange exchange, int status, byte[] body, String sessionHeader) throws IOException {
        respondWith(exchange, status, "application/json", body, sessionHeader);
    }

    private void respondWith(HttpExchange exchange, int status, String contentType, byte[] body,
                             String sessionHeader) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (sessionHeader != null && !sessionHeader.isEmpty()) {
            exchange.getResponseHeaders().set(McpProtocol.HEADER_SESSION_ID, sessionHeader);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
