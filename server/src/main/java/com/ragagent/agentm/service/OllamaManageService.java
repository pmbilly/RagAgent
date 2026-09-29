package com.ragagent.agentm.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.llm.ollama.OllamaService;

/**
 * ollama 管理端点用例：status/models/check/download/progress 与拉取任务机制。
 *
 * <p>initialization 薄层化的产物：端点注解留在 controller，方法体逐字迁移至此。</p>
 */
public final class OllamaManageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OllamaService ollamaService;
    private final OllamaDownloadTaskStore downloadTasks;

    public OllamaManageService(OllamaService ollamaService, OllamaDownloadTaskStore downloadTasks) {
        this.ollamaService = ollamaService;
        this.downloadTasks = downloadTasks;
    }

    // ══════════════ ollama 管理段 ══════════════

    /** GET /initialization/ollama/status——StartService 失败仍是 200 + available:false。 */
    public ResponseEntity<Object> ollamaStatus() {
        // 展示用基址的缺省是 host.docker.internal（与 OllamaService 的
        // localhost:11434 连接缺省刻意不同）
        String envUrl = System.getenv("OLLAMA_BASE_URL");
        String baseURL = envUrl == null || envUrl.isEmpty()
                ? "http://host.docker.internal:11434" : envUrl;
        ObjectNode data = MAPPER.createObjectNode();
        try {
            ollamaService.startService();
        } catch (RuntimeException e) {
            data.put("available", false);
            data.put("baseUrl", baseURL);
            data.put("error", e.getMessage());
            return ModelConnectivityTestService.ok(data);
        }
        String version;
        try {
            version = ollamaService.getVersion();
        } catch (RuntimeException e) {
            version = "unknown";
        }
        data.put("available", ollamaService.isAvailable());
        data.put("baseUrl", baseURL);
        data.put("version", version);
        return ModelConnectivityTestService.ok(data);
    }

    /** GET /initialization/ollama/models——ListModelsDetailed（name/size/digest/modified_at）。 */
    public ResponseEntity<Object> ollamaModels() {
        ensureOllamaStarted();
        List<com.ragagent.llm.ollama.OllamaModelInfo> models;
        try {
            models = ollamaService.listModelsDetailed();
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("获取模型列表失败: " + e.getMessage()));
        }
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode arr = data.putArray("models");
        for (var m : models) {
            ObjectNode n = arr.addObject();
            n.put("name", m.name());
            n.put("size", m.size());
            n.put("digest", m.digest());
            // Go：time.Time 经 json 反序列化保留 UTC location → marshal 仍是 Z，
            // 不做服务器本地时区转换（与 startTime 的 time.Now() 本地时区路径刻意不同）
            n.put("modifiedAt", ModelConnectivityTestService.goTimeAsIs(m.modifiedAt()));
        }
        return ModelConnectivityTestService.ok(data);
    }

    /** POST /initialization/ollama/models/check——逐模型可用性（map 按名字母序）。 */
    public ResponseEntity<Object> ollamaModelsCheck(String rawBody) {
        List<String> models = bindOllamaModelsCheck(rawBody);
        ensureOllamaStarted();
        Map<String, Boolean> sorted = new TreeMap<>();
        for (String modelName : models) {
            boolean available;
            try {
                available = ollamaService.isModelAvailable(modelName);
            } catch (RuntimeException e) {
                available = false;
            }
            sorted.put(modelName, available);
        }
        ObjectNode data = MAPPER.createObjectNode();
        ObjectNode statusMap = data.putObject("models");
        for (Map.Entry<String, Boolean> e : sorted.entrySet()) {
            statusMap.put(e.getKey(), e.getValue().booleanValue());
        }
        return ModelConnectivityTestService.ok(data);
    }

    /** POST /initialization/ollama/models/download——建任务 + 虚拟线程异步拉取。 */
    public ResponseEntity<Object> ollamaModelDownload(String rawBody) {
        String modelName = bindDownloadRequest(rawBody);
        ensureOllamaStarted();
        boolean available;
        try {
            available = ollamaService.isModelAvailable(modelName);
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("检查模型状态失败: " + e.getMessage()));
        }
        if (available) {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("modelName", modelName);
            data.putRawValue("progress", new com.fasterxml.jackson.databind.util.RawValue(GoDoubleSerializer.format(100.0)));
            data.put("status", "completed");
            data.put("message", "模型已存在");
            return ResponseEntity.ok(data);
        }
        var existing = downloadTasks.findActiveByModel(modelName);
        if (existing != null) {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("modelName", existing.modelName);
            data.putRawValue("progress", new com.fasterxml.jackson.databind.util.RawValue(GoDoubleSerializer.format(existing.progress)));
            data.put("status", existing.status);
            data.put("taskId", existing.id);
            Map<String, Object> body = new TreeMap<>();
            body.put("data", data);
            body.put("message", "模型下载任务已存在");
            body.put("success", true);
            return ResponseEntity.ok(body);
        }
        String taskId = UUID.randomUUID().toString();
        downloadTasks.create(taskId, modelName, OffsetDateTime.now());
        Thread.ofVirtual().start(() -> downloadModelAsync(taskId, modelName));
        ObjectNode data = MAPPER.createObjectNode();
        data.put("modelName", modelName);
        data.putRawValue("progress", new com.fasterxml.jackson.databind.util.RawValue(GoDoubleSerializer.format(0.0)));
        data.put("status", "pending");
        data.put("taskId", taskId);
        data.put("message", "模型下载任务已创建");
        return ResponseEntity.ok(data);
    }

    /** GET /initialization/ollama/download/progress/:taskId——DownloadTask 按 struct 序输出。 */
    public ResponseEntity<Object> downloadProgress(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("任务ID不能为空"));
        }
        var task = downloadTasks.get(taskId);
        if (task == null) {
            throw new BizException(AppError.notFound("下载任务不存在"));
        }
        return ModelConnectivityTestService.ok(taskNode(task));
    }

    /** GET /initialization/ollama/download/tasks——全量任务列表（map 序随机）。 */
    public ResponseEntity<Object> downloadTasksList() {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var task : downloadTasks.list()) {
            arr.add(taskNode(task));
        }
        return ModelConnectivityTestService.ok(arr);
    }

    private void ensureOllamaStarted() {
        if (ollamaService.isAvailable()) {
            return;
        }
        try {
            ollamaService.startService();
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Ollama服务不可用: " + e.getMessage()));
        }
    }

    static ObjectNode taskNode(OllamaDownloadTaskStore.DownloadTask task) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", task.id);
        n.put("modelName", task.modelName);
        n.put("status", task.status);
        n.putRawValue("progress", new com.fasterxml.jackson.databind.util.RawValue(GoDoubleSerializer.format(task.progress)));
        n.put("message", task.message);
        n.put("startTime", ModelConnectivityTestService.goTime(task.startTime));
        if (task.endTime != null) {
            n.put("endTime", ModelConnectivityTestService.goTime(task.endTime));
        }
        return n;
    }

    /** 异步下载与进度回写。 */
    private void downloadModelAsync(String taskId, String modelName) {
        downloadTasks.updateStatus(taskId, "downloading", 0.0, "开始下载模型", OffsetDateTime.now());
        try {
            pullModelWithProgress(modelName, (progress, message) ->
                    downloadTasks.updateStatus(taskId, "downloading", progress, message,
                            OffsetDateTime.now()));
            downloadTasks.updateStatus(taskId, "completed", 100.0, "下载完成", OffsetDateTime.now());
        } catch (RuntimeException e) {
            downloadTasks.updateStatus(taskId, "failed", 0.0, "下载失败: " + e.getMessage(),
                    OffsetDateTime.now());
        }
    }

    /** 进度回调（progress + message）。 */
    private interface ProgressListener {
        void onProgress(double progress, String message);
    }

    private void pullModelWithProgress(String modelName, ProgressListener listener) {
        ollamaService.startService();
        if (ollamaService.isModelAvailable(modelName)) {
            listener.onProgress(100.0, "模型已存在");
            return;
        }
        ollamaService.pullWithProgress(modelName, progress -> {
            double progressPercent = 0.0;
            String message = "下载中";
            long total = progress.path("total").asLong(0);
            long completed = progress.path("completed").asLong(0);
            String status = progress.path("status").asText("");
            if (total > 0 && completed > 0) {
                progressPercent = (double) completed / (double) total * 100;
                message = String.format(Locale.ROOT, "下载中: %.1f%% (%s)", progressPercent, status);
            } else if (!status.isEmpty()) {
                message = status;
            }
            listener.onProgress(progressPercent, message);
        });
    }

    private static List<String> bindOllamaModelsCheck(String rawBody) {
        JsonNode n = bindJsonObject(rawBody);
        JsonNode models = n.get("models");
        List<String> out = new ArrayList<>();
        if (models != null && models.isArray()) {
            models.forEach(m -> out.add(m.asText("")));
        }
        if (models == null || !models.isArray() || out.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'Models' Error:Field validation for 'Models' failed on the 'required' tag"));
        }
        return out;
    }

    private static String bindDownloadRequest(String rawBody) {
        JsonNode n = bindJsonObject(rawBody);
        String modelName = ModelConnectivityTestService.text(n, "modelName");
        if (modelName.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'ModelName' Error:Field validation for 'ModelName' failed on the 'required' tag"));
        }
        return modelName;
    }

    /** 绑定进匿名 struct 的公共段（EOF / 语法错 / 非对象）。 */
    static JsonNode bindJsonObject(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (n == null) {
            // Go：body "null" → 零值绑定不报错，等价空对象
            n = MAPPER.createObjectNode();
        }
        if (!n.isObject()) {
            throw new BizException(AppError.badRequest(
                    "json: cannot unmarshal " + jsonKindName(n) + " into Go value of type struct"));
        }
        return n;
    }

    static String jsonKindName(JsonNode n) {
        if (n.isArray()) {
            return "array";
        }
        if (n.isTextual()) {
            return "string";
        }
        if (n.isNumber()) {
            return "number";
        }
        if (n.isBoolean()) {
            return "bool";
        }
        return "object";
    }

}
