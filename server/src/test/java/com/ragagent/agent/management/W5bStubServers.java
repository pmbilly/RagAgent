package com.ragagent.agent.management;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * W5b 契约测试的双 stub：
 *
 * <ul>
 *   <li>{@link #startOllama()}：Ollama 管理 API stub——<b>固定端口 11434</b>
 *       （OllamaService bean 的缺省基址是 env OLLAMA_BASE_URL 或
 *       {@code http://localhost:11434}，测试 JVM 无该 env → 打 11434；
 *       bean 在上下文启动时创建，探活发生在请求期 → 先起 stub 再发请求即可）。
 *       HEAD / 探活、GET /api/version、GET /api/tags、POST /api/pull（NDJSON 进度）。
 *       与 scripts/stub-ollama-server.py（Go 录制用）同一份剧本。</li>
 *   <li>{@link #startUpstream()}：OpenAI 兼容上游 stub（chat/embeddings/rerank/
 *       audio transcriptions，端口随机）。与扩展后的 scripts/stub-llm-server.py
 *       同一份剧本：chat 场景按末条 user 消息里的 {@code <<SCENARIO:...>>} 标记路由；
 *       ASR 场景按请求体里的 model 字段路由；无 Authorization 的 audio 请求回 401
 *       （fillSecrets 探针）。</li>
 * </ul>
 *
 * 纪律：HttpServer 必须 {@code setExecutor}（否则并发请求挂死，§5 #8）。
 */
final class W5bStubServers {

    private W5bStubServers() {}

    private static final AtomicBoolean OLLAMA_STARTED = new AtomicBoolean(false);
    private static HttpServer upstream;
    private static int upstreamPort = -1;

    /** Ollama stub（11434；幂等——UP 段测试与重复 JVM 内只起一次）。 */
    static synchronized void startOllama() {
        if (OLLAMA_STARTED.getAndSet(true)) {
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 11434), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                if (method.equals("HEAD")) {
                    ex.sendResponseHeaders(200, -1);
                    ex.close();
                    return;
                }
                if (method.equals("GET") && path.equals("/api/version")) {
                    respond(ex, 200, "application/json", "{\"version\":\"w5b-stub\"}");
                    return;
                }
                if (method.equals("GET") && path.equals("/api/tags")) {
                    respond(ex, 200, "application/json", OLLAMA_TAGS);
                    return;
                }
                if (method.equals("POST") && path.equals("/api/pull")) {
                    readAll(ex);
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"status\":\"pulling manifest\"}\n");
                    sb.append("{\"status\":\"downloading\",\"digest\":\"sha256:w5b\",")
                            .append("\"total\":100,\"completed\":60}\n");
                    sb.append("{\"status\":\"downloading\",\"digest\":\"sha256:w5b\",")
                            .append("\"total\":100,\"completed\":100}\n");
                    sb.append("{\"status\":\"success\"}\n");
                    respond(ex, 200, "application/x-ndjson", sb.toString());
                    return;
                }
                respond(ex, 404, "application/json", "{\"error\":\"not found\"}");
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start ollama stub on 11434", e);
        }
    }

    /** graph 场景的助手回复（带围栏；含一个未知关系与仅出现在未知关系里的端点）。 */
    private static final String GRAPH_REPLY = "```json\n[\n"
            + "  {\"entity\": \"张三\", \"entity_attributes\": [\"研究员\"], \"chunks\": [\"c1\"]},\n"
            + "  {\"entity\": \"李四\", \"entity_attributes\": []},\n"
            + "  {\"entity1\": \"张三\", \"entity2\": \"李四\", \"relation\": \"Author\"},\n"
            + "  {\"entity1\": \"张三\", \"entity2\": \"王五\", \"relation\": \"Unknown\"}\n"
            + "]\n```";

    private static final String OLLAMA_TAGS = "{\"models\":[{"
            + "\"name\":\"stub-model:latest\",\"model\":\"stub-model:latest\","
            + "\"size\":4700000000,\"digest\":\"sha256:w5bdigest\","
            + "\"modified_at\":\"2026-09-01T08:00:00Z\",\"details\":{}}]}";

    /** OpenAI 兼容上游 stub（随机端口）。 */
    static synchronized int startUpstream() {
        if (upstream != null) {
            return upstreamPort;
        }
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.setExecutor(Executors.newFixedThreadPool(4));
            upstream.createContext("/", W5bStubServers::handleUpstream);
            upstream.start();
            upstreamPort = upstream.getAddress().getPort();
            return upstreamPort;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start upstream stub", e);
        }
    }

    private static void handleUpstream(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        byte[] body = readAll(ex);
        try {
            if (method.equals("POST") && path.endsWith("/chat/completions")) {
                String text = new String(body, StandardCharsets.UTF_8);
                String content;
                // 标记匹配只认 `SCENARIO:graph`：Go 的 json.Marshal 默认做 HTML 转义
                // （`<`→`\u003c`），出站体里看不到裸 `<<`——§0.-10/§0.-11 两批做过
                // 全 body 逐字节对拍，转义形态即 Go 形态。
                if (text.contains("SCENARIO:graph")) {
                    content = GRAPH_REPLY;
                } else if (text.contains("Please randomly generate a text")) {
                    // fabri-text 的 with_tag/with_no_tag 模板前缀（服务端拼好发来的）
                    content = "这是一段由 stub 生成的示例文本，用于 fabri-text 契约测试。";
                } else {
                    content = "stub-chat-reply";
                }
                respond(ex, 200, "application/json", "{\"id\":\"chatcmpl-w5b\",\"object\":\"chat.completion\","
                        + "\"created\":1735689600,\"model\":\"stub-model\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + jsonEscape(content)
                        + "\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":9,\"total_tokens\":21}}");
                return;
            }
            if (method.equals("POST") && path.endsWith("/embeddings")) {
                respond(ex, 200, "application/json", "{\"object\":\"list\",\"data\":["
                        + "{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]}],"
                        + "\"model\":\"stub-model\",\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}");
                return;
            }
            if (method.equals("POST") && path.endsWith("/rerank")) {
                respond(ex, 200, "application/json", "{\"results\":[{\"index\":0,"
                        + "\"relevance_score\":0.99}],\"model\":\"stub-model\"}");
                return;
            }
            if (method.equals("POST") && path.endsWith("/audio/transcriptions")) {
                Map<String, String> form = parseMultipart(body, contentType(ex));
                String model = form.getOrDefault("__model__", "");
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                switch (model) {
                    case "asr-401" -> respond(ex, 401, "application/json",
                            "{\"error\":{\"message\":\"invalid api key\",\"type\":\"invalid_request_error\"}}");
                    case "asr-404" -> respond(ex, 404, "application/json",
                            "{\"error\":{\"message\":\"Not Found\"}}");
                    case "asr-modelmissing" -> respond(ex, 400, "application/json",
                            "{\"error\":{\"message\":\"model stub-model not found\"}}");
                    case "asr-500text" -> respond(ex, 500, "text/plain", "boom");
                    default -> {
                        if (auth == null || auth.strip().isEmpty()
                                || auth.strip().equals("Bearer")) {
                            // fillSecrets 探针：未带 key（JDK 会把 "Bearer " 尾随空格
                            // 规范化成 "Bearer"，Go 原样 "Bearer " —— 两者同判）→ 401
                            respond(ex, 401, "application/json",
                                    "{\"error\":{\"message\":\"invalid api key\",\"type\":\"invalid_request_error\"}}");
                            return;
                        }
                        respond(ex, 200, "application/json",
                                "{\"text\":\"stub-transcript\",\"segments\":[{\"start\":0.0,\"end\":1.5,"
                                        + "\"text\":\" stub-transcript \"}]}");
                    }
                }
                return;
            }
            respond(ex, 404, "application/json", "{\"error\":\"not found\"}");
        } finally {
            ex.close();
        }
    }

    /** 提取 multipart 的 model 字段（够 stub 用即可）。 */
    private static Map<String, String> parseMultipart(byte[] body, String contentType) {
        Map<String, String> out = new HashMap<>();
        String boundary = "";
        if (contentType != null && contentType.contains("boundary=")) {
            boundary = contentType.substring(contentType.indexOf("boundary=") + 9).trim();
        }
        if (boundary.isEmpty()) {
            return out;
        }
        String text = new String(body, StandardCharsets.ISO_8859_1);
        String[] parts = text.split("--" + boundary);
        for (String part : parts) {
            int nameIdx = part.indexOf("name=\"");
            if (nameIdx < 0) {
                continue;
            }
            int nameEnd = part.indexOf('"', nameIdx + 6);
            String name = part.substring(nameIdx + 6, nameEnd);
            int blank = part.indexOf("\r\n\r\n");
            if (blank < 0) {
                continue;
            }
            String value = part.substring(blank + 4);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            }
            if (name.equals("model")) {
                out.put("__model__", value);
            }
            out.put(name, value);
        }
        return out;
    }

    private static String contentType(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("Content-Type");
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '<' || c == '>' || c == '&') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static byte[] readAll(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
