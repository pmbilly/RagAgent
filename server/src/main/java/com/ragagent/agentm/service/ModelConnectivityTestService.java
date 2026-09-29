package com.ragagent.agentm.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
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
 * 模型连通性测试端点用例（remote/embedding/rerank/asr/multimodal）与测试模型装配、SSRF 校验、密钥解密机制。
 *
 * <p>initialization 薄层化的产物：端点注解留在 controller，方法体逐字迁移至此。</p>
 */
public final class ModelConnectivityTestService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ModelService modelService;
    private final SsrfGuard ssrfGuard;
    private final OllamaService ollamaService;
    private final ConcurrencyGovernor concurrencyGovernor;
    private final TenantService tenantService;
    private final CryptoService cryptoService;
    private final AsrTranscriber asrTranscriber;
    private final com.ragagent.knowledge.client.DocReaderClient documentReader;

    public ModelConnectivityTestService(KnowledgeBaseService kbService, KnowledgeBaseMapper kbMapper, KnowledgeMapper knowledgeMapper, ModelService modelService, SsrfGuard ssrfGuard, OllamaService ollamaService, ConcurrencyGovernor concurrencyGovernor, TenantService tenantService, CryptoService cryptoService, AsrTranscriber asrTranscriber, com.ragagent.knowledge.client.DocReaderClient documentReader) {
        this.kbService = kbService;
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.modelService = modelService;
        this.ssrfGuard = ssrfGuard;
        this.ollamaService = ollamaService;
        this.concurrencyGovernor = concurrencyGovernor;
        this.tenantService = tenantService;
        this.cryptoService = cryptoService;
        this.asrTranscriber = asrTranscriber;
        this.documentReader = documentReader;
    }

    // ══════════════ 模型连通性测试段 ══════════════

    /** POST /initialization/remote/check——chat 模块最小化连通性调用。 */
    public ResponseEntity<Object> remoteCheck(String rawBody) {
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
            opts.setThinking(Boolean.FALSE); // for dashscope.aliyuncs qwen3-32b
            chat.chat(List.of(new ChatMessage("user", "test")), opts);
            available = true;
            message = "连接正常，模型可用";
        } catch (RuntimeException e) {
            // BizException.getMessage() 带 "error code: ..., error message: " 前缀，
            // 判定须拆包取 appError().message()。
            String raw = e instanceof BizException be ? be.appError().message() : e.getMessage();
            String errMsg = raw == null ? "" : raw;
            if (errMsg.contains("status code: 400")) {
                // 400 = 端点可达且鉴权通过，仅参数不匹配
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
    public ResponseEntity<Object> embeddingTest(String rawBody) {
        ModelTestRequest r = fillSecretsFromStoredModel(bindModelTestRequest(rawBody));
        String source = r.source();
        if (source.isEmpty()) {
            source = "remote";
        }
        if (!r.baseUrl().isEmpty()) {
            requireSsrf("Base URL", r.baseUrl());
        }
        // 阿里云多模态 Embedding 模型暂不支持（早期短路）
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
            // pooler：单文本 embed 不触达批路径
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
    public ResponseEntity<Object> rerankCheck(String rawBody) {
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
    public ResponseEntity<Object> asrCheck(String rawBody) {
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
                // 端点可达（非致命错误）——available=true
                available = true;
                message = "ASR端点可达（非致命错误: " + errMsg + "）";
            }
        }
        return okAvailability(available, message);
    }

    /** POST /initialization/multimodal/test——multipart 上传图片，走 DocReader 解析。 */
    public ResponseEntity<Object> multimodalTest(
            String vlmModel,
            String vlmBaseUrl,
            String vlmInterfaceType,
            String storageType,
            String cosSecretId,
            String cosSecretKey,
            String cosRegion,
            String cosBucketName,
            String cosAppId,
            String minioBucketName,
            String chunkSizeRaw,
            String chunkOverlapRaw,
            String separatorsRaw,
            org.springframework.web.multipart.MultipartFile image) {
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
        // 键按字母序：message < processing_time < success / caption < ocr <
        // processing_time < success（ObjectNode 保插入序，必须按字母序插入）
        ObjectNode data = MAPPER.createObjectNode();
        if (error != null) {
            data.put("message", error);
            data.put("processingTime", processingTime);
            data.put("success", false);
        } else {
            data.put("caption", "");
            data.put("ocr", "");
            data.put("processingTime", processingTime);
            data.put("success", true);
        }
        return ok(data);
    }

    /** 多模态测试的 DocReader 调用：ReadRequest(FileContent/FileName/FileType)。 */
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

    // ══════════════ ModelTestRequest 绑定与共用件 ══════════════

    private record ModelTestRequest(String source, String modelName, String baseUrl, String apiKey,
            String provider, String interfaceType, int dimension, boolean supportsDimensionOverride,
            Map<String, String> customHeaders, Map<String, String> extraConfig, String appSecret,
            String modelId) {}

    private static ModelTestRequest bindModelTestRequest(String rawBody) {
        JsonNode n = OllamaManageService.bindJsonObject(rawBody);
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
                InitializationConfigService.toStringMap(n.get("customHeaders")),
                InitializationConfigService.toStringMap(n.get("extraConfig")),
                text(n, "appSecret"),
                text(n, "modelId"));
    }

    /**
     * modelId 命中的存量模型补齐空密钥与 extraConfig
     * （record 不可变 → 返回替换值，调用方重接）。
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

    /** 测试请求 → 临时 Model（不落库）。 */
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

    /** 错误串 → 中文短提示。 */
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

    /** 解析当前空间凭证；null = 空间信息未找到。 */
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

    /** 无状态闸门版模型获取 + WeKnoraCloud 凭证解析。 */
    LlmChatClient getChatModelOr400(String modelId) {
        try {
            return getChatModel(modelId);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest("获取模型失败: " + e.getMessage()));
        }
    }

    LlmChatClient getChatModel(String modelId) {
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

    /** 宽容解密（失败原样返回）。 */
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

    static ResponseEntity<Object> okAvailability(boolean available, String message) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("available", available);
        data.put("message", message);
        return ok(data);
    }

    static ResponseEntity<Object> embeddingResult(boolean available, String message, int dimension) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("available", available);
        data.put("dimension", dimension);
        data.put("message", message);
        return ok(data);
    }

    static ResponseEntity<Object> ok(Object data) {
        return ResponseEntity.ok(data);
    }

    /** 时间保持原 offset 输出（JSON 反序列化来的时间不改时区）。 */
    static String goTimeAsIs(OffsetDateTime value) {
        return value == null ? "0001-01-01T00:00:00Z"
                : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    static String goTime(OffsetDateTime value) {
        if (value == null) {
            return "0001-01-01T00:00:00Z";
        }
        return value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

}
