package com.ragagent.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 协议行为的端到端用例（内嵌 MCP 服务端桩）：initialize 握手 + session 回传 +
 * notifications/initialized + 各方法的线上形状 + 传输层分支与拒绝分支。
 *
 * <p>对照 Go：mcp-go 的 {@code client.Client} 已实现的这些行为，在本项目里由
 * {@link StreamableHttpTransport} + {@link DefaultMcpClient} 自研承担。</p>
 */
class McpClientProtocolTest {

    @BeforeEach
    void allowLoopback() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);
    }

    @AfterEach
    void resetGuard() {
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
    }

    private static McpService service(String url) {
        McpService service = new McpService();
        service.setId("svc-1");
        service.setName("stub");
        service.setEnabled(true);
        service.setUrl(url);
        service.setTransportType(McpTransportType.HTTP_STREAMABLE.value());
        return service;
    }

    private static McpContext ctx() {
        return McpContext.deadline(Instant.now().plusSeconds(10));
    }

    @Nested
    @DisplayName("HTTP Streamable：完整握手与各方法")
    class Streamable {

        @Test
        @DisplayName("initialize 握手：session 回传、初始化通知、instructions")
        void handshake() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                InitializeResult result = client.initialize(ctx());

                assertEquals(McpProtocol.PROTOCOL_VERSION, result.protocolVersion());
                // 线上报文逐字节：协议版本照 mcp-go v0.52.0（2025-11-25），键序
                // protocolVersion→clientInfo→capabilities（照 SDK 的 params 字段序）
                String initBody = server.requestBodies.stream()
                        .filter(b -> b.contains("\"method\":\"initialize\""))
                        .findFirst().orElseThrow();
                assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-11-25\",\"clientInfo\":"
                        + "{\"name\":\"WeKnora\",\"version\":\"1.0.0\"},\"capabilities\":{}}}", initBody);
                assertEquals("stub-server", result.serverInfo().name());
                assertEquals("Stub", result.serverInfo().title());
                assertEquals("full server instructions", result.instructions());
                assertEquals("full server instructions", client.serverInstructions());
                assertTrue(server.initializedNotificationSeen,
                        "握手后必须补发 notifications/initialized");
                assertTrue(server.methods.contains(McpProtocol.METHOD_INITIALIZED_NOTIFICATION));

                // 后续请求必须回传服务端下发的 Mcp-Session-Id
                client.listTools(ctx());
                assertTrue(server.sessionIdsSeen.contains("stub-session-1"),
                        "后续请求必须带上会话 ID，实际：" + server.sessionIdsSeen);

                client.disconnect();
                assertTrue(!client.isConnected());
            }
        }

        @Test
        @DisplayName("initialize 应答版本不在白名单 → 报错且不置 initialized（照 mcp-go 校验）")
        void unsupportedProtocolVersionRejected() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.responders.put(McpProtocol.METHOD_INITIALIZE, (request, path) -> {
                    ObjectNode result = McpServerStub.MAPPER.createObjectNode();
                    result.put("protocolVersion", "1999-01-01");
                    result.set("capabilities", McpServerStub.MAPPER.createObjectNode());
                    ObjectNode info = McpServerStub.MAPPER.createObjectNode();
                    info.put("name", "stub-server");
                    info.put("version", "9.9.9");
                    result.set("serverInfo", info);
                    return result;
                });
                McpClient client = McpClientFactory.createClient(
                        new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());

                McpException e = assertThrows(McpException.class, () -> client.initialize(ctx()));
                assertEquals("failed to initialize: unsupported protocol version: \"1999-01-01\"",
                        e.getMessage());
                assertNull(e.code(), "SDK 自带错误在 Go 侧不对应任何哨兵");
                assertTrue(!server.initializedNotificationSeen, "版本校验失败时不得补发 initialized 通知");

                // 握手失败 → initialized 未置位 → 后续调用按"未初始化"拒绝（照 Go 的 initialized 标志）
                McpException notConnected = assertThrows(McpException.class, () -> client.listTools(ctx()));
                assertTrue(notConnected.hasCode(McpErrorCode.NOT_CONNECTED));
                client.disconnect();
            }
        }

        @Test
        @DisplayName("tools/list 分页与 schema 原样保留")
        void listTools() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.responders.put(McpProtocol.METHOD_TOOLS_LIST, (request, path) -> {
                    try {
                        return McpServerStub.MAPPER.readTree(
                                "{\"tools\":[{\"name\":\"a\",\"description\":\"d\","
                                        + "\"inputSchema\":{\"type\":\"object\",\"oneOf\":[{\"type\":\"string\"}]}}]}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                client.initialize(ctx());

                List<McpTool> tools = client.listTools(ctx());
                assertEquals(1, tools.size());
                assertEquals("a", tools.get(0).getName());
                assertEquals("d", tools.get(0).getDescription());
                assertTrue(((com.fasterxml.jackson.databind.JsonNode) tools.get(0).getInputSchema()).has("oneOf"));
            }
        }

        @Test
        @DisplayName("tools/call：只保留 text 与 image 内容项")
        void callTool() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.responders.put(McpProtocol.METHOD_TOOLS_CALL, (request, path) -> {
                    assertEquals("echo", request.path("params").path("name").asText(""));
                    assertEquals("hi", request.path("params").path("arguments").path("msg").asText(""));
                    try {
                        return McpServerStub.MAPPER.readTree("""
                                {"content":[
                                  {"type":"text","text":"hello"},
                                  {"type":"image","data":"QUJD","mimeType":"image/png"},
                                  {"type":"resource","uri":"x://y"}
                                ],"isError":false}
                                """);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                client.initialize(ctx());

                CallToolResult result = client.callTool("echo", Map.of("msg", "hi"), ctx());
                assertTrue(!result.isError());
                assertEquals(2, result.content().size(), "resource 类型内容项应被丢弃（对照 Go 的转换）");
                assertEquals("text", result.content().get(0).type());
                assertEquals("hello", result.content().get(0).text());
                assertEquals("image", result.content().get(1).type());
                assertEquals("QUJD", result.content().get(1).data());
                assertEquals("image/png", result.content().get(1).mimeType());
            }
        }

        @Test
        @DisplayName("resources/list 与 resources/read（text / blob 两种）")
        void resources() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.responders.put(McpProtocol.METHOD_RESOURCES_LIST, (request, path) -> {
                    try {
                        return McpServerStub.MAPPER.readTree(
                                "{\"resources\":[{\"uri\":\"r://a\",\"name\":\"a\",\"description\":\"d\",\"mimeType\":\"text/plain\"}]}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                server.responders.put(McpProtocol.METHOD_RESOURCES_READ, (request, path) -> {
                    assertEquals("r://a", request.path("params").path("uri").asText(""));
                    try {
                        return McpServerStub.MAPPER.readTree(
                                "{\"contents\":[{\"uri\":\"r://a\",\"mimeType\":\"text/plain\",\"text\":\"body\"},"
                                        + "{\"uri\":\"r://b\",\"blob\":\"QUJD\"}]}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                client.initialize(ctx());

                List<McpResource> resources = client.listResources(ctx());
                assertEquals(1, resources.size());
                assertEquals("r://a", resources.get(0).getUri());
                assertEquals("text/plain", resources.get(0).getMimeType());

                ReadResourceResult read = client.readResource("r://a", ctx());
                assertEquals(2, read.contents().size());
                assertEquals("body", read.contents().get(0).text());
                assertEquals("QUJD", read.contents().get(1).blob());
            }
        }

        @Test
        @DisplayName("响应为 text/event-stream 时也能取出匹配 id 的那条 JSON-RPC 响应")
        void sseResponseBranch() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.sseMethods.add(McpProtocol.METHOD_TOOLS_LIST);
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                client.initialize(ctx());
                assertEquals(0, client.listTools(ctx()).size());
            }
        }

        @Test
        @DisplayName("会话失效错误（Invalid session ID）触发主动断开")
        void sessionInvalidationDisconnects() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                client.initialize(ctx());

                server.errorMessages.put(McpProtocol.METHOD_TOOLS_LIST, "Invalid session ID");
                assertThrows(McpException.class, () -> client.listTools(ctx()));
                assertTrue(!client.isConnected(), "会话失效必须主动断开，让下次重建连接");
            }
        }
    }

    @Nested
    @DisplayName("OAuth 挑战与拒绝分支")
    class Rejections {

        @Test
        @DisplayName("401 + RFC 9728 metadata → McpOAuthRequiredException（带 metadataUrl）")
        void unauthorizedWithMetadata() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.forcedStatus.put(McpProtocol.METHOD_INITIALIZE, 401);
                server.unauthorizedHeader =
                        "Bearer resource_metadata=\"https://example.com/.well-known/oauth-protected-resource\"";
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());

                McpOAuthRequiredException err = assertThrows(McpOAuthRequiredException.class,
                        () -> client.initialize(ctx()));
                assertEquals("https://example.com/.well-known/oauth-protected-resource", err.metadataUrl());
            }
        }

        @Test
        @DisplayName("裸 401（无 metadata）→ 普通初始化失败，不引导去 OAuth")
        void bareUnauthorized() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.forcedStatus.put(McpProtocol.METHOD_INITIALIZE, 401);
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());

                McpException err = assertThrows(McpException.class, () -> client.initialize(ctx()));
                assertTrue(!(err instanceof McpOAuthRequiredException));
            }
        }

        @Test
        @DisplayName("initialize 收到 4xx → 提示服务端可能只支持传统 HTTP+SSE")
        void legacySseHint() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                server.forcedStatus.put(McpProtocol.METHOD_INITIALIZE, 405);
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                client.connect(ctx());
                McpException err = assertThrows(McpException.class, () -> client.initialize(ctx()));
                assertTrue(err.getMessage().contains("legacy HTTP+SSE"), err.getMessage());
            }
        }

        @Test
        @DisplayName("stdio 传输硬拒绝")
        void stdioRejected() {
            McpService service = service("http://127.0.0.1:1/x");
            service.setTransportType(McpTransportType.STDIO.value());
            McpException err = assertThrows(McpException.class,
                    () -> McpClientFactory.createClient(new McpClientConfig(service)));
            assertTrue(err.getMessage().contains("stdio transport is disabled"), err.getMessage());
        }

        @Test
        @DisplayName("未知传输类型 → ErrUnsupportedTransport")
        void unknownTransportRejected() {
            McpService service = service("http://127.0.0.1:1/x");
            service.setTransportType("bogus");
            McpException err = assertThrows(McpException.class,
                    () -> McpClientFactory.createClient(new McpClientConfig(service)));
            assertTrue(err.hasCode(McpErrorCode.UNSUPPORTED_TRANSPORT));
        }

        @Test
        @DisplayName("URL 缺失 → 按传输类型给出 URL is required")
        void missingUrl() {
            McpService service = service(null);
            McpException err = assertThrows(McpException.class,
                    () -> McpClientFactory.createClient(new McpClientConfig(service)));
            assertEquals("URL is required for HTTP Streamable transport", err.getMessage());
        }

        @Test
        @DisplayName("未 initialize 就调方法 → ErrNotConnected")
        void notConnected() throws Exception {
            try (McpServerStub server = new McpServerStub()) {
                McpClient client = McpClientFactory.createClient(new McpClientConfig(service(server.url("/mcp"))));
                McpException err = assertThrows(McpException.class, () -> client.listTools(ctx()));
                assertTrue(err.hasCode(McpErrorCode.NOT_CONNECTED));

                client.connect(ctx());
                err = assertThrows(McpException.class, () -> client.listTools(ctx()));
                assertTrue(err.hasCode(McpErrorCode.NOT_CONNECTED), "未握手时读目录同样应报未连接");

                McpException alreadyConnected = assertThrows(McpException.class, () -> client.connect(ctx()));
                assertTrue(alreadyConnected.hasCode(McpErrorCode.ALREADY_CONNECTED));
            }
        }
    }

    @Nested
    @DisplayName("SSRF 校验：service.url 与 authServerMetadataUrl 都要查")
    class Ssrf {

        @Test
        @DisplayName("受限主机名（service.url）被拒")
        void serviceUrlValidated() {
            McpService service = service("http://metadata.google.internal/mcp");
            McpException err = assertThrows(McpException.class,
                    () -> McpClientFactory.createClient(new McpClientConfig(service)));
            assertTrue(err.getMessage().startsWith("MCP service URL failed SSRF validation: "), err.getMessage());
        }

        @Test
        @DisplayName("受限主机名（authServerMetadataUrl）被拒——防止只查 url 的绕过口")
        void metadataUrlValidated() {
            McpService service = service("http://127.0.0.1:1/mcp");
            McpAuthConfig auth = new McpAuthConfig();
            auth.setAuthType(McpAuthType.OAUTH);
            auth.setAuthServerMetadataUrl("http://metadata.google.internal/.well-known/oauth");
            service.setAuthConfig(auth);

            McpException err = assertThrows(McpException.class,
                    () -> McpClientFactory.createClient(new McpClientConfig(service)));
            assertTrue(err.getMessage().startsWith("MCP OAuth metadata URL failed SSRF validation: "),
                    err.getMessage());
        }

        @Test
        @DisplayName("持久化边界的同一个入口：ValidateServiceOutboundURLs 对 null 服务报错")
        void nullServiceRejected() {
            McpException err = assertThrows(McpException.class,
                    () -> McpServiceUrls.validateServiceOutboundUrls(null));
            assertEquals("MCP service is required", err.getMessage());
        }

        @Test
        @DisplayName("OAuth 服务缺 OAuth 装配 → 明确报错")
        void oauthSupportRequired() {
            McpService service = service("http://127.0.0.1:1/mcp");
            McpAuthConfig auth = new McpAuthConfig();
            auth.setAuthType(McpAuthType.OAUTH);
            service.setAuthConfig(auth);
            McpException err = assertThrows(McpException.class,
                    () -> McpClientFactory.createClient(new McpClientConfig(service)));
            assertEquals("OAuth repository is required for OAuth MCP services", err.getMessage());
        }
    }

    @Test
    @DisplayName("鉴权头真的出现在出站请求上（api_key 策略；陈旧 token 不得一起发）")
    void authHeadersOnTheWire() throws Exception {
        try (McpServerStub server = new McpServerStub()) {
            McpService service = service(server.url("/mcp"));
            McpAuthConfig auth = new McpAuthConfig();
            auth.setAuthType(McpAuthType.API_KEY);
            auth.setApiKey("k1");
            auth.setToken("stale-token");
            service.setAuthConfig(auth);

            McpClient client = McpClientFactory.createClient(new McpClientConfig(service));
            client.connect(ctx());
            client.initialize(ctx());

            assertTrue(server.apiKeyHeaders.contains("k1"), "X-API-Key 必须出现在请求上");
            assertTrue(server.authorizationHeaders.stream().noneMatch(v -> v.contains("stale-token")),
                    "被选中策略之外的陈旧凭据绝不能一起发出去");
        }
    }
}
