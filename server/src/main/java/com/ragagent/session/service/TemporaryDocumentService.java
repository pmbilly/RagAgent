package com.ragagent.session.service;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.chunker.Chunker;
import com.ragagent.knowledge.chunker.ParsedChunk;
import com.ragagent.knowledge.chunker.SplitterConfig;
import com.ragagent.knowledge.chunker.Tokens;
import com.ragagent.knowledge.service.DocReaderClient;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;

/**
 * 会话附件服务（对照 Go internal/application/service/temporary_document.go）。
 *
 * 落地：Create（文件名校验/扩展白名单/大小限制/落盘/建行/异步投递）、
 * Get/List/Delete/OpenFile、Process（纯文本直读 + docreader → chunker
 * auto/1600/160 → ApproxTokenCount → MarkReady）。
 *
 * 已知差异（随对应波次收口）：任务队列用进程内 executor（asynq 随波 4）；
 * 扩展白名单静态表（ListEngines 未翻译）；agent_id 门控随波 5；
 * VLM 图片理解 / ASR 依赖运行时模型工厂（阶段 7），OCR/caption 降级跳过。
 */
@Service
public class TemporaryDocumentService {

    private static final Logger log = LoggerFactory.getLogger(TemporaryDocumentService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 Go temporaryDocumentExtensions（L84-90，带点——service 的 ext 不去点）。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".docx", ".doc", ".pdf", ".ppt", ".pptx", ".epub", ".mhtml",
            ".xlsx", ".xls",
            ".md", ".markdown", ".txt", ".csv", ".json", ".xml", ".yaml", ".yml", ".log", ".html",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tiff", ".webp",
            ".mp3", ".wav", ".m4a", ".flac", ".ogg", ".aac");

    /** 对照 Go temporaryTextExtensions（L92-94，带点）。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".markdown", ".txt", ".csv", ".json", ".xml", ".yaml", ".yml", ".log");

    private static final long DEFAULT_TTL_HOURS = 24;
    private static final int CHUNK_SIZE = 1600;
    private static final int CHUNK_OVERLAP = 160;

    /** 解析任务的单线程 executor（对照 asynq worker 的串行消费语义）。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "temporary-document-worker");
        t.setDaemon(true);
        return t;
    });

    private final TemporaryDocumentRepository repo;
    private final AttachmentFileStore fileStore;
    private final DocReaderClient docReader;

    /** 对照 container.go L1776 的 10 分钟 ticker 周期。 */
    static final java.time.Duration CLEANUP_INTERVAL = java.time.Duration.ofMinutes(10);

    private volatile boolean cleanupStopped;

    public TemporaryDocumentService(TemporaryDocumentRepository repo,
                                    AttachmentFileStore fileStore,
                                    DocReaderClient docReader) {
        this.repo = repo;
        this.fileStore = fileStore;
        this.docReader = docReader;
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

    /** 校验失败抛 IllegalArgumentException（handler 落 400 + 原文）。 */
    public TemporaryDocument create(long tenantId, String sessionId, String fileName,
            String mimeType, long fileSize, byte[] data) {
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
        long maxSize = com.ragagent.knowledge.service.LocalStorageService.maxFileSizeBytes();
        long maxMb = com.ragagent.knowledge.service.LocalStorageService.maxFileSizeMb();
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
        document.setProcessingOptions("{}");
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
        enqueueProcess(tenantId, document.getId());
        return document;
    }

    /** 对照 Go supportsExtension：静态白名单（ListEngines 未翻译，见类注释）。 */
    private boolean supportsExtension(String ext) {
        return SUPPORTED_EXTENSIONS.contains(ext);
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

    /** 对照 asynq.Enqueue：进程内 executor 投递，失败直接 MarkFailed。 */
    private void enqueueProcess(long tenantId, String documentId) {
        try {
            executor.submit(() -> processWithRetry(tenantId, documentId));
        } catch (RuntimeException e) {
            log.error("schedule attachment parsing failed: document={}", documentId, e);
            repo.markFailed(tenantId, documentId, "failed to schedule document parsing");
        }
    }

    /** 对照 asynq 的 MaxRetry 2：解析异常最多重试 2 次，仍失败落终态 failed。 */
    private void processWithRetry(long tenantId, String documentId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                process(tenantId, documentId);
                return;
            } catch (RuntimeException e) {
                log.warn("temporary document parse attempt {} failed: document={} {}",
                        attempt + 1, documentId, e.toString());
            }
        }
    }

