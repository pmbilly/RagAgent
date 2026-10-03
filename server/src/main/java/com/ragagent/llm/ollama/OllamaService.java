package com.ragagent.llm.ollama;

import java.io.BufferedReader;
import com.ragagent.common.deployment.AppEnvLookup;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ollama 服务管理。
 *
 * <p>实现说明：</p>
 * <ul>
 *   <li>直接按 Ollama 的 REST 协议发请求（{@code /api/chat}、{@code /api/tags}、
 *       {@code /api/pull}、{@code /api/show}、{@code /api/create}、{@code /api/delete}、
 *       {@code /api/version}）。<b>线格式与官方客户端一致</b>：流式接口是
 *       <b>NDJSON</b>（换行分隔的 JSON）而不是 SSE，请求头 {@code Accept: application/x-ndjson}。</li>
 *   <li>心跳是 {@code HEAD /}。</li>
 *   <li>基址指向 ollama.com 时官方客户端会签 Authorization 头；本模块只服务
 *       私部署路径（与 WeKnora 一致），不实现该分支。</li>
 *   <li>{@code Embeddings} / {@code Generate} 的强类型请求体属于 embedding / rerank
 *       模块，故在这里以原始 JSON 形态提供，避免两处各定一套 DTO。</li>
 * </ul>
 *
 * <p>线程安全：{@code isAvailable} / {@code isOptional} 受一把锁保护。</p>
 */
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    /** 缺省基址。 */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /** 单行 NDJSON 的最大字节数。 */
    private static final int MAX_NDJSON_LINE_BYTES = 8 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 只保护 isAvailable 的读写。 */
    private final ReentrantLock mu = new ReentrantLock();

    private final HttpClient client;
    private final String baseUrl;
    private volatile boolean isAvailable;
    private final boolean isOptional;

    public OllamaService(String baseUrl, boolean isOptional) {
        this.baseUrl = baseUrl == null || baseUrl.isEmpty() ? DEFAULT_BASE_URL : trimTrailingSlashes(baseUrl);
        this.isOptional = isOptional;
        // 连接超时 10s，且**不设整体 Timeout**（长流式调用靠取消，不是靠 client 超时）
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 从环境变量读基址与"可选"标记。
     *
     * <p><b>每次调用返回新实例</b>（不是单例，单例与否交给上层决定）。基址取
     * {@code OLLAMA_BASE_URL}，缺省
     * {@code http://localhost:11434}；{@code OLLAMA_OPTIONAL=true} 时服务不可用也不报错。</p>
     */
    public static OllamaService getOllamaService() {
        String envUrl = AppEnvLookup.get("OLLAMA_BASE_URL");
        log.info("Ollama base URL: {}", envUrl == null ? "" : envUrl);
        String baseUrl = envUrl == null || envUrl.isEmpty() ? DEFAULT_BASE_URL : envUrl;
        boolean isOptional = "true".equals(AppEnvLookup.get("OLLAMA_OPTIONAL"));
        if (isOptional) {
            log.info("Ollama service set to optional mode");
        }
        return new OllamaService(baseUrl, isOptional);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    /**
     * {@code HEAD /} 探活。
     * 不可用时：optional 模式吞掉错误（只把 isAvailable 置 false），否则抛。
     */
    public void startService() {
        mu.lock();
        try {
            try {
                HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(uri("/"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(30))
                        .build());
                closeQuietly(resp);
                if (resp.statusCode() >= 400) {
                    throw new IllegalStateException("ollama heartbeat status " + resp.statusCode());
                }
                isAvailable = true;
            } catch (Exception e) {
                log.warn("ollama service unavailable: {}", e.toString());
                isAvailable = false;
                if (isOptional) {
                    log.info("ollama service set as optional, will continue running the application");
                    return;
                }
                throw new IllegalStateException("ollama service unavailable: " + e.getMessage(), e);
            }
        } finally {
            mu.unlock();
        }
    }

    /** 服务是否可用。 */
    public boolean isAvailable() {
        mu.lock();
        try {
            return isAvailable;
        } finally {
            mu.unlock();
        }
    }

    /** 是否 optional 模式（服务不可用也不报错）。 */
    public boolean isOptional() {
        return isOptional;
    }

    /**
     * 先探活；模型名不带 {@code ":"} 时按
     * {@code name:latest} 比对（Ollama 列表里的名字总是带 tag 的）。
     */
    public boolean isModelAvailable(String modelName) {
        startService();
        if (!isAvailable() && isOptional) {
            return false;
        }
        JsonNode listResp = getJson("/api/tags");
        if (listResp == null) {
            throw new IllegalStateException("failed to get model list: empty response");
        }
        String checkModelName = modelName != null && modelName.contains(":") ? modelName : modelName + ":latest";
        JsonNode models = listResp.get("models");
        if (models != null && models.isArray()) {
            for (JsonNode model : models) {
                if (checkModelName.equals(textOrNull(model.get("name")))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 服务不可用且 optional → 记警告后<b>静默返回</b>；
     * 模型已存在 → 直接返回；否则拉取并打进度日志。
     */
    public void pullModel(String modelName) {
        startService();
        if (!isAvailable() && isOptional) {
            log.warn("Ollama service unavailable, unable to pull model {}", modelName);
            return;
        }
        if (isModelAvailable(modelName)) {
            log.info("Model {} already exists", modelName);
            return;
        }
        try {
            streamNdjson("/api/pull", Map.of("name", modelName), progress -> {
                String status = textOrNull(progress.get("status"));
                long total = progress.path("total").asLong(0);
                long completed = progress.path("completed").asLong(0);
                if (status != null && !status.isEmpty()) {
                    if (total > 0 && completed > 0) {
                        log.info("Pull progress: {} ({}%)", status,
                                String.format(java.util.Locale.ROOT, "%.2f", (double) completed / (double) total * 100));
                    } else {
                        log.info("Pull status: {}", status);
                    }
                }
                if (total > 0 && completed == total) {
                    log.info("Model {} pull completed", modelName);
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to pull model: " + e.getMessage(), e);
        }
    }

    /**
     * 拉取模型并回调进度：{@code POST /api/pull}
     * 并把每行 NDJSON 进度原样回调给调用方。与 {@link #pullModel(String)} 的区别：
     * pullModel 固定打日志，本方法把进度交给调用方（下载任务进度条需要它）。
     *
     * <p>不先探活也不查模型是否已存在——前置检查由调用方负责。</p>
     */
    public void pullWithProgress(String modelName, ProgressCallback fn) {
        try {
            streamNdjson("/api/pull", Map.of("name", modelName), fn::onProgress);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to pull model: " + e.getMessage(), e);
        }
    }

    /**
     * 确保模型可用的完整语义（chat 路径每次调用都走它）：
     * <ol>
     *   <li>服务不可用但 optional → 直接返回（不拉取、不报错）；</li>
     *   <li>查询模型可用性出错：optional → 记警告后返回；否则向上抛；</li>
     *   <li>模型不存在 → 触发拉取。</li>
     * </ol>
     */
    public void ensureModelAvailable(String modelName) {
        if (!isAvailable() && isOptional) {
            log.warn("Ollama service unavailable, skipping ensuring model {} availability", modelName);
            return;
        }
        boolean available;
        try {
            available = isModelAvailable(modelName);
        } catch (RuntimeException e) {
            if (isOptional) {
                log.warn("Failed to check model {} availability, but Ollama is set as optional", modelName);
                return;
            }
            throw e;
        }
        if (!available) {
            pullModel(modelName);
        }
    }

    // ------------------------------------------------------------------
    // 模型管理
    // ------------------------------------------------------------------

    /** 不可用且 optional → "unavailable"。 */
    public String getVersion() {
        if (!isAvailable() && isOptional) {
            return "unavailable";
        }
        JsonNode resp = getJson("/api/version");
        if (resp == null) {
            throw new IllegalStateException("failed to get Ollama version: empty response");
        }
        String version = textOrNull(resp.get("version"));
        if (version == null) {
            throw new IllegalStateException("failed to get Ollama version: missing version field");
        }
        return version;
    }

    /** 创建模型（请求体用 {@code template} 字段而不是 modelfile）。 */
    public void createModel(String name, String modelfile) {
        try {
            streamNdjson("/api/create", Map.of("model", name, "template", modelfile == null ? "" : modelfile),
                    progress -> {
                        String status = textOrNull(progress.get("status"));
                        if (status != null && !status.isEmpty()) {
                            log.info("Model creation status: {}", status);
                        }
                    });
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to create model: " + e.getMessage(), e);
        }
    }

    /** 返回 {@code POST /api/show} 的原始响应。 */
    public JsonNode getModelInfo(String modelName) {
        JsonNode resp = postJson("/api/show", Map.of("name", modelName));
        if (resp == null) {
            throw new IllegalStateException("failed to get model information: empty response");
        }
        return resp;
    }

    /** 列出模型，只要名字。 */
    public List<String> listModels() {
        JsonNode listResp = getJson("/api/tags");
        if (listResp == null) {
            throw new IllegalStateException("failed to get model list: empty response");
        }
        List<String> names = new ArrayList<>();
        JsonNode models = listResp.get("models");
        if (models != null && models.isArray()) {
            for (JsonNode model : models) {
                String name = textOrNull(model.get("name"));
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 列出模型详情（含把原始列表打进日志）。 */
    public List<OllamaModelInfo> listModelsDetailed() {
        JsonNode listResp = getJson("/api/tags");
        if (listResp == null) {
            throw new IllegalStateException("failed to get model list: empty response");
        }
        List<OllamaModelInfo> models = new ArrayList<>();
        JsonNode raw = listResp.get("models");
        log.info("List models detailed: {}", raw == null ? "[]" : raw.toString());
        if (raw != null && raw.isArray()) {
            for (JsonNode model : raw) {
                String name = textOrNull(model.get("name"));
                String digest = textOrNull(model.get("digest"));
                OffsetDateTime modifiedAt = null;
                String modifiedAtRaw = textOrNull(model.get("modified_at"));
                if (modifiedAtRaw != null) {
                    try {
                        modifiedAt = OffsetDateTime.parse(modifiedAtRaw);
                    } catch (RuntimeException e) {
                        log.debug("unparsable model modified_at {}: {}", modifiedAtRaw, e.toString());
                    }
                }
                models.add(new OllamaModelInfo(name, model.path("size").asLong(0),
                        digest == null ? "" : digest, modifiedAt));
            }
        }
        return models;
    }

    /** 删除模型。 */
    public void deleteModel(String modelName) {
        try {
            postJson("/api/delete", Map.of("name", modelName));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to delete model: " + e.getMessage(), e);
        }
    }

    /** 合法模型名：非空且不含空格。 */
    public static boolean isValidModelName(String name) {
        return name != null && !name.isEmpty() && !name.contains(" ");
    }

    // ------------------------------------------------------------------
    // 推理
    // ------------------------------------------------------------------

    /** 每收到一行 NDJSON 就回调一次。 */
    @FunctionalInterface
    public interface ChatCallback {
        void onResponse(OllamaChatResponse response);
    }

    /** 进度回调。 */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(JsonNode progress);
    }

    /**
     * 先探活，再 {@code POST /api/chat}。
     *
     * <p>流式与否由请求体的 {@code stream} 决定；响应一律按 NDJSON 逐行回调
     * （{@code stream=false} 时服务端只会回一行）。</p>
     */
    public void chat(OllamaChatRequest request, ChatCallback fn) {
        startService();
        streamNdjson("/api/chat", request, line -> fn.onResponse(MAPPER.convertValue(line, OllamaChatResponse.class)));
    }

    /**
     * Embeddings（{@code POST /api/embed}）。
     *
     * <p>强类型 DTO 属于 embedding 模块，故这里以原始 JSON 出入
     * （线格式：{@code {"model":..., "input":...}} → {@code {"embeddings":[...]}}）。</p>
     */
    public JsonNode embeddings(JsonNode request) {
        startService();
        return postJson("/api/embed", request);
    }

    /**
     * Generate（{@code POST /api/generate}，rerank 路径在用）。
     * 与 chat 一样按 NDJSON 逐行回调。
     */
    public void generate(JsonNode request, ProgressCallback fn) {
        startService();
        streamNdjson("/api/generate", request, fn::onProgress);
    }

    // ------------------------------------------------------------------
    // HTTP 细节
    // ------------------------------------------------------------------

    /** POST 并整段读取响应 + {@code checkError}。 */
    private JsonNode postJson(String path, Object body) {
        byte[] payload = serialize(body);
        HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build());
        byte[] respBody = readAll(resp);
        checkError(resp.statusCode(), respBody);
        if (respBody.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(respBody);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** GET 并整段读取响应 + {@code checkError}。 */
    private JsonNode getJson(String path) {
        HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(uri(path))
                .header("Accept", "application/json")
                .GET()
                .build());
        byte[] respBody = readAll(resp);
        checkError(resp.statusCode(), respBody);
        if (respBody.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(respBody);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 流式读取：请求头 {@code Accept: application/x-ndjson}，
     * 响应体逐行解析；每行先看有没有 {@code error} 字段与状态码。
     *
     * <p>超长行直接抛错（宁可显式失败，也不要静默半截流）。</p>
     */
    private void streamNdjson(String path, Object body, java.util.function.Consumer<JsonNode> onLine) {
        byte[] payload = serialize(body);
        HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build());

        try (InputStream stream = resp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_NDJSON_LINE_BYTES) {
                    throw new IllegalStateException("ollama response line exceeds " + MAX_NDJSON_LINE_BYTES + " bytes");
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (IOException e) {
                    // 解析不了就按错误处理（状态码 >=400 时带状态）
                    throw statusError(resp.statusCode(), line, e);
                }
                String error = textOrNull(node.get("error"));
                if (resp.statusCode() == 401) {
                    throw new IllegalStateException("401 Unauthorized");
                }
                if (resp.statusCode() >= 400) {
                    throw statusError(resp.statusCode(), error == null ? "" : error, null);
                }
                if (error != null && !error.isEmpty()) {
                    throw new IllegalStateException(error);
                }
                onLine.accept(node);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 错误检查：>=400 时，body 能解析成 JSON 就取其中的 {@code error} 字段
     * （<b>没有该字段就是空消息，不是整段 body</b>）；解析不了才用整段 body 当消息。
     */
    private static void checkError(int statusCode, byte[] body) {
        if (statusCode < 400) {
            return;
        }
        if (statusCode == 401) {
            // 401 不含服务端消息
            throw statusError(statusCode, "", null);
        }
        String message;
        try {
            JsonNode node = MAPPER.readTree(body);
            String error = node == null ? null : textOrNull(node.get("error"));
            message = error == null ? "" : error;
        } catch (IOException e) {
            message = new String(body, StandardCharsets.UTF_8);
        }
        throw statusError(statusCode, message, null);
    }

    /**
     * 错误消息形如 {@code "404 Not Found: model not found"}。
     * JDK 的 HttpResponse 不提供 reason phrase，故这里用一张小表补常见的几个，
     * 表外只带状态码（消息文本本身的前缀，不影响调用方判断）。
     */
    private static IllegalStateException statusError(int statusCode, String message, Exception cause) {
        String status = statusCode + " " + reasonPhrase(statusCode);
        String text = message == null || message.isEmpty() ? status.strip() : status.strip() + ": " + message;
        return cause == null ? new IllegalStateException(text) : new IllegalStateException(text, cause);
    }

    private static String reasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    /** 基址的路径前缀要保留。 */
    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static byte[] readAll(HttpResponse<InputStream> resp) {
        try (InputStream body = resp.body()) {
            return body.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> resp) {
        try (InputStream body = resp.body()) {
            // 探活（HEAD）响应体本来就是空的，关掉即可
            if (body == null) {
                return;
            }
        } catch (IOException ignored) {
            // 关不掉也无所谓
        }
    }

    private static byte[] serialize(Object body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
