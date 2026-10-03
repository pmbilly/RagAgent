package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * HTTP Streamable 传输（MCP 2025-03-26 起的默认传输；对照 mcp-go
 * {@code client/transport.StreamableHTTP}）。
 *
 * <p>线上行为逐条对照 mcp-go：</p>
 * <ul>
 *   <li>单端点 POST；{@code Content-Type: application/json}，
 *       {@code Accept: application/json, text/event-stream}；</li>
 *   <li>initialize 响应里的 {@code Mcp-Session-Id} 头被记下，<b>后续每个请求都要回传</b>
 *       （:571-578 / :630-634）；</li>
 *   <li>initialize 协商出的 protocolVersion 经 {@code Mcp-Protocol-Version} 头回传（:636-641）；</li>
 *   <li>只接受 200 / 202；202 = 通知已被受理，返回 null；</li>
 *   <li>401 → 解析 {@code WWW-Authenticate} 的 RFC 9728 {@code resource_metadata}，
 *       抛 {@link McpAuthorizationRequiredException}；</li>
 *   <li>404 且方法是 initialize → 服务端很可能只支持传统 SSE（legacy SSE server 信号）；</li>
 *   <li>响应 Content-Type 为 {@code text/event-stream} 时，从流里挑出<b>与本次请求 id 匹配</b>
 *       的那条 JSON-RPC 响应（其余是通知/其他请求，忽略）；</li>
 *   <li>close 时若持有 session，先发 DELETE 通知服务端会话结束（:211-230，5s 超时）。</li>
 * </ul>
 */
