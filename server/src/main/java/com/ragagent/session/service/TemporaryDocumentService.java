package com.ragagent.session.service;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /** 对照 Go {@code temporaryDocumentPromptBudget}。 */
    static final int PROMPT_BUDGET_TOKENS = 12_000;

    /** 对照 Go {@code temporaryDocumentInlineTokens}：低于它直接给全文。 */
    static final int PROMPT_INLINE_TOKENS = 12_000;

    /** 对照 Go {@code temporaryDocumentMaxPromptParts}。 */
    static final int MAX_PROMPT_PARTS = 16;

    /** 对照 Go ResolveForPrompt 的硬编码上限 {@code len(result.ImageURLs) < 4}。 */
    static final int MAX_IMAGE_URLS = 4;

    /** 对照 Go {@code types.MaxTemporaryAttachmentsPerMessage}。 */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;

    /** 对照 Go {@code image_resolver.go} 的图标过滤阈值（minImageDimension / minImageBytes）。 */
    static final int MIN_IMAGE_DIMENSION = 64;
    static final int MIN_IMAGE_BYTES = 512;

    /** 对照 Go {@code docparser.imageFormats}（builtin_converter.go L20-24，无点形态）。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp");

    /** 对照 Go {@code isVisualDocumentQuery} 的标记词表。 */
    private static final List<String> VISUAL_QUERY_MARKERS =
            List.of("图", "表格", "截图", "页面", "排版", "chart", "figure", "diagram", "image", "layout");

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
                // 对照 Go parse 里的 imageResolver.ResolveAndStore（L443-457）：docreader
                // 直出的 inline ImageRef 落盘并把 markdown 引用改写成可服务 URL；列表写进
                // image_refs jsonb，供 ResolveForPrompt 提炼给 vision 模型。
                StoredImages stored = storeDocumentImages(tenantId, document, parsed.imageRefs(),
                        parsed.markdown());
                content = stored.markdown();
                metadata = new java.util.LinkedHashMap<>();
                metadata.put("parser", engine.isEmpty() ? "document_reader" : engine);
                imageRefs = stored.imageRefsJson();
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

    // ══ 图片落地（对照 Go ImageResolver.ResolveAndStore 的 docreader 直出分支） ══

    /** {@link #storeDocumentImages} 的结果：重写引用后的 markdown + image_refs jsonb。 */
    record StoredImages(String markdown, String imageRefsJson) {
    }

    /**
     * 对照 Go {@code ImageResolver.ResolveAndStore}（image_resolver.go L80-151）对
     * docreader 直出 {@code ImageRefs} 的处理 + {@code saveReferencedImage}（L156-218）。
     *
     * <p><b>本批收敛</b>：只处理带内联字节的 ImageRef——这是聊天附件的唯一来源
     * （docreader 的 ImageParser 对图片产 {@code ![](images/x.png)} + inline bytes，
     * main.py 的 {@code _resolve_images} 恒填 {@code image_data} 不用 storage_key）；
     * data URI / HTML data URI / bare base64 / 相对路径 HTML 四路（KB 文档解析场景的
     * 边角）备案不实现。</p>
     *
     * <p><b>图标过滤</b>照抄 {@code isIconImage}（两轴均 &lt; 64，或解不开且 &lt; 512 字节）；
     * 图片型附件不过滤——Go 的 SimpleFormatReader 走 {@code imageToResult} 设
     * {@code IsOriginal=true} 跳过判定，Java 从 gRPC 拿不到该字段，以"来源文件本身是
     * 图片格式"（{@code docparser.IsImageFormat}）等价对齐。</p>
     */
    StoredImages storeDocumentImages(long tenantId, TemporaryDocument document,
            List<DocReaderClient.ImageRef> refs, String markdown) {
        if (refs == null || refs.isEmpty()) {
            return new StoredImages(markdown, "[]");
        }
        Map<String, DocReaderClient.ImageRef> refMap = new LinkedHashMap<>();
        for (DocReaderClient.ImageRef ref : refs) {
            if (ref.originalRef() != null && !ref.originalRef().isEmpty()) {
                refMap.put(ref.originalRef(), ref);
            }
        }
        boolean isOriginalDocument = isImageFormat(document.getFileType());
        Map<String, String> savedByFilename = new java.util.HashMap<>();
        List<String> jsonItems = new ArrayList<>();

        // 对照 Go：按 markdown 里出现的图片目标逐个处理（scanMarkdownImageTargets），
        // 命中 refMap 才落盘并改写引用；未被引用的 ref 不落盘。
        java.util.regex.Pattern target = java.util.regex.Pattern.compile(
                "(!\\[[^\\]]*\\]\\()\\s*(<[^>]*>|[^)]*?)\\s*(?:\"[^\"]*\"\\s*)?\\)");
        java.util.regex.Matcher m = target.matcher(markdown);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String rawPath = m.group(2);
            String path = rawPath.startsWith("<") && rawPath.endsWith(">")
                    ? rawPath.substring(1, rawPath.length() - 1) : rawPath;
            DocReaderClient.ImageRef ref = refMap.get(path);
            if (ref == null || ref.imageData() == null || ref.imageData().length == 0) {
                continue; // 对照 saveReferencedImage：无内联字节 → 原样保留
            }
            byte[] bytes = ref.imageData();
            if (!isOriginalDocument && isIconImage(bytes)) {
                continue; // 对照 isIconImage：非原图的图标/装饰元素过滤
            }
            String servingUrl = savedByFilename.get(ref.filename());
            if (servingUrl == null) {
                String ext = extFromMime(ref.mimeType());
                if (ext.isEmpty()) {
                    ext = extOf(ref.filename());
                }
                if (ext.isEmpty()) {
                    ext = ".png";
                }
                try {
                    servingUrl = fileStore.saveBytes(bytes, tenantId,
                            java.util.UUID.randomUUID() + ext);
                } catch (RuntimeException e) {
                    log.warn("failed to save image {}: {}", path, e.getMessage());
                    continue; // 对照 Go：写失败只 WARN 继续
                }
                if (ref.filename() != null && !ref.filename().isEmpty()) {
                    savedByFilename.put(ref.filename(), servingUrl);
                }
            }
            jsonItems.add("{\"original_ref\":" + quote(path)
                    + ",\"url\":" + quote(servingUrl)
                    + ",\"mime_type\":" + quote(ref.mimeType() == null ? "" : ref.mimeType()) + "}");
            // 只换路径本体：保留原目标的尾部（空白 / title / 右括号）
            String tail = m.group(0).substring(m.end(2) - m.start());
            m.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(m.group(1) + servingUrl + tail));
        }
        m.appendTail(out);
        String updated = jsonItems.isEmpty() ? markdown : out.toString();
        return new StoredImages(updated, "[" + String.join(",", jsonItems) + "]");
    }

    /** 对照 Go isIconImage：解码尺寸两轴均 &lt; 64 视为图标；解码失败回退字节数 &lt; 512。 */
    static boolean isIconImage(byte[] data) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(data));
            if (image == null) {
                return data.length < MIN_IMAGE_BYTES;
            }
            return image.getWidth() < MIN_IMAGE_DIMENSION && image.getHeight() < MIN_IMAGE_DIMENSION;
        } catch (Exception e) {
            return data.length < MIN_IMAGE_BYTES;
        }
    }

    /** 对照 Go extFromMime（image_resolver.go L220-239）。 */
    static String extFromMime(String mime) {
        if (mime == null) {
            return "";
        }
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/svg+xml" -> ".svg";
            default -> "";
        };
    }

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
     * 提示词附件列表 + 给 vision 模型的图片 URL（≤ {@value #MAX_IMAGE_URLS} 个）。
     *
     * <p>错误语义照 Go：文档缺失 / failed / 未 ready 都是 error（调用方记 warn 并
     * 放弃本轮附件注入，不让回合失败）。</p>
     */
    public PromptResult resolveForPrompt(long tenantId, String sessionId,
            List<String> documentIds, String query) {
        List<MessageAttachment> attachments = new ArrayList<>();
        List<String> imageUrls = new ArrayList<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return new PromptResult(attachments, imageUrls);
        }
        if (documentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new AttachmentResolveException("a message can use at most "
                    + MAX_ATTACHMENTS_PER_MESSAGE + " attachments");
        }
        int perDocumentBudget = PROMPT_BUDGET_TOKENS / documentIds.size();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String documentId : documentIds) {
            if (!seen.add(documentId)) {
                continue;
            }
            TemporaryDocument document = repo.getScoped(tenantId, sessionId, documentId);
            if (document == null) {
                throw new AttachmentResolveException(
                        "attachment " + documentId + " was not found in this session");
            }
            if (!TemporaryDocument.STATUS_READY.equals(document.getStatus())) {
                if (TemporaryDocument.STATUS_FAILED.equals(document.getStatus())) {
                    throw new AttachmentResolveException("attachment " + document.getFileName()
                            + " failed to parse: " + document.getErrorMessage());
                }
                throw new AttachmentResolveException("attachment " + document.getFileName()
                        + " is still being processed");
            }
            ContentSelection selection = selectContent(document, parseChunks(document.getChunks()),
                    query, perDocumentBudget);
            MessageAttachment att = new MessageAttachment();
            att.setId(document.getId());
            att.setUrl(document.getResourceRef());
            att.setFileName(document.getFileName());
            att.setFileType(document.getFileType());
            att.setFileSize(document.getFileSize());
            att.setContent(selection.content());
            att.setContentMode(selection.selected() == selection.total()
                    ? "full" : "selected_chunks");
            att.setTokenCount(document.getTokenCount());
            att.setSelectedChunks(selection.selected());
            att.setTotalChunks(selection.total());
            attachments.add(att);
            // 图片型附件恒暴露原图给 vision 模型；文本文档只在问题带视觉意图时附带
            // 抽取图（对照 Go 注释：避免无谓的多模态时延）。
            if (isImageFormat(document.getFileType()) || isVisualDocumentQuery(query)) {
                List<String> refs = imageUrlsOf(document.getImageRefs());
                if (refs.isEmpty() && isImageFormat(document.getFileType())
                        && document.getResourceRef() != null && !document.getResourceRef().isEmpty()) {
                    // Java 侧历史行兜底（Go 无此分支——Go 从未存在 image_refs 为空的行）：
                    // 本次图片收口（2026-09-27）之前解析的图片附件 image_refs 恒 "[]"，
                    // 而图片型附件的"原图"就是源文件本身（Go 的 SimpleFormatReader 路径
                    // 直接把原图字节写进 ImageRefs）⇒ 直接回退到 resource_ref，免重解析、
                    // 免再落一份副本；新行（含 image_refs）优先走上面的正常分支。
                    // 源文件已被 TTL 清理时与 Go 的失效 URL 同语义（读不到即回落原文）。
                    refs = List.of(document.getResourceRef());
                }
                for (String url : refs) {
                    if (imageUrls.size() >= MAX_IMAGE_URLS) {
                        break;
                    }
                    imageUrls.add(url);
                }
            }
        }
        return new PromptResult(attachments, imageUrls);
    }

    /** selectContent 的三元返回（对照 Go 的 (content, selected, total)）。 */
    record ContentSelection(String content, int selected, int total) {
    }

    /** 对照 Go TemporaryDocumentChunk（jsonb 元素形态）。 */
    record DocumentChunk(int seq, String content, String contextHeader, int tokenCount) {
    }

    static List<DocumentChunk> parseChunks(String chunksJson) {
        List<DocumentChunk> out = new ArrayList<>();
        for (Map<?, ?> raw : readJsonArray(chunksJson)) {
            out.add(new DocumentChunk(
                    intOf(raw.get("seq")),
                    strOf(raw.get("content")),
                    strOf(raw.get("context_header")),
                    intOf(raw.get("token_count"))));
        }
        return out;
    }

    /** 对照 Go temporaryDocumentImageRefs：image_refs jsonb → 非空 URL 列表。 */
    static List<String> imageUrlsOf(String imageRefsJson) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> ref : readJsonArray(imageRefsJson)) {
            Object url = ref.get("url");
            if (url instanceof String s && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 对照 Go {@code selectTemporaryDocumentContentWithBudget}（L650-705）：
     * 全文（chunks 空或 token 数不超阈值/预算），否则按查询词打分选块
     * （score = Σ 出现次数 × (1 + 词长/2)，降序稳定；按预算与 16 块上限装填，
     * 最后按 seq 升序用 {@code \n\n---\n\n} 拼装）。
     */
    static ContentSelection selectContent(TemporaryDocument document, List<DocumentChunk> chunks,
            String query, int budget) {
        if (budget <= 0) {
            budget = PROMPT_BUDGET_TOKENS;
        }
        if (chunks.isEmpty()
                || (document.getTokenCount() <= PROMPT_INLINE_TOKENS
                        && document.getTokenCount() <= budget)) {
            return new ContentSelection(document.getContent(), chunks.size(), chunks.size());
        }
        List<String> terms = queryTerms(query);
        List<Integer> order = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            order.add(i);
        }
        List<Integer> scores = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            String text = (strOf(chunk.contextHeader()) + "\n" + strOf(chunk.content()))
                    .toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                score += countOccurrences(text, term)
                        * (1 + term.codePointCount(0, term.length()) / 2);
            }
            scores.add(score);
        }
        order.sort((a, b) -> {
            int cmp = Integer.compare(scores.get(b), scores.get(a));
            return cmp != 0 ? cmp : Integer.compare(chunks.get(a).seq(), chunks.get(b).seq());
        });
        List<DocumentChunk> selected = new ArrayList<>();
        int tokens = 0;
        for (int idx : order) {
            if (selected.size() >= MAX_PROMPT_PARTS) {
                break;
            }
            DocumentChunk candidate = chunks.get(idx);
            if (tokens > 0 && tokens + candidate.tokenCount() > budget) {
                continue;
            }
            selected.add(candidate);
            tokens += candidate.tokenCount();
        }
        selected.sort((a, b) -> Integer.compare(a.seq(), b.seq()));
        StringBuilder builder = new StringBuilder();
        for (DocumentChunk part : selected) {
            if (builder.length() > 0) {
                builder.append("\n\n---\n\n");
            }
            if (part.contextHeader() != null && !part.contextHeader().isEmpty()) {
                builder.append(part.contextHeader()).append("\n\n");
            }
            builder.append(part.content() == null ? "" : part.content().trim());
        }
        return new ContentSelection(builder.toString(), selected.size(), chunks.size());
    }

    /** 对照 Go temporaryDocumentQueryTerms（L715-739）：词元 + 相邻汉字二元组。 */
    static List<String> queryTerms(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String field : q.split("[\\s\\p{P}]+")) {
            if (field.codePointCount(0, field.length()) < 2) {
                continue;
            }
            if (seen.add(field)) {
                terms.add(field);
            }
        }
        int[] cps = q.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            if (isHan(cps[i]) && isHan(cps[i + 1])) {
                String term = new String(cps, i, 2);
                if (seen.add(term)) {
                    terms.add(term);
                }
            }
        }
        return terms;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** 对照 Go isVisualDocumentQuery（L741-749）。 */
    static boolean isVisualDocumentQuery(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String marker : VISUAL_QUERY_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go strings.Count（非重叠子串计数）。 */
    static int countOccurrences(String text, String term) {
        if (text == null || term == null || term.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + term.length())) {
            count++;
        }
        return count;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
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
