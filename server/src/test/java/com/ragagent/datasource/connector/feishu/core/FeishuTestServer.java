package com.ragagent.datasource.connector.feishu.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 本机飞书 API 桩服务器。
 *
 * <p><b>测试禁止依赖真实网络</b>（约定 §7.5 第 7 条）：绑 {@code 127.0.0.1}、端口 0
 * 由内核分配，喂与真实 API 同形的 JSON。绝不出现真实公网域名。</p>
 *
 * <h2>路由规则</h2>
 * <ul>
 *   <li>不以 {@code '/'} 结尾的模式 = <b>精确</b>匹配；</li>
 *   <li>以 {@code '/'} 结尾的模式 = <b>子树</b>匹配（前缀）；</li>
 *   <li>精确优先于子树；多条子树命中时取<b>最长</b>前缀。</li>
 * </ul>
 *
 * <p>放行 loopback 的 SSRF 白名单由 {@link FeishuTestSupport#allowLoopback()} 负责
 * （{@code SSRF_WHITELIST=127.0.0.1,::1,localhost}）。</p>
 */
public final class FeishuTestServer implements AutoCloseable {

    /** 桩上的一次请求（方法 + 路径 + 查询串），供断言"发了哪些调用"。 */
    public record Recorded(String method, String path, String query, String authorization) {

        /** 便捷：带查询串的完整路径。 */
        public String fullPath() {
            return query == null || query.isEmpty() ? path : path + "?" + query;
        }

        /** 便捷：查询参数取值（缺席回空串）。 */
        public String queryParam(String name) {
            if (query == null || query.isEmpty()) {
                return "";
            }
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                String k = i < 0 ? kv : kv.substring(0, i);
                if (k.equals(name)) {
                    return i < 0 ? "" : urlDecode(kv.substring(i + 1));
                }
            }
            return "";
        }
    }

    /** 处理一次请求；{@code body} 是已读尽的请求体（GET 为空数组）。 */
    public interface Handler {
        void handle(HttpExchange exchange, byte[] body) throws IOException;
    }

    private final HttpServer server;
    private final Map<String, Handler> exactRoutes = new LinkedHashMap<>();
    private final Map<String, Handler> subtreeRoutes = new LinkedHashMap<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final String baseUrl;

    public FeishuTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 注册一个路由（模式语义见类注释）。 */
    public void handle(String pattern, Handler handler) {
        if (pattern.endsWith("/")) {
            subtreeRoutes.put(pattern, handler);
        } else {
            exactRoutes.put(pattern, handler);
        }
    }

    /** 服务地址，如 {@code http://127.0.0.1:53121}（塞进 Config.BaseURL）。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 已收到的全部请求（按到达顺序）。 */
    public List<Recorded> requests() {
        return Collections.unmodifiableList(recorded);
    }

    /** 打到某个路径的请求次数。 */
    public int countPath(String path) {
        int n = 0;
        for (Recorded r : recorded) {
            if (r.path().equals(path)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        byte[] body;
        try {
            body = exchange.getRequestBody().readAllBytes();
        } catch (IOException e) {
            body = new byte[0];
        }
        String path = exchange.getRequestURI().getPath();
        recorded.add(new Recorded(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization")));

        Handler handler = exactRoutes.get(path);
        if (handler == null) {
            String best = null;
            for (String prefix : subtreeRoutes.keySet()) {
                if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                    best = prefix;
                }
            }
            if (best != null) {
                handler = subtreeRoutes.get(best);
            }
        }
        if (handler == null) {
            sendJson(exchange, 404, "{\"code\":-1,\"msg\":\"no stub route for " + path + "\"}");
            return;
        }
        try {
            handler.handle(exchange, body);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            sendJson(exchange, 500, "{\"code\":-1,\"msg\":\"stub error: " + e + "\"}");
        }
    }

    // ── 响应助手（一律显式关闭 exchange，避免连接悬挂） ────────────────────

    public static void sendJson(HttpExchange exchange, String body) throws IOException {
        sendJson(exchange, 200, body);
    }

    /** 发一段 JSON。{@code body} 为 null 时发空体（配合 202/204 用）。 */
    public static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 发原始字节（下载类端点）。 */
    public static void sendBytes(HttpExchange exchange, String contentType, byte[] data)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    /** 只发状态码 + 一段文本体。 */
    public static void sendStatus(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 发状态码 + 额外响应头（重试类用例需要 {@code Retry-After}）。 */
    public static void sendStatusWithHeader(HttpExchange exchange, int status, String headerName,
                                            String headerValue, String body) throws IOException {
        if (headerName != null && headerValue != null) {
            exchange.getResponseHeaders().set(headerName, headerValue);
        }
        sendStatus(exchange, status, body);
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** 从请求体 JSON 里取一个字符串字段（桩记录导出任务的 token 用）。 */
    public static String jsonField(byte[] body, String field) {
        String s = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        String needle = "\"" + field + "\":\"";
        int i = s.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int start = i + needle.length();
        int end = s.indexOf('"', start);
        return end < 0 ? "" : s.substring(start, end);
    }

    /** 构造 JSON 字符串字面量（转义引号/反斜杠），让桩里的中文与引号安全。 */
    public static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 便捷：把若干 {@code "key": value} 片段拼成一个 JSON 对象。 */
    public static String jsonObject(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(kv[i])).append(':').append(kv[i + 1]);
        }
        return sb.append('}').toString();
    }

    /** 便捷：{@code []} 空数组。 */
    public static final String EMPTY_ARRAY = "[]";

    /** 便捷：{@code {}} 空对象。 */
    public static final String EMPTY_OBJECT = "{}";
}
