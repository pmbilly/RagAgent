package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 默认 MCP 客户端（对照 Go internal/mcp/client.go 的 {@code mcpGoClient}）。
 *
 * <p>三件事：<b>握手/状态机</b>、<b>工具目录的分页与上限</b>、<b>OAuth 调用纪律</b>。
 * 报文收发全部委托给 {@link McpTransport}（对照 Go 委托给 mark3labs/mcp-go）。</p>
 *
 * <p><b>为什么 tools/list 走原始 JSON-RPC</b>（Go client.go:483-486 注释）：SDK 的强类型
 * ToolInputSchema 会丢掉 {@code oneOf} 这类根级关键字，并把 {@code definitions} 改写成
 * {@code $defs} 却不改引用——所以必须用同一套已鉴权的传输通道自己读原始 schema。
 * 用字符串请求 ID（{@code "weknora-tools-<uuid>"}）确保不会与客户端的数字自增 ID 撞车。</p>
 */
public final class DefaultMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpService service;
    private final McpTransport transport;
    private final McpOAuthRuntime oauthRuntime;
    private final Duration timeout;

    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean initialized = new AtomicBoolean();
    /** 数字请求 ID 自增（对照 mcp-go 客户端的 requestID 计数器）。 */
    private final AtomicLong requestIdSeq = new AtomicLong(1);
    /** initialize 里服务端下发的文档；volatile 即可（对照 Go 的 metadataMu + string）。 */
    private volatile String instructions = "";

    public DefaultMcpClient(McpService service, McpTransport transport, McpOAuthRuntime oauthRuntime) {
        this.service = service;
        this.transport = transport;
        this.oauthRuntime = oauthRuntime;
        this.timeout = resolveTimeout(service);
    }

    /** 对照 Go {@code NewMCPClient} 的超时解析：AdvancedConfig.timeout > 0 才覆盖，默认 30s。 */
    static Duration resolveTimeout(McpService service) {
        McpAdvancedConfig advanced = service == null ? null : service.getAdvancedConfig();
        if (advanced != null && advanced.getTimeout() > 0) {
            return Duration.ofSeconds(advanced.getTimeout());
        }
        return McpProtocol.DEFAULT_TIMEOUT;
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    @Override
    public void connect(McpContext ctx) {
        if (connected.get()) {
            throw new McpException(McpErrorCode.ALREADY_CONNECTED);
        }
        try {
            oauthCall(ctx, () -> {
                transport.start(ctx);
                return null;
            });
        } catch (RuntimeException e) {
            McpOAuthRequiredException oauthRequired = McpAuthHeaders.asOAuthRequired(e);
            if (oauthRequired != null) {
                throw oauthRequired;
            }
            throw wrap("failed to start client", e);
        }
        connected.set(true);
        log.info("MCP client connected to {}", McpLog.sanitize(service.getUrl()));
    }

    @Override
    public void disconnect() {
        if (!connected.compareAndSet(true, false)) {
            return;
        }
        initialized.set(false);
        transport.close();
    }

    // ------------------------------------------------------------------
    // initialize
    // ------------------------------------------------------------------

    @Override
    public InitializeResult initialize(McpContext ctx) {
        if (!connected.get()) {
            throw new McpException(McpErrorCode.NOT_CONNECTED);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", McpProtocol.CLIENT_NAME);
        clientInfo.put("version", McpProtocol.CLIENT_VERSION);
        params.put("clientInfo", clientInfo);

        JsonRpcResponse response;
        try {
            response = oauthCall(ctx, () -> {
                JsonRpcRequest request = new JsonRpcRequest(nextRequestId(), McpProtocol.METHOD_INITIALIZE, params);
                JsonRpcResponse r = transport.send(request, ctx);
                if (r == null) {
                    throw new McpException(McpErrorCode.INVALID_RESPONSE, "empty initialize response");
                }
                if (r.hasError()) {
                    throw r.errorAsException();
                }
                String negotiated = r.result() == null ? "" : r.result().path("protocolVersion").asText("");
                if (!negotiated.isEmpty() && transport instanceof StreamableHttpTransport streamable) {
                    // 对照 mcp-go：协商出的版本经 Mcp-Protocol-Version 头回传后续请求
                    streamable.setProtocolVersion(negotiated);
                }
                // MCP 要求握手成功后补一条通知，否则服务端不认为初始化完成。
                // 通知发送失败在 Go 侧会让整个 initialize 失败（mcp-go client.go:253-259）。
                try {
                    transport.sendNotification(McpProtocol.METHOD_INITIALIZED_NOTIFICATION, null, ctx);
                } catch (RuntimeException e) {
                    throw wrap("failed to send initialized notification", e);
                }
                return r;
            });
        } catch (RuntimeException e) {
            checkErrorAndDisconnectIfNeeded(e);
            McpOAuthRequiredException oauthRequired = McpAuthHeaders.asOAuthRequired(e);
            if (oauthRequired != null) {
                throw oauthRequired;
            }
            throw wrap("failed to initialize", e);
        }

        JsonNode result = response.result() == null ? MAPPER.createObjectNode() : response.result();
        InitializeResult parsed = new InitializeResult(
                result.path("protocolVersion").asText(""),
                parseCapabilities(result.get("capabilities")),
                parseServerInfo(result.get("serverInfo")),
                result.path("instructions").asText(""));

        initialized.set(true);
        this.instructions = parsed.instructions();

        String serviceName = McpLog.sanitize(service.getName());
        log.debug("MCP initialize handshake service={} protocol={} name={} version={} title={}",
                serviceName,
                parsed.protocolVersion(),
                McpLog.sanitize(parsed.serverInfo().name()),
                McpLog.sanitize(parsed.serverInfo().version()),
                McpLog.sanitize(parsed.serverInfo().title()));
        if (parsed.instructions().isEmpty()
                && (parsed.serverInfo().description() == null || parsed.serverInfo().description().isEmpty())) {
            log.debug("MCP initialize optional docs absent service={} description_len=0 instructions_len=0", serviceName);
        } else {
            log.debug("MCP initialize docs service={} description_len={} instructions_len={} instructions_preview={}",
                    serviceName,
                    parsed.serverInfo().description() == null ? 0 : parsed.serverInfo().description().length(),
                    parsed.instructions().length(),
                    McpLog.preview(parsed.instructions(), 240));
        }
        return parsed;
    }

    @Override
    public String serverInstructions() {
        return instructions;
    }

    // ------------------------------------------------------------------
    // tools
    // ------------------------------------------------------------------

    @Override
    public List<McpTool> listTools(McpContext ctx) {
        if (!initialized.get()) {
            throw new McpException(McpErrorCode.NOT_CONNECTED);
        }
        try {
            return oauthCall(ctx, () -> listRawTools(ctx));
        } catch (RuntimeException e) {
            checkErrorAndDisconnectIfNeeded(e);
            throw wrap("failed to list tools", e);
        }
    }

    /**
     * 分页读取工具目录（对照 Go {@code listRawTools}，client.go:487-549）。
     *
     * <p>租户自填的 MCP 端点不可信，整个目录又要全量驻留内存并在之后做 schema 编译，
     * 因此对协议分页做硬上限——敌对或死循环的服务端不能在 list 超时前把目录无限撑大。
     * 超限一律<b>整体拒绝</b>，绝不发布"半份目录"。</p>
     */
    private List<McpTool> listRawTools(McpContext ctx) {
        List<McpTool> tools = new ArrayList<>();
        String cursor = "";
        Set<String> seen = new HashSet<>();
        for (int pages = 0; ; pages++) {
            ctx.throwIfCancelled();
            if (pages >= McpProtocol.MAX_TOOL_LIST_PAGES) {
                throw new McpException(McpErrorCode.INVALID_RESPONSE,
                        "tools/list exceeded " + McpProtocol.MAX_TOOL_LIST_PAGES + " pages");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            // 字符串 ID：不会与客户端数字自增 ID 撞车（对照 Go 的注释）
            JsonRpcRequest request = new JsonRpcRequest(
                    "weknora-tools-" + UUID.randomUUID(), McpProtocol.METHOD_TOOLS_LIST, params);
            JsonRpcResponse response = transport.send(request, ctx);
            if (response == null) {
                throw new McpException(McpErrorCode.INVALID_RESPONSE, "empty tools/list response");
            }
            if (response.hasError()) {
                throw response.errorAsException();
            }
            JsonNode page = response.result();
            if (page == null || !page.has("tools")) {
                throw new McpException(McpErrorCode.INVALID_RESPONSE,
                        "invalid tools/list response: missing \"tools\"");
            }
            for (JsonNode tool : page.path("tools")) {
                JsonNode schema = tool.get("inputSchema");
                long schemaBytes = schema == null ? 0 : schema.toString().getBytes(StandardCharsets.UTF_8).length;
                if (schemaBytes > McpProtocol.MAX_TOOL_SCHEMA_BYTES) {
                    throw new McpException(McpErrorCode.INVALID_RESPONSE,
                            "tool \"" + tool.path("name").asText("") + "\" input schema exceeds "
                                    + McpProtocol.MAX_TOOL_SCHEMA_BYTES + " bytes");
                }
                tools.add(new McpTool(
                        tool.path("name").asText(""),
                        tool.path("description").asText(""),
                        schema));
            }
            if (tools.size() > McpProtocol.MAX_TOOLS_PER_SERVICE) {
                throw new McpException(McpErrorCode.INVALID_RESPONSE,
                        "tools/list exceeded " + McpProtocol.MAX_TOOLS_PER_SERVICE + " tools");
            }
            String nextCursor = page.path("nextCursor").asText("");
            if (nextCursor.isEmpty()) {
                return tools;
            }
            if (!seen.add(nextCursor)) {
                throw new McpException(McpErrorCode.INVALID_RESPONSE, "tools/list returned a repeated cursor");
            }
            cursor = nextCursor;
        }
    }

    // ------------------------------------------------------------------
    // resources / tools/call
    // ------------------------------------------------------------------

    @Override
    public List<McpResource> listResources(McpContext ctx) {
        if (!initialized.get()) {
            throw new McpException(McpErrorCode.NOT_CONNECTED);
        }
        try {
            JsonRpcResponse response = oauthCall(ctx, () -> send(McpProtocol.METHOD_RESOURCES_LIST,
                    new LinkedHashMap<>(), ctx));
            List<McpResource> resources = new ArrayList<>();
            for (JsonNode item : resultArray(response, "resources")) {
                McpResource resource = new McpResource();
                resource.setUri(item.path("uri").asText(""));
                resource.setName(item.path("name").asText(""));
                resource.setDescription(item.path("description").asText(""));
                resource.setMimeType(item.path("mimeType").asText(""));
                resources.add(resource);
            }
            return resources;
        } catch (RuntimeException e) {
            checkErrorAndDisconnectIfNeeded(e);
            throw wrap("failed to list resources", e);
        }
    }

    @Override
    public CallToolResult callTool(String name, Map<String, Object> args, McpContext ctx) {
        if (!initialized.get()) {
            throw new McpException(McpErrorCode.NOT_CONNECTED);
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", name);
            if (args != null) {
                params.put("arguments", args);
            }
            JsonRpcResponse response = oauthCall(ctx, () -> send(McpProtocol.METHOD_TOOLS_CALL, params, ctx));
            JsonNode result = response.result() == null ? MAPPER.createObjectNode() : response.result();
            List<ContentItem> content = new ArrayList<>();
            for (JsonNode item : result.path("content")) {
                // 逐条对照 Go 的转换：只认 text 与 image，其余类型静默丢弃
                String type = item.path("type").asText("");
                if ("text".equals(type)) {
                    content.add(ContentItem.text(item.path("text").asText("")));
                } else if ("image".equals(type)) {
                    content.add(ContentItem.image(
                            item.path("data").asText(""), item.path("mimeType").asText("")));
                }
            }
            return new CallToolResult(result.path("isError").asBoolean(false), content);
        } catch (RuntimeException e) {
            checkErrorAndDisconnectIfNeeded(e);
            throw wrap("failed to call tool", e);
        }
    }

    @Override
    public ReadResourceResult readResource(String uri, McpContext ctx) {
        if (!initialized.get()) {
            throw new McpException(McpErrorCode.NOT_CONNECTED);
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("uri", uri);
            JsonRpcResponse response = oauthCall(ctx, () -> send(McpProtocol.METHOD_RESOURCES_READ, params, ctx));
            List<ResourceContent> contents = new ArrayList<>();
            for (JsonNode item : resultArray(response, "contents")) {
                if (item.has("text")) {
                    contents.add(new ResourceContent(
                            item.path("uri").asText(""),
                            item.path("mimeType").asText(null),
                            item.path("text").asText(""),
                            null));
                } else if (item.has("blob")) {
                    contents.add(new ResourceContent(
                            item.path("uri").asText(""),
                            item.path("mimeType").asText(null),
                            null,
                            item.path("blob").asText("")));
                }
            }
            return new ReadResourceResult(contents);
        } catch (RuntimeException e) {
            checkErrorAndDisconnectIfNeeded(e);
            throw wrap("failed to read resource", e);
        }
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public String serviceId() {
        return service == null ? null : service.getId();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 发一条带数字 ID 的请求并做通用错误处理（对照 Go 通过 SDK 发的那几条）。 */
    private JsonRpcResponse send(String method, Object params, McpContext ctx) {
        JsonRpcResponse response = transport.send(new JsonRpcRequest(nextRequestId(), method, params), ctx);
        if (response == null) {
            throw new McpException(McpErrorCode.INVALID_RESPONSE, "empty " + method + " response");
        }
        if (response.hasError()) {
            throw response.errorAsException();
        }
        return response;
    }

    private static JsonNode resultArray(JsonRpcResponse response, String field) {
        JsonNode result = response.result();
        if (result == null || !result.has(field)) {
            throw new McpException(McpErrorCode.INVALID_RESPONSE,
                    "invalid response: missing \"" + field + "\"");
        }
        return result.path(field);
    }

    private long nextRequestId() {
        return requestIdSeq.getAndIncrement();
    }

    /**
     * 测试可见：把客户端直接置成"已 initialize"状态（对照 Go 测试里
     * {@code c.initialized.Store(true)} 的用法，用于只测目录读取逻辑）。
     */
    void markInitializedForTest() {
        initialized.set(true);
    }

    /**
     * 对照 Go {@code oauthCall}（client.go:316-335）：带 WeKnora 自己的 token 生命周期检查跑一次操作。
     * 资源端的 401 <b>恰好强制刷新一次并重试一次</b>；其它错误一律不重试——
     * 免得在语义不明的网络失败后把工具的副作用做第二遍。
     */
    private <T> T oauthCall(McpContext ctx, Supplier<T> operation) {
        if (oauthRuntime != null) {
            oauthRuntime.ensureFresh(ctx, false, null);
        }
        try {
            return operation.get();
        } catch (RuntimeException e) {
            if (oauthRuntime == null || !oauthRuntime.isAuthorizationFailure(e)) {
                throw e;
            }
            oauthRuntime.ensureFresh(ctx, true, e);
            return operation.get();
        }
    }

    /**
     * 对照 Go {@code checkErrorAndDisconnectIfNeeded}（client.go:301-314）：SSE 与 Streamable
     * 都用服务端分配的会话（{@code Mcp-Session-Id}），会话过期/被回收后应当主动断开，
     * 让后续 {@code GetOrCreateClient} 重新建连。
     *
     * <p>已知的会话失效文案："Invalid session ID"（服务端认这个头但拒绝其值）、
     * "No active connection"（服务端根本没有这个会话）。</p>
     */
    private void checkErrorAndDisconnectIfNeeded(Throwable err) {
        Throwable cur = err;
        while (cur != null) {
            String message = cur.getMessage();
            if (message != null
                    && (message.contains("Invalid session ID") || message.contains("No active connection"))) {
                disconnect();
                return;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
    }

    /** 对照 Go {@code fmt.Errorf("%s: %w", prefix, err)}：保留底层 code，消息加前缀。 */
    private static McpException wrap(String prefix, Throwable cause) {
        McpErrorCode code = cause instanceof McpException me ? me.code() : null;
        return new McpException(code, prefix + ": " + cause.getMessage(), cause);
    }

    private static ServerInfo parseServerInfo(JsonNode node) {
        if (node == null || node.isNull()) {
            return ServerInfo.empty();
        }
        return new ServerInfo(
                node.path("name").asText(""),
                node.path("version").asText(""),
                textOrNull(node, "title"),
                textOrNull(node, "description"));
    }

    private static ServerCapabilities parseCapabilities(JsonNode node) {
        if (node == null || node.isNull()) {
            return ServerCapabilities.empty();
        }
        ServerCapabilities.ToolsCapability tools = node.hasNonNull("tools")
                ? new ServerCapabilities.ToolsCapability(node.get("tools").path("listChanged").asBoolean(false))
                : null;
        ServerCapabilities.ResourcesCapability resources = node.hasNonNull("resources")
                ? new ServerCapabilities.ResourcesCapability(
                        node.get("resources").path("subscribe").asBoolean(false),
                        node.get("resources").path("listChanged").asBoolean(false))
                : null;
        ServerCapabilities.PromptsCapability prompts = node.hasNonNull("prompts")
                ? new ServerCapabilities.PromptsCapability(node.get("prompts").path("listChanged").asBoolean(false))
                : null;
        return new ServerCapabilities(tools, resources, prompts, null, null);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
