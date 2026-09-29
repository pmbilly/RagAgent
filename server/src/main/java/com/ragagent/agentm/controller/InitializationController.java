package com.ragagent.agentm.controller;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.service.AsrTestAudio;
import com.ragagent.agentm.service.AsrTranscriber;
import com.ragagent.agentm.service.ExtractPrompts;
import com.ragagent.agentm.service.OllamaDownloadTaskStore;
import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.service.TenantService;
import com.ragagent.chatpipeline.EntityExtraction;
import com.ragagent.chatpipeline.PipelineConfig.PromptTemplateStructured;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseResponse;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.security.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.LlmChatClients;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelService;
import com.ragagent.rerank.RerankerFactory;
import com.ragagent.rerank.RerankerConfig;
import com.ragagent.embedding.Embedder;
import com.ragagent.embedding.EmbedderConfig;
import com.ragagent.embedding.EmbedderFactory;

/**
 * initialization 路由（对照 Go internal/handler/initialization.go 全文 + 
 * routes_infra.go RegisterInitializationRoutes）。波 3 agents 批落地 config/{kbId}
 * 三条；收尾批 W5b 补齐系统级 14 条（ollama 管理 6 + 模型连通性测试 5 + 抽取 3，
 * routes_infra.go L109-125，JWT 侧 Viewer+/Admin+、API-Key 全部
 * manage_models(fullAccess)）。
 *
 * <p>守卫层次（golden 钉死顺序，不能重排）：</p>
 * <ol>
 *   <li>GET：KBAccessRead → {@code kbGuard.requireKbAccess}（缺失→404 "knowledge base
 *       not found" 信封、跨租户→403 信封——Go handler 里的「知识库不存在」在同租户路径
 *       不可达，录到的 404 全是中间件文案）。</li>
 *   <li>POST/PUT：OwnedKBOrAdminFromKbIDParam（缺失→404 守卫文案；存在但非创建者且非
 *       Admin+ → 403 纯字符串）→ KBAccessWrite → handler。</li>
 * </ol>
 *
 * <p>已知降级：PUT 的 storageBackendId 解析分支（StorageBackendResolver）未实现——
 * 本批场景全走 provider 兼容投影；POST 建模型的 Go 既有行为（model 行 tenant_id=0、
 * handler 不回填）照抄。</p>
 */
