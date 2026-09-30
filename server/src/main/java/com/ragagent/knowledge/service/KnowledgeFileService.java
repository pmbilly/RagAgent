package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.InputSanitizer;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ragagent.storage.fileserve.FileTransport.OpenedFile;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 知识文件/行写面：manual 知识更新（全列写语义）、文件下载流、图片信息更新与
 * 图片子块维护。
 */
@Service
public class KnowledgeFileService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChunkMapper chunkMapper;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final KnowledgeService facade;
    private final TenantFileStorage fileStorage;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeService.KnowledgeProcessWorker worker;

    public KnowledgeFileService(
                            ChunkMapper chunkMapper,
                            ChunkVectorIndexer chunkVectorIndexer,
                            @Lazy KnowledgeService facade,
                            TenantFileStorage fileStorage,
                            KnowledgeMapper knowledgeMapper,
                            @Lazy KnowledgeService.KnowledgeProcessWorker worker) {
        this.chunkMapper = chunkMapper;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.facade = facade;
        this.fileStorage = fileStorage;
        this.knowledgeMapper = knowledgeMapper;
        this.worker = worker;
    }

    public Knowledge updateManualKnowledge(String id, String title, String content,
                                           String status, String channel) {
        if (content == null && title == null && status == null && channel == null) {
            throw BizException.badRequest("请求内容不能为空");
        }
        String cleanContent = InputSanitizer.cleanMarkdown(content == null ? "" : content);
        if (cleanContent.trim().isEmpty()) {
            throw new BizException(AppError.validation("内容不能为空"));
        }
        if (cleanContent.length() > 200000) {
            throw new BizException(AppError.validation("内容长度超出限制（最多200000个字符）"));
        }
        String safeTitle = InputSanitizer.validateInput(title == null ? "" : title);
        if (safeTitle == null) {
            throw new BizException(AppError.validation("标题包含非法字符或超出长度限制"));
        }
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase();
        if (normalizedStatus.isEmpty()) {
            normalizedStatus = "draft";
        }
        if (!"draft".equals(normalizedStatus) && !"publish".equals(normalizedStatus)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }

        Knowledge existing = facade.loadKnowledgeWrite(id);
        if (!"manual".equals(existing.getType())) {
            throw BizException.badRequest("仅支持手工知识的在线编辑");
        }
        KnowledgeBase kb = facade.requireKb(existing.getKnowledgeBaseId());

        int version = 1;
        JsonNode oldMeta = existing.getMetadata();
        if (oldMeta != null && oldMeta.hasNonNull("version")) {
            version = oldMeta.path("version").asInt(0) + 1;
            if (version <= 1) {
                version = 1;
            }
        }
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("content", cleanContent);
        meta.put("format", "markdown");
        meta.put("status", normalizedStatus);
        meta.put("version", version);
        meta.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));

        if (!safeTitle.isEmpty()) {
            existing.setTitle(safeTitle);
        } else if (existing.getTitle() == null || existing.getTitle().isEmpty()) {
            existing.setTitle("手工知识-" + DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now()));
        }
        existing.setFileName(KnowledgeService.ensureManualFileName(existing.getTitle()));
        existing.setFileType("manual");
        existing.setType("manual");
        existing.setSource("manual");
        existing.setEnableStatus("disabled");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        existing.setUpdatedAt(now);
        existing.setEmbeddingModelId(kb.getEmbeddingModelId());

        if ("draft".equals(normalizedStatus)) {
            existing.setParseStatus("draft");
            existing.setDescription("");
            existing.setProcessedAt(null);
            updateKnowledgeRow(existing, meta);
            existing.setMetadata(meta);
            return existing;
        }

        // Publish：pending + 异步清理重建
        existing.setParseStatus("pending");
        existing.setDescription("");
        existing.setProcessedAt(null);
        updateKnowledgeRow(existing, meta);
        existing.setMetadata(meta);
        worker.enqueue(existing.getId());
        return existing;
    }

    /**
     * 摘要路径的**窄写入**。
     * <p>❌ 原实现走 {@link #updateKnowledgeRow}（**全列写**）：它会把<b>加载时</b>的旧
     * {@code parse_status} 一并写回。摘要是在导入后处理的 finalizing 交接**之后**才跑完 LLM
     * （数十秒），回写就把 `finalizing/completed` 打回加载时的 {@code processing} ✗；而全列写按
     * （用户报障：`05.03-问题发布.md` 卡 processing ✗）。</p>
     * <p>本方法只写摘要自己那几列（description / summary_status / metadata / updated_at），
     * 绝不碰 parse_status、enable_status、pending_subtasks_count。</p>
     */
    void updateSummaryColumns(Knowledge k) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId())
                .set("description", k.getDescription())
                .set("summary_status", k.getSummaryStatus())
                .set("metadata", k.getMetadata() == null
                                ? JsonNodeFactory.instance.objectNode()
                                : k.getMetadata(),
                        "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set("updated_at", k.getUpdatedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : k.getUpdatedAt()));
    }

    /**
     * 全列写（DeletedAt/PendingSubtasksCount 除外）：
     * description=""/processed_at=NULL 这类零值也必须落库，不能走 MP 默认的跳空列。
     */
    void updateKnowledgeRow(Knowledge k, JsonNode metadata) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId())
                .set("type", k.getType())
                .set("title", k.getTitle())
                .set("description", k.getDescription())
                .set("source", k.getSource())
                .set("parse_status", k.getParseStatus())
                .set("summary_status", k.getSummaryStatus())
                .set("enable_status", k.getEnableStatus())
                .set("embedding_model_id", k.getEmbeddingModelId())
                .set("file_name", k.getFileName())
                .set("file_type", k.getFileType())
                .set("file_size", k.getFileSize() == null ? 0L : k.getFileSize())
                .set("file_hash", k.getFileHash())
                .set("file_path", k.getFilePath())
                .set("metadata", metadata,
                        "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set("updated_at", k.getUpdatedAt())
                .set("processed_at", k.getProcessedAt())
                .set("error_message", k.getErrorMessage()));
    }

    /** 下载文件名清洗：换行/制表删除、斜杠转连字符、引号转单引号、
     *  空白回落 untitled、补 .md 后缀。 */
    public static String sanitizeManualDownloadFilename(String title) {
        String safeName = title == null ? "" : title
                .replace("\n", "").replace("\r", "").replace("\t", "")
                .replace("/", "-").replace("\\", "-").replace("\"", "'");
        if (safeName.trim().isEmpty()) {
            safeName = "untitled";
        }
        if (!safeName.toLowerCase().endsWith(".md")) {
            safeName = safeName + ".md";
        }
        return safeName;
    }

    /** 文件流的打开句柄（filename 是清洗后的下载名；manual = 内存流）。 */
    public record KnowledgeFileStream(String filename,
            OpenedFile opened, boolean manual) {
    }

    /**
     * 打开知识文件流。manual 知识直接读 metadata.content，文档走存储层
     * （本地可 seek → 支持 Range；云按 provider 能力）。不整份读入内存。
     */
    public KnowledgeFileStream openKnowledgeFile(String id) {
        Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (knowledge == null) {
            throw BizException.notFound("record not found");
        }
        if ("manual".equals(knowledge.getType())) {
            String content = knowledge.getMetadata() != null
                    && knowledge.getMetadata().hasNonNull("content")
                    ? knowledge.getMetadata().get("content").asText() : "";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return new KnowledgeFileStream(sanitizeManualDownloadFilename(knowledge.getTitle()),
                    OpenedFile.ofStream(
                            new ByteArrayInputStream(bytes), bytes.length),
                    true);
        }
        String filePath = knowledge.getFilePath() == null ? "" : knowledge.getFilePath();
        return new KnowledgeFileStream(knowledge.getFileName(),
                fileStorage.open(KnowledgeService.tenantId(), filePath), false);
    }

    /**
     * chunk 归属校验（403）、子块 caption/OCR 同步、缺块补建、
     * {@code updateChunkVector(updateChunks + addChunks)}（模型 ID 空 → 1007
     * "model ID cannot be empty"，契约样例锁定）、
     * knowledge.file_hash = md5(knowledgeID+fileHash+imageInfo)。
     */
    @Transactional
    public void updateImageInfo(String knowledgeId, String chunkId, String rawImageInfo) {
        Knowledge knowledge = facade.loadKnowledgeWrite(knowledgeId);
        String imageInfo = CleanInvalidUtf8.clean(rawImageInfo == null ? "" : rawImageInfo);
        final JsonNode images;
        try {
            images = MAPPER.readTree(imageInfo);
        } catch (Exception e) {
            // 只保留解析器首行信息（Jackson 的完整 message 附带源码片段与位置，噪声大）
            String detail = e.getMessage() == null ? "parse failed" : e.getMessage().split("\n", 2)[0];
            throw new BizException(AppError.internal("invalid image info payload: " + detail));
        }
        if (!images.isArray() || images.size() != 1) {
            log.warn("Expected exactly one image info, got {}",
                    images.isArray() ? images.size() : -1);
            return; // 不满足结构 → 静默跳过（响应仍 200）
        }
        JsonNode image = images.get(0);

        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getId, chunkId)
                .eq(Chunk::getTenantId, KnowledgeService.tenantId())
                .isNull(Chunk::getDeletedAt)
                .last("LIMIT 1"));
        if (chunk == null) {
            // 父块缺失 "chunk not found"（handler 包 500）
            throw new BizException(AppError.internal("chunk not found"));
        }
        if (!chunk.getId().equals(chunkId) || !chunk.getKnowledgeId().equals(knowledge.getId())
                || !chunk.getTenantId().equals(knowledge.getTenantId())
                || !chunk.getKnowledgeBaseId().equals(knowledge.getKnowledgeBaseId())) {
            throw BizException.forbidden("chunk does not belong to its knowledge document");
        }
        chunk.setImageInfo(imageInfo);
        long tenantId = KnowledgeService.tenantId();
        List<Chunk> chunkChildren = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getParentChunkId, chunkId)
                .eq(Chunk::getTenantId, tenantId)
                .isNull(Chunk::getDeletedAt));

        List<Chunk> updateChunks = new ArrayList<>();
        updateChunks.add(chunk);
        List<Chunk> addChunks = new ArrayList<>();
        boolean hasOcr = false;
        boolean hasCaption = false;
        String originalUrl = image.path("original_url").asText("");
        String caption = image.path("caption").asText("");
        String ocrText = image.path("ocr_text").asText("");
        for (Chunk child : chunkChildren) {
            JsonNode childImages;
            try {
                childImages = MAPPER.readTree(child.getImageInfo() == null ? "" : child.getImageInfo());
            } catch (Exception e) {
                continue; // 单块失败仅告警
            }
            if (!childImages.isArray() || childImages.isEmpty()) {
                continue;
            }
            if (!originalUrl.equals(childImages.get(0).path("original_url").asText(""))) {
                continue;
            }
            switch (child.getChunkType() == null ? "" : child.getChunkType()) {
                case "image_caption" -> {
                    hasCaption = true;
                    if (!caption.equals(childImages.get(0).path("caption").asText(""))) {
                        child.setContent(caption);
                        child.setImageInfo(imageInfo);
                        updateChunks.add(child);
                    }
                }
                case "image_ocr" -> {
                    hasOcr = true;
                    if (!ocrText.equals(childImages.get(0).path("ocr_text").asText(""))) {
                        child.setContent(ocrText);
                        child.setImageInfo(imageInfo);
                        updateChunks.add(child);
                    }
                }
                default -> {
                }
            }
        }
        if (!hasCaption && !caption.isEmpty()) {
            addChunks.add(newImageChunk(knowledge, chunk, "image_caption", caption, imageInfo));
        }
        if (!hasOcr && !ocrText.isEmpty()) {
            addChunks.add(newImageChunk(knowledge, chunk, "image_ocr", ocrText, imageInfo));
        }
        for (Chunk c : addChunks) {
            chunkMapper.insert(c);
        }
        for (Chunk c : updateChunks) {
            chunkMapper.updateById(c);
        }
        // 向量同步（更新既有 + 新增图片子块两批）：
        // 内部过 NeedsEmbedding（策略判定在 ChunkVectorIndexer）→ GetEmbeddingModel；
        // 模型 ID 空 → 1007 "model ID cannot be empty"（契约样例 kg-image-update/again 钉住）
        List<Chunk> vectorChunks = new ArrayList<>(updateChunks.size() + addChunks.size());
        vectorChunks.addAll(updateChunks);
        vectorChunks.addAll(addChunks);
        chunkVectorIndexer.updateChunkVector(chunk.getKnowledgeBaseId(), vectorChunks);
        // 对照：knowledge.file_hash = calculateStr(knowledgeID, fileHash, imageInfo)
        Knowledge fresh = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (fresh != null) {
            String fileHash = LocalStorageService.md5Hex((knowledgeId + (fresh.getFileHash() == null
                    ? "" : fresh.getFileHash()) + imageInfo)
                    .getBytes(StandardCharsets.UTF_8));
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", fresh.getId())
                    .set("file_hash", fileHash)
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }

    private static Chunk newImageChunk(Knowledge k, Chunk parent, String type,
                                       String content, String imageInfo) {
        Chunk c = new Chunk();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        c.setId(UUID.randomUUID().toString());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setTenantId(k.getTenantId());
        c.setKnowledgeId(parent.getKnowledgeId());
        c.setKnowledgeBaseId(parent.getKnowledgeBaseId());
        c.setContent(content);
        c.setChunkType(type);
        c.setParentChunkId(parent.getId());
        c.setImageInfo(imageInfo);
        c.setIsEnabled(true);
        return c;
    }
}
