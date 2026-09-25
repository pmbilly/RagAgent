package com.ragagent.retrieval.vlm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.ollama.OllamaService;
import com.sun.net.httpserver.HttpServer;

/**
 * Ollama 界面的 VLM（W5γ4.16 小账批补齐）——对照 Go {@code vlm/ollama.go}：
 * 单条 user 消息（prompt + 图片原始字节）、{@code stream=false}、
 * {@code options.temperature=0.1}，取 {@code message.content}；心跳走 {@code HEAD /}
 * （OllamaService.startService 的探活），对话走 {@code POST /api/chat}（NDJSON）。
 */
class VlmOllamaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> chatBodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startStub(String chatResponse) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            chatBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = chatResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static VlmClient.VlmConfig ollamaConfig() {
        return new VlmClient.VlmConfig("local", "", "llava", "", "m1", "ollama", "",
                Map.of());
    }

    @Test
    @DisplayName("Ollama VLM：请求形状（模型/单条 user 消息/图片原始字节/stream=false/temperature=0.1）+ 取 content")
    void ollamaPredictShape() throws Exception {
        String baseUrl = startStub("{\"model\":\"llava\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"图里有一只猫\"},\"done\":true}\n");
        OllamaService service = new OllamaService(baseUrl, false);

        String result = VlmClient.predictOllama(service, ollamaConfig(),
                new byte[][] {new byte[] {1, 2, 3}}, "这张图里有什么？");

        assertThat(result).isEqualTo("图里有一只猫");
        JsonNode request = MAPPER.readTree(chatBodies.get(0));
        assertThat(request.path("model").asText()).isEqualTo("llava");
        assertThat(request.has("stream")).isTrue();
        assertThat(request.path("stream").asBoolean()).isFalse(); // 照 Go：streamFlag=false
        JsonNode message = request.path("messages").get(0);
        assertThat(message.path("role").asText()).isEqualTo("user");
        assertThat(message.path("content").asText()).isEqualTo("这张图里有什么？");
        // Go 的 []ImageData = [][]byte → JSON base64（Jackson 同款）
        assertThat(message.path("images").get(0).asText())
                .isEqualTo(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        assertThat(request.path("options").path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(request.path("messages")).hasSize(1);
    }

    @Test
    @DisplayName("空图被丢弃；纯文本 prompt 也能走（images 为空数组）")
    void ollamaSkipsEmptyImages() throws Exception {
        String baseUrl = startStub("{\"message\":{\"content\":\"ok\"},\"done\":true}\n");
        OllamaService service = new OllamaService(baseUrl, false);

        assertThat(VlmClient.predictOllama(service, ollamaConfig(),
                new byte[][] {new byte[0], new byte[] {9}}, "p")).isEqualTo("ok");
        JsonNode request = MAPPER.readTree(chatBodies.get(0));
        assertThat(request.path("messages").get(0).path("images")).hasSize(1);
    }

    @Test
    @DisplayName("服务不可用：错误族照 Go（Ollama VLM request: …）")
    void ollamaUnavailable() {
        OllamaService down = new OllamaService("http://127.0.0.1:1", false);
        assertThatThrownBy(() -> VlmClient.predictOllama(down, ollamaConfig(),
                new byte[][] {new byte[] {1}}, "x"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessageContaining("Ollama VLM request:");
    }

    @Test
    @DisplayName("predict 分派：ollama 配置不走 OpenAI 传输层（transport 不会被调用）")
    void predictDispatchesOllama() {
        VlmClient.Transport failingTransport = (url, apiKey, body) -> {
            throw new AssertionError("ollama 路径不该走 OpenAI 传输层，url=" + url);
        };
        // 未配 OLLAMA_BASE_URL → 默认 localhost:11434（本机无服务）→ 报 Ollama 侧错误而非传输层
        assertThatThrownBy(() -> VlmClient.predict(ollamaConfig(), failingTransport,
                new byte[][] {new byte[] {1}}, "x"))
                .isInstanceOf(VlmClient.VlmException.class)
                .hasMessageContaining("Ollama VLM request:");
    }
}
