package com.ragagent.sandbox.runtime.terminal;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.session.service.TerminalBridge;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * envd 终端执行体的**本地桩**验证（W5δ 执行体批，离线可跑）：生命周期、PTY 事件三态、
 * 输入/改窗口的线上形状、重附回落、以及"流结束但无 end 事件"的 Go 同款判定。
 */
class EnvdTerminalSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private HttpClient client;
    private EnvdTerminalManager manager;

    private final Map<String, List<byte[]>> bodies = new ConcurrentHashMap<>();
    private final Map<String, List<Headers>> headers = new ConcurrentHashMap<>();
    private final Map<String, ExchangeHandler> handlers = new ConcurrentHashMap<>();
    private final CountDownLatch inputSeen = new CountDownLatch(1);
    private final AtomicInteger ttlRefreshes = new AtomicInteger();

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** 终端引用的测试替身（provider/id/traffic token）—— 对照 Go 的句柄面。 */
    private static TerminalTypes.RemoteTerminalRef ref(String id) {
        return new TerminalTypes.RemoteTerminalRef() {
            @Override
            public String provider() {
                return "cube";
            }

            @Override
            public String sandboxId() {
                return id;
            }

            @Override
            public String trafficAccessToken() {
                return "envd-token";
            }
        };
    }

    /** 最近一次 resolver 收到的引用（验证接缝传的是引用本体，而非裸 id）。 */
    private final java.util.concurrent.atomic.AtomicReference<TerminalTypes.RemoteTerminalRef> lastRef =
            new java.util.concurrent.atomic.AtomicReference<>();

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
                case "/process.Process/Start" -> happyStart(exchange);
                case "/process.Process/SendInput", "/process.Process/Update" -> unaryOk(exchange);
                default -> notFound(exchange);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        client = HttpClient.newBuilder().build();
        manager = new EnvdTerminalManager("cube",
                r -> { lastRef.set(r); return new EnvdTerminalManager.Endpoint(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                        Map.of("X-Access-Token", "envd-token"),
                        Duration.ofHours(24),
                        Duration.ofMinutes(30),
                        timeout -> ttlRefreshes.incrementAndGet()); },
                client);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("生命周期：Start→PID→data→end→exited；输入走 SendInput、改窗口走 Update（方法名照 SDK）")
    void sessionLifecycle() throws Exception {
        TerminalTypes.RemoteTerminalOptions opts = new TerminalTypes.RemoteTerminalOptions();
        TerminalTypes.RemoteTerminalSession session = manager.openTerminal(ref("sbx-1"), opts);

        assertThat(session.pid()).isEqualTo(4242);

        // 桩：先给 hello，再等 SendInput，才给 world + end（故读事件要分两段）
        List<TerminalTypes.RemoteTerminalEvent> hello = readEvents(session, 1);
        assertThat(new String(hello.get(0).data, StandardCharsets.UTF_8)).isEqualTo("hello");

        session.write("ls\n".getBytes(StandardCharsets.UTF_8));
        assertThat(inputSeen.await(5, TimeUnit.SECONDS)).as("SendInput 必须到达").isTrue();

        List<TerminalTypes.RemoteTerminalEvent> rest = readEvents(session, 2);
        assertThat(new String(rest.get(0).data, StandardCharsets.UTF_8)).isEqualTo("world");
        assertThat(rest.get(1).exited).isTrue();
        assertThat(rest.get(1).exitCode).as("退出码取 end.exitCode").isEqualTo(7);
        JsonNode inputBody = MAPPER.readTree(bodies.get("/process.Process/SendInput").get(0));
        assertThat(inputBody.path("process").path("pid").asInt()).isEqualTo(4242);
        assertThat(inputBody.path("input").path("pty").asText()).isEqualTo("bHMK");

        // 改窗口：方法名是 Update（不是 Resize），形状 {"process":{"pid"},"pty":{"size":{rows,cols}}}
        session.resize(100, 40);
        JsonNode updateBody = MAPPER.readTree(bodies.get("/process.Process/Update").get(0));
        assertThat(updateBody.path("process").path("pid").asInt()).isEqualTo(4242);
        assertThat(updateBody.path("pty").path("size").path("rows").asInt()).isEqualTo(40);
        assertThat(updateBody.path("pty").path("size").path("cols").asInt()).isEqualTo(100);

        // TTL：开会话立即刷一次（照 Go 的"Cube Connect 不顺手续 TTL"）
        assertThat(ttlRefreshes.get()).isGreaterThanOrEqualTo(1);

        // 建流请求形状：cmd/args/envs（SDK 默认 TERM/LANG/LC_ALL）/cwd + pty.size
        JsonNode startPayload = framePayload(bodies.get("/process.Process/Start").get(0));
        assertThat(startPayload.path("process").path("cmd").asText()).isEqualTo("/bin/bash");
        assertThat(startPayload.path("process").path("args").get(0).asText()).isEqualTo("-i");
        assertThat(startPayload.path("process").path("envs").path("TERM").asText())
                .isEqualTo("xterm-256color");
        assertThat(startPayload.path("process").path("envs").path("LANG").asText())
                .isEqualTo("C.UTF-8");
        assertThat(startPayload.path("process").path("cwd").asText()).isEqualTo("/workspace");
        assertThat(startPayload.path("pty").path("size").path("rows").asInt()).isEqualTo(24);
        assertThat(startPayload.path("pty").path("size").path("cols").asInt()).isEqualTo(80);

        // 收尾：幂等 close；关闭后 write 抛"已关闭"（照 Go 的 errPtyInputClosed）
        session.close();
        session.close();
        assertThat(session).isInstanceOf(TerminalTypes.TerminalSessionState.class);
        assertThat(((TerminalTypes.TerminalSessionState) session).finished()).isTrue();
        assertThatThrownByClosed(session);
    }

    @Test
    @DisplayName("流结束但没有 end 事件 → 错误事件（照 Go 的 Wait 判定）")
    void streamWithoutEndEventIsError() throws Exception {
        handlers.put("/process.Process/Start", exchange -> streamFrames(exchange, List.of(
                frame("{\"event\":{\"start\":{\"pid\":9}}}"),
                frame("{\"event\":{\"data\":{\"pty\":\"YQ==\"}}}"),
                endStream("{}"))));

        TerminalTypes.RemoteTerminalSession session =
                manager.openTerminal(ref("sbx-2"), new TerminalTypes.RemoteTerminalOptions());

        List<TerminalTypes.RemoteTerminalEvent> events = readEvents(session, 2);
        assertThat(events.get(0).data).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
        assertThat(events.get(1).err).isNotNull();
        assertThat(events.get(1).err.getMessage())
                .contains("PTY stream ended without an end event");
    }

    @Test
    @DisplayName("end-stream 携带错误 → 错误事件带原文（照 connect.go 的 end-stream 解析）")
    void endStreamErrorBecomesErrorEvent() throws Exception {
        handlers.put("/process.Process/Start", exchange -> streamFrames(exchange, List.of(
                frame("{\"event\":{\"start\":{\"pid\":10}}}"),
                endStream("{\"error\":{\"code\":\"unavailable\",\"message\":\"pty gone\"}}"))));

        TerminalTypes.RemoteTerminalSession session =
                manager.openTerminal(ref("sbx-3"), new TerminalTypes.RemoteTerminalOptions());

        List<TerminalTypes.RemoteTerminalEvent> events = readEvents(session, 1);
        assertThat(events.get(0).err).isNotNull();
        assertThat(events.get(0).err.getMessage()).contains("pty gone");
    }

    @Test
    @DisplayName("end 事件的 status 文本兜底：signal 15 → 退出码 143；缺失 exitCode 也能出码")
    void exitCodeFromStatusFallback() throws Exception {
        handlers.put("/process.Process/Start", exchange -> streamFrames(exchange, List.of(
                frame("{\"event\":{\"start\":{\"pid\":11}}}"),
                frame("{\"event\":{\"end\":{\"status\":\"terminated by signal 15\"}}}"),
                endStream("{}"))));

        TerminalTypes.RemoteTerminalSession session =
                manager.openTerminal(ref("sbx-4"), new TerminalTypes.RemoteTerminalOptions());

        List<TerminalTypes.RemoteTerminalEvent> events = readEvents(session, 1);
        assertThat(events.get(0).exited).isTrue();
        assertThat(events.get(0).exitCode).isEqualTo(128 + 15);
    }

    @Test
    @DisplayName("attachPid>0：先试 Connect，失败回落 Create（照 openCubePty）")
    void attachFallsBackToCreate() throws Exception {
        handlers.put("/process.Process/Connect", exchange -> {
            byte[] body = "{\"code\":\"not_found\",\"message\":\"no such pid\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        TerminalTypes.RemoteTerminalOptions opts = new TerminalTypes.RemoteTerminalOptions();
        opts.attachPid = 777;
        TerminalTypes.RemoteTerminalSession session = manager.openTerminal(ref("sbx-5"), opts);

        assertThat(session.pid()).as("回落 Create 后拿到新 PID").isEqualTo(4242);
        assertThat(bodies).containsKey("/process.Process/Connect");
        JsonNode connectPayload = framePayload(bodies.get("/process.Process/Connect").get(0));
        assertThat(connectPayload.path("process").path("pid").asInt())
                .as("Connect 是流式调用，体为帧化 ptySelectorRequest")
                .isEqualTo(777);
        assertThat(bodies).containsKey("/process.Process/Start");
    }

    @Test
    @DisplayName("桥适配：data/exited 逐事件，流终结后 next() 返回 null（= Go 的 channel 关闭）")
    void bridgeAdapterTranslatesEvents() throws Exception {
        TerminalTypes.RemoteTerminalSession session =
                manager.openTerminal(ref("sbx-6"), new TerminalTypes.RemoteTerminalOptions());
        TerminalBridge.PtySession bridge = TerminalBridge.adapt(session);

        TerminalBridge.PtySession.OutputEvent first = bridge.next();
        assertThat(new String(first.data(), StandardCharsets.UTF_8)).isEqualTo("hello");

        session.write("x".getBytes(StandardCharsets.UTF_8));
        TerminalBridge.PtySession.OutputEvent second = bridge.next();
        assertThat(new String(second.data(), StandardCharsets.UTF_8)).isEqualTo("world");

        TerminalBridge.PtySession.OutputEvent third = bridge.next();
        assertThat(third.exited()).isTrue();
        assertThat(third.exitCode()).isEqualTo(7);

        assertThat(bridge.next()).as("终结后取空 → null").isNull();
        bridge.close();
        session.close();
    }

    // —— 桩实现 ——

    private void happyStart(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        out.write(frame("{\"event\":{\"start\":{\"pid\":4242}}}"));
        out.flush();
        out.write(frame("{\"event\":{\"data\":{\"pty\":\"aGVsbG8=\"}}}"));
        out.flush();

        boolean seen = false;
        try {
            seen = inputSeen.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!seen) {
            out.write(endStream("{\"error\":{\"code\":\"unavailable\",\"message\":"
                    + "\"SendInput never arrived\"}}"));
            out.flush();
            exchange.close();
            return;
        }
        out.write(frame("{\"event\":{\"data\":{\"pty\":\"d29ybGQ=\"}}}"));
        out.write(frame("{\"event\":{\"end\":{\"exitCode\":7,\"exited\":true}}}"));
        out.write(endStream("{}"));
        out.flush();
        exchange.close();
    }

    private void unaryOk(HttpExchange exchange) throws IOException {
        inputSeen.countDown();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
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
        for (byte[] f : frames) {
            out.write(f);
            out.flush();
        }
        exchange.close();
    }

    private List<TerminalTypes.RemoteTerminalEvent> readEvents(
            TerminalTypes.RemoteTerminalSession session, int count) throws InterruptedException {
        List<TerminalTypes.RemoteTerminalEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            TerminalTypes.RemoteTerminalEvent event =
                    session.output().poll(5, TimeUnit.SECONDS);
            assertThat(event).as("第 %d 个事件未到", i + 1).isNotNull();
            events.add(event);
        }
        return events;
    }

    /** 一元体是裸 JSON；流式体是帧化 JSON —— 取帧内 payload 解析。 */
    private static JsonNode framePayload(byte[] framed) throws IOException {
        long size = ((long) (framed[1] & 0xff) << 24) | ((framed[2] & 0xff) << 16)
                | ((framed[3] & 0xff) << 8) | (framed[4] & 0xff);
        return MAPPER.readTree(framed, 5, (int) size);
    }

    private static byte[] frame(String json) {
        return EnvdConnectTransport.encodeEnvelope(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] endStream(String json) {
        byte[] framed = frame(json);
        framed[0] = EnvdConnectTransport.FLAG_END_STREAM;
        return framed;
    }

    private static void assertThatThrownByClosed(TerminalTypes.RemoteTerminalSession session) {
        try {
            session.write("z".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("关闭后 write 必须抛");
        } catch (PtyInputCoalescer.PtyInputClosedException expected) {
            assertThat(expected.getMessage()).isEqualTo(PtyInputCoalescer.ERR_PTY_INPUT_CLOSED);
        } catch (Exception unexpected) {
            throw new AssertionError("期望 PtyInputClosedException，实得 " + unexpected, unexpected);
        }
    }

    @Test
    @DisplayName("resolver 收到引用本体（provider/id/token）——与 Go 传句柄同形，无需反查绑定存储")
    void resolverReceivesRef() throws Exception {
        manager.openTerminal(ref("sbx-ref"), new TerminalTypes.RemoteTerminalOptions());

        assertThat(lastRef.get()).as("resolver 必须被调用且拿到引用").isNotNull();
        assertThat(lastRef.get().sandboxId()).isEqualTo("sbx-ref");
        assertThat(lastRef.get().provider()).isEqualTo("cube");
        assertThat(lastRef.get().trafficAccessToken()).as("token 随引用走（Go RemoteInboundTokenCarrier 等价面）")
                .isEqualTo("envd-token");
    }
}
