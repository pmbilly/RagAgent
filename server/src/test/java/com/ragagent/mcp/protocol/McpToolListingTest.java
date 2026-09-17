package com.ragagent.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/mcp/client_tools_test.go：
 * {@code TestRawToolsPaginationErrorsAndCancellation} + {@code TestRawToolsBoundsHostileDirectorySize}。
 *
 * <p>Go 用 {@code rawToolsTransport}（内嵌 {@code transport.Interface} 只覆盖 SendRequest）来打桩；
 * Java 侧打桩点是 {@link McpTransport#send}，等价。</p>
 */
class McpToolListingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go rawToolsTransport：只覆盖 send 的可编程传输。 */
    private static final class FakeTransport implements McpTransport {
        private final java.util.function.Function<JsonRpcRequest, JsonRpcResponse> handler;
        int calls;
        final List<String> ids = new ArrayList<>();
        final List<Object> params = new ArrayList<>();

        FakeTransport(java.util.function.Function<JsonRpcRequest, JsonRpcResponse> handler) {
            this.handler = handler;
        }

        @Override
        public void start(McpContext ctx) {
        }

        @Override
        public JsonRpcResponse send(JsonRpcRequest request, McpContext ctx) {
            calls++;
            ids.add(String.valueOf(request.id()));
            params.add(request.params());
            return handler.apply(request);
        }

        @Override
        public void sendNotification(String method, Object p, McpContext ctx) {
        }

        @Override
        public void setConnectionLostHandler(Consumer<Throwable> handler) {
        }

        @Override
        public void close() {
        }
    }

    private static DefaultMcpClient clientWith(McpTransport transport) {
        DefaultMcpClient client = new DefaultMcpClient(new McpService(), transport, null);
        client.markInitializedForTest();
        return client;
    }

    /** 构造一条成功的 JSON-RPC 响应；入参是 {@code result} 的内容（对照 Go 的 Result 字段）。 */
    private static JsonRpcResponse ok(String resultJson) {
        try {
            var envelope = MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", 1);
            envelope.set("result", MAPPER.readTree(resultJson));
            return JsonRpcResponse.from(envelope);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 Go TestRawToolsPaginationErrorsAndCancellation。 */
    @Nested
    @DisplayName("分页错误与取消")
    class PaginationErrorsAndCancellation {

        private void runCase(String label, JsonRpcResponse secondPage) {
            int[] calls = {0};
            FakeTransport transport = new FakeTransport(request -> {
                assertTrue(String.valueOf(request.id()).contains("weknora-tools-"),
                        label + "：请求 ID 必须是 weknora-tools- 前缀的字符串");
                assertEquals(McpProtocol.METHOD_TOOLS_LIST, request.method());
                if (++calls[0] == 1) {
                    assertEquals(Map.of(), request.params(), label + "：首屏 params 是空对象");
                    return ok("""
                            {"tools":[{"name":"first","inputSchema":{"type":"object"}}],"nextCursor":"repeat"}
                            """);
                }
                assertEquals(Map.of("cursor", "repeat"), request.params(),
                        label + "：第二页必须带 repeat 游标");
                return secondPage;
            });
            DefaultMcpClient client = clientWith(transport);

            // 出错时调用方只会拿到异常，绝不发布"半份目录"
            McpException err = assertThrows(McpException.class, () -> client.listTools(McpContext.none()));
            assertNotNull(err);
            assertEquals(2, transport.calls, label + "：恰好请求两页（第二页失败后不再继续）");

            // 请求 ID 不能重复（Go：字符串 ID 不会与 SDK 数字 ID 撞车）
            Set<String> unique = new HashSet<>(transport.ids);
            assertEquals(transport.ids.size(), unique.size(), label + "：请求 ID 必须唯一");

            // 已取消的 ctx：不得发出任何新请求
            int callsBefore = transport.calls;
            McpCancellation cancelled = new McpCancellation();
            cancelled.cancel();
            McpException cancelErr = assertThrows(McpException.class,
                    () -> client.listTools(McpContext.cancellable(cancelled)));
            // 对照 Go：ctx 错误被 "failed to list tools: %w" 包裹，errors.Is 仍能认出 context.Canceled
            assertTrue(cancelErr.getMessage().endsWith("context canceled"), cancelErr.getMessage());
            assertEquals(callsBefore, transport.calls, label + "：ctx 已取消时不得再发请求");
        }

        @Test
        @DisplayName("游标重复 → 报错，且不发布半份目录")
        void repeatedCursor() {
            runCase("repeat", ok("""
                    {"tools":[],"nextCursor":"repeat"}
                    """));
        }

        @Test
        @DisplayName("第二页返回 JSON-RPC error → 报错")
        void secondPageError() {
            runCase("error", JsonRpcResponse.from(MAPPER.createObjectNode().put("jsonrpc", "2.0")
                    .put("id", 1)
                    .set("error", MAPPER.createObjectNode().put("code", -32603).put("message", "page unavailable"))));
        }

        @Test
        @DisplayName("第二页不是合法 tools 响应 → 报错")
        void secondPageInvalid() {
            runCase("not-json", new JsonRpcResponse("2.0", MAPPER.getNodeFactory().numberNode(1),
                    TextNode.valueOf("not-json"), null));
        }
    }

    /** 对照 Go TestRawToolsBoundsHostileDirectorySize。 */
    @Nested
    @DisplayName("恶意/失控目录的硬上限")
    class HostileDirectoryBounds {

        @Test
        @DisplayName("页数超限：不同游标绕不过重复检测，但会被页数上限拦下")
        void pageLimit() {
            int[] calls = {0};
            FakeTransport transport = new FakeTransport(request -> {
                calls[0]++;
                return ok("{\"tools\":[{\"name\":\"t" + calls[0] + "\"}],\"nextCursor\":\"c" + calls[0] + "\"}");
            });
            DefaultMcpClient client = clientWith(transport);

            McpException err = assertThrows(McpException.class, () -> client.listTools(McpContext.none()));
            assertTrue(err.getMessage().contains("exceeded"), "消息应含 exceeded：" + err.getMessage());
            assertTrue(calls[0] <= McpProtocol.MAX_TOOL_LIST_PAGES);
        }

        @Test
        @DisplayName("工具总数超限：整体拒绝")
        void toolCountLimit() {
            StringBuilder page = new StringBuilder("{\"tools\":[");
            for (int i = 0; i <= McpProtocol.MAX_TOOLS_PER_SERVICE; i++) {
                if (i > 0) {
                    page.append(',');
                }
                page.append("{\"name\":\"t").append(i).append("\"}");
            }
            page.append("]}");
            FakeTransport transport = new FakeTransport(request -> ok(page.toString()));
            DefaultMcpClient client = clientWith(transport);

            McpException err = assertThrows(McpException.class, () -> client.listTools(McpContext.none()));
            assertTrue(err.getMessage().contains("exceeded"), "消息应含 exceeded：" + err.getMessage());
        }

        @Test
        @DisplayName("单个 schema 超限：整体拒绝")
        void schemaSizeLimit() {
            String schema = "{\"type\":\"object\",\"description\":\"" + "x".repeat(McpProtocol.MAX_TOOL_SCHEMA_BYTES) + "\"}";
            FakeTransport transport = new FakeTransport(
                    request -> ok("{\"tools\":[{\"name\":\"huge\",\"inputSchema\":" + schema + "}]}"));
            DefaultMcpClient client = clientWith(transport);

            McpException err = assertThrows(McpException.class, () -> client.listTools(McpContext.none()));
            assertTrue(err.getMessage().contains("exceeds"), "消息应含 exceeds：" + err.getMessage());
        }

        @Test
        @DisplayName("上限之内：正常返回，且 schema 原样保留")
        void withinLimits() {
            FakeTransport transport = new FakeTransport(request -> ok(
                    "{\"tools\":[{\"name\":\"ok\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\",\"oneOf\":[{\"type\":\"string\"}]}}]}"));
            DefaultMcpClient client = clientWith(transport);

            List<McpTool> tools = client.listTools(McpContext.none());
            assertEquals(1, tools.size());
            assertEquals("ok", tools.get(0).getName());
            JsonNode schema = (JsonNode) tools.get(0).getInputSchema();
            assertTrue(schema.has("oneOf"), "根级 oneOf 必须原样保留（Go 注释点名的 SDK 丢字段问题）");
        }

        @Test
        @DisplayName("首屏 params 是空对象（对照 Go 的结构体 omitempty 行为）")
        void firstPageSendsEmptyParams() {
            FakeTransport transport = new FakeTransport(
                    request -> ok("{\"tools\":[{\"name\":\"ok\"}]}"));
            DefaultMcpClient client = clientWith(transport);
            client.listTools(McpContext.none());
            assertEquals(Map.of(), transport.params.get(0));
        }

        @Test
        @DisplayName("未 initialize 时读目录 → ErrNotConnected")
        void requiresInitialized() {
            FakeTransport transport = new FakeTransport(request -> ok("{\"tools\":[]}"));
            DefaultMcpClient client = new DefaultMcpClient(new McpService(), transport, null);
            McpException err = assertThrows(McpException.class, () -> client.listTools(McpContext.none()));
            assertTrue(err.hasCode(McpErrorCode.NOT_CONNECTED));
            assertEquals(0, transport.calls);
        }

        @Test
        @DisplayName("deadline 已过期的 ctx：报超时且不发请求")
        void expiredDeadline() {
            FakeTransport transport = new FakeTransport(request -> ok("{\"tools\":[]}"));
            DefaultMcpClient client = clientWith(transport);
            McpException err = assertThrows(McpException.class,
                    () -> client.listTools(McpContext.deadline(Instant.now().minusSeconds(1))));
            assertTrue(err.getMessage().endsWith("context deadline exceeded"), err.getMessage());
            assertTrue(err.hasCode(McpErrorCode.TIMEOUT));
            assertEquals(0, transport.calls);
        }
    }

    /** 传输错误必须原样（不被吞成"列表失败"）传给上层——对照 Go 的错误链保留。 */
    @Test
    @DisplayName("传输层失败保持错误链与原始异常")
    void transportErrorsArePreserved() {
        RuntimeException sentinel = new RuntimeException("network failure");
        FakeTransport transport = new FakeTransport(request -> {
            throw sentinel;
        });
        DefaultMcpClient client = clientWith(transport);
        McpException err = assertThrows(McpException.class, () -> client.listTools(McpContext.none()));
        assertTrue(err.getMessage().startsWith("failed to list tools: "), err.getMessage());
        assertTrue(chainContains(err, sentinel));
    }

    private static boolean chainContains(Throwable err, Throwable target) {
        Throwable cur = err;
        while (cur != null) {
            if (cur == target) {
                return true;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return false;
    }

    @Test
    @DisplayName("分页第二页的 cursor 参数形状（对照 JSONEq {\"cursor\":\"repeat\"}）")
    void cursorParamShape() {
        FakeTransport transport = new FakeTransport(request -> transportParams(request));
        DefaultMcpClient client = clientWith(transport);
        client.listTools(McpContext.none());
        Map<?, ?> second = (Map<?, ?>) transport.params.get(1);
        assertEquals("repeat", second.get("cursor"));
        assertEquals(1, second.size());
    }

    private static JsonRpcResponse transportParams(JsonRpcRequest request) {
        if (request.params() instanceof Map<?, ?> map && "repeat".equals(map.get("cursor"))) {
            return ok("{\"tools\":[]}");
        }
        return ok("{\"tools\":[{\"name\":\"a\"}],\"nextCursor\":\"repeat\"}");
    }
}
