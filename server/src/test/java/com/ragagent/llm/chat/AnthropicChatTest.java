package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.PromptCacheStatus;
import com.ragagent.llm.domain.StreamResponse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Anthropic chat 语义测试（表内 7 条 + 端点拼接 2 条）。
 *
 * <p>fake 服务端用 JDK 自带的
 * {@code com.sun.net.httpserver.HttpServer}，并用
 * {@link SsrfGuard#reloadWhitelist} 把 127.0.0.1 放行（等价于往
 * {@code SSRF_WHITELIST} 注入 127.0.0.1）。</p>
 *
 * <p>工厂分派（provider 路由）不在本测试范围，这里钉等价可测的一条：
 * <b>没给 baseURL 时回落到厂商默认基址，且默认基址拼出 {@code /v1/messages}</b>。</p>
 */
class AnthropicChatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模拟上游的固定响应体。 */
    private static final String PLAIN_RESPONSE = """
            {
              "id":"msg_123","type":"message","role":"assistant",
              "content":[{"type":"text","text":"hello"}],
              "stop_reason":"end_turn",
              "usage":{"input_tokens":3,"output_tokens":2}
            }""";

    private final SsrfGuard guard = new SsrfGuard();

    @BeforeEach
    void setUp() {
        guard.reloadWhitelist("127.0.0.1");
        LlmTransport.setSsrfGuard(guard);
    }

    @AfterEach
    void tearDown() {
        LlmTransport.setSsrfGuard(new SsrfGuard());
    }

    // ------------------------------------------------------------------
    // 本地假服务端
    // ------------------------------------------------------------------

    /** 一次请求的抓包（路径 + 请求体 JSON）。 */
    private record Captured(String path, JsonNode body) {
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private HttpServer startServer(AtomicReference<Captured> captured, Responder responder) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            captured.set(new Captured(exchange.getRequestURI().getPath(),
                    requestBody.length == 0 ? null : MAPPER.readTree(requestBody)));
            responder.respond(exchange);
        });
        server.start();
        return server;
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static AnthropicChat chat(String baseUrl) {
        ChatConfig config = new ChatConfig();
        config.setModelName("claude-sonnet-4-5");
        config.setApiKey("test-key");
        config.setBaseUrl(baseUrl);
        config.setProvider("anthropic");
        config.setCustomHeaders(java.util.Map.of("anthropic-beta", "test-beta"));
        return new AnthropicChat(config);
    }

    private static List<StreamResponse> drain(BlockingQueue<StreamResponse> queue) throws InterruptedException {
        List<StreamResponse> chunks = new ArrayList<>();
        while (true) {
            StreamResponse chunk = queue.poll(10, TimeUnit.SECONDS);
            if (chunk == null) {
                break;
            }
            chunks.add(chunk);
            if (chunk.isDone()) {
                break;
            }
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // 对应用例
    // ------------------------------------------------------------------

    /** 请求形态（顶层 system + 尾断点）与响应解析。 */
    @Test
    void anthropicChatSendsTopLevelSystemAndParsesResponse() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured,
                exchange -> write(exchange, 200, "application/json", PLAIN_RESPONSE));
        try {
            AnthropicChat client = chat("http://127.0.0.1:" + server.getAddress().getPort());
            ChatResponse resp = client.chat(
                    List.of(ChatMessage.system("You are helpful."), ChatMessage.user("Hi")),
                    options(7, 0.2));

            Captured request = captured.get();
            assertEquals("/v1/messages", request.path());
            assertEquals("claude-sonnet-4-5", request.body().get("model").asText());
            assertEquals(7, request.body().get("max_tokens").asInt());

            JsonNode system = request.body().get("system");
            assertTrue(system.isArray(), "system 应升格为内容块（有 cache_control 断点）");
            assertEquals("You are helpful.", system.get(0).get("text").asText());
            assertEquals("ephemeral", system.get(0).get("cache_control").get("type").asText());

            assertEquals(1, request.body().get("messages").size());
            JsonNode user = request.body().get("messages").get(0);
            assertEquals("user", user.get("role").asText());
            assertTrue(user.get("content").isArray(), "最后一条消息也要打断点");
            assertEquals("Hi", user.get("content").get(0).get("text").asText());
            assertEquals("ephemeral", user.get("content").get(0).get("cache_control").get("type").asText());

            assertEquals("hello", resp.getContent());
            assertEquals("end_turn", resp.getFinishReason());
            assertEquals(3, resp.getUsage().getPromptTokens());
            assertEquals(2, resp.getUsage().getCompletionTokens());
            assertEquals(5, resp.getUsage().getTotalTokens());
        } finally {
            server.stop(0);
        }
    }

    /** 头断言（x-api-key / anthropic-version / 自定义头）。 */
    @Test
    void anthropicChatSendsAuthHeadersAndCustomHeaders() throws Exception {
        AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            headers.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            write(exchange, 200, "application/json", PLAIN_RESPONSE);
        });
        server.start();
        try {
            chat("http://127.0.0.1:" + server.getAddress().getPort())
                    .chat(List.of(ChatMessage.user("Hi")), null);

            // com.sun.net.httpserver 的头名字大小写不敏感，键名即客户端发来的原样
            assertEquals("test-key", headers.get().getFirst("x-api-key"));
            assertEquals(AnthropicChat.ANTHROPIC_VERSION, headers.get().getFirst("anthropic-version"));
            assertEquals("test-beta", headers.get().getFirst("anthropic-beta"));
        } finally {
            server.stop(0);
        }
    }

    /** input + cache_read + cache_creation = prompt。 */
    @Test
    void anthropicChatMergesCacheUsageIntoPromptTokens() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured, exchange -> write(exchange, 200, "application/json", """
                {
                  "id":"msg_cached","type":"message","role":"assistant",
                  "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
                  "usage":{"input_tokens":24,"output_tokens":2,
                           "cache_creation_input_tokens":100,"cache_read_input_tokens":900}
                }"""));
        try {
            ChatResponse resp = chat("http://127.0.0.1:" + server.getAddress().getPort())
                    .chat(List.of(ChatMessage.user("Hi")), null);

            assertEquals(1024, resp.getUsage().getPromptTokens());
            assertEquals(900, resp.getUsage().getCacheReadTokens());
            assertEquals(100, resp.getUsage().getCacheWriteTokens());
            assertEquals(124, resp.getUsage().getCacheMissTokens());
            assertTrue(resp.getUsage().isCacheReported());
            assertEquals(PromptCacheStatus.HIT, resp.getUsage().getCacheStatus());
        } finally {
            server.stop(0);
        }
    }

    /** 默认 max_tokens 硬编码 1024（opts 为 null 时）。 */
    @Test
    void anthropicChatDefaultsMaxTokensTo1024() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured,
                exchange -> write(exchange, 200, "application/json", PLAIN_RESPONSE));
        try {
            chat("http://127.0.0.1:" + server.getAddress().getPort())
                    .chat(List.of(ChatMessage.user("Hi")), null);
            assertEquals(AnthropicChat.DEFAULT_MAX_TOKENS, captured.get().body().get("max_tokens").asInt());
            assertEquals(1024, AnthropicChat.DEFAULT_MAX_TOKENS);
        } finally {
            server.stop(0);
        }
    }

    /** 非 /v1 结尾的代理路径 → 补 /v1/messages。 */
    @Test
    void anthropicChatAppendsV1MessagesToProxyPath() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured,
                exchange -> write(exchange, 200, "application/json", PLAIN_RESPONSE));
        try {
            ChatResponse resp = chat("http://127.0.0.1:" + server.getAddress().getPort() + "/api/proxy/forward")
                    .chat(List.of(ChatMessage.user("Hi")), null);
            assertEquals("/api/proxy/forward/v1/messages", captured.get().path());
            assertEquals("hello", resp.getContent());
        } finally {
            server.stop(0);
        }
    }

    /** 已是 /messages 结尾 → 原样使用。 */
    @Test
    void anthropicChatKeepsMessagesEndpoint() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured,
                exchange -> write(exchange, 200, "application/json", PLAIN_RESPONSE));
        try {
            ChatResponse resp = chat("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/messages")
                    .chat(List.of(ChatMessage.user("Hi")), null);
            assertEquals("/api/v1/messages", captured.get().path());
            assertEquals("hello", resp.getContent());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 端点拼接的三种形态：已是 /messages → 原样；/v1 或 /v1beta 结尾 → 补 /messages；
     * 其余 → 补 /v1/messages。（构造器会做 SSRF 校验，故这里把测试用主机名加白，
     * 等价于往 {@code SSRF_WHITELIST} 写入这些主机。）
     */
    @Test
    void endpointJoiningThreeForms() {
        guard.reloadWhitelist("api.anthropic.com,proxy.example.com");
        LlmTransport.setSsrfGuard(guard);

        assertEquals("https://api.anthropic.com/v1/messages", chat("").endpoint());
        assertEquals("https://api.anthropic.com/v1beta/messages", chat("https://api.anthropic.com/v1beta").endpoint());
        assertEquals("https://proxy.example.com/anthropic/v1/messages",
                chat("https://proxy.example.com/anthropic/").endpoint());
        assertEquals("https://proxy.example.com/messages",
                chat("https://proxy.example.com/messages").endpoint());
    }

    /** 非流式入口拿到 text/event-stream 的整段解析。 */
    @Test
    void anthropicChatParsesSseBodyWhenContentTypeIsEventStream() throws Exception {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":14,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":100}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"pong"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured,
                exchange -> write(exchange, 200, "text/event-stream", sse));
        try {
            ChatResponse resp = chat("http://127.0.0.1:" + server.getAddress().getPort())
                    .chat(List.of(ChatMessage.user("ping")), null);

            assertEquals("pong", resp.getContent());
            assertEquals("end_turn", resp.getFinishReason());
            assertEquals(114, resp.getUsage().getPromptTokens());
            assertEquals(5, resp.getUsage().getCompletionTokens());
            assertEquals(119, resp.getUsage().getTotalTokens());
            assertEquals(100, resp.getUsage().getCacheReadTokens());
            assertEquals(14, resp.getUsage().getCacheMissTokens());
        } finally {
            server.stop(0);
        }
    }

    /** 流式两块（答案 + done），请求带 stream=true。 */
    @Test
    void anthropicChatStreamEmitsAnswerThenDone() throws Exception {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":14,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":100}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"pong"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        AtomicReference<Captured> captured = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            captured.set(new Captured(exchange.getRequestURI().getPath(), MAPPER.readTree(requestBody)));
            write(exchange, 200, "text/event-stream", sse);
        });
        server.start();
        try {
            BlockingQueue<StreamResponse> queue = chat("http://127.0.0.1:" + server.getAddress().getPort())
                    .chatStream(List.of(ChatMessage.user("ping")), null);
            List<StreamResponse> chunks = drain(queue);

            assertEquals(2, chunks.size());
            assertEquals("text/event-stream", accept.get());
            assertTrue(captured.get().body().get("stream").asBoolean());
            assertEquals("pong", chunks.get(0).getContent());
            assertFalse(chunks.get(0).isDone());
            assertTrue(chunks.get(1).isDone());
            assertEquals("end_turn", chunks.get(1).getFinishReason());
            assertNotNull(chunks.get(1).getUsage());
            assertEquals(114, chunks.get(1).getUsage().getPromptTokens());
            assertEquals(5, chunks.get(1).getUsage().getCompletionTokens());
            assertEquals(119, chunks.get(1).getUsage().getTotalTokens());
            assertEquals(100, chunks.get(1).getUsage().getCacheReadTokens());
            assertEquals(14, chunks.get(1).getUsage().getCacheMissTokens());
        } finally {
            server.stop(0);
        }
    }

    /** 关缓存时保持纯字符串。 */
    @Test
    void cacheRetentionNoneKeepsPlainStrings() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        HttpServer server = startServer(captured,
                exchange -> write(exchange, 200, "application/json", PLAIN_RESPONSE));
        try {
            ChatOptions opts = new ChatOptions();
            opts.setCacheRetention(CacheRetention.NONE);
            chat("http://127.0.0.1:" + server.getAddress().getPort())
                    .chat(List.of(ChatMessage.system("You are helpful."), ChatMessage.user("Hi")), opts);

            assertEquals("You are helpful.", captured.get().body().get("system").asText());
            assertEquals("Hi", captured.get().body().get("messages").get(0).get("content").asText());
        } finally {
            server.stop(0);
        }
    }

    private static ChatOptions options(int maxTokens, double temperature) {
        ChatOptions opts = new ChatOptions();
        opts.setMaxTokens(maxTokens);
        opts.setTemperature(temperature);
        return opts;
    }
}
