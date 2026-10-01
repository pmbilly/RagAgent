package com.ragagent.session.service;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.auth.service.TenantService;
import com.ragagent.knowledge.chunker.Chunker;
import com.ragagent.knowledge.chunker.ParsedChunk;
import com.ragagent.knowledge.chunker.SplitterConfig;
import com.ragagent.knowledge.chunker.Tokens;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.knowledge.support.ParserEngineRules;
import com.ragagent.llm.asr.AsrTranscriber;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;
import com.ragagent.session.service.TemporaryDocumentService.CreateOptions;

/**
 * {@code TemporaryDocumentService} 的**解析 / 落盘管线切片**（§14 步骤 2）：异步投递与重试、
 * docreader → chunker（auto/1600/160）→ ApproxTokenCount → MarkReady、音频转写、
 * 文本与图片落盘（chunks jsonb / image_refs jsonb / markdown 内联图替换）。
 *
 * <p>为什么单独一类：这条管线自成一条单向流（投递 → 取行 → 解析 → 落库 → 标记 ready），
 * 与门面的 HTTP 面（create/get/list/delete/openFile）、生命周期（cleanup ticker）与
 * 提示词渲染切片互不依赖。门面保留 {@code processNow} 薄委托（流程契约测试的驱动口）。</p>
 *
 * <p>共享项处置（§11.17 口径）：{@code MAPPER}/{@code readJsonArray}/{@code extOf}/
 * {@code isImageFormat} 门面也在用（删除/入 KB/附件提示词路径）→ 留门面，本类按类名引用；
 * {@code CreateOptions} 是门面的公开 record → 本类 import 其嵌套类型。</p>
 */
