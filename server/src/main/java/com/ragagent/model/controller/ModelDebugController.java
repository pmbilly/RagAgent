package com.ragagent.model.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.asr.AsrTranscriber;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.embedding.Embedder;
import com.ragagent.common.storage.UploadLimits;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.ProviderAdapters;
import com.ragagent.llm.chat.ThinkingStrategies;
import com.ragagent.llm.chat.ThinkingStrategy;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.dto.ModelDebugAsrResponse;
import com.ragagent.model.dto.ModelDebugChatResponse;
import com.ragagent.model.dto.ModelDebugResult;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.model.service.ModelService;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.vlm.VlmClient;
import com.ragagent.retrieval.vlm.VlmHttpTransport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ragagent.model.service.ModelRuntimeConfigs;

/**
 * 模型调试端点（POST /api/v1/models/{id}/debug）。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>multipart 表单：input / options(JSON) / documents(JSON 数组) / file；</li>
 *   <li>五类模型分支：KnowledgeQA(流式 chat) / Embedding / Rerank / VLLM / ASR；</li>
 *   <li>运行时错误<b>不是</b> HTTP 错误——落在结果对象里（{@code ok=false} +
 *       {@code error} 原文），HTTP 恒 200；参数校验错误才是 400 错误信封；</li>
 *   <li>请求预览只含非密字段：extraConfig 过 {@code redactedDebugConfig}，
 *       customHeaders 只露头名。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelDebugController {


    /** 对照 modelDebugMaxInputBytes = 64 * 1024。 */
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    /** 对照 documents 上限（model.go:394）。 */
    private static final int MAX_DOCUMENTS = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelService modelService;
    private final ModelRuntimeFactory runtimeFactory;
    private final AsrTranscriber asrTranscriber;
    private final VlmClient.Transport vlmTransport = new VlmHttpTransport();

    public ModelDebugController(ModelService modelService, ModelRuntimeFactory runtimeFactory,
                                AsrTranscriber asrTranscriber) {
        this.modelService = modelService;
        this.runtimeFactory = runtimeFactory;
        this.asrTranscriber = asrTranscriber;
    }

    @PostMapping("/{id}/debug")
    public ResponseEntity<ModelDebugResult> debugModel(@PathVariable("id") String id,
                                        @RequestParam(value = "input", required = false) String input,
                                        @RequestParam(value = "options", required = false) String optionsRaw,
                                        @RequestParam(value = "documents", required = false) String documentsRaw,
                                        @RequestParam(value = "file", required = false) MultipartFile file) {
        long startedNanos = System.nanoTime();
        id = sanitizeForLog(id);
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }

        Model model;
        try {
            model = modelService.getModelByID(id);
        } catch (ModelNotFoundException e) {
            throw new BizException(AppError.notFound("Model not found"));
        }

        // ── 表单字段校验（对照 handler L378-398） ──
        if (input == null) {
            input = "";
        }
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new BizException(AppError.badRequest("input is too long"));
        }
        DebugOptions opts = parseOptions(optionsRaw);

        List<String> documents = null;
        if (documentsRaw != null && !documentsRaw.trim().isEmpty()) {
            documents = parseDocuments(documentsRaw);
            if (documents.size() > MAX_DOCUMENTS) {
                throw new BizException(AppError.badRequest("documents cannot exceed 100 items"));
            }
        }

        // ── 可选文件（对照 L400-425） ──
        byte[] fileBytes = null;
        String fileName = "";
        long fileSize = 0;
        if (file != null && fileName(file) != null && !fileName(file).isEmpty()) {
            fileName = fileName(file);
            fileSize = file.getSize();
            long maxBytes = UploadLimits.maxFileSizeBytes();
            long maxMb = UploadLimits.maxFileSizeMb();
            if (fileSize > maxBytes) {
                throw new BizException(AppError.badRequest("file cannot exceed " + maxMb + " MB"));
            }
            try {
                fileBytes = file.getBytes();
            } catch (Exception e) {
                throw new BizException(AppError.badRequest("failed to read uploaded file"));
            }
            if (fileBytes.length > maxBytes) {
                throw new BizException(AppError.badRequest("file cannot exceed " + maxMb + " MB"));
            }
            fileSize = fileBytes.length;
        }

        Map<String, Object> requestPreview =
                requestPreview(model, input, documents, opts, fileName, fileSize);
        Map<String, Object> observations = new TreeMap<>(); // Go gin.H 按键排序输出

        return switch (model.getType() == null ? "" : model.getType()) {
            case "KnowledgeQA" -> debugChat(model, input, opts, startedNanos, requestPreview, observations);
            case "Embedding" -> debugEmbedding(model, input, startedNanos, requestPreview, observations);
            case "Rerank" -> debugRerank(model, input, documents, startedNanos, requestPreview, observations);
            case "VLLM" -> debugVlm(model, input, fileBytes, startedNanos, requestPreview, observations);
            case "ASR" -> debugAsr(model, fileBytes, fileName, startedNanos, requestPreview, observations);
            default -> throw new BizException(AppError.badRequest("unsupported model type"));
        };
    }

    // ── KnowledgeQA（对照 L431-475） ─────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugChat(Model model, String input, DebugOptions opts,
                                        long startedNanos, Map<String, Object> requestPreview,
                                        Map<String, Object> observations) {
        if (input.trim().isEmpty()) {
            throw new BizException(AppError.badRequest("query cannot be empty"));
        }
        LlmChatClient instance;
        try {
            instance = runtimeFactory.getChatModel(model.getId());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        List<ChatMessage> messages = new ArrayList<>(2);
        if (opts.systemPrompt != null && !opts.systemPrompt.trim().isEmpty()) {
            messages.add(new ChatMessage("system", opts.systemPrompt));
        }
        messages.add(new ChatMessage("user", input));
        ChatOptions chatOpts = new ChatOptions();
        if (opts.temperature != null) {
            chatOpts.setTemperature(opts.temperature);
        }
        if (opts.topP != null) {
            chatOpts.setTopP(opts.topP);
        }
        if (opts.maxTokens != null) {
            chatOpts.setMaxTokens(opts.maxTokens);
        }
        chatOpts.setThinking(opts.thinking);

        // 对照 chat.ConfigFromModel(model, "", "") + EffectiveThinkingControl
        ChatConfig chatConfig = ModelRuntimeConfigs.chatConfig(model, "", "");
        String thinkingControl = effectiveThinkingControl(chatConfig);
        observations.put("stream", true);
        observations.put("requestedThinking", opts.thinking != null && opts.thinking);
        observations.put("thinkingControl", thinkingControl);
        observations.put("thinkingParameterSent", opts.thinking != null && !"none".equals(thinkingControl));

        BlockingQueue<StreamResponse> stream;
        try {
            stream = instance.chatStream(messages, chatOpts);
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        ModelDebugChatResponse resp = new ModelDebugChatResponse();
        String streamError = null;
        try {
            // 对照 Go consumeModelDebugChatStream 的 range-over-channel（收到 channel
            // 关闭才停）：Go/Java 的适配器都会在最后一个内容分片（done=true）之后再
            // 补一条带 usage 的终态 done 事件，因此看到 done 不能立刻停——
            // 改成「done 之后短超时排空队列」：终态事件由同一线程紧跟着 put，
            // 2 秒窗口足够覆盖调度交错；poll 超时 = 对 channel close 的模拟。
            boolean sawDone = false;
            while (true) {
                StreamResponse event = sawDone
                        ? stream.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                        : stream.take();
                if (event == null) {
                    break;
                }
                resp.getStreamEvents().add(event);
                ResponseType type = event.getResponseType();
                if (type == ResponseType.THINKING) {
                    resp.setReasoningContent(
                            (resp.getReasoningContent() == null ? "" : resp.getReasoningContent())
                                    + event.getContent());
                } else if (type == ResponseType.ANSWER) {
                    resp.setContent(resp.getContent() + event.getContent());
                    if (event.getToolCalls() != null && !event.getToolCalls().isEmpty()) {
                        resp.setToolCalls(event.getToolCalls());
                    }
                    if (event.getFinishReason() != null && !event.getFinishReason().isEmpty()) {
                        resp.setFinishReason(event.getFinishReason());
                    }
                    if (event.getUsage() != null) {
                        resp.setUsage(event.getUsage());
                    }
                } else if (type == ResponseType.TOOL_CALL) {
                    if (event.getToolCalls() != null && !event.getToolCalls().isEmpty()) {
                        resp.setToolCalls(event.getToolCalls());
                    }
                } else if (type == ResponseType.ERROR) {
                    streamError = event.getContent();
                    break;
                }
                if (event.isDone()) {
                    sawDone = true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            streamError = "interrupted";
        }
        // 对照 L470-474：resp 恒非 null（consume 出错也返回部分结果）
        String reasoning = resp.getReasoningContent() == null ? "" : resp.getReasoningContent();
        observations.put("reasoningReturned", !reasoning.trim().isEmpty());
        observations.put("reasoningCharacters", reasoning.codePointCount(0, reasoning.length()));
        observations.put("answerCharacters", resp.getContent().codePointCount(0, resp.getContent().length()));
        return writeResult(startedNanos, requestPreview, resp, streamError, observations);
    }

    // ── Embedding（对照 L476-488） ───────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugEmbedding(Model model, String input, long startedNanos,
                                             Map<String, Object> requestPreview, Map<String, Object> observations) {
        if (input.trim().isEmpty()) {
            throw new BizException(AppError.badRequest("input cannot be empty"));
        }
        Embedder instance;
        try {
            instance = runtimeFactory.getEmbeddingModel(model.getId());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        float[] vector = null;
        String error = null;
        try {
            vector = instance.embed(input);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        observations.put("dimension", vector == null ? 0 : vector.length);
        return writeResult(startedNanos, requestPreview, vector, error, observations);
    }

    // ── Rerank（对照 L489-501） ──────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugRerank(Model model, String input, List<String> documents,
                                          long startedNanos, Map<String, Object> requestPreview,
                                          Map<String, Object> observations) {
        if (input.trim().isEmpty() || documents == null || documents.isEmpty()) {
            throw new BizException(AppError.badRequest("query and documents cannot be empty"));
        }
        Reranker instance;
        try {
            instance = runtimeFactory.getRerankModel(model.getId());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        List<RankResult> results = null;
        String error = null;
        try {
            results = instance.rerank(input, documents);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        observations.put("resultCount", results == null ? 0 : results.size());
        return writeResult(startedNanos, requestPreview, results, error, observations);
    }

    // ── VLLM（对照 L502-514） ────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugVlm(Model model, String input, byte[] fileBytes,
                                       long startedNanos, Map<String, Object> requestPreview,
                                       Map<String, Object> observations) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BizException(AppError.badRequest("image file is required"));
        }
        Model vlmModel;
        try {
            vlmModel = runtimeFactory.getVlmModel(model.getId());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        // 凭证 + 构造期校验：照 Go GetVLMModel 的 resolveWeKnoraCloudCredentials
        // （model 级优先、租户回落）与 vlm.NewVLM（weknoracloud 的凭证检查先于基址；
        // ollama 不校验基址）——与 agent 侧 VLM 装配共用 vlmConfigFor 同一实现。
        VlmClient.VlmConfig config;
        try {
            config = runtimeFactory.vlmConfigFor(vlmModel);
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        String result = null;
        String error = null;
        try {
            result = VlmClient.predict(config, vlmTransport, new byte[][] {fileBytes}, input);
        } catch (VlmClient.VlmException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        String answer = result == null ? "" : result;
        observations.put("answerCharacters", answer.codePointCount(0, answer.length()));
        return writeResult(startedNanos, requestPreview, result, error, observations);
    }

    // ── ASR（对照 L515-530） ─────────────────────────────────────────────

    private ResponseEntity<ModelDebugResult> debugAsr(Model model, byte[] fileBytes, String fileName,
                                       long startedNanos, Map<String, Object> requestPreview,
                                       Map<String, Object> observations) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BizException(AppError.badRequest("audio file is required"));
        }
        try {
            runtimeFactory.getAsrModel(model.getId());
        } catch (RuntimeException e) {
            return writeResult(startedNanos, requestPreview, null, e.getMessage(), observations);
        }
        ModelParameters p = model.getParameters();
        // 对照 asr.ConfigFromModel：language 不从模型来（恒空），customHeaders 透传
        var config = new AsrTranscriber.AsrConfig(
                p == null ? "" : p.getBaseUrl(), model.getName(), p == null ? "" : p.getApiKey(),
                model.getId(), "", p == null ? null : p.getCustomHeaders());
        AsrTranscriber.TranscriptionResult result = null;
        String error = null;
        try {
            result = asrTranscriber.transcribe(config, fileBytes, fileName);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        ModelDebugAsrResponse raw = null;
        if (result != null) {
            observations.put("textCharacters",
                    result.text() == null ? 0 : result.text().codePointCount(0, result.text().length()));
            observations.put("segmentCount", result.segments() == null ? 0 : result.segments().size());
            List<ModelDebugAsrResponse.Segment> segments = null;
            if (result.segments() != null) {
                segments = new ArrayList<>(result.segments().size());
                for (var s : result.segments()) {
                    segments.add(new ModelDebugAsrResponse.Segment(s.start(), s.end(), s.text()));
                }
            }
            raw = new ModelDebugAsrResponse(result.text(), segments);
        }
        return writeResult(startedNanos, requestPreview, raw, error, observations);
    }

    // ── 结果整形（对照 writeModelDebugResult + modelDebugRequestPreview + redactedDebugConfig） ──

    /** 结果整形：HTTP 恒 200，裸结果对象（无信封）。 */
    private ResponseEntity<ModelDebugResult> writeResult(long startedNanos, Map<String, Object> request,
                                                         Object rawResponse, String error,
                                                         Map<String, Object> observations) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        return ResponseEntity.ok(new ModelDebugResult(
                error == null, elapsedMs, error, request, rawResponse, observations));
    }

    /**
     * 请求预览（只含非密字段）：顶层键按字母序输出（确定性）；
     * options 各键仅在显式给出时出现（键序 = 声明序）。
     */
    private Map<String, Object> requestPreview(Model model, String input, List<String> documents,
                                               DebugOptions opts, String fileName, long fileSize) {
        ModelParameters p = model.getParameters();
        Map<String, Object> preview = new TreeMap<>();
        if (p != null && p.getCustomHeaders() != null && !p.getCustomHeaders().isEmpty()) {
            // 只露头名（值可能含密钥），排序输出保证确定性
            preview.put("customHeaderNames", new ArrayList<>(new java.util.TreeSet<>(
                    p.getCustomHeaders().keySet())));
        }
        if (documents != null && !documents.isEmpty()) {
            preview.put("documents", documents);
        }
        if (fileName != null && !fileName.isEmpty()) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("name", fileName);
            fileInfo.put("size", fileSize);
            preview.put("file", fileInfo);
        }
        preview.put("input", input);
        if (p != null && p.getExtraConfig() != null) {
            preview.put("modelExtraConfig", redactedDebugConfig(p.getExtraConfig()));
        }
        preview.put("modelId", model.getId());
        preview.put("modelName", model.getName());
        preview.put("modelType", model.getType());

        Map<String, Object> options = new LinkedHashMap<>();
        if (opts.systemPrompt != null && !opts.systemPrompt.isEmpty()) {
            options.put("systemPrompt", opts.systemPrompt);
        }
        if (opts.temperature != null) {
            options.put("temperature", opts.temperature);
        }
        if (opts.topP != null) {
            options.put("topP", opts.topP);
        }
        if (opts.maxTokens != null) {
            options.put("maxTokens", opts.maxTokens);
        }
        if (opts.thinking != null) {
            options.put("thinking", opts.thinking);
        }
        preview.put("options", options);

        preview.put("provider", p == null || p.getProvider() == null ? "" : p.getProvider());
        preview.put("source", model.getSource());
        return preview;
    }

    /** 对照 redactedDebugConfig：空 → null；命中敏感词的值换 [REDACTED]（键序按字母序）。 */
    private static Map<String, String> redactedDebugConfig(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        Map<String, String> out = new TreeMap<>(); // Go map 序列化按键排序
        for (Map.Entry<String, String> e : config.entrySet()) {
            String lower = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                    || lower.contains("api_key") || lower.contains("apikey")
                    || lower.contains("authorization")) {
                out.put(e.getKey(), "[REDACTED]");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    // ── options / documents 解析 ──

    /** 调试选项（null 表示"未显式给出"，与显式 false/0 区分）。 */
    private record DebugOptions(String systemPrompt, Double temperature, Double topP,
                                Integer maxTokens, Boolean thinking) {
        static final DebugOptions EMPTY = new DebugOptions("", null, null, null, null);
    }

    /** options JSON 解析：键为 camelCase；null / 空 → 零值；类型/范围错误 → 400。 */
    private static DebugOptions parseOptions(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DebugOptions.EMPTY;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("invalid options: malformed JSON"));
        }
        if (node == null || node.isNull()) {
            // "null" 解析为零值无错误
            return DebugOptions.EMPTY;
        }
        if (!node.isObject()) {
            throw new BizException(AppError.badRequest("invalid options: expected a JSON object"));
        }
        String systemPrompt = "";
        Double temperature = null;
        Double topP = null;
        Integer maxTokens = null;
        Boolean thinking = null;
        JsonNode v;
        if ((v = node.get("systemPrompt")) != null && !v.isNull()) {
            if (!v.isTextual()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: systemPrompt must be a string"));
            }
            systemPrompt = v.asText();
        }
        if ((v = node.get("temperature")) != null && !v.isNull()) {
            if (!v.isNumber()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: temperature must be a number"));
            }
            temperature = v.asDouble();
        }
        if ((v = node.get("topP")) != null && !v.isNull()) {
            if (!v.isNumber()) {
                throw new BizException(AppError.badRequest("invalid options: topP must be a number"));
            }
            topP = v.asDouble();
        }
        if ((v = node.get("maxTokens")) != null && !v.isNull()) {
            if (!v.isIntegralNumber()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: maxTokens must be an integer"));
            }
            maxTokens = v.asInt();
        }
        if ((v = node.get("thinking")) != null && !v.isNull()) {
            if (!v.isBoolean()) {
                throw new BizException(AppError.badRequest(
                        "invalid options: thinking must be a boolean"));
            }
            thinking = v.asBoolean();
        }
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 8192)) {
            throw new BizException(AppError.badRequest("maxTokens must be between 1 and 8192"));
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new BizException(AppError.badRequest("temperature must be between 0 and 2"));
        }
        if (topP != null && (topP <= 0 || topP > 1)) {
            throw new BizException(AppError.badRequest("topP must be greater than 0 and at most 1"));
        }
        return new DebugOptions(systemPrompt, temperature, topP, maxTokens, thinking);
    }

    /** 对照 documents 解析（L388-398）：非法 JSON / 非字符串数组 → 固定文案。 */
    private static List<String> parseDocuments(String raw) {
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("documents must be a JSON string array"));
        }
        if (node == null || !node.isArray()) {
            throw new BizException(AppError.badRequest("documents must be a JSON string array"));
        }
        List<String> documents = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new BizException(AppError.badRequest("documents must be a JSON string array"));
            }
            documents.add(item.asText());
        }
        return documents;
    }

    // ── thinking 观测（对照 chat.EffectiveThinkingControl，thinking.go:147-159） ──

    static String effectiveThinkingControl(ChatConfig config) {
        if (config == null) {
            return "none";
        }
        ThinkingStrategy override = ThinkingStrategies.parseThinkingOverride(config.getExtraConfig());
        if (override != null) {
            return override.name();
        }
        // 对照 Go：provider 为空串才 DetectProvider 回落；非空但未知名（Java 的
        // fromValue=null）不回落——resolve(null) 无命中 → BaseProvider（Go baseProvider 同形）
        String pv = config.getProvider() == null ? "" : config.getProvider();
        ProviderName name = pv.isEmpty()
                ? ProviderRegistry.detectProvider(config.getBaseUrl())
                : ProviderName.fromValue(pv);
        return ProviderAdapters.resolve(name, config.getModelName()).thinking().name();
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    /** 对照 secutils.SanitizeForLog：\n\r\t → 空格，其余 C0 控制符移除。 */
    private static String sanitizeForLog(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = input.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(r -> {
            if (r >= 32) {
                sb.appendCodePoint(r);
            }
        });
        return sb.toString();
    }

    private static String fileName(MultipartFile file) {
        return file == null ? null : file.getOriginalFilename();
    }
}
