package com.ragagent.mcp.oauth;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.ragagent.mcp.protocol.JsonRpcRequest;
import com.ragagent.mcp.protocol.JsonRpcResponse;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpTransport;
import com.ragagent.mcp.protocol.SseTransport;
import com.ragagent.mcp.protocol.StreamableHttpTransport;

/**
 * 带 OAuth 的 MCP 传输：在既有 SSE / HTTP-Streamable 传输之上按请求注入
 * {@code Authorization: Bearer <token>}。
 *
 * <h3>为什么是"包装 + 换 token 时重建"而不是"每请求塞头"</h3>
 * <p>既有 {@link SseTransport}/{@link StreamableHttpTransport} 在构造期
 * <b>拷贝</b>头表（{@code new LinkedHashMap<>(headers)}），构造后无法再改；
 * 故这里在<b>请求时</b>比对当前 token
 * 与 delegate 构造时用的 token：不同就关掉旧 delegate、用新 token 重建一个
 * （若已 start 过则顺带把新 delegate 也 start 起来）。</p>
 *
 * <p><b>已评估的行为</b>：token 轮换后本类会重建连接。
 * 触发重建的唯一时机是"刷新把一个已过期的 token 换成了新的"，此时上游 401 本来
 * 也要求重试；重建只多一次握手（SSE 多一次 GET 建流），语义等价。
 * token 未变时零开销，路径完全等同非 OAuth 传输。</p>
 */
public final class OAuthTransport implements McpTransport {

    private final boolean sse;
    private final URI url;
    private final Map<String, String> baseHeaders;
    private final Duration timeout;
    private final OAuthHandler handler;

    private McpTransport delegate;
    private String delegateToken;
    private McpContext startCtx;
    private boolean started;
    private Consumer<Throwable> connectionLostHandler;

    public OAuthTransport(boolean sse, URI url, Map<String, String> headers, Duration timeout,
                          OAuthHandler handler) {
        this.sse = sse;
        this.url = url;
        this.baseHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = timeout;
        this.handler = handler;
    }

    /** 测试可见：当前 delegate 的构造所用 token（null = 还没建过）。 */
    String delegateToken() {
        return delegateToken;
    }

    /** 测试可见：delegate 重建次数。 */
    int delegateBuilds() {
        return delegateBuilds;
    }

    private int delegateBuilds;

    @Override
    public synchronized void start(McpContext ctx) {
        started = true;
        startCtx = ctx;
        delegate(ctx).start(ctx);
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request, McpContext ctx) {
        return delegate(ctx).send(request, ctx);
    }

    @Override
    public void sendNotification(String method, Object params, McpContext ctx) {
        delegate(ctx).sendNotification(method, params, ctx);
    }

    @Override
    public void setConnectionLostHandler(Consumer<Throwable> handler) {
        this.connectionLostHandler = handler;
        synchronized (this) {
            if (delegate != null) {
                delegate.setConnectionLostHandler(handler);
            }
        }
    }

    @Override
    public String sessionId() {
        synchronized (this) {
            return delegate == null ? null : delegate.sessionId();
        }
    }

    @Override
    public synchronized void close() {
        started = false;
        if (delegate != null) {
            delegate.close();
            delegate = null;
            delegateToken = null;
        }
    }

    /**
     * 取（必要时重建）delegate。token 未变则原样返回既有 delegate。
     *
     * <p>token 缺失时 {@link OAuthHandler#getAuthorizationHeader} 抛
     * {@link OAuthAuthorizationRequiredException}——<b>正是</b> {@code oauthCall}
     * 用来触发"强制刷新一次再重试一次"的信号。</p>
     */
    private synchronized McpTransport delegate(McpContext ctx) {
        String token = handler.getAuthorizationHeader(ctx);
        if (delegate != null && token.equals(delegateToken)) {
            return delegate;
        }
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
        Map<String, String> headers = new LinkedHashMap<>(baseHeaders);
        headers.put("Authorization", token);
        McpTransport built = sse
                ? new SseTransport(url, headers, timeout)
                : new StreamableHttpTransport(url, headers, timeout);
        if (connectionLostHandler != null) {
            built.setConnectionLostHandler(connectionLostHandler);
        }
        delegate = built;
        delegateToken = token;
        delegateBuilds++;
        if (started) {
            // 已 start 过：新 delegate 必须补一次握手，否则后续 send 无会话可用
            built.start(startCtx == null ? ctx : startCtx);
        }
        return built;
    }
}
