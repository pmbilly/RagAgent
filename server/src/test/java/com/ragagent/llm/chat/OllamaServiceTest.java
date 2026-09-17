package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ragagent.llm.ollama.OllamaModelInfo;
import com.ragagent.llm.ollama.OllamaService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * OllamaService 的 HTTP 契约测试（对照 Go internal/models/utils/ollama/ollama.go）。
 *
 * <p>与 Go 的差异：Go 用官方 SDK（{@code api.NewClient}）打真实 httptest 服务端；
 * Java 侧没有该 SDK，OllamaService 自己发 HTTP，所以这里就是一个真实的本地 Ollama 假服务端——
 * <b>同时验证了"线格式与 SDK 一致"</b>：心跳是 {@code HEAD /}，模型列表是
 * {@code GET /api/tags}，拉取是 NDJSON 的 {@code POST /api/pull}。</p>
 */
class OllamaServiceTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OllamaService startOllama(boolean isOptional) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            exchange.getRequestBody().readAllBytes();
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            switch (path) {
                case "/api/tags" -> write(exchange, 200, "application/json",
                        "{\"models\":[{\"name\":\"qwen3:latest\",\"size\":100,\"digest\":\"sha256:abc\"}]}");
                case "/api/pull" -> write(exchange, 200, "application/x-ndjson",
                        "{\"status\":\"pulling manifest\"}\n{\"status\":\"success\",\"total\":100,\"completed\":100}\n");
                case "/api/version" -> write(exchange, 200, "application/json", "{\"version\":\"0.9.0\"}");
                case "/api/boom" -> write(exchange, 404, "application/json", "{\"error\":\"model not found\"}");
                default -> write(exchange, 200, "application/json", "{}");
            }
        });
        server.start();
        return new OllamaService("http://127.0.0.1:" + server.getAddress().getPort(), isOptional);
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 探活 + 模型列表 + ":latest" 补全 + 版本。 */
    @Test
    void heartbeatModelListAndVersion() throws Exception {
        OllamaService service = startOllama(false);

        assertTrue(service.isModelAvailable("qwen3:latest"));
        // 不带 tag 的名字会自动补 :latest 再比对（对照 Go）
        assertTrue(service.isModelAvailable("qwen3"));
        assertFalse(service.isModelAvailable("missing"));
        assertEquals("0.9.0", service.getVersion());
        assertTrue(service.isAvailable());

        List<OllamaModelInfo> models = service.listModelsDetailed();
        assertEquals(1, models.size());
        assertEquals("qwen3:latest", models.get(0).name());
        assertEquals(100, models.get(0).size());
        assertEquals(List.of("qwen3:latest"), service.listModels());

        assertTrue(requests.contains("HEAD /"), "心跳是 HEAD /");
        assertTrue(requests.contains("GET /api/tags"));
    }

    /** EnsureModelAvailable：模型缺失才拉取，已存在则不动。 */
    @Test
    void ensureModelAvailablePullsOnlyWhenMissing() throws Exception {
        OllamaService service = startOllama(false);

        service.ensureModelAvailable("qwen3:latest");
        assertFalse(requests.contains("POST /api/pull"), "模型已存在不该触发拉取");

        service.ensureModelAvailable("llama3:8b");
        assertTrue(requests.contains("POST /api/pull"), "模型缺失必须触发拉取");
    }

    /** 接口报错的文案与 Go 的 StatusError 对齐：{@code "<code> <reason>: <error 字段>"}。 */
    @Test
    void apiErrorsCarryStatusAndMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            write(exchange, 404, "application/json", "{\"error\":\"model not found\"}");
        });
        server.start();
        OllamaService service = new OllamaService("http://127.0.0.1:" + server.getAddress().getPort(), false);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.isModelAvailable("x"));
        assertEquals("404 Not Found: model not found", error.getMessage());
    }

    /**
     * optional 模式：服务不可用时所有 ensure/版本查询都静默降级；
     * 非 optional 模式则必须把不可用暴露出来。
     */
    @Test
    void optionalModeSwallowsUnavailableService() throws Exception {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + deadPort;

        OllamaService optional = new OllamaService(url, true);
        optional.ensureModelAvailable("qwen3:latest"); // 不抛
        assertFalse(optional.isAvailable());
        assertEquals("unavailable", optional.getVersion());

        OllamaService strict = new OllamaService(url, false);
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> strict.ensureModelAvailable("qwen3:latest"));
        assertTrue(error.getMessage().startsWith("ollama service unavailable: "), error.getMessage());
    }

    /** 模型名校验（对照 Go IsValidModelName）。 */
    @Test
    void modelNameValidation() {
        assertTrue(OllamaService.isValidModelName("qwen3:latest"));
        assertFalse(OllamaService.isValidModelName(""));
        assertFalse(OllamaService.isValidModelName("has space"));
        assertFalse(OllamaService.isValidModelName(null));
    }
}
