package com.ragagent.session.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.asr.AsrTranscriber;
import com.ragagent.auth.service.TenantService;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;

/**
 * 会话附件服务（对照 Go internal/application/service/temporary_document.go）。
 *
 * 落地：Create（文件名校验/扩展白名单/大小限制/落盘/建行/异步投递）、
 * Get/List/Delete/OpenFile、Process（纯文本直读 + docreader → chunker
 * auto/1600/160 → ApproxTokenCount → MarkReady）。
 *
 * <p>提示词渲染（ResolveForPrompt 的预算选块 + 图片 URL 选取）已拆至
 * {@link TemporaryDocumentPromptResolver}（§14 步骤 2），本类保留公开入口并薄委托。</p>
 *
 * 已知差异（随对应波次收口）：任务队列用进程内 executor（asynq 随波 4）；
 * 扩展白名单静态表（ListEngines 未翻译）；agent_id 门控随波 5；
 * VLM 图片理解 / ASR 依赖运行时模型工厂（阶段 7），OCR/caption 降级跳过。
 */
@Service
public class TemporaryDocumentService {

    private static final Logger log = LoggerFactory.getLogger(TemporaryDocumentService.class);

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 Go temporaryDocumentExtensions（L84-90，带点——service 的 ext 不去点）。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".docx", ".doc", ".pdf", ".ppt", ".pptx", ".epub", ".mhtml",
            ".xlsx", ".xls",
            ".md", ".markdown", ".txt", ".csv", ".json", ".xml", ".yaml", ".yml", ".log", ".html",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tiff", ".webp",
            ".mp3", ".wav", ".m4a", ".flac", ".ogg", ".aac");

    private static final long DEFAULT_TTL_HOURS = 24;

    /** 对照 Go {@code types.MaxTemporaryAttachmentsPerMessage}。 */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;


    /** 对照 Go {@code docparser.imageFormats}（builtin_converter.go L20-24，无点形态）。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp");


    private final TemporaryDocumentRepository repo;
    private final AttachmentFileStore fileStore;
    /** 共享 agent 的解析依赖（ASR 模型）与租户级引擎规则回落。 */
    /** 解析/落盘管线切片（§14 步骤 2 第二刀）。 */
    private final TemporaryDocumentProcessor processor;

    /** 提示词渲染切片（§14 步骤 2：ResolveForPrompt 的选块与图片 URL 选取）。 */
    private final TemporaryDocumentPromptResolver promptResolver;

    /** 对照 container.go L1776 的 10 分钟 ticker 周期。 */
    static final java.time.Duration CLEANUP_INTERVAL = java.time.Duration.ofMinutes(10);

    private volatile boolean cleanupStopped;

    public TemporaryDocumentService(TemporaryDocumentRepository repo,
                                    AttachmentFileStore fileStore,
                                    DocReaderClient docReader,
                                    ModelRuntimeFactory modelRuntimeFactory,
                                    AsrTranscriber asrTranscriber,
                                    TenantService tenantService) {
        this.repo = repo;
        this.fileStore = fileStore;
        this.processor = new TemporaryDocumentProcessor(repo, fileStore, docReader,
                modelRuntimeFactory, asrTranscriber, tenantService);
        this.promptResolver = new TemporaryDocumentPromptResolver(repo);
    }

    /**
     * 对照 Go {@code CleanupExpired}（temporary_document.go L757-784）：批 100 扫
     * 过期文档并逐个删除（失败 WARN 继续），直到扫空。由启动 ticker 周期调用
     * （见 {@link #startCleanupTicker}）；durable 的 expires_at 是真源，ticker 只
     * 决定存储回收的速度。
     */
    public void cleanupExpired() {
        while (true) {
            List<TemporaryDocument> documents =
                    repo.listExpired(OffsetDateTime.now(ZoneId.systemDefault()), 100);
            if (documents == null || documents.isEmpty()) {
                return;
            }
            for (TemporaryDocument document : documents) {
                try {
                    delete(document.getTenantId(), document.getSessionId(), document.getId());
                } catch (RuntimeException e) {
                    log.warn("cleanup temporary document failed: document_id={} err={}",
                            document.getId(), e.getMessage());
                }
            }
            if (documents.size() < 100) {
                return;
            }
        }
    }

    /**
     * 对照 Go container.go L1769-1790 的 {@code startTemporaryDocumentCleanup}：
     * 10 分钟周期的后台回收循环（守护虚拟线程）。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startCleanupTicker() {
        Thread.ofVirtual().name("temporary-document-cleanup").start(() -> {
            while (!cleanupStopped) {
                try {
                    Thread.sleep(CLEANUP_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    cleanupExpired();
                } catch (RuntimeException e) {
                    log.warn("[TemporaryDocument] cleanup failed: {}", e.getMessage());
                }
            }
        });
    }

    @jakarta.annotation.PreDestroy
    public void stopCleanupTicker() {
        cleanupStopped = true;
    }


    /**
     * 对照 Go {@code types.TemporaryDocumentCreateOptions}（temporary_document.go L94-110）：
     * ResourceTenantID 是经验证的共享 agent 来源空间（解析依赖范围）；文档行本身仍属
     * 上传方 TenantID。jsonb 键序照 Go struct 声明序、omitempty 零值省略。
     */
    public record CreateOptions(String parserEngine, long resourceTenantId, String asrModelId,
                                String vlmModelId, boolean imageUnderstanding, int ocrMaxPages) {

        public CreateOptions {
            parserEngine = parserEngine == null ? "" : parserEngine;
            asrModelId = asrModelId == null ? "" : asrModelId;
            vlmModelId = vlmModelId == null ? "" : vlmModelId;
        }

        public static CreateOptions empty() {
            return new CreateOptions("", 0, "", "", false, 0);
        }

        public CreateOptions withParserEngine(String v) {
            return new CreateOptions(v == null ? "" : v, resourceTenantId, asrModelId,
                    vlmModelId, imageUnderstanding, ocrMaxPages);
        }

        public CreateOptions withResourceTenantId(long v) {
            return new CreateOptions(parserEngine, v, asrModelId, vlmModelId,
                    imageUnderstanding, ocrMaxPages);
        }

        public CreateOptions withAsrModelId(String v) {
            return new CreateOptions(parserEngine, resourceTenantId, v == null ? "" : v,
                    vlmModelId, imageUnderstanding, ocrMaxPages);
        }

        public CreateOptions withVlm(String vlmModelId, boolean imageUnderstanding,
                                     int ocrMaxPages) {
            return new CreateOptions(parserEngine, resourceTenantId, asrModelId,
                    vlmModelId == null ? "" : vlmModelId, imageUnderstanding, ocrMaxPages);
        }

        /** processing_options 序列化（零值省略；键名＝Java 字段名，§2 第 11 条）。 */
        String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (resourceTenantId != 0) {
                m.put("resourceTenantId", resourceTenantId);
            }
            if (!asrModelId.isEmpty()) {
                m.put("asrModelId", asrModelId);
            }
            if (!parserEngine.isEmpty()) {
                m.put("parserEngine", parserEngine);
            }
            if (!vlmModelId.isEmpty()) {
                m.put("vlmModelId", vlmModelId);
            }
            if (imageUnderstanding) {
                m.put("imageUnderstanding", true);
            }
            if (ocrMaxPages != 0) {
                m.put("ocrMaxPages", ocrMaxPages);
            }
            try {
                return MAPPER.writeValueAsString(m);
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    /** 校验失败抛 IllegalArgumentException（handler 落 400 + 原文）。 */
    public TemporaryDocument create(long tenantId, String sessionId, String fileName,
            String mimeType, long fileSize, byte[] data) {
        return create(tenantId, sessionId, fileName, mimeType, fileSize, data,
                CreateOptions.empty());
    }

    public TemporaryDocument create(long tenantId, String sessionId, String fileName,
            String mimeType, long fileSize, byte[] data, CreateOptions options) {
        if (tenantId == 0 || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("invalid attachment scope");
        }
        ValidatedName name = validateInput(fileName);
        if (!name.valid()) {
            throw new IllegalArgumentException("invalid characters in file name");
        }
        String baseName = AttachmentFileStore.safeFileName(name.value());
        String ext = extOf(baseName); // 带点，如 ".txt"
        if (!supportsExtension(ext)) {
            throw new IllegalArgumentException("unsupported file type: " + ext);
        }
        long maxSize = com.ragagent.knowledge.storage.LocalStorageService.maxFileSizeBytes();
        long maxMb = com.ragagent.knowledge.storage.LocalStorageService.maxFileSizeMb();
        if (fileSize <= 0 || fileSize > maxSize) {
            throw new IllegalArgumentException(
                    "file size must be between 1 byte and " + maxMb + "MB");
        }
        if (data.length > maxSize) {
            throw new IllegalArgumentException("file exceeds size limit of " + maxMb + "MB");
        }
        String storageName = AttachmentFileStore.storageName(ext);
        String resourceRef = fileStore.saveBytes(data, tenantId, storageName);

        TemporaryDocument document = new TemporaryDocument();
        document.setId(java.util.UUID.randomUUID().toString());  // Go BeforeCreate L53-55
        document.setTenantId(tenantId);
        document.setSessionId(sessionId);
        document.setResourceRef(resourceRef);
        document.setFileName(baseName);
        document.setFileType(ext);
        document.setMimeType(mimeType == null ? "" : mimeType.trim());
        document.setFileSize((long) data.length);
        document.setStatus(TemporaryDocument.STATUS_UPLOADED);
        document.setExpiresAt(OffsetDateTime.now(ZoneId.systemDefault()).plusHours(ttlHours()));
        document.setImageRefs("[]");
        document.setMetadata("{}");
        document.setProcessingOptions(
                (options == null ? CreateOptions.empty() : options).toJson());
        document.setChunks("[]");
        document.setErrorMessage("");
        document.setTokenCount(0);
        document.setChunkCount(0);
        // Go 的 created_at/updated_at 由 GORM RETURNING 回填 DB 默认值（UTC 墙钟，
        // golden 实测 …Z 形态）——Java 无 RETURNING 回填，显式按同一语义赋值
        document.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        document.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        try {
            repo.create(document);
        } catch (RuntimeException e) {
            fileStore.deleteFile(resourceRef);
            throw new IllegalArgumentException("create attachment record: " + e.getMessage());
        }
        processor.enqueueProcess(tenantId, document.getId());
        return document;
    }

    /** 对照 Go supportsExtension：静态白名单（ListEngines 未翻译，见类注释）。 */
    private boolean supportsExtension(String ext) {
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 内联 base64 图片（QA 请求 images[].data）的落盘（对照 Go saveImageAttachments
     * 的本地盘 dev 形态）：按魔数嗅探扩展名后存入附件存储，返回 {@code local://} 引用。
     * 引用由 ImageResolver 直接解析成字节进 vision，也随用户消息落库供历史渲染。
     */
    public String saveInlineImageBytes(long tenantId, byte[] data) {
        return fileStore.saveBytes(data, tenantId, "inline-image" + imageExtensionOf(data));
    }

    /** 按魔数嗅探图片扩展名（png/jpeg/gif/webp/bmp），未知回落 .png。 */
    static String imageExtensionOf(byte[] d) {
        if (d == null || d.length < 12) {
            return ".png";
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return ".png";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return ".gif";
        }
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return ".webp";
        }
        if ((d[0] & 0xFF) == 0x42 && (d[1] & 0xFF) == 0x4D) {
            return ".bmp";
        }
        return ".png";
    }

    // ── 查询 / 删除 / 打开 ──────────────────────────────

    public TemporaryDocument get(long tenantId, String sessionId, String documentId) {
        return repo.getScoped(tenantId, sessionId, documentId);
    }

    public List<TemporaryDocument> list(long tenantId, String sessionId) {
        List<TemporaryDocument> documents = repo.listScoped(tenantId, sessionId);
        return documents == null ? List.of() : documents;
    }

    /** 对照 Go Delete（L267-277）：图片引用文件 + 源文件 + 行。 */
    public void delete(long tenantId, String sessionId, String documentId) {
        TemporaryDocument document = repo.getScoped(tenantId, sessionId, documentId);
        if (document == null) {
            return;
        }
        for (Map<?, ?> ref : readJsonArray(document.getImageRefs())) {
            Object url = ref.get("url");
            if (url instanceof String s && !s.isEmpty()) {
                fileStore.deleteFile(s);
            }
        }
        fileStore.deleteFile(document.getResourceRef());
        repo.deleteScoped(tenantId, sessionId, documentId);
    }

    /** 对照 Go OpenFile（L248-261）：返回文件字节与原始文件名。 */
    public record OpenedFile(byte[] data, String fileName) {
    }

    public OpenedFile openFile(long tenantId, String sessionId, String documentId) {
        TemporaryDocument document = repo.getScoped(tenantId, sessionId, documentId);
        if (document == null) {
            throw new AttachmentNotFoundException();
        }
        return new OpenedFile(fileStore.getFile(document.getResourceRef()), document.getFileName());
    }

    /** 附件不存在（handler 落 404 "Attachment not found"）。 */
    public static class AttachmentNotFoundException extends RuntimeException {
    }

    // ── 异步解析（对照 Go Process，L279-348 + parse L350-449） ─────────────





    // ── 辅助（对照 Go 的包级函数） ──────────────────────────────





    // ══ 图片落地（对照 Go ImageResolver.ResolveAndStore 的 docreader 直出分支） ══





    /** 对照 Go {@code docparser.IsImageFormat}（builtin_converter.go L108-110）。 */
    static boolean isImageFormat(String fileType) {
        if (fileType == null) {
            return false;
        }
        String t = fileType.toLowerCase(Locale.ROOT);
        if (t.startsWith(".")) {
            t = t.substring(1);
        }
        return IMAGE_EXTENSIONS.contains(t);
    }

    // ══ ResolveForPrompt（对照 Go temporary_document.go L597-644） ══

    /** 对照 Go {@code types.TemporaryDocumentPromptResult}。 */
    public record PromptResult(List<MessageAttachment> attachments, List<String> imageUrls) {
    }

    /** ResolveForPrompt 的失败（对照 Go 的 error 返回；调用方 warn 后跳过注入）。 */
    public static class AttachmentResolveException extends RuntimeException {
        public AttachmentResolveException(String message) {
            super(message);
        }
    }

    /**
     * 对照 Go {@code ResolveForPrompt}：把 ready 的临时附件按预算选内容，产出
     * 提示词附件列表 + 给 vision 模型的图片 URL（≤ 4 个）。
     *
     * <p>实现已拆至 {@link TemporaryDocumentPromptResolver}（§14 步骤 2），本方法只做薄委托；
     * 错误语义照 Go：文档缺失 / failed / 未 ready 都是 error（调用方记 warn 并
     * 放弃本轮附件注入，不让回合失败）。</p>
     */
    /** 同步跑一次解析（对照 {@code ProcessNow}；流程契约测试的驱动口，实现见 {@link TemporaryDocumentProcessor}）。 */
    public void processNow(long tenantId, String documentId) {
        processor.processNow(tenantId, documentId);
    }

    public PromptResult resolveForPrompt(long tenantId, String sessionId,
            List<String> documentIds, String query) {
        return promptResolver.resolveForPrompt(tenantId, sessionId, documentIds, query);
    }




    /** jsonb 数组读取（{@link TemporaryDocumentPromptResolver} 解析 chunks/image_refs 也用它）。 */
    @SuppressWarnings("unchecked") // MAPPER.readValue(json, List.class) 的原始类型转换
    static List<Map<?, ?>> readJsonArray(String json) {
        // "null" 是 text 路径写入的字面量（Go nil slice 语义），与空数组同义
        if (json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }



    private static long ttlHours() {
        String env = System.getenv("WEKNORA_CHAT_ATTACHMENT_TTL_HOURS");
        if (env != null && !env.isBlank()) {
            try {
                return Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TTL_HOURS;
    }

    /** 对照 Go service 的 ext：filepath.Ext 结果只 Lower 不去点（".txt"）。 */
    static String extOf(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        int idx = lower.lastIndexOf('.');
        return idx < 0 ? "" : lower.substring(idx);
    }

    /** 对照 Go ValidateInput（security.go L81-106）：控制字符 + XSS 模式。 */
    private record ValidatedName(String value, boolean valid) {
    }

    private static ValidatedName validateInput(String input) {
        if (input == null || input.isEmpty()) {
            return new ValidatedName("", true);
        }
        for (int cp : input.codePoints().toArray()) {
            if (cp < 32 && cp != 9 && cp != 10 && cp != 13) {
                return new ValidatedName("", false);
            }
        }
        for (java.util.regex.Pattern p : XSS_PATTERNS) {
            if (p.matcher(input).find()) {
                return new ValidatedName("", false);
            }
        }
        return new ValidatedName(input.trim(), true);
    }

    /** 对照 Go xssPatterns（security.go L29-42）。 */
    private static final List<java.util.regex.Pattern> XSS_PATTERNS = List.of(
            java.util.regex.Pattern.compile("(?i)<script[^>]*>.*?</script>"),
            java.util.regex.Pattern.compile("(?i)<iframe[^>]*>.*?</iframe>"),
            java.util.regex.Pattern.compile("(?i)<object[^>]*>.*?</object>"),
            java.util.regex.Pattern.compile("(?i)<embed[^>]*>.*?</embed>"),
            java.util.regex.Pattern.compile("(?i)<embed[^>]*>"),
            java.util.regex.Pattern.compile("(?i)<form[^>]*>.*?</form>"),
            java.util.regex.Pattern.compile("(?i)<input[^>]*>"),
            java.util.regex.Pattern.compile("(?i)<button[^>]*>.*?</button>"),
            java.util.regex.Pattern.compile("(?i)javascript:"),
            java.util.regex.Pattern.compile("(?i)vbscript:"),
            java.util.regex.Pattern.compile("(?i)onload\\s*="),
            java.util.regex.Pattern.compile("(?i)onerror\\s*="));
}