    /** 供测试直调（绕过 executor 的时序）。 */
    public void processNow(long tenantId, String documentId) {
        process(tenantId, documentId);
    }

    private void process(long tenantId, String documentId) {
        TemporaryDocument document = repo.getById(tenantId, documentId);
        if (document == null || TemporaryDocument.STATUS_READY.equals(document.getStatus())) {
            return;
        }
        repo.markProcessing(tenantId, documentId, OffsetDateTime.now(ZoneId.systemDefault()));

        String content;
        String imageRefs;
        Map<String, String> metadata;
        try {
            byte[] data = fileStore.getFile(document.getResourceRef());
            String ext = document.getFileType();
            String engine = parseEngineOf(document);
            if (TEXT_EXTENSIONS.contains(ext) && (engine.isEmpty() || engine.equals("auto"))) {
                content = new String(data, StandardCharsets.UTF_8);
                metadata = Map.of("parser", "plain_text");
                // Go：nil slice json.Marshal → 4 字节 "null" 字面量写入 jsonb（不是 SQL NULL，
                // golden 实测读回渲染 null）——H2 列 NOT NULL，必须写字符串 "null"
                imageRefs = "null";
            } else {
                // Go 传给 docreader 的 fileType 去掉点（ReadRequest.FileType）
                String extNoDot = ext.startsWith(".") ? ext.substring(1) : ext;
                DocReaderClient.ParseResult parsed = docReader.read(data, document.getFileName(),
                        extNoDot, document.getFileName(), "auto".equals(engine) ? "" : engine);
                content = parsed.markdown();
                metadata = new java.util.LinkedHashMap<>();
                metadata.put("parser", engine.isEmpty() ? "document_reader" : engine);
                // docreader 路径 images 是非 nil 空 slice → "[]"（VLM 图片理解随波 5/7）
                imageRefs = "[]";
            }
        } catch (Exception parseErr) {
            String message = String.valueOf(parseErr.getMessage());
            if (message.length() > 2000) {
                message = message.substring(0, 2000);
            }
            repo.markFailed(tenantId, documentId, message);
            log.error("temporary document parse failed: document={} {}", documentId, message);
            return;
        }

        content = cleanInvalidUtf8(content);
        String lang = Tokens.detectLanguage(content);
        SplitterConfig cfg = new SplitterConfig();
        cfg.setChunkSize(CHUNK_SIZE);
        cfg.setChunkOverlap(CHUNK_OVERLAP);
        cfg.setStrategy(Chunker.STRATEGY_AUTO);
        List<ParsedChunk> parts = Chunker.split(content, cfg);
        repo.markReady(tenantId, documentId, content, chunksJson(parts, lang), imageRefs,
                quoteMap(metadata), Tokens.approxTokenCount(content, lang),
                parts.size(), OffsetDateTime.now(ZoneId.systemDefault()));
    }

    // ── 辅助（对照 Go 的包级函数） ──────────────────────────────

    /** 对照 Go processing_options → ParserEngine。 */
    private static String parseEngineOf(TemporaryDocument document) {
        try {
            Map<?, ?> options = MAPPER.readValue(
                    document.getProcessingOptions() == null ? "{}" : document.getProcessingOptions(),
                    Map.class);
            Object engine = options.get("parser_engine");
            return engine instanceof String s ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 对照 Go common.CleanInvalidUTF8：替换非法 UTF-8 序列（REPLACE 语义）。 */
    static String cleanInvalidUtf8(String value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)))
                .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return value;
        }
    }

    /** chunks jsonb（对照 Go TemporaryDocumentChunk 的 json tag，context_header omitempty）。 */
    private static String chunksJson(List<ParsedChunk> parts, String lang) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ParsedChunk part : parts) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"seq\":").append(part.getSeq())
                    .append(",\"content\":").append(quote(part.getContent()));
            if (!part.getContextHeader().isEmpty()) {
                sb.append(",\"context_header\":").append(quote(part.getContextHeader()));
            }
            sb.append(",\"start\":").append(part.getStart())
                    .append(",\"end\":").append(part.getEnd())
                    .append(",\"token_count\":")
                    .append(Tokens.approxTokenCount(part.embeddingContent(), lang))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static List<Map<?, ?>> readJsonArray(String json) {
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

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String quoteMap(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
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
    private static String extOf(String fileName) {
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
