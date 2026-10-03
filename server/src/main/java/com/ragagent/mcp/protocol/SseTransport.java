package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 传统 HTTP+SSE 传输（MCP 2024-11-05 的 HTTP with SSE；对照 mcp-go
 * {@code client/transport.SSE}）。
 *
 * <p>线上行为逐条对照 mcp-go：</p>
 * <ul>
 *   <li><b>建流</b>：GET 服务 URL，{@code Accept: text/event-stream} +
 *       {@code Cache-Control: no-cache}
 *       （{@code Connection: keep-alive} 是受限头无法设置，见 {@link #start} 的说明），
 *       自定义/鉴权头一并带上；</li>
 *   <li><b>endpoint 帧</b>：服务端首帧给出 POST 地址（可相对），相对基准是<b>建流的 URL</b>；
 *       且要求 host 与建流 URL 一致，否则丢弃（防被重定向到第三方）；</li>
 *   <li><b>endpoint 等待上限</b> 30s（mcp-go WithEndpointTimeout 默认值）；</li>
 *   <li><b>发请求</b>：POST 到 endpoint，{@code Content-Type: application/json}，
 *       响应体丢弃；真正的 JSON-RPC 响应从 SSE 流里按 id 匹配回来；</li>
 *   <li><b>断流</b>：读取线程结束/报错且未主动 close 时，回调 OnConnectionLost。</li>
 * </ul>
 *
 * <p><b>刻意差异</b>：建流只施加连接超时，不施加整体超时——若给建流 GET 套上 30s
 * 整体超时，SSE 长连接会被反复掐断。
 * 与 {@code LlmTransport} 对"流式调用不设 per-request timeout"的处理一致。</p>
 */
public final class SseTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUrl;
    private final Map<String, String> headers;
    private final Duration timeout;

    private final Map<String, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private final CountDownLatch endpointReady = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile URI endpoint;
    private volatile String endpointError;
    private volatile InputStream stream;
    private volatile Thread readerThread;
    private volatile Consumer<Throwable> connectionLostHandler;

    public SseTransport(URI baseUrl, Map<String, String> headers, Duration timeout) {
        this.baseUrl = baseUrl;
        this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        this.timeout = timeout;
    }

    @Override
    public void start(McpContext ctx) {
        ctx.throwIfCancelled();
        // ⚠️ {@code Connection: keep-alive} 头是 JDK HttpClient 的受限头（设置即抛
        // IllegalArgumentException），无法显式设置。
        // 语义上无损失——HTTP/1.1 默认就是持久连接，连接的复用与回收由 JDK 客户端管理。
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl)
                .header("Accept", McpProtocol.ACCEPT_SSE)
                .header("Cache-Control", "no-cache")
                .GET();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.setHeader(e.getKey(), e.getValue());
        }
        HttpResponse<InputStream> response;
        try {
            response = McpHttp.send(builder.build());
        } catch (IOException e) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "failed to connect: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled", e);
        }
        if (response.statusCode() != 200) {
            McpHttp.closeQuietly(response);
            throw new McpException(McpErrorCode.CONNECTION_CLOSED,
                    "SSE connection failed with status " + response.statusCode());
        }
        this.stream = response.body();

        Thread reader = Thread.ofVirtual().name("mcp-sse-reader-" + baseUrl.getHost()).unstarted(this::readLoop);
        this.readerThread = reader;
        // 调用方取消 ⇒ 关掉流，让阻塞中的读立即返回（对照 mcp-go 用 ctx 取消时 reader.Close() 的做法）
        ctx.cancellation().onCancel(() -> closeQuietly(stream));
        reader.start();

        Duration wait = ctx.deadline() == null ? McpProtocol.SSE_ENDPOINT_TIMEOUT
                : ctx.effectiveTimeout(McpProtocol.SSE_ENDPOINT_TIMEOUT);
        try {
            if (!endpointReady.await(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new McpException(McpErrorCode.TIMEOUT,
                        "timeout waiting for endpoint after " + wait);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled", e);
        }
        ctx.throwIfCancelled();
        if (endpointError != null) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, endpointError);
        }
        if (endpoint == null) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "endpoint not received");
        }
    }

    private void readLoop() {
        try (McpSseReader reader = new McpSseReader(stream)) {
            McpSseReader.SseMessage message;
            while ((message = reader.next()) != null) {
                handleEvent(message);
            }
            if (!closed.get()) {
                notifyConnectionLost(new IOException("SSE stream closed by server"));
            }
        } catch (IOException e) {
            if (!closed.get()) {
                notifyConnectionLost(e);
            }
        }
    }

    /** 对照 mcp-go {@code handleSSEEvent}。 */
    private void handleEvent(McpSseReader.SseMessage message) {
        switch (message.event()) {
            case McpProtocol.SSE_EVENT_ENDPOINT -> {
                URI resolved;
                try {
                    resolved = baseUrl.resolve(message.data());
                } catch (IllegalArgumentException e) {
                    endpointError = "Error parsing endpoint URL: " + e.getMessage();
                    endpointReady.countDown();
                    return;
                }
                if (resolved.getHost() == null || !resolved.getHost().equals(baseUrl.getHost())) {
                    // 固定文案："Endpoint origin does not match connection origin"
                    endpointError = "Endpoint origin does not match connection origin";
                    endpointReady.countDown();
                    return;
                }
                endpoint = resolved;
                endpointReady.countDown();
            }
            case McpProtocol.SSE_EVENT_MESSAGE -> {
                JsonNode node;
                try {
                    node = MAPPER.readTree(message.data());
                } catch (Exception e) {
                    return; // 非 JSON 帧忽略
                }
                JsonRpcResponse parsed = JsonRpcResponse.from(node);
                if (parsed.isNotification()) {
                    return; // 本项目不消费服务端通知/ping
                }
                CompletableFuture<JsonRpcResponse> waiter = pending.remove(parsed.idKey());
                if (waiter != null) {
                    waiter.complete(parsed);
                }
            }
            default -> {
                // 未知事件名忽略
            }
        }
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request, McpContext ctx) {
        ctx.throwIfCancelled();
        URI target = endpoint;
        if (target == null) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "endpoint not received");
        }
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(request.toJson(MAPPER));
        } catch (Exception e) {
            throw new McpException(McpErrorCode.INVALID_RESPONSE, "failed to marshal request: " + e.getMessage(), e);
        }

        CompletableFuture<JsonRpcResponse> waiter = new CompletableFuture<>();
        pending.put(request.idKey(), waiter);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .header("Content-Type", "application/json")
                    .timeout(ctx.effectiveTimeout(timeout))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
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
            McpHttp.closeQuietly(response);
            if (status != 200 && status != 202 && status != 204) {
                throw new McpException(McpErrorCode.INVALID_RESPONSE,
                        "request failed with status " + status);
            }

            try {
                // 用服务超时兜底，避免没有 deadline 的调用方永久挂住（见类注释）。
                return waiter.get(ctx.effectiveTimeout(timeout).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new McpException(McpErrorCode.TIMEOUT, "operation timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof McpException me) {
                    throw me;
                }
                throw new McpException(McpErrorCode.CONNECTION_CLOSED,
                        "request failed: " + (cause == null ? e : cause.getMessage()), cause);
            }
        } finally {
            pending.remove(request.idKey());
        }
    }

    /**
     * 对照 mcp-go {@code SSE.SendNotification}：POST 到 endpoint，不等响应、不注册 pending
     * （JSON-RPC 通知本来就没有响应）。
     */
    @Override
    public void sendNotification(String method, Object params, McpContext ctx) {
        ctx.throwIfCancelled();
        URI target = endpoint;
        if (target == null) {
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "endpoint not received");
        }
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .header("Content-Type", "application/json")
                .timeout(ctx.effectiveTimeout(timeout))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
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

    @Override
    public void setConnectionLostHandler(Consumer<Throwable> handler) {
        this.connectionLostHandler = handler;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(stream);
        Thread reader = readerThread;
        if (reader != null) {
            reader.interrupt();
        }
        for (Map.Entry<String, CompletableFuture<JsonRpcResponse>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(
                    new McpException(McpErrorCode.CONNECTION_CLOSED, "connection closed"));
        }
        pending.clear();
        endpointReady.countDown();
    }

    private void notifyConnectionLost(Throwable e) {
        Consumer<Throwable> handler = connectionLostHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.accept(e);
        } catch (RuntimeException ignored) {
            // 回调异常不应影响读线程收尾
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭路径
        }
    }

    /** 测试/诊断用：当前 POST 端点。 */
    public URI endpoint() {
        return endpoint;
    }
}
