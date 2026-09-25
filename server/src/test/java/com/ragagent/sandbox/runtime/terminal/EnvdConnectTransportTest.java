package com.ragagent.sandbox.runtime.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.sandbox.runtime.SandboxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * envd Connect 传输的**本地桩**验证（W5δ 传输层 spike，离线可跑）：
 * 桩服务端模拟 envd 的 {@code /process.Process/*}，把"流开着时仍能发一元输入"这件事钉成回归。
 *
 * <p>桩照 Cube SDK 的实现写（{@code connect.go} 的帧 + {@code pty.go} 的路径/头），
 * 所以这里验的不是"我们自造的协议"，而是 SDK 已实证过的线上形状。</p>
 */
class EnvdConnectTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private HttpClient client;
    private EnvdConnectTransport transport;

    /** 桩收到的请求体（path → 原文）。 */
    private final Map<String, List<byte[]>> bodies = new ConcurrentHashMap<>();
    /** 桩收到的一次性头（path → 头集合）。 */
    private final Map<String, List<Headers>> headers = new ConcurrentHashMap<>();
    /** 测试可覆盖的非默认路由。 */
    private final Map<String, ExchangeHandler> handlers = new ConcurrentHashMap<>();

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
    /** happy-path 的流会在写出第二帧前等它，用来证明流与一元调用真的交织。 */
    private final CountDownLatch sendInputSeen = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodies.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>())
                    .add(exchange.getRequestBody().readAllBytes());
            headers.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>())
                    .add(exchange.getRequestHeaders());
            ExchangeHandler custom = handlers.get(path);
            if (custom != null) {
                custom.handle(exchange);
                return;
            }
            switch (path) {
                case "/process.Process/Start" -> streamStart(exchange);
                case "/process.Process/SendInput" -> unaryOk(exchange);
                default -> notFound(exchange);
            }
        });
        // 必须给线程池：默认执行器是单线程，阻塞的流处理器会把一元请求堵在门外
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        client = HttpClient.newBuilder().build();
        transport = new EnvdConnectTransport(client,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                EnvdConnectTransport.headers(
                        "X-Access-Token", "envd-token",
                        "Authorization", "Basic cm9vdDo="));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("★流开着时仍能发一元输入（无需半关闭/全双工），且请求形状照 SDK")
    void streamRunsWhileUnaryInputFlows() throws Exception {
        try (EnvdConnectTransport.StreamingCall call = transport.openStream(
                "Start", Map.of("cols", 80, "rows", 24), Duration.ofHours(24))) {

            JsonNode first = call.next();
            assertThat(first.path("event").path("data").asText()).isEqualTo("aGk=");

            JsonNode ack = transport.unary("SendInput", Map.of("input", "bHM="));
            assertThat(ack.path("ok").asBoolean()).isTrue();

            JsonNode second = call.next();
            assertThat(second.path("event").path("data").asText()).isEqualTo("d29ybGQ=");

            assertThat(call.next()).as("end-stream 帧之后返回 null").isNull();
            assertThat(call.endError()).as("正常结束无错误").isNull();
        }

        // —— 流式请求：完整帧化 JSON + application/connect+json + Connect-Timeout-Ms ——
        byte[] startBody = bodies.get("/process.Process/Start").get(0);
        assertThat(startBody[0]).as("帧 flag=0").isZero();
        long size = ((long) (startBody[1] & 0xff) << 24) | ((startBody[2] & 0xff) << 16)
                | ((startBody[3] & 0xff) << 8) | (startBody[4] & 0xff);
        assertThat(size).as("帧长度 = 体长-5（请求体一次性发完）").isEqualTo(startBody.length - 5);
        JsonNode payload = MAPPER.readTree(startBody, 5, (int) size);
        assertThat(payload.path("cols").asInt()).isEqualTo(80);

        Headers startHeaders = headers.get("/process.Process/Start").get(0);
        assertThat(startHeaders.getFirst("Content-Type")).isEqualTo("application/connect+json");
        assertThat(startHeaders.getFirst("Connect-Protocol-Version")).isEqualTo("1");
        assertThat(startHeaders.getFirst("Connect-Timeout-Ms")).isEqualTo("86400000");
        assertThat(startHeaders.getFirst("X-Access-Token")).isEqualTo("envd-token");
        assertThat(startHeaders.getFirst("Authorization")).isEqualTo("Basic cm9vdDo=");

        // —— 一元请求：裸 JSON（不帧化）+ application/json ——
        byte[] inputBody = bodies.get("/process.Process/SendInput").get(0);
        assertThat(inputBody[0]).as("一元体是裸 JSON").isEqualTo((byte) '{');
        Headers inputHeaders = headers.get("/process.Process/SendInput").get(0);
        assertThat(inputHeaders.getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(inputHeaders.getFirst("Connect-Protocol-Version")).isEqualTo("1");
    }

    @Test
    @DisplayName("压缩帧 → 拒绝（照 SDK pty.go:605-607）")
    void compressedFrameRejected() throws Exception {
        handlers.put("/process.Process/Connect", exchange -> streamFrames(exchange, List.of(
                new byte[] {EnvdConnectTransport.FLAG_COMPRESSED, 0, 0, 0, 2, '{', '}'})));

        try (EnvdConnectTransport.StreamingCall call = transport.openStream(
                "Connect", Map.of("pid", 7), Duration.ofHours(24))) {
            assertThatThrownBy(call::next)
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("unsupported compressed Connect stream message");
        }
    }

    @Test
    @DisplayName("end-stream 携带错误 → endError 出文本（照 connect.go:64-88）")
    void endStreamErrorSurfaced() throws Exception {
        handlers.put("/process.Process/Kill", exchange -> streamFrames(exchange, List.of(
                envelope(EnvdConnectTransport.FLAG_END_STREAM,
                        "{\"error\":{\"code\":\"resource_exhausted\",\"message\":\"pty gone\"}}"))));

        try (EnvdConnectTransport.StreamingCall call = transport.openStream(
                "Kill", Map.of("pid", 7), Duration.ofHours(24))) {
            assertThat(call.next()).isNull();
            assertThat(call.endError()).isEqualTo("resource_exhausted: pty gone");
        }
    }

    @Test
    @DisplayName("一元 HTTP 错误 → SandboxException「<method> failed: <message>」（照 pty.go:unary）")
    void unaryHttpErrorMapped() {
        handlers.put("/process.Process/Resize", exchange -> {
            byte[] body = "{\"code\":\"not_found\",\"message\":\"no such process\"}"
                    .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // 桩侧写失败无需影响断言
            } finally {
                exchange.close();
            }
        });

        assertThatThrownBy(() -> transport.unary("Resize", Map.of("pid", 7)))
                .isInstanceOf(SandboxException.class)
                .hasMessage("Resize failed: no such process");
    }

    // —— 桩实现 ——

    private void streamStart(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        out.write(envelope((byte) 0, "{\"event\":{\"data\":\"aGk=\"}}"));
        out.flush();

        boolean seen = false;
        try {
            seen = sendInputSeen.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!seen) {
            out.write(envelope(EnvdConnectTransport.FLAG_END_STREAM,
                    "{\"error\":{\"code\":\"unavailable\",\"message\":\"SendInput never arrived\"}}"));
            out.flush();
            exchange.close();
            return;
        }
        out.write(envelope((byte) 0, "{\"event\":{\"data\":\"d29ybGQ=\"}}"));
        out.write(envelope(EnvdConnectTransport.FLAG_END_STREAM, "{}"));
        out.flush();
        exchange.close();
    }

    private void unaryOk(HttpExchange exchange) throws IOException {
        sendInputSeen.countDown();
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void notFound(HttpExchange exchange) throws IOException {
        byte[] body = "{\"code\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(404, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void streamFrames(HttpExchange exchange, List<byte[]> frames) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        for (byte[] frame : frames) {
            out.write(frame);
            out.flush();
        }
        exchange.close();
    }

    private static byte[] envelope(byte flag, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] framed = EnvdConnectTransport.encodeEnvelope(payload);
        framed[0] = flag;
        return framed;
    }
}