@RestController
public class InitializationController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeAccessGuard kbGuard;
    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ModelService modelService;
    private final SsrfGuard ssrfGuard;
    private final OllamaService ollamaService;
    private final OllamaDownloadTaskStore downloadTasks;
    private final AsrTranscriber asrTranscriber;
    private final ExtractPrompts extractPrompts;
    private final ConcurrencyGovernor concurrencyGovernor;
    private final com.ragagent.knowledge.client.DocReaderClient documentReader;
    private final TenantService tenantService;
    private final CryptoService cryptoService;

    public InitializationController(KnowledgeAccessGuard kbGuard, KnowledgeBaseService kbService,
            KnowledgeBaseMapper kbMapper, KnowledgeMapper knowledgeMapper,
            ModelService modelService, SsrfGuard ssrfGuard,
            OllamaService ollamaService, OllamaDownloadTaskStore downloadTasks,
            AsrTranscriber asrTranscriber, ExtractPrompts extractPrompts,
            ConcurrencyGovernor concurrencyGovernor,
            com.ragagent.knowledge.client.DocReaderClient documentReader,
            TenantService tenantService, CryptoService cryptoService) {
        this.kbGuard = kbGuard;
        this.kbService = kbService;
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.modelService = modelService;
        this.ssrfGuard = ssrfGuard;
        this.ollamaService = ollamaService;
        this.downloadTasks = downloadTasks;
        this.asrTranscriber = asrTranscriber;
        this.extractPrompts = extractPrompts;
        this.concurrencyGovernor = concurrencyGovernor;
        this.documentReader = documentReader;
        this.tenantService = tenantService;
        this.cryptoService = cryptoService;
    }

    // ══════════════ GET /initialization/config/:kbId ══════════════

    @GetMapping("/api/v1/initialization/config/{kbId}")
    public ResponseEntity<Object> getConfig(@PathVariable("kbId") String kbId) {
        kbGuard.requireKbAccess(kbId);
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        List<Model> models = new ArrayList<>();
        for (String id : List.of(orEmpty(kb.getEmbeddingModelId()), orEmpty(kb.getSummaryModelId()),
                orEmpty(kb.getVlmConfig().getModelId()))) {
            if (id.isEmpty()) {
                continue;
            }
            try {
                Model m = modelService.getModelByID(id);
                if (m != null) {
                    models.add(m);
                }
            } catch (Exception ignored) {
                // Go：Warn 后 continue
            }
        }
        Map<String, Object> body = new TreeMap<>();
        body.put("data", configResponse(models, kb, hasFiles(kbId)));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ POST /initialization/initialize/:kbId ══════════════

    @PostMapping("/api/v1/initialization/initialize/{kbId}")
    public ResponseEntity<Object> initialize(@PathVariable("kbId") String kbId,
            @RequestBody(required = false) String rawBody) {
        InitializationRequest req = bindInitializationRequest(rawBody);
        KnowledgeBase kb = kbForWrite(kbId);
        validateConfigs(req);

        List<Model> processed = new ArrayList<>();
        for (ModelDescriptor d : buildModelDescriptors(req)) {
            Model model = toModel(d);
            String existingId = findExistingModelId(kb, d.type());
            Model existing = null;
            if (!existingId.isEmpty()) {
                try {
                    existing = modelService.getModelByID(existingId);
                } catch (Exception ignored) {
                    existing = null;
                }
            }
            if (existing != null) {
                existing.setName(model.getName());
                existing.setSource(model.getSource());
                existing.setDescription(model.getDescription());
                existing.setParameters(model.getParameters());
                modelService.updateModel(existing);
                processed.add(existing);
            } else {
                modelService.createModel(model);
                processed.add(model);
            }
        }
        applyInitialization(kb, req, processed);
        saveKb(kb);

        Map<String, Object> data = new TreeMap<>();
        data.put("knowledge_base", KnowledgeBaseResponse.from(kb, kbService.retrieveDriver()));
        // Go 直接 marshal *types.Model（非 NewModelResponse：api_key 留在 parameters、无 credentials）
        data.put("models", processed.stream().map(com.ragagent.agentm.dto.InitResponses::rawModel).toList());
        Map<String, Object> body = new TreeMap<>();
        body.put("data", data);
        body.put("message", "知识库配置更新成功");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ PUT /initialization/config/:kbId ══════════════

    @PutMapping("/api/v1/initialization/config/{kbId}")
    public ResponseEntity<Object> updateConfig(@PathVariable("kbId") String kbId,
            @RequestBody(required = false) String rawBody) {
        KBModelConfigRequest req = bindKBModelConfigRequest(rawBody);
        kbGuard.requireKbAccess(kbId);
        requireOwned(kbId);
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }

        // Embedding 变更 + 已有文件 → 400（先于模型校验）
        if (!kb.getEmbeddingModelId().isEmpty() && !req.embeddingModelId().isEmpty()
                && !kb.getEmbeddingModelId().equals(req.embeddingModelId())
                && hasFiles(kbId)) {
            throw new BizException(AppError.badRequest("知识库中已有文件，无法修改Embedding模型"));
        }
        requireModel(req.llmModelId(), "LLM模型不存在");
        if (!req.embeddingModelId().isEmpty()) {
            requireModel(req.embeddingModelId(), "Embedding模型不存在");
        }

        kb.setSummaryModelId(req.llmModelId());
        if (!req.embeddingModelId().isEmpty()) {
            kb.setEmbeddingModelId(req.embeddingModelId());
        }

        // VLM / ASR 重置后再按请求回填
        kb.setVlmConfig(new KnowledgeBaseVlmConfig());
        KnowledgeBaseVlmConfig vlm = kb.getVlmConfig();
        JsonNode vlmReq = req.vlmConfig();
        if (vlmReq != null && req.multimodalEnabled() && !vlmReq.path("model_id").asText("").isEmpty()) {
            String vlmModelId = vlmReq.path("model_id").asText("");
            try {
                if (modelService.getModelByID(vlmModelId) != null) {
                    vlm.setEnabled(vlmReq.path("enabled").asBoolean(false));
                    vlm.setModelId(vlmModelId);
                }
            } catch (Exception ignored) {
                // Go：Warn "VLM model not found"
            }
        }
        if (!vlm.isEnabled()) {
            vlm.setModelId("");
        }
        kb.setAsrConfig(new KnowledgeBaseAsrConfig());
        KnowledgeBaseAsrConfig asr = kb.getAsrConfig();
        JsonNode asrReq = req.asrConfig();
        if (asrReq != null && asrReq.path("enabled").asBoolean(false)
                && !asrReq.path("model_id").asText("").isEmpty()) {
            try {
                if (modelService.getModelByID(asrReq.path("model_id").asText()) != null) {
                    asr.setEnabled(true);
                    asr.setModelId(asrReq.path("model_id").asText());
                    asr.setLanguage(asrReq.path("language").asText(""));
                }
            } catch (Exception ignored) {
                // Go：Warn
            }
        }

        // 文档分块
        var chunking = kb.getChunkingConfig();
        if (req.chunkSize() > 0) {
            chunking.setChunkSize(req.chunkSize());
        }
        if (req.chunkOverlap() >= 0) {
            chunking.setChunkOverlap(req.chunkOverlap());
        }
        if (req.separators() != null && !req.separators().isEmpty()) {
            chunking.setSeparators(req.separators());
        }
        chunking.setParserEngineRules(req.parserEngineRules());
        chunking.setEnableParentChild(req.enableParentChild());
        if (req.parentChunkSize() != null && req.parentChunkSize() > 0) {
            chunking.setParentChunkSize(req.parentChunkSize());
        }
        if (req.childChunkSize() != null && req.childChunkSize() > 0) {
            chunking.setChildChunkSize(req.childChunkSize());
        }
        if (req.strategy() != null) {
            chunking.setStrategy(req.strategy());
        }
        if (req.tokenLimit() != null) {
            chunking.setTokenLimit(req.tokenLimit());
        }
        if (req.languages() != null) {
            chunking.setLanguages(req.languages());
        }
        if (req.tableMetadataInstructions() != null) {
            chunking.setTableMetadataInstructions(req.tableMetadataInstructions().trim());
        }

        if (!req.multimodalEnabled()) {
            vlm.setModelId("");
        }
        if (vlmReq != null) {
            vlm.setDescriptionLanguage(vlmReq.path("description_language").asText("").trim());
            vlm.setCustomInstructions(vlmReq.path("custom_instructions").asText("").trim());
        }

        // 存储引擎：provider 兼容投影
        String provider = req.storageProvider() == null ? "" : req.storageProvider().trim().toLowerCase();
        if (provider.isEmpty()) {
            provider = "local";
        }
        List<String> supported = List.of("local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs");
        if (!supported.contains(provider)) {
            throw new BizException(AppError.badRequest("Storage provider is not allowed by STORAGE_ALLOW_LIST"));
        }
        kb.setStorageProvider(provider);

        // 知识图谱
        if (req.nodeExtractEnabled()) {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("enabled", true);
            extract.put("text", req.nodeExtractText());
            extract.set("tags", MAPPER.valueToTree(req.nodeExtractTags()));
            extract.set("nodes", MAPPER.valueToTree(req.nodeExtractNodes()));
            extract.set("relations", MAPPER.valueToTree(req.nodeExtractRelations()));
            extract.put("custom_instructions", req.nodeExtractCustomInstructions().trim());
            kb.setExtractConfig(extract);
        } else if (kb.getExtractConfig() != null) {
            ((ObjectNode) kb.getExtractConfig()).put("enabled", false);
        } else {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("enabled", false);
            kb.setExtractConfig(extract);
        }

        // 问题生成
        ObjectNode qg = MAPPER.createObjectNode();
        if (req.questionGenerationEnabled()) {
            int count = req.questionGenerationCount();
            if (count <= 0) {
                count = 3;
            }
            if (count > 10) {
                count = 10;
            }
            qg.put("enabled", true);
            qg.put("question_count", count);
            qg.put("custom_instructions", req.questionGenerationInstructions().trim());
        } else {
            qg.put("enabled", false);
            qg.put("custom_instructions", req.questionGenerationInstructions().trim());
        }
        kb.setQuestionGenerationConfig(qg);

        saveKb(kb);
        Map<String, Object> body = new TreeMap<>();
        body.put("message", "配置更新成功");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ W5b：ollama 管理段（对照 CheckOllamaStatus L923-1246）══════════════

    /** GET /initialization/ollama/status——StartService 失败仍是 200 + available:false。 */
    @GetMapping("/api/v1/initialization/ollama/status")
    public ResponseEntity<Object> ollamaStatus() {
        // Go：展示用基址的缺省是 host.docker.internal（与 OllamaService 的
        // localhost:11434 缺省刻意不同，照抄）
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
            return ok(data);
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
        return ok(data);
    }

    /** GET /initialization/ollama/models——ListModelsDetailed（name/size/digest/modified_at）。 */
    @GetMapping("/api/v1/initialization/ollama/models")
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
            n.put("modified_at", goTimeAsIs(m.modifiedAt()));
        }
        return ok(data);
    }

    /** POST /initialization/ollama/models/check——逐模型可用性（map 按名字母序）。 */
    @PostMapping("/api/v1/initialization/ollama/models/check")
    public ResponseEntity<Object> ollamaModelsCheck(@RequestBody(required = false) String rawBody) {
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
        return ok(data);
    }

    /** POST /initialization/ollama/models/download——建任务 + 虚拟线程异步拉取。 */
    @PostMapping("/api/v1/initialization/ollama/models/download")
    public ResponseEntity<Object> ollamaModelDownload(@RequestBody(required = false) String rawBody) {
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
            Map<String, Object> body = new TreeMap<>();
            body.put("data", data);
            body.put("message", "模型已存在");
            body.put("success", true);
            return ResponseEntity.ok(body);
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
        Map<String, Object> body = new TreeMap<>();
        body.put("data", data);
        body.put("message", "模型下载任务已创建");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** GET /initialization/ollama/download/progress/:taskId——DownloadTask 按 struct 序输出。 */
    @GetMapping("/api/v1/initialization/ollama/download/progress/{taskId}")
    public ResponseEntity<Object> downloadProgress(@PathVariable("taskId") String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("任务ID不能为空"));
        }
        var task = downloadTasks.get(taskId);
        if (task == null) {
            throw new BizException(AppError.notFound("下载任务不存在"));
        }
        return ok(taskNode(task));
    }

    /** GET /initialization/ollama/download/tasks——全量任务列表（map 序随机）。 */
    @GetMapping("/api/v1/initialization/ollama/download/tasks")
    public ResponseEntity<Object> downloadTasksList() {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var task : downloadTasks.list()) {
            arr.add(taskNode(task));
        }
        return ok(arr);
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

    private static ObjectNode taskNode(OllamaDownloadTaskStore.DownloadTask task) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", task.id);
        n.put("modelName", task.modelName);
        n.put("status", task.status);
        n.putRawValue("progress", new com.fasterxml.jackson.databind.util.RawValue(GoDoubleSerializer.format(task.progress)));
        n.put("message", task.message);
        n.put("startTime", goTime(task.startTime));
        if (task.endTime != null) {
            n.put("endTime", goTime(task.endTime));
        }
        return n;
    }

    /** 对照 downloadModelAsync + pullModelWithProgress（异步下载与进度回写）。 */
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

    /** 进度回调（progress float64 + message；对照 Go func(float64, string)）。 */
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
        String modelName = text(n, "modelName");
        if (modelName.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'ModelName' Error:Field validation for 'ModelName' failed on the 'required' tag"));
        }
        return modelName;
    }

    /** 绑定进匿名 struct 的公共段（EOF / 语法错 / 非对象）。 */
    private static JsonNode bindJsonObject(String rawBody) {
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

    private static String jsonKindName(JsonNode n) {
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

    // ══════════════ W5b：模型连通性测试段（对照 ModelTestRequest 家族 L1615-2341）══════════════

    /** POST /initialization/remote/check——chat 模块最小化连通性调用。 */
    @PostMapping("/api/v1/initialization/remote/check")
    public ResponseEntity<Object> remoteCheck(@RequestBody(required = false) String rawBody) {
        ModelTestRequest req = fillSecretsFromStoredModel(bindModelTestRequest(rawBody));
        if (req.modelName().isEmpty() || req.baseUrl().isEmpty()) {
            throw new BizException(AppError.badRequest("模型名称和Base URL不能为空"));
        }
        requireSsrf("Base URL", req.baseUrl());
        String[] creds = resolveTenantWeKnoraCloudCreds();
        if (creds == null) {
            throw new BizException(AppError.badRequest("空间信息未找到"));
        }
        Model model = buildTestModel(req, "KnowledgeQA", "remote");
        boolean available;
        String message;
        try {
            LlmChatClient chat = LlmChatClients.create(
                    ChatConfig.fromModel(model, creds[0], creds[1]), ollamaService, concurrencyGovernor);
            ChatOptions opts = new ChatOptions();
            opts.setMaxTokens(1);
            opts.setThinking(Boolean.FALSE); // for dashscope.aliyuncs qwen3-32b（Go 注释照抄）
            chat.chat(List.of(new ChatMessage("user", "test")), opts);
            available = true;
            message = "连接正常，模型可用";
        } catch (RuntimeException e) {
            // BizException.getMessage() 带 "error code: ..., error message: " 前缀，
            // 对照 Go 的 error.Error() 原文须拆包取 appError().message()。
            String raw = e instanceof BizException be ? be.appError().message() : e.getMessage();
            String errMsg = raw == null ? "" : raw;
            if (errMsg.contains("status code: 400")) {
                // 400 = 端点可达且鉴权通过，仅参数不匹配（照抄 Go 判定）
                available = true;
                message = "连接正常，模型可用";
            } else {
                available = false;
                message = classifyConnectionError(errMsg) + "：" + errMsg;
            }
        }
        return okAvailability(available, message);
    }

    /** POST /initialization/embedding/test——embed 一次 "hello" 并回报维度。 */
    @PostMapping("/api/v1/initialization/embedding/test")
    public ResponseEntity<Object> embeddingTest(@RequestBody(required = false) String rawBody) {
        ModelTestRequest r = fillSecretsFromStoredModel(bindModelTestRequest(rawBody));
        String source = r.source();
        if (source.isEmpty()) {
            source = "remote";
        }
        if (!r.baseUrl().isEmpty()) {
            requireSsrf("Base URL", r.baseUrl());
        }
        // 阿里云多模态 Embedding 模型暂不支持（照抄 Go 的早期短路）
        if ("aliyun".equalsIgnoreCase(r.provider())) {
            String lower = r.modelName().toLowerCase(Locale.ROOT);
            if (lower.contains("vision") || lower.contains("multimodal")) {
                ObjectNode data = MAPPER.createObjectNode();
                data.put("available", false);
                data.put("dimension", 0);
                data.put("message", "阿里云多模态 Embedding 模型暂不支持，请使用纯文本 Embedding 模型（如 text-embedding-v4）");
                return ok(data);
            }
        }
        String[] creds = resolveTenantWeKnoraCloudCreds();
        if (creds == null) {
            throw new BizException(AppError.badRequest("空间信息未找到"));
        }
        Model model = buildTestModel(r, "Embedding", "remote");
        EmbedderConfig config = EmbedderConfig.configFromModel(model, creds[0], creds[1]);
        Embedder emb;
        try {
            // pooler：单文本 embed 不触达批路径（Go 的 handler 注入容器 pooler，行为同）
            emb = EmbedderFactory.newEmbedder(config, null, ollamaService, concurrencyGovernor);
        } catch (RuntimeException e) {
            return embeddingResult(false, "创建Embedder失败: " + e.getMessage(), 0);
        }
        float[] vec;
        try {
            vec = emb.embed("hello");
        } catch (RuntimeException e) {
            return embeddingResult(false, "调用Embedding失败: " + e.getMessage(), 0);
        }
        return embeddingResult(true, "测试成功，向量维度=" + vec.length, vec.length);
    }

    /** POST /initialization/rerank/check——rerank 一次 ["ping"]/["pong"]。 */
    @PostMapping("/api/v1/initialization/rerank/check")
    public ResponseEntity<Object> rerankCheck(@RequestBody(required = false) String rawBody) {
        ModelTestRequest req = fillSecretsFromStoredModel(bindModelTestRequest(rawBody));
        if (req.modelName().isEmpty() || req.baseUrl().isEmpty()) {
            throw new BizException(AppError.badRequest("模型名称和Base URL不能为空"));
        }
        requireSsrf("Base URL", req.baseUrl());
        String[] creds = resolveTenantWeKnoraCloudCreds();
        if (creds == null) {
            throw new BizException(AppError.badRequest("空间信息未找到"));
        }
        Model model = buildTestModel(req, "Rerank", "remote");
        String appID = creds[0];
        String appSecret = creds[1];
        String providerName = providerValue(model);
        if ("lkeap".equals(providerName) || "volcengine".equals(providerName)) {
            appID = "";
            appSecret = decryptModelAppSecret(model.getParameters().getAppSecret());
        }
        boolean available;
        String message;
        try {
            var config = RerankerConfig.configFromModel(model, appID, appSecret);
            var reranker = RerankerFactory.newReranker(config);
            var results = reranker.rerank("ping", List.of("pong"));
            int count = results == null ? 0 : results.size();
            if (count > 0) {
                available = true;
                message = "重排功能正常，返回" + count + "个结果";
            } else {
                available = false;
                message = "重排接口连接成功，但未返回重排结果";
            }
        } catch (RuntimeException e) {
            available = false;
            message = "重排测试失败: " + e.getMessage();
        }
        return okAvailability(available, message);
    }

    /** POST /initialization/asr/check——发一段静默 WAV 验证 transcription 端点。 */
    @PostMapping("/api/v1/initialization/asr/check")
    public ResponseEntity<Object> asrCheck(@RequestBody(required = false) String rawBody) {
        ModelTestRequest req = fillSecretsFromStoredModel(bindModelTestRequest(rawBody));
        if (req.modelName().isEmpty() || req.baseUrl().isEmpty()) {
            throw new BizException(AppError.badRequest("模型名称和Base URL不能为空"));
        }
        requireSsrf("Base URL", req.baseUrl());
        Model model = buildTestModel(req, "ASR", "remote");
        var p = model.getParameters();
        var config = new AsrTranscriber.AsrConfig(p.getBaseUrl(), model.getName(), p.getApiKey(),
                model.getId(), "", p.getCustomHeaders());
        boolean available;
        String message;
        try {
            var result = asrTranscriber.transcribe(config, AsrTestAudio.WAV, "asr_test.wav");
            available = true;
            message = "ASR连接成功";
            if (!result.text().isEmpty()) {
                message = "ASR连接成功，转写结果: " + result.text();
            }
        } catch (AsrTranscriber.AsrCreateException e) {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("available", false);
            data.put("message", "创建ASR实例失败: " + e.getMessage());
            return ok(data);
        } catch (AsrTranscriber.AsrTranscribeException e) {
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (errMsg.contains("401") || errMsg.contains("Unauthorized") || errMsg.contains("authentication")) {
                available = false;
                message = "认证失败，请检查API Key：" + errMsg;
            } else if (errMsg.contains("404") || errMsg.contains("Not Found")) {
                available = false;
                message = "API端点不存在，请检查Base URL：" + errMsg;
            } else if (errMsg.contains("connection refused") || errMsg.contains("no such host")
                    || errMsg.contains("dial tcp")) {
                available = false;
                message = "无法连接到服务器，请检查Base URL：" + errMsg;
            } else if (errMsg.contains("model") && errMsg.contains("not found")) {
                available = false;
                message = "模型不存在，请检查模型名称：" + errMsg;
            } else {
                // 端点可达（非致命错误）——照抄 Go 的 available=true 分支
                available = true;
                message = "ASR端点可达（非致命错误: " + errMsg + "）";
            }
        }
        return okAvailability(available, message);
    }

    /** POST /initialization/multimodal/test——multipart 上传图片，走 DocReader 解析。 */
    @PostMapping("/api/v1/initialization/multimodal/test")
    public ResponseEntity<Object> multimodalTest(
            @RequestParam(value = "vlm_model", required = false) String vlmModel,
            @RequestParam(value = "vlm_base_url", required = false) String vlmBaseUrl,
            @RequestParam(value = "vlm_interface_type", required = false) String vlmInterfaceType,
            @RequestParam(value = "storage_type", required = false) String storageType,
            @RequestParam(value = "cos_secret_id", required = false) String cosSecretId,
            @RequestParam(value = "cos_secret_key", required = false) String cosSecretKey,
            @RequestParam(value = "cos_region", required = false) String cosRegion,
            @RequestParam(value = "cos_bucket_name", required = false) String cosBucketName,
            @RequestParam(value = "cos_app_id", required = false) String cosAppId,
            @RequestParam(value = "minio_bucket_name", required = false) String minioBucketName,
            @RequestParam(value = "chunk_size", required = false) String chunkSizeRaw,
            @RequestParam(value = "chunk_overlap", required = false) String chunkOverlapRaw,
            @RequestParam(value = "separators", required = false) String separatorsRaw,
            @RequestParam(value = "image", required = false) org.springframework.web.multipart.MultipartFile image) {
        // ollama 场景自动拼接 base url
        if ("ollama".equals(vlmInterfaceType)) {
            vlmBaseUrl = orEmpty(System.getenv("OLLAMA_BASE_URL")) + "/v1";
        }
        storageType = storageType == null ? "" : storageType.toLowerCase(Locale.ROOT);
        if (orEmpty(vlmModel).isEmpty() || orEmpty(vlmBaseUrl).isEmpty()) {
            throw new BizException(AppError.badRequest("VLM模型名称和Base URL不能为空"));
        }
        requireSsrf("VLM Base URL", vlmBaseUrl);
        switch (storageType) {
            case "cos" -> {
                if (orEmpty(cosSecretId).isEmpty() || orEmpty(cosSecretKey).isEmpty()
                        || orEmpty(cosRegion).isEmpty() || orEmpty(cosBucketName).isEmpty()
                        || orEmpty(cosAppId).isEmpty()) {
                    throw new BizException(AppError.badRequest("COS配置信息不能为空"));
                }
            }
            case "minio" -> {
                if (orEmpty(minioBucketName).isEmpty()) {
                    throw new BizException(AppError.badRequest("MinIO配置信息不能为空"));
                }
            }
            default -> throw new BizException(AppError.badRequest("无效的存储类型"));
        }
        long maxSizeMB = com.ragagent.knowledge.storage.LocalStorageService.maxFileSizeMb();
        long maxSize = maxSizeMB * 1024 * 1024;
        if (image == null) {
            throw new BizException(AppError.badRequest("获取上传图片失败"));
        }
        String contentType = image.getContentType() == null ? "" : image.getContentType();
        if (!contentType.startsWith("image/")) {
            throw new BizException(AppError.badRequest("只允许上传图片文件"));
        }
        if (image.getSize() > maxSize) {
            throw new BizException(AppError.badRequest("图片文件大小不能超过" + maxSizeMB + "MB"));
        }
        int chunkSize;
        try {
            chunkSize = Integer.parseInt(orEmpty(chunkSizeRaw));
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest("Failed to parse chunk size"));
        }
        if (chunkSize < 100 || chunkSize > 10000) {
            chunkSize = 1000;
        }
        int chunkOverlap;
        try {
            chunkOverlap = Integer.parseInt(orEmpty(chunkOverlapRaw));
        } catch (NumberFormatException e) {
            throw new BizException(AppError.badRequest("Failed to parse chunk overlap"));
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = 200;
        }
        List<String> separators = new ArrayList<>(List.of("\n\n", "\n", "。", "！", "？", ";", "；"));
        if (separatorsRaw != null && !separatorsRaw.isEmpty()) {
            try {
                JsonNode sepNode = MAPPER.readTree(separatorsRaw);
                if (sepNode.isArray()) {
                    List<String> parsed = new ArrayList<>();
                    sepNode.forEach(s -> parsed.add(s.asText()));
                    separators = parsed;
                } else {
                    separators = List.of("\n\n", "\n", "。", "！", "？", ";", "；");
                }
            } catch (Exception e) {
                separators = List.of("\n\n", "\n", "。", "！", "？", ";", "；");
            }
        }
        byte[] imageContent;
        try {
            imageContent = image.getBytes();
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("读取图片文件失败"));
        }
        long start = System.currentTimeMillis();
        String error;
        try {
            testMultimodalWithDocReader(imageContent, orEmpty(image.getOriginalFilename()),
                    separators);
            error = null;
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? "" : e.getMessage();
        }
        long processingTime = System.currentTimeMillis() - start;
        // gin.H 字母序：message < processing_time < success / caption < ocr <
        // processing_time < success（ObjectNode 保插入序，必须按字母序插入）
        ObjectNode data = MAPPER.createObjectNode();
        if (error != null) {
            data.put("message", error);
            data.put("processing_time", processingTime);
            data.put("success", false);
        } else {
            data.put("caption", "");
            data.put("ocr", "");
            data.put("processing_time", processingTime);
            data.put("success", true);
        }
        return ok(data);
    }

    /** 对照 testMultimodalWithDocReader：ReadRequest(FileContent/FileName/FileType)。 */
    private void testMultimodalWithDocReader(byte[] imageContent, String filename,
            List<String> separators) {
        String fileExt = "";
        int idx = filename.lastIndexOf('.');
        if (idx != -1) {
            fileExt = filename.substring(idx + 1).toLowerCase(Locale.ROOT);
        }
        try {
            documentReader.read(imageContent, filename, fileExt, null, null);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.startsWith("docreader parse error: ")) {
                throw new IllegalStateException("DocReader服务返回错误: "
                        + msg.substring("docreader parse error: ".length()));
            }
            throw new IllegalStateException("调用DocReader服务失败: " + msg);
        } catch (Exception e) {
            throw new IllegalStateException("调用DocReader服务失败: " + e.getMessage());
        }
    }

    /** 非致命 multipart 解析错误（对照 Go "表单参数解析失败"）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<Object> multipartParseFailure() {
        throw new BizException(AppError.badRequest("表单参数解析失败"));
    }

    // ══════════════ W5b：抽取段（对照 ExtractTextRelations / FabriTag / FabriText L2383-2605）══════════════

    /** POST /initialization/extract/text-relation——LLM 驱动的实体关系抽取。 */
    @PostMapping("/api/v1/initialization/extract/text-relation")
    public ResponseEntity<Object> extractTextRelations(@RequestBody(required = false) String rawBody) {
        JsonNode n;
        try {
            n = bindJsonObject(rawBody);
        } catch (BizException e) {
            // Go：bind 失败统一是这句固定文案（不是 err.Error()）
            throw new BizException(AppError.badRequest("文本关系提取请求参数错误"));
        }
        String t = text(n, "text");
        List<String> tags = toStringList(n.get("tags"));
        String modelId = text(n, "model_id");
        boolean tagsInvalid = n.get("tags") == null || !n.get("tags").isArray() || tags.isEmpty();
        if (t.isEmpty() || tagsInvalid || modelId.isEmpty()) {
            // Go 的 binding:required 三连；text/tags 缺失时同样先落 bind 错误文案
            throw new BizException(AppError.badRequest("文本关系提取请求参数错误"));
        }
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 0) {
            throw new BizException(AppError.badRequest("文本内容不能为空"));
        }
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5000) {
            throw new BizException(AppError.badRequest("文本内容长度不能超过5000字符"));
        }
        if (tags.isEmpty()) {
            throw new BizException(AppError.badRequest("至少需要选择一个关系标签"));
        }
        LlmChatClient chatModel = getChatModelOr400(modelId);
        PromptTemplateStructured cfg = extractPrompts.extractGraph();
        PromptTemplateStructured template = new PromptTemplateStructured();
        template.setDescription(cfg.getDescription());
        template.setTags(tags);
        template.setExamples(cfg.getExamples());
        EntityExtraction.Extractor extractor = new EntityExtraction.Extractor(chatModel, template);
        EntityExtraction.EntityGraph graph;
        try {
            graph = extractor.extract(t);
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("文本关系提取失败: " + e.getMessage()));
        }
        removeUnknownRelation(graph, tags);
        ObjectNode data = MAPPER.createObjectNode();
        if (graph.node.isEmpty()) {
            data.putNull("nodes");
        } else {
            ArrayNode nodes = data.putArray("nodes");
            for (var node : graph.node) {
                ObjectNode on = nodes.addObject();
                if (!node.getName().isEmpty()) {
                    on.put("name", node.getName());
                }
                if (node.getChunks() != null && !node.getChunks().isEmpty()) {
                    var chunks = on.putArray("chunks");
                    node.getChunks().forEach(chunks::add);
                }
                if (node.getAttributes() != null && !node.getAttributes().isEmpty()) {
                    var attrs = on.putArray("attributes");
                    node.getAttributes().forEach(attrs::add);
                }
            }
        }
        // Go RemoveUnknownRelation 用 make(...,0) 重建 → relations 恒非 null
        ArrayNode relations = data.putArray("relations");
        for (var rel : graph.relation) {
            ObjectNode on = relations.addObject();
            if (!rel.node1().isEmpty()) {
                on.put("node1", rel.node1());
            }
            if (!rel.node2().isEmpty()) {
                on.put("node2", rel.node2());
            }
            if (!rel.type().isEmpty()) {
                on.put("type", rel.type());
            }
        }
        return ok(data);
    }

    /** POST /initialization/extract/fabri-tag——随机标签组（无 LLM 调用）。 */
    @PostMapping("/api/v1/initialization/extract/fabri-tag")
    public ResponseEntity<Object> fabriTag() {
        List<String> tagRandom = randomSelect(TAG_OPTIONS,
                java.util.concurrent.ThreadLocalRandom.current().nextInt(TAG_OPTIONS.size() - 1) + 1);
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode tags = data.putArray("tags");
        tagRandom.forEach(tags::add);
        return ok(data);
    }

    /** POST /initialization/extract/fabri-text——按标签生成示例文本（LLM 驱动）。 */
    @PostMapping("/api/v1/initialization/extract/fabri-text")
    public ResponseEntity<Object> fabriText(@RequestBody(required = false) String rawBody) {
        JsonNode n;
        try {
            n = bindJsonObject(rawBody);
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("invalid fabri text request parameters"));
        }
        List<String> tags = toStringList(n.get("tags"));
        String modelId = text(n, "model_id");
        if (modelId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid fabri text request parameters"));
        }
        LlmChatClient chatModel = getChatModelOr400(modelId);
        String content = extractPrompts.fabriText().withNoTag();
        if (!tags.isEmpty()) {
            String tagStr;
            try {
                tagStr = MAPPER.writeValueAsString(tags);
            } catch (Exception e) {
                tagStr = "[]";
            }
            content = String.format(extractPrompts.fabriText().withTag(), tagStr);
        }
        ChatOptions opts = new ChatOptions();
        opts.setTemperature(0.3);
        opts.setMaxTokens(4096);
        opts.setThinking(Boolean.FALSE);
        String result;
        try {
            result = chatModel.chat(List.of(new ChatMessage("user", content)), opts).getContent();
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("failed to generate fabri text: " + e.getMessage()));
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.put("text", result);
        return ok(data);
    }

    /** 对照 RandomSelect：洗牌后取前 n（n 钳到上限）。 */
    private static List<String> randomSelect(List<String> strs, int n) {
        if (n <= 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(strs);
        java.util.Collections.shuffle(result);
        if (n > strs.size()) {
            n = strs.size();
        }
        return new ArrayList<>(result.subList(0, n));
    }

    /** 对照 tagOptions。 */
    private static final List<String> TAG_OPTIONS = List.of(
            "Content", "Culture", "Person", "Event", "Time", "Location",
            "Work", "Author", "Relation", "Attribute");

    /** 对照 Extractor.RemoveUnknownRelation：过滤不在请求 tags 内的关系。 */
    private static void removeUnknownRelation(EntityExtraction.EntityGraph graph, List<String> tags) {
        java.util.Set<String> known = new java.util.HashSet<>(tags);
        List<com.ragagent.chatpipeline.ChatManage.GraphRelation> kept = new ArrayList<>();
        for (var relation : graph.relation) {
            if (known.contains(relation.type())) {
                kept.add(relation);
            }
        }
        graph.relation = kept;
    }

    // ══════════════ W5b：ModelTestRequest 绑定与共用件 ══════════════

    private record ModelTestRequest(String source, String modelName, String baseUrl, String apiKey,
            String provider, String interfaceType, int dimension, boolean supportsDimensionOverride,
            Map<String, String> customHeaders, Map<String, String> extraConfig, String appSecret,
            String modelId) {}

    private static ModelTestRequest bindModelTestRequest(String rawBody) {
        JsonNode n = bindJsonObject(rawBody);
        String modelName = text(n, "modelName");
        if (modelName.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'ModelTestRequest.ModelName' Error:Field validation for 'ModelName' "
                            + "failed on the 'required' tag"));
        }
        return new ModelTestRequest(
                text(n, "source"),
                modelName,
                text(n, "baseUrl"),
                text(n, "apiKey"),
                text(n, "provider"),
                text(n, "interfaceType"),
                n.path("dimension").asInt(0),
                n.path("supportsDimensionOverride").asBoolean(false),
                toStringMap(n.get("customHeaders")),
                toStringMap(n.get("extraConfig")),
                text(n, "appSecret"),
                text(n, "modelId"));
    }

    /**
     * 对照 fillSecretsFromStoredModel：modelId 命中的存量模型补齐空密钥与 extraConfig
     * （Go 就地改写 *req；record 不可变 → 返回替换值，调用方重接）。
     */
    private ModelTestRequest fillSecretsFromStoredModel(ModelTestRequest req) {
        if (req == null || req.modelId().isEmpty()) {
            return req;
        }
        if (!req.apiKey().isEmpty() && !req.appSecret().isEmpty() && req.extraConfig() != null) {
            return req;
        }
        Model stored;
        try {
            stored = modelService.getModelByID(req.modelId());
        } catch (RuntimeException e) {
            return req;
        }
        if (stored == null || stored.getParameters() == null) {
            return req;
        }
        var p = stored.getParameters();
        String apiKey = req.apiKey().isEmpty() ? orEmpty(p.getApiKey()) : req.apiKey();
        String appSecret = req.appSecret().isEmpty() ? orEmpty(p.getAppSecret()) : req.appSecret();
        Map<String, String> extra = req.extraConfig() != null ? req.extraConfig() : p.getExtraConfig();
        return new ModelTestRequest(req.source(), req.modelName(), req.baseUrl(), apiKey,
                req.provider(), req.interfaceType(), req.dimension(), req.supportsDimensionOverride(),
                req.customHeaders(), extra, appSecret, req.modelId());
    }

    /** 对照 buildTestModel：测试请求 → 临时 Model（不落库）。 */
    private static Model buildTestModel(ModelTestRequest req, String modelType, String defaultSource) {
        String source = req.source() == null ? "" : req.source().toLowerCase(Locale.ROOT);
        if (source.isEmpty()) {
            source = defaultSource;
        }
        Model m = new Model();
        m.setName(req.modelName());
        m.setType(modelType);
        m.setSource(source);
        ModelParameters p = new ModelParameters();
        p.setBaseUrl(req.baseUrl());
        p.setApiKey(req.apiKey());
        p.setAppSecret(req.appSecret());
        p.setProvider(req.provider());
        p.setInterfaceType(req.interfaceType());
        p.setExtraConfig(req.extraConfig());
        p.setCustomHeaders(req.customHeaders());
        p.getEmbeddingParameters().setDimension(req.dimension());
        p.getEmbeddingParameters().setTruncatePromptTokens(256);
        p.getEmbeddingParameters().setSupportsDimensionOverride(req.supportsDimensionOverride());
        m.setParameters(p);
        return m;
    }

    /** 对照 classifyConnectionError：错误串 → 中文短提示。 */
    private static String classifyConnectionError(String errMsg) {
        if (errMsg.contains("401") || errMsg.contains("unauthorized")) {
            return "认证失败，请检查API Key";
        }
        if (errMsg.contains("403") || errMsg.contains("forbidden")) {
            return "权限不足，请检查API Key权限";
        }
        if (errMsg.contains("404") || errMsg.contains("not found")) {
            return "API端点不存在，请检查Base URL";
        }
        if (errMsg.contains("timeout") || errMsg.contains("context deadline exceeded")) {
            return "连接超时，请检查网络连接";
        }
        if (errMsg.contains("connection refused") || errMsg.contains("no such host")
                || errMsg.contains("dial tcp")) {
            return "无法连接到服务器，请检查Base URL";
        }
        return "连接失败";
    }

    /** 对照 resolveTenantWeKnoraCloudCreds：null = "!ok"（空间信息未找到）。 */
    private String[] resolveTenantWeKnoraCloudCreds() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            return null;
        }
        var tenant = tenantService.getTenantById(tid);
        JsonNode creds = tenant == null || tenant.getCredentials() == null
                ? null : tenant.getCredentials().get("weknoracloud");
        if (creds == null) {
            return new String[] {"", ""};
        }
        String appId = creds.path("app_id").asText("");
        var decrypted = cryptoService.decryptStoredSecretLenient(creds.path("app_secret").asText(""));
        String appSecret = decrypted.ok() ? decrypted.plaintext() : "";
        if (appId.isEmpty() || appSecret.isEmpty()) {
            return new String[] {"", ""};
        }
        return new String[] {appId, appSecret};
    }

    /** 对照 modelService.GetChatModel（无状态闸门版 + WeKnoraCloud 凭证解析）。 */
    private LlmChatClient getChatModelOr400(String modelId) {
        try {
            return getChatModel(modelId);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest("获取模型失败: " + e.getMessage()));
        }
    }

    private LlmChatClient getChatModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            throw new IllegalStateException("model ID cannot be empty");
        }
        long tid = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        Model model = modelService.getByIdVisible(tid, modelId);
        if (model == null) {
            throw new IllegalStateException("model not found");
        }
        var p = model.getParameters();
        String appID = p == null ? "" : orEmpty(p.getAppId());
        String appSecret = p == null ? "" : decryptModelAppSecret(p.getAppSecret());
        String provider = p == null ? "" : orEmpty(p.getProvider());
        if ("weknoracloud".equals(provider) && (appID.isEmpty() || appSecret.isEmpty())) {
            String[] tenantCreds = resolveTenantWeKnoraCloudCreds();
            if (tenantCreds != null) {
                if (appID.isEmpty()) {
                    appID = tenantCreds[0];
                }
                if (appSecret.isEmpty()) {
                    appSecret = tenantCreds[1];
                }
            }
        }
        return LlmChatClients.create(ChatConfig.fromModel(model, appID, appSecret),
                ollamaService, concurrencyGovernor);
    }

    /** 对照 handler.decryptModelAppSecret：宽容解密（失败原样返回）。 */
    private String decryptModelAppSecret(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        try {
            return cryptoService.decryptAESGCM(encrypted, cryptoService.getAESKey());
        } catch (RuntimeException e) {
            return encrypted;
        }
    }

    private static String providerValue(Model model) {
        var p = model.getParameters();
        String value = p == null ? "" : orEmpty(p.getProvider());
        var name = com.ragagent.llm.provider.ProviderName.fromValue(value);
        if (name == null) {
            name = com.ragagent.llm.provider.ProviderRegistry.detectProvider(
                    p == null ? "" : orEmpty(p.getBaseUrl()));
        }
        return name == null ? "" : name.value();
    }

    private void requireSsrf(String label, String url) {
        try {
            ssrfGuard.validateURLForSSRF(url);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(ssrfGuard.formatSSRFError(label, url, e)));
        }
    }

    private static ResponseEntity<Object> okAvailability(boolean available, String message) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("available", available);
        data.put("message", message);
        return ok(data);
    }

    private static ResponseEntity<Object> embeddingResult(boolean available, String message, int dimension) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("available", available);
        data.put("dimension", dimension);
        data.put("message", message);
        return ok(data);
    }

    private static ResponseEntity<Object> ok(Object data) {
        Map<String, Object> body = new TreeMap<>();
        body.put("data", data);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** Go time.Time 的原 offset 输出（JSON 反序列化来的时间不改时区，照 time.Time marshal 语义）。 */
    private static String goTimeAsIs(OffsetDateTime value) {
        return value == null ? "0001-01-01T00:00:00Z"
                : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** Go time.Time 的 RFC3339Nano + 服务器本地时区（同 GoTimeSerializer 逻辑）。 */
    private static String goTime(OffsetDateTime value) {
        if (value == null) {
            return "0001-01-01T00:00:00Z";
        }
        return value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static Map<String, String> toStringMap(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        var fields = n.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText(""));
        }
        return out;
    }

    // ══════════════ 绑定 ══════════════

    private record KBModelConfigRequest(String llmModelId, String embeddingModelId,
            JsonNode vlmConfig, JsonNode asrConfig, int chunkSize, int chunkOverlap,
            List<String> separators, List<com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule>
            parserEngineRules, boolean enableParentChild, Integer parentChunkSize,
            Integer childChunkSize, String strategy, Integer tokenLimit, List<String> languages,
            String tableMetadataInstructions, boolean multimodalEnabled, String storageProvider,
            boolean nodeExtractEnabled, String nodeExtractText, List<String> nodeExtractTags,
            List<Object> nodeExtractNodes, List<Object> nodeExtractRelations,
            String nodeExtractCustomInstructions, boolean questionGenerationEnabled,
            int questionGenerationCount, String questionGenerationInstructions) {}

    private static KBModelConfigRequest bindKBModelConfigRequest(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    com.ragagent.common.web.GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (n == null || !n.isObject()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        String llmModelId = text(n, "llmModelId");
        if (llmModelId.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'KBModelConfigRequest.LLMModelID' Error:Field validation for "
                            + "'LLMModelID' failed on the 'required' tag"));
        }
        JsonNode ds = n.get("documentSplitting");
        JsonNode ne = n.get("nodeExtract");
        JsonNode qg = n.get("questionGeneration");
        List<String> seps = new ArrayList<>();
        if (ds != null && ds.get("separators") != null && ds.get("separators").isArray()) {
            ds.get("separators").forEach(s -> seps.add(s.asText()));
        }
        List<com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule> rules = new ArrayList<>();
        if (ds != null && ds.get("parserEngineRules") != null && ds.get("parserEngineRules").isArray()) {
            for (JsonNode r : ds.get("parserEngineRules")) {
                com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule rule =
                        new com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig.ParserEngineRule();
                rule.setFileTypes(toStringList(r.get("file_types")));
                rule.setEngine(r.path("engine").asText(""));
                rule.setXlsxFirstRowAsHeader(r.path("xlsx_first_row_as_header").asBoolean(false));
                rules.add(rule);
            }
        }
        return new KBModelConfigRequest(
                llmModelId,
                text(n, "embeddingModelId"),
                n.get("vlm_config"),
                n.get("asr_config"),
                ds == null ? 0 : ds.path("chunkSize").asInt(0),
                ds == null ? 0 : ds.path("chunkOverlap").asInt(0),
                seps,
                rules,
                ds != null && ds.path("enableParentChild").asBoolean(false),
                ds != null && ds.hasNonNull("parentChunkSize") ? ds.path("parentChunkSize").asInt() : null,
                ds != null && ds.hasNonNull("childChunkSize") ? ds.path("childChunkSize").asInt() : null,
                ds != null && ds.hasNonNull("strategy") ? ds.path("strategy").asText() : null,
                ds != null && ds.hasNonNull("tokenLimit") ? ds.path("tokenLimit").asInt() : null,
                ds != null && ds.hasNonNull("languages") ? toStringList(ds.get("languages")) : null,
                ds != null && ds.hasNonNull("tableMetadataInstructions")
                        ? ds.path("tableMetadataInstructions").asText() : null,
                n.path("multimodal").path("enabled").asBoolean(false),
                text(n, "storageProvider"),
                ne != null && ne.path("enabled").asBoolean(false),
                ne == null ? "" : ne.path("text").asText(""),
                ne != null ? toStringList(ne.get("tags")) : List.of(),
                ne != null && ne.get("nodes") != null ? toList(ne.get("nodes")) : List.of(),
                ne != null && ne.get("relations") != null ? toList(ne.get("relations")) : List.of(),
                ne == null ? "" : ne.path("customInstructions").asText(""),
                qg != null && qg.path("enabled").asBoolean(false),
                qg == null ? 0 : qg.path("questionCount").asInt(0),
                qg == null ? "" : qg.path("customInstructions").asText(""));
    }

    private record InitializationRequest(String llmSource, String llmModelName, String llmBaseUrl,
            String llmApiKey, String embSource, String embModelName, String embBaseUrl,
            String embApiKey, int embDimension, boolean rerankEnabled, String rerankModelName,
            String rerankBaseUrl, String rerankApiKey, boolean multimodalEnabled,
            JsonNode multimodal, int chunkSize, int chunkOverlap, List<String> separators,
            boolean nodeExtractEnabled, String nodeExtractText, List<String> nodeExtractTags,
            List<Object> nodeExtractNodes, List<Object> nodeExtractRelations) {}

    private static InitializationRequest bindInitializationRequest(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest(
                    com.ragagent.common.web.GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (n == null || !n.isObject()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        JsonNode llm = n.get("llm");
        JsonNode emb = n.get("embedding");
        JsonNode ds = n.get("documentSplitting");
        // gin binding 按 struct 字段序报第一个错误
        if (llm == null || llm.path("source").asText("").isEmpty()) {
            throw required("InitializationRequest.LLM.Source", "Source");
        }
        if (llm.path("modelName").asText("").isEmpty()) {
            throw required("InitializationRequest.LLM.ModelName", "ModelName");
        }
        if (emb == null || emb.path("source").asText("").isEmpty()) {
            throw required("InitializationRequest.Embedding.Source", "Source");
        }
        if (emb.path("modelName").asText("").isEmpty()) {
            throw required("InitializationRequest.Embedding.ModelName", "ModelName");
        }
        int chunkSize = 0;
        int chunkOverlap = 0;
        List<String> seps = new ArrayList<>();
        if (ds == null) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting' Error:Field validation for "
                            + "'DocumentSplitting' failed on the 'required' tag"));
        }
        chunkSize = ds.path("chunkSize").asInt(0);
        chunkOverlap = ds.path("chunkOverlap").asInt(0);
        if (ds.get("separators") != null && ds.get("separators").isArray()) {
            ds.get("separators").forEach(s -> seps.add(s.asText()));
        }
        if (chunkSize < 100) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting.ChunkSize' "
                            + "Error:Field validation for 'ChunkSize' failed on the 'min' tag"));
        }
        if (chunkSize > 10000) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting.ChunkSize' "
                            + "Error:Field validation for 'ChunkSize' failed on the 'max' tag"));
        }
        if (seps.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'InitializationRequest.DocumentSplitting.Separators' "
                            + "Error:Field validation for 'Separators' failed on the 'min' tag"));
        }
        JsonNode mm = n.get("multimodal");
        JsonNode ne = n.get("nodeExtract");
        return new InitializationRequest(
                llm.path("source").asText(""), llm.path("modelName").asText(""),
                llm.path("baseUrl").asText(""), llm.path("apiKey").asText(""),
                emb.path("source").asText(""), emb.path("modelName").asText(""),
                emb.path("baseUrl").asText(""), emb.path("apiKey").asText(""),
                emb.path("dimension").asInt(0),
                n.path("rerank").path("enabled").asBoolean(false),
                n.path("rerank").path("modelName").asText(""),
                n.path("rerank").path("baseUrl").asText(""),
                n.path("rerank").path("apiKey").asText(""),
                mm != null && mm.path("enabled").asBoolean(false),
                mm,
                chunkSize, chunkOverlap, seps,
                ne != null && ne.path("enabled").asBoolean(false),
                ne == null ? "" : ne.path("text").asText(""),
                ne != null ? toStringList(ne.get("tags")) : List.of(),
                ne != null && ne.get("nodes") != null ? toList(ne.get("nodes")) : List.of(),
                ne != null && ne.get("relations") != null ? toList(ne.get("relations")) : List.of());
    }

    private static BizException required(String structField, String field) {
        return new BizException(AppError.badRequest("Key: '" + structField
                + "' Error:Field validation for '" + field + "' failed on the 'required' tag"));
    }

    // ══════════════ 守卫与校验 ══════════════

    private KnowledgeBase kbForWrite(String kbId) {
        kbGuard.requireKbAccess(kbId);
        requireOwned(kbId);
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        return kb;
    }

    private void requireOwned(String kbId) {
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        String role = TenantContext.currentRole();
        boolean admin = TenantRole.fromString(role == null ? "" : role)
                .hasPermission(TenantRole.ADMIN);
        String uid = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        String creator = kb.getCreatorId() == null ? "" : kb.getCreatorId();
        if (!admin && (creator.isEmpty() || !creator.equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    private void validateConfigs(InitializationRequest req) {
        List<String[]> urls = new ArrayList<>();
        urls.add(new String[] {"LLM BaseURL", req.llmBaseUrl()});
        urls.add(new String[] {"Embedding BaseURL", req.embBaseUrl()});
        urls.add(new String[] {"Rerank BaseURL", req.rerankBaseUrl()});
        if (req.multimodal() != null && req.multimodal().get("vlm") != null) {
            urls.add(new String[] {"VLM BaseURL", req.multimodal().path("vlm").path("baseUrl").asText("")});
        }
        for (String[] u : urls) {
            if (!u[1].isEmpty()) {
                try {
                    ssrfGuard.validateURLForSSRF(u[1]);
                } catch (Exception e) {
                    throw new BizException(AppError.badRequest(
                            ssrfGuard.formatSSRFError(u[0], u[1], e)));
                }
            }
        }
        if (req.multimodalEnabled()) {
            JsonNode vlm = req.multimodal() == null ? null : req.multimodal().get("vlm");
            if (vlm == null) {
                throw new BizException(AppError.badRequest("启用多模态时需要配置VLM信息"));
            }
            String modelName = vlm.path("modelName").asText("");
            String baseUrl = vlm.path("baseUrl").asText("");
            if ("ollama".equals(vlm.path("interfaceType").asText(""))) {
                String ollama = System.getenv("OLLAMA_BASE_URL");
                baseUrl = (ollama == null ? "" : ollama) + "/v1";
            }
            if (modelName.isEmpty() || baseUrl.isEmpty()) {
                throw new BizException(AppError.badRequest("VLM配置不完整"));
            }
        }
        if (req.rerankEnabled() && (req.rerankModelName().isEmpty() || req.rerankBaseUrl().isEmpty())) {
            throw new BizException(AppError.badRequest("Rerank配置不完整"));
        }
        if (req.nodeExtractEnabled()) {
            String neo4j = System.getenv("NEO4J_ENABLE");
            if (!"true".equalsIgnoreCase(neo4j == null ? "" : neo4j)) {
                throw new BizException(AppError.badRequest("请正确配置环境变量NEO4J_ENABLE"));
            }
            if (req.nodeExtractText().isEmpty() || req.nodeExtractTags().isEmpty()) {
                throw new BizException(AppError.badRequest("Node Extractor配置不完整"));
            }
            if (req.nodeExtractNodes().isEmpty() || req.nodeExtractRelations().isEmpty()) {
                throw new BizException(AppError.badRequest("请先提取实体和关系"));
            }
        }
    }

    // ══════════════ 模型处理 ══════════════

    private record ModelDescriptor(String type, String name, String source, String description,
            String baseUrl, String apiKey, int dimension, String interfaceType) {}

    private List<ModelDescriptor> buildModelDescriptors(InitializationRequest req) {
        List<ModelDescriptor> list = new ArrayList<>();
        list.add(new ModelDescriptor("KnowledgeQA", req.llmModelName(), req.llmSource(),
                "LLM Model for Knowledge QA", req.llmBaseUrl(), req.llmApiKey(), 0, ""));
        list.add(new ModelDescriptor("Embedding", req.embModelName(), req.embSource(),
                "Embedding Model", req.embBaseUrl(), req.embApiKey(), req.embDimension(), ""));
        if (req.rerankEnabled()) {
            list.add(new ModelDescriptor("Rerank", req.rerankModelName(), "remote",
                    "Rerank Model", req.rerankBaseUrl(), req.rerankApiKey(), 0, ""));
        }
        if (req.multimodalEnabled() && req.multimodal() != null && req.multimodal().get("vlm") != null) {
            JsonNode vlm = req.multimodal().get("vlm");
            list.add(new ModelDescriptor("VLLM", vlm.path("modelName").asText(""), "remote",
                    "VLM Model", vlm.path("baseUrl").asText(""), vlm.path("apiKey").asText(""),
                    0, vlm.path("interfaceType").asText("")));
        }
        return list;
    }

    private Model toModel(ModelDescriptor d) {
        Model m = new Model();
        m.setType(d.type());
        m.setName(d.name());
        m.setSource(d.source());
        m.setDescription(d.description());
        ModelParameters p = new ModelParameters();
        p.setBaseUrl(d.baseUrl());
        p.setApiKey(d.apiKey());
        p.setInterfaceType(d.interfaceType());
        if ("Embedding".equals(d.type())) {
            p.getEmbeddingParameters().setDimension(d.dimension());
        }
        m.setParameters(p);
        m.setDisplayName("");
        m.setIsDefault(false);
        m.setStatus("active");
        // Go 的 uint 零值语义：handler 不回填 tenant → 行落 tenant_id=0，
        // 后续按租户回读找不到（golden init-get-config-after 无 llm/embedding 键即此因）
        m.setTenantId(0L);
        return m;
    }

    private String findExistingModelId(KnowledgeBase kb, String type) {
        return switch (type) {
            case "Embedding" -> orEmpty(kb.getEmbeddingModelId());
            case "KnowledgeQA" -> orEmpty(kb.getSummaryModelId());
            case "VLLM" -> orEmpty(kb.getVlmConfig().getModelId());
            default -> "";
        };
    }

    private void applyInitialization(KnowledgeBase kb, InitializationRequest req,
            List<Model> processed) {
        String embeddingId = "";
        String llmId = "";
        String vlmId = "";
        for (Model m : processed) {
            switch (m.getType()) {
                case "Embedding" -> embeddingId = m.getId();
                case "KnowledgeQA" -> llmId = m.getId();
                case "VLLM" -> vlmId = m.getId();
                default -> {
                }
            }
        }
        kb.setSummaryModelId(llmId);
        kb.setEmbeddingModelId(embeddingId);
        var chunking = kb.getChunkingConfig();
        chunking.setChunkSize(req.chunkSize());
        chunking.setChunkOverlap(req.chunkOverlap());
        chunking.setSeparators(req.separators());

        if (req.multimodalEnabled()) {
            KnowledgeBaseVlmConfig vlm = new KnowledgeBaseVlmConfig();
            vlm.setEnabled(true);
            vlm.setModelId(vlmId);
            kb.setVlmConfig(vlm);
            String storageType = req.multimodal() == null ? ""
                    : req.multimodal().path("storageType").asText("");
            // cos/minio 凭据落库段依赖部署环境（dev 无），Go 也只在段存在时触达
            if (("cos".equals(storageType) || "minio".equals(storageType))
                    && req.multimodal().get(storageType) != null) {
                kb.setStorageProvider(storageType);
            }
        } else {
            kb.setVlmConfig(new KnowledgeBaseVlmConfig());
            kb.setStorageProvider("");
        }

        if (req.nodeExtractEnabled()) {
            ObjectNode extract = MAPPER.createObjectNode();
            extract.put("text", req.nodeExtractText());
            extract.set("tags", MAPPER.valueToTree(req.nodeExtractTags()));
            var nodes = extract.putArray("nodes");
            for (Object n : req.nodeExtractNodes()) {
                JsonNode node = MAPPER.valueToTree(n);
                ObjectNode copy = nodes.addObject();
                copy.put("name", node.path("name").asText(""));
                copy.set("attributes", node.get("attributes") == null
                        ? MAPPER.createArrayNode() : node.get("attributes").deepCopy());
            }
            var relations = extract.putArray("relations");
            for (Object r : req.nodeExtractRelations()) {
                JsonNode rel = MAPPER.valueToTree(r);
                ObjectNode copy = relations.addObject();
                copy.put("node1", rel.path("node1").asText(""));
                copy.put("node2", rel.path("node2").asText(""));
                copy.put("type", rel.path("type").asText(""));
            }
            kb.setExtractConfig(extract);
        }
    }

    private void saveKb(KnowledgeBase kb) {
        kbMapper.updateById(kb);
    }

    // ══════════════ GET config 响应（全 map 字母序，对照 buildConfigResponse）══════════════

    private Map<String, Object> configResponse(List<Model> models, KnowledgeBase kb,
            boolean hasFiles) {
        Map<String, Object> config = new TreeMap<>();
        config.put("hasFiles", hasFiles);
        boolean canView = canViewIntegrationSecrets();

        for (Model m : models) {
            String baseUrl = m.getParameters() == null ? "" : m.getParameters().getBaseUrl();
            if (m.isIsBuiltin() || !canView) {
                baseUrl = "";
            }
            boolean hasKey = m.getParameters() != null && !m.getParameters().getApiKey().isEmpty()
                    && !m.isIsBuiltin();
            switch (m.getType()) {
                case "KnowledgeQA" -> config.put("llm", sortedBlock(Map.of(
                        "source", orEmpty(m.getSource()),
                        "modelName", orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "credentials", Map.of("apiKey", hasKey))));
                case "Embedding" -> config.put("embedding", sortedBlock(Map.of(
                        "source", orEmpty(m.getSource()),
                        "modelName", orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "dimension", m.getParameters() == null ? 0
                                : m.getParameters().getEmbeddingParameters().getDimension(),
                        "credentials", Map.of("apiKey", hasKey))));
                case "Rerank" -> config.put("rerank", sortedBlock(Map.of(
                        "enabled", true,
                        "modelName", orEmpty(m.getName()),
                        "baseUrl", baseUrl,
                        "credentials", Map.of("apiKey", hasKey))));
                case "VLLM" -> {
                    Map<String, Object> mm = castMap(config.get("multimodal"));
                    if (mm == null) {
                        mm = new TreeMap<>();
                        mm.put("enabled", true);
                        config.put("multimodal", mm);
                    }
                    mm.put("vlm", sortedBlock(Map.of(
                            "modelName", orEmpty(m.getName()),
                            "baseUrl", baseUrl,
                            "interfaceType", m.getParameters() == null ? ""
                                    : m.getParameters().getInterfaceType(),
                            "modelId", m.getId(),
                            "credentials", Map.of("apiKey", hasKey))));
                }
                default -> {
                }
            }
        }

        String storageProvider = kb.getStorageProvider();
        boolean hasMultimodal = kb.getVlmConfig().isEnabled()
                || !kb.getStorageConfig().getSecretId().isEmpty()
                || !kb.getStorageConfig().getBucketName().isEmpty()
                || (!storageProvider.isEmpty() && !"local".equals(storageProvider));
        Map<String, Object> mm = castMap(config.get("multimodal"));
        if (mm == null) {
            mm = new TreeMap<>();
            mm.put("enabled", hasMultimodal);
            config.put("multimodal", mm);
        } else {
            mm.put("enabled", hasMultimodal);
        }
        String descLang = kb.getVlmConfig().getDescriptionLanguage();
        String customInstr = kb.getVlmConfig().getCustomInstructions();
        if ((descLang != null && !descLang.isEmpty()) || (customInstr != null && !customInstr.isEmpty())) {
            if (descLang != null && !descLang.isEmpty()) {
                mm.put("descriptionLanguage", descLang);
            }
            if (customInstr != null && !customInstr.isEmpty()) {
                mm.put("customInstructions", customInstr);
            }
        }

        if (config.get("rerank") == null) {
            config.put("rerank", sortedBlock(Map.of(
                    "enabled", false,
                    "modelName", "",
                    "baseUrl", "",
                    "credentials", Map.of("apiKey", false))));
        }

        var c = kb.getChunkingConfig();
        Map<String, Object> ds = new TreeMap<>();
        ds.put("chunkSize", c.getChunkSize());
        ds.put("chunkOverlap", c.getChunkOverlap());
        ds.put("separators", c.getSeparators());
        if (c.getStrategy() != null && !c.getStrategy().isEmpty()) {
            ds.put("strategy", c.getStrategy());
        }
        if (c.getTokenLimit() > 0) {
            ds.put("tokenLimit", c.getTokenLimit());
        }
        if (c.getLanguages() != null && !c.getLanguages().isEmpty()) {
            ds.put("languages", c.getLanguages());
        }
        if (c.getTableMetadataInstructions() != null && !c.getTableMetadataInstructions().isEmpty()) {
            ds.put("tableMetadataInstructions", c.getTableMetadataInstructions());
        }
        config.put("documentSplitting", ds);

        String effectiveProvider = kb.getStorageProvider();
        if (!kb.getStorageConfig().getSecretId().isEmpty()
                || (!effectiveProvider.isEmpty() && !"local".equals(effectiveProvider))) {
            Map<String, Object> mm2 = castMap(config.get("multimodal"));
            if (mm2 == null) {
                mm2 = new TreeMap<>();
                mm2.put("enabled", true);
                config.put("multimodal", mm2);
            }
            mm2.put("storageType", effectiveProvider);
            if ("cos".equals(effectiveProvider)) {
                Map<String, Object> cos = new TreeMap<>();
                cos.put("region", kb.getStorageConfig().getRegion());
                cos.put("bucketName", kb.getStorageConfig().getBucketName());
                cos.put("appId", kb.getStorageConfig().getAppId());
                cos.put("pathPrefix", kb.getStorageConfig().getPathPrefix());
                cos.put("credentials", Map.of(
                        "secretId", !kb.getStorageConfig().getSecretId().isEmpty(),
                        "secretKey", !kb.getStorageConfig().getSecretKey().isEmpty()));
                mm2.put("cos", cos);
            } else if ("minio".equals(effectiveProvider)) {
                mm2.put("minio", sortedBlock(Map.of(
                        "bucketName", kb.getStorageConfig().getBucketName(),
                        "pathPrefix", kb.getStorageConfig().getPathPrefix())));
            }
        }

        JsonNode extract = kb.getExtractConfig();
        if (extract != null) {
            Map<String, Object> ne = new TreeMap<>();
            ne.put("enabled", extract.path("enabled").asBoolean(false));
            ne.put("text", extract.path("text").asText(""));
            ne.put("tags", extract.get("tags"));
            ne.put("nodes", extract.get("nodes"));
            ne.put("relations", extract.get("relations"));
            String ci = extract.path("custom_instructions").asText("");
            if (!ci.isEmpty()) {
                ne.put("customInstructions", ci);
            }
            config.put("nodeExtract", ne);
        } else {
            config.put("nodeExtract", sortedBlock(Map.of("enabled", false)));
        }

        JsonNode qg = kb.getQuestionGenerationConfig();
        if (qg != null) {
            config.put("questionGeneration", sortedBlock(Map.of(
                    "enabled", qg.path("enabled").asBoolean(false),
                    "questionCount", qg.path("question_count").asInt(0),
                    "customInstructions", qg.path("custom_instructions").asText(""))));
        } else {
            config.put("questionGeneration", sortedBlock(Map.of("enabled", false)));
        }
        return config;
    }

    // ══════════════ 小工具 ══════════════

    private boolean hasFiles(String kbId) {
        Long tid = TenantContext.currentTenantId();
        Long count = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(tid != null, Knowledge::getTenantId, tid)
                .isNull(Knowledge::getDeletedAt));
        return count != null && count > 0;
    }

    private void requireModel(String id, String message) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest(message));
        }
        try {
            Model m = modelService.getModelByID(id);
            if (m != null) {
                return;
            }
        } catch (Exception ignored) {
            // 落到下方 400
        }
        throw new BizException(AppError.badRequest(message));
    }

    private boolean canViewIntegrationSecrets() {
        String role = TenantContext.currentRole();
        if (TenantRole.fromString(role == null ? "" : role).hasPermission(TenantRole.ADMIN)) {
            return true;
        }
        var scope = APIKeyScopeContext.current();
        return scope != null && (scope.fullAccess() || scope.hasCapability("manage_tenant_settings"));
    }

    /** Map.of 乱序 → TreeMap 重排（Go map 序列化 = 键字母序）。 */
    private static Map<String, Object> sortedBlock(Map<String, Object> entries) {
        return new TreeMap<>(entries);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static List<String> toStringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode e : n) {
                out.add(e.asText(""));
            }
        }
        return out;
    }

    private static List<Object> toList(JsonNode n) {
        List<Object> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(out::add);
        }
        return out;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
