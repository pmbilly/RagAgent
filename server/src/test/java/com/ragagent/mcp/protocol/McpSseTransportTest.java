package com.ragagent.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTransportType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 传统 HTTP+SSE 传输（MCP 2024-11-05）的端到端用例：
 * GET 建流 → {@code endpoint} 帧 → POST 发请求 → 响应从 SSE 流回。
 *
 * <p>对照 Go：mcp-go {@code transport.SSE} 的行为（sse.go 的
 * {@code start} / {@code handleSSEEvent} / {@code SendRequest}）。</p>
 */
class McpSseTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<OutputStream> streams = new CopyOnWriteArrayList<>();
    private final CountDownLatch streamOpen = new CountDownLatch(1);
    private final AtomicInteger posts = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            streams.add(out);
            // MCP 2024-11-05：首帧 endpoint 事件给出 POST 地址（相对路径，基准是建流 URL）
            out.write("event: endpoint\ndata: /messages?sessionId=abc\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            streamOpen.countDown();
            try {
                Thread.sleep(30_000); // 保持长连（测试结束时随进程退出）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/sse-bad-origin", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            // 端点指向另一个 host —— 客户端必须拒绝（对照 mcp-go "Endpoint origin does not match"）
            out.write("event: endpoint\ndata: http://evil.example.com/messages\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/messages", exchange -> {
            posts.incrementAndGet();
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", request.get("id"));
            if (McpProtocol.METHOD_INITIALIZE.equals(request.path("method").asText(""))) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
                ObjectNode info = MAPPER.createObjectNode();
                info.put("name", "sse-stub");
                info.put("version", "1.0.0");
                result.set("serverInfo", info);
                result.put("instructions", "sse instructions");
                envelope.set("result", result);
            } else if (McpProtocol.METHOD_TOOLS_LIST.equals(request.path("method").asText(""))) {
                try {
                    envelope.set("result", MAPPER.readTree("{\"tools\":[{\"name\":\"t1\"}]}"));
                } catch (Exception e) {
                    throw new IOException(e);
                }
            } else {
                envelope.set("result", MAPPER.createObjectNode());
            }
            // 响应经 SSE 流回（legacy SSE 的响应通道），POST 本身只回 202
            for (OutputStream stream : streams) {
                try {
                    stream.write(("event: message\ndata: " + MAPPER.writeValueAsString(envelope) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                } catch (IOException ignored) {
                    // 流已关闭
                }
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("endpoint 帧 + POST 请求 + 流内响应：完整握手与 tools/list")
    void fullFlow() throws Exception {
        McpService service = new McpService();
        service.setId("sse-svc");
        service.setName("sse");
        service.setEnabled(true);
        service.setUrl(url() + "/sse");
        service.setTransportType(McpTransportType.SSE.value());

        McpClient client = McpClientFactory.createClient(new McpClientConfig(service));
        client.connect(McpContext.deadline(java.time.Instant.now().plusSeconds(10)));
        assertTrue(streamOpen.await(5, TimeUnit.SECONDS), "SSE 流必须建立");

        InitializeResult result = client.initialize(McpContext.deadline(java.time.Instant.now().plusSeconds(10)));
        assertEquals("sse-stub", result.serverInfo().name());
        assertEquals("sse instructions", client.serverInstructions());

        assertEquals(1, client.listTools(McpContext.deadline(java.time.Instant.now().plusSeconds(10))).size());
        assertTrue(posts.get() >= 2, "initialize 与 tools/list 都应 POST 到 endpoint");

        client.disconnect();
    }

    @Test
    @DisplayName("endpoint 帧的 host 与建流 URL 不一致时拒绝（防被引到第三方）")
    void endpointOriginMustMatch() {
        SseTransport transport = new SseTransport(
                java.net.URI.create(url() + "/sse-bad-origin"), java.util.Map.of(),
                java.time.Duration.ofSeconds(5));
        try {
            McpException err = org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                    () -> transport.start(McpContext.deadline(java.time.Instant.now().plusSeconds(5))));
            assertTrue(err.getMessage().contains("Endpoint origin does not match"), err.getMessage());
        } finally {
            transport.close();
        }
    }
}