public final class StreamableHttpTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI serverUrl;
    private final Map<String, String> headers;
    private final Duration timeout;

    private volatile String sessionId = "";
    private volatile String protocolVersion = "";
    private volatile Consumer<Throwable> connectionLostHandler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public StreamableHttpTransport(URI serverUrl, Map<String, String> headers, Duration timeout) {
        this.serverUrl = serverUrl;
        this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        this.timeout = timeout;
    }

    @Override
    public void start(McpContext ctx) {
        // 对照 mcp-go StreamableHTTP.Start：本传输不需要常连接，start 是空操作。
        // 会话在第一次 initialize POST 的响应头里建立。
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request, McpContext ctx) {
        ctx.throwIfCancelled();
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(request.toJson(MAPPER));
        } catch (Exception e) {
            throw new McpException(McpErrorCode.INVALID_RESPONSE, "failed to marshal request: " + e.getMessage(), e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUrl)
                .header("Content-Type", "application/json")
                .header("Accept", McpProtocol.ACCEPT_STREAMABLE)
                .timeout(ctx.effectiveTimeout(timeout))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!sessionId.isEmpty()) {
            builder.header(McpProtocol.HEADER_SESSION_ID, sessionId);
        }
        if (!protocolVersion.isEmpty()) {
            builder.header(McpProtocol.HEADER_PROTOCOL_VERSION, protocolVersion);
        }
        // 调用方头最后设置：自定义头里的同名键会覆盖协议默认值——顺序刻意如此。
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.setHeader(e.getKey(), e.getValue());
        }

        HttpResponse<InputStream> response;
        try {
            response = McpHttp.send(builder.build());
        } catch (IOException e) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED,
                    "failed to send request: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled", e);
        }

        int status = response.statusCode();
        if (status != 200 && status != 202) {
            try {
                return handleErrorResponse(status, response, request);
            } finally {
                McpHttp.closeQuietly(response);
            }
        }

        if (McpProtocol.METHOD_INITIALIZE.equals(request.method())) {
            // 会话 ID 可以为空（无状态服务端），为空时不覆盖已有值。
            List<String> ids = response.headers().allValues(McpProtocol.HEADER_SESSION_ID);
            if (!ids.isEmpty() && ids.get(0) != null && !ids.get(0).isEmpty()) {
                sessionId = ids.get(0);
            }
        }

        if (status == 202) {
            McpHttp.closeQuietly(response);
            return null;
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String mediaType = contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if ("application/json".equals(mediaType)) {
                byte[] raw = readAll(response);
                JsonNode node = MAPPER.readTree(raw);
                JsonRpcResponse parsed = JsonRpcResponse.from(node);
                if (parsed.isNotification()) {
                    throw new McpException(McpErrorCode.INVALID_RESPONSE,
                            "response should contain RPC id: " + new String(raw, StandardCharsets.UTF_8));
                }
                return parsed;
            }
            if ("text/event-stream".equals(mediaType)) {
                return readSseResponse(response, request);
            }
            throw new McpException(McpErrorCode.INVALID_RESPONSE, "unexpected content type: " + contentType);
        } catch (IOException e) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED,
                    "failed to decode response: " + e.getMessage(), e);
        } finally {
            McpHttp.closeQuietly(response);
        }
    }

    /**
     * 对照 mcp-go {@code StreamableHTTP.SendNotification}：无 id 的 POST，服务端回 200/202 即算送达，
     * 响应体一律丢弃。
     */
    @Override
    public void sendNotification(String method, Object params, McpContext ctx) {
        ctx.throwIfCancelled();
        com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", JsonRpcRequest.JSONRPC_VERSION);
        node.put("method", method);
        if (params != null) {
            node.set("params", MAPPER.valueToTree(params));
        }
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new McpException(McpErrorCode.INVALID_RESPONSE, "failed to marshal request: " + e.getMessage(), e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUrl)
                .header("Content-Type", "application/json")
                .header("Accept", McpProtocol.ACCEPT_STREAMABLE)
                .timeout(ctx.effectiveTimeout(timeout))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!sessionId.isEmpty()) {
            builder.header(McpProtocol.HEADER_SESSION_ID, sessionId);
        }
        if (!protocolVersion.isEmpty()) {
            builder.header(McpProtocol.HEADER_PROTOCOL_VERSION, protocolVersion);
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.setHeader(e.getKey(), e.getValue());
        }
        HttpResponse<InputStream> response;
        try {
            response = McpHttp.send(builder.build());
        } catch (IOException e) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "failed to send request: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled", e);
        }
        int status = response.statusCode();
        McpHttp.closeQuietly(response);
        if (status != 200 && status != 202 && status != 204) {
            throw new McpException(McpErrorCode.INVALID_RESPONSE, "request failed with status " + status);
        }
    }

    /**
     * 对照 mcp-go 的 SSE 响应分支（{@code handleSSEResponse}）：只有 id 与请求匹配的帧才是
     * 本次的响应；id 为空的帧是通知，服务端发起的请求被忽略（Java 侧不支持 sampling/roots）。
     */
    private JsonRpcResponse readSseResponse(HttpResponse<InputStream> response, JsonRpcRequest request)
            throws IOException {
        String wantId = request.idKey();
        try (McpSseReader reader = new McpSseReader(response.body())) {
            McpSseReader.SseMessage message;
            while ((message = reader.next()) != null) {
                JsonNode node;
                try {
                    node = MAPPER.readTree(message.data());
                } catch (Exception nonJson) {
                    continue; // 非 JSON 帧（注释/心跳）忽略，非致命
                }
                JsonRpcResponse parsed = JsonRpcResponse.from(node);
                if (parsed.isNotification()) {
                    continue;
                }
                if (wantId.equals(parsed.idKey())) {
                    return parsed;
                }
            }
        }
        throw new McpException(McpErrorCode.CONNECTION_CLOSED,
                "SSE stream ended before a response for request " + wantId + " was received");
    }

    /** 非 200/202 的错误路径。 */
    private JsonRpcResponse handleErrorResponse(int status, HttpResponse<InputStream> response, JsonRpcRequest request) {
        if (status == 401) {
            String metadataUrl = McpAuthHeaders.extractResourceMetadataUrl(
                    response.headers().allValues("WWW-Authenticate"));
            throw new McpAuthorizationRequiredException(metadataUrl);
        }
        if (McpProtocol.METHOD_INITIALIZE.equals(request.method()) && status >= 400 && status < 500) {
            // initialize 收到 4xx 说明服务端只支持传统 HTTP+SSE（legacy SSE server 信号）。
            throw new McpException(McpErrorCode.CONNECTION_CLOSED,
                    "server returned " + status + " for initialize: it likely only supports the legacy HTTP+SSE transport");
        }
        byte[] body = readAllQuietly(response);
        JsonNode node = null;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception ignored) {
            // 不是 JSON-RPC 错误体，走下面的通用分支
        }
        if (node != null && node.has("error")) {
            JsonRpcResponse errResponse = JsonRpcResponse.from(node);
            McpException ex = errResponse.errorAsException();
            if (ex != null) {
                throw ex;
            }
        }
        throw new McpException(McpErrorCode.INVALID_RESPONSE,
                "request failed with status " + status + ": " + new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] readAll(HttpResponse<InputStream> response) throws IOException {
        return response.body().readAllBytes();
    }

    private static byte[] readAllQuietly(HttpResponse<InputStream> response) {
        try {
            return response.body().readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** 对照 mcp-go {@code StreamableHTTP.Close}：有会话时先发 DELETE 让服务端回收。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        String session = sessionId;
        sessionId = "";
        if (session.isEmpty()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(serverUrl)
                    .timeout(Duration.ofSeconds(5))
                    .header(McpProtocol.HEADER_SESSION_ID, session)
                    .DELETE();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.setHeader(e.getKey(), e.getValue());
            }
            HttpResponse<InputStream> resp = McpHttp.send(builder.build());
            McpHttp.closeQuietly(resp);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            notifyConnectionLostQuietly(e);
        }
    }

    @Override
    public void setConnectionLostHandler(Consumer<Throwable> handler) {
        this.connectionLostHandler = handler;
    }

    @Override
    public String sessionId() {
        return sessionId.isEmpty() ? null : sessionId;
    }

    /** initialize 结果里协商出的协议版本（由 {@link DefaultMcpClient} 在握手后回填）。 */
    public void setProtocolVersion(String version) {
        this.protocolVersion = version == null ? "" : version;
    }

    private void notifyConnectionLostQuietly(Throwable e) {
        Consumer<Throwable> handler = connectionLostHandler;
        if (handler != null) {
            try {
                handler.accept(e);
            } catch (RuntimeException ignored) {
                // 回调异常不影响关闭流程
            }
        }
    }
}