final class TemporaryDocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(TemporaryDocumentProcessor.class);

    /** 对照 Go temporaryTextExtensions（L92-94，带点）。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".markdown", ".txt", ".csv", ".json", ".xml", ".yaml", ".yml", ".log");

    private final TemporaryDocumentRepository repo;
    private final AttachmentFileStore fileStore;
    private final DocReaderClient docReader;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final AsrTranscriber asrTranscriber;
    private final TenantService tenantService;

    TemporaryDocumentProcessor(TemporaryDocumentRepository repo,
                               AttachmentFileStore fileStore,
                               DocReaderClient docReader,
                               ModelRuntimeFactory modelRuntimeFactory,
                               AsrTranscriber asrTranscriber,
                               TenantService tenantService) {
        this.repo = repo;
        this.fileStore = fileStore;
        this.docReader = docReader;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.asrTranscriber = asrTranscriber;
        this.tenantService = tenantService;
    }

    private static final int CHUNK_SIZE = 1600;
    private static final int CHUNK_OVERLAP = 160;
    /** 对照 Go {@code image_resolver.go} 的图标过滤阈值（minImageDimension / minImageBytes）。 */
    static final int MIN_IMAGE_DIMENSION = 64;
    static final int MIN_IMAGE_BYTES = 512;
    /** 解析任务的单线程 executor（对照 asynq worker 的串行消费语义）。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "temporary-document-worker");
        t.setDaemon(true);
        return t;
    });
    /** 对照 Go {@code docparser.audioFormats}（builtin_converter.go L27-30；无点，不含 aac）。 */
    private static final Set<String> AUDIO_FORMAT_EXTENSIONS =
            Set.of("mp3", "wav", "m4a", "flac", "ogg");
    /** 对照 asynq.Enqueue：进程内 executor 投递，失败直接 MarkFailed。 */
    void enqueueProcess(long tenantId, String documentId) {
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
    void processNow(long tenantId, String documentId) {
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
            CreateOptions options = optionsOf(document);
            // 资源租户：共享 agent 的解析依赖范围；文档行仍属上传方（照 Go Process 的
            // "attachment row and file remain scoped to payload.TenantID"）
            long resourceTenantId = options.resourceTenantId() != 0 ? options.resourceTenantId()
                    : tenantId;
            byte[] data = fileStore.getFile(document.getResourceRef());
            String ext = document.getFileType();
            String extNoDot = ext.startsWith(".") ? ext.substring(1) : ext;
            String engine = options.parserEngine();
            if (engine.isEmpty() || "auto".equals(engine)) {
                // 租户级规则兜底（照 Go parse：未显式指定时用租户配置解析）
                engine = tenantParserEngine(resourceTenantId, ext);
            }
            if (TEXT_EXTENSIONS.contains(ext) && (engine.isEmpty() || engine.equals("auto"))) {
                content = new String(data, StandardCharsets.UTF_8);
                metadata = Map.of("parser", "plain_text");
                // Go：nil slice json.Marshal → 4 字节 "null" 字面量写入 jsonb（不是 SQL NULL，
                // golden 实测读回渲染 null）——H2 列 NOT NULL，必须写字符串 "null"
                imageRefs = "null";
            } else if (AUDIO_FORMAT_EXTENSIONS.contains(extNoDot)) {
                // 音频：ASR 转写（照 Go parse 的 docparser.IsAudioFormat 分支）
                content = transcribeAudio(resourceTenantId, options.asrModelId(), data,
                        document.getFileName());
                metadata = Map.of("parser", "asr");
                imageRefs = "null";
            } else {
                // Go 传给 docreader 的 fileType 去掉点（ReadRequest.FileType）
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
    /** 读回 processing_options（键名＝Java 字段名，与 {@code CreateOptions.toJson} 同源）。 */
    static CreateOptions optionsOf(TemporaryDocument document) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = TemporaryDocumentService.MAPPER.readTree(
                    document.getProcessingOptions() == null ? "{}" : document.getProcessingOptions());
            return new CreateOptions(node.path("parserEngine").asText(""),
                    node.path("resourceTenantId").asLong(0),
                    node.path("asrModelId").asText(""),
                    node.path("vlmModelId").asText(""),
                    node.path("imageUnderstanding").asBoolean(false),
                    node.path("ocrMaxPages").asInt(0));
        } catch (Exception e) {
            return CreateOptions.empty();
        }
    }
    /** 对照 Go parse：未显式指定 engine 时用资源租户的 chat 解析规则兜底。 */
    private String tenantParserEngine(long resourceTenantId, String ext) {
        try {
            var tenant = tenantService.getTenantById(resourceTenantId);
            if (tenant == null || tenant.getParserEngineConfig() == null) {
                return "";
            }
            return ParserEngineRules.resolve(
                    tenant.getParserEngineConfig().get("chat_parser_engine_rules"), ext);
        } catch (RuntimeException e) {
            log.warn("failed to resolve tenant parser engine: {}", e.toString());
            return "";
        }
    }
    /** 对照 Go parse 的音频分支：GetASRModel → Transcribe；错误文案逐字（含前缀）。 */
    private String transcribeAudio(long resourceTenantId, String asrModelId, byte[] data,
            String fileName) {
        if (asrModelId.isEmpty()) {
            throw new RuntimeException("audio transcription model is not configured");
        }
        return withResourceTenant(resourceTenantId, () -> {
            com.ragagent.model.domain.Model model;
            try {
                model = modelRuntimeFactory.getAsrModel(asrModelId);
            } catch (RuntimeException e) {
                throw new RuntimeException("load ASR model: " + e.getMessage(), e);
            }
            var p = model.getParameters();
            // 对照 asr.ConfigFromModel：language 不从模型来（恒空），customHeaders 透传
            var config = new AsrTranscriber.AsrConfig(p == null ? "" : p.getBaseUrl(),
                    model.getName(), p == null ? "" : p.getApiKey(), model.getId(), "",
                    p == null ? null : p.getCustomHeaders());
            try {
                return asrTranscriber.transcribe(config, data, fileName).text();
            } catch (RuntimeException e) {
                throw new RuntimeException("transcribe audio: " + e.getMessage(), e);
            }
        });
    }
    /**
     * 临时把线程租户切到资源租户（对照 Go 的
     * {@code ctx = WithValue(TenantIDContextKey, resourceTenantID)}）：ASR 模型的
     * 可见性按共享来源空间解析，调用后恢复原值。
     */
    private <T> T withResourceTenant(long resourceTenantId, java.util.function.Supplier<T> body) {
        Long previous = com.ragagent.common.context.TenantContext.currentTenantId();
        if (previous != null && previous == resourceTenantId) {
            return body.get();
        }
        com.ragagent.common.context.TenantContext.set(resourceTenantId,
                com.ragagent.common.context.TenantContext.currentPrincipal(),
                com.ragagent.common.context.TenantContext.currentRole(),
                com.ragagent.common.context.TenantContext.isSystemAdmin(),
                com.ragagent.common.context.TenantContext.currentUserId(),
                com.ragagent.common.context.TenantContext.canAccessAllTenants());
        try {
            return body.get();
        } finally {
            com.ragagent.common.context.TenantContext.set(previous,
                    com.ragagent.common.context.TenantContext.currentPrincipal(),
                    com.ragagent.common.context.TenantContext.currentRole(),
                    com.ragagent.common.context.TenantContext.isSystemAdmin(),
                    com.ragagent.common.context.TenantContext.currentUserId(),
                    com.ragagent.common.context.TenantContext.canAccessAllTenants());
        }
    }
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
        boolean isOriginalDocument = TemporaryDocumentService.isImageFormat(document.getFileType());
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
                    ext = TemporaryDocumentService.extOf(ref.filename());
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
            jsonItems.add("{\"originalRef\":" + quote(path)
                    + ",\"url\":" + quote(servingUrl)
                    + ",\"mimeType\":" + quote(ref.mimeType() == null ? "" : ref.mimeType()) + "}");
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
    /** chunks jsonb（元素形态＝`DocumentChunk` 的字段名，contextHeader 空时不写）。 */
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
                sb.append(",\"contextHeader\":").append(quote(part.getContextHeader()));
            }
            sb.append(",\"start\":").append(part.getStart())
                    .append(",\"end\":").append(part.getEnd())
                    .append(",\"tokenCount\":")
                    .append(Tokens.approxTokenCount(part.embeddingContent(), lang))
                    .append('}');
        }
        return sb.append(']').toString();
    }
    private static String quote(String value) {
        try {
            return TemporaryDocumentService.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }
    private static String quoteMap(Map<String, String> map) {
        try {
            return TemporaryDocumentService.MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
