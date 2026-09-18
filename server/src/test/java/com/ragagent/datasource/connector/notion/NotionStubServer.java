package com.ragagent.datasource.connector.notion;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 本机 Notion API 桩服务器（对照 Go 测试的 {@code httptest.NewServer} +
 * {@code fakeNotion()}）。
 *
 * <p>绑定 {@code 127.0.0.1:0}（端口由内核分配），所以**不依赖任何真实网络**
 * （约定 §7.5 第 7 条）。用 JDK 自带的 {@code com.sun.net.httpserver}，
 * 不引新依赖。</p>
 *
 * <p>它同时是<b>请求记录器</b>：Go 侧的 {…} 断言不到"发了什么请求"，
 * 这里把 method/path/query/body/headers 全部留下来，
 * 好让分页、重试、退避这些"只有看请求才验得了"的契约能被钉住。</p>
 */
final class NotionStubServer implements AutoCloseable {

    /** 记录下来的一个请求。 */
    record Recorded(String method, String path, String query, String body,
                    Map<String, List<String>> headers) {

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? "" : entry.getValue().get(0);
                }
            }
            return "";
        }

        /** 便于断言："POST /v1/search? body={...}"。 */
        String describe() {
            return method + " " + path + (query.isEmpty() ? "" : "?" + query)
                    + (body.isEmpty() ? "" : " body=" + body);
        }
    }

    @FunctionalInterface
    interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final String BODY_ATTRIBUTE = "notion.stub.body";

    private final HttpServer server;
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private Route fallback;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    NotionStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // ⚠️ 必须显式给 executor：不设的话 JDK 的 HttpServer 会用**分派线程**
        // 跑 handler，实测表现为分派线程在 ServerImpl.Dispatcher.reRegister 里
        // 空转（CPU 100%）、客户端永远等不到响应（本模块踩过一次）。
        // 与 FakeYuque / GitLabServerStub 的既有写法一致：虚拟线程执行器。
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 登记一条**精确路径**的路由（与 Go 的 {@code ServeMux} 不同，这里不按前缀匹配）。 */
    void route(String path, Route route) {
        routes.put(path, route);
    }

    /** 登记一条 JSON 响应路由。 */
    void json(String path, String body) {
        routes.put(path, exchange -> respond(exchange, 200, body));
    }

    /** 登记一条固定状态码 + 文本的响应路由。 */
    void status(String path, int status, String body) {
        routes.put(path, exchange -> respond(exchange, status, body));
    }

    /** 默认路由（未命中精确路径时）。 */
    void fallback(Route route) {
        this.fallback = route;
    }

    int requestCount() {
        return requests.size();
    }

    Recorded lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    List<String> requestDescribes() {
        List<String> out = new ArrayList<>();
        for (Recorded r : requests) {
            out.add(r.describe());
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(exchange.getRequestMethod(), path,
                query == null ? "" : query, body,
                new LinkedHashMap<>(exchange.getRequestHeaders())));
        // ⚠️ HttpExchange 的请求体**只能读一次**：dispatch 已经读过了，
        // 路由处理器里再调 getRequestBody().readAllBytes() 只会拿到空串
        // （本模块踩过一次——分页用例因此进入死循环）。把读到的体挂成属性，
        // 路由用 {@link #body(HttpExchange)} 取。
        exchange.setAttribute(BODY_ATTRIBUTE, body);

        Route route = routes.get(path);
        if (route == null) {
            route = fallback;
        }
        if (route == null) {
            respond(exchange, 404, "{\"object\":\"error\",\"status\":404,\"message\":\"not found\"}");
            return;
        }
        route.handle(exchange);
    }

    /**
     * 取回 dispatch 已经读好的请求体（{@code getRequestBody()} 只能读一次，
     * 在路由里再读会得到空串）。
     */
    static String body(HttpExchange exchange) {
        Object value = exchange.getAttribute(BODY_ATTRIBUTE);
        return value == null ? "" : (String) value;
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 拼一段 Notion 的富文本 JSON（{@code type=text}）。 */
    static String richText(String content) {
        return "{\"type\":\"text\",\"plain_text\":\"" + escape(content)
                + "\",\"text\":{\"content\":\"" + escape(content) + "\"}}";
    }

    /** 拼一个 page / data_source 对象的 JSON。 */
    static String escape(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
