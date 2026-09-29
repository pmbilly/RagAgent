package com.ragagent.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.domain.ChunkRevisionConflictException;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.security.ChunkAccessGuard;

/**
 * chunk 版本化编辑面：乐观锁更新的编辑/回滚/修订历史、软删除、图片子块联动与父内容重建，
 * 编辑后同步向量索引并按需入队摘要刷新。
 */
@Service
public class ChunkEditService {

    private static final Logger log = LoggerFactory.getLogger(ChunkEditService.class);

    private static final String CHUNK_TYPE_TEXT = "text";
    private static final String CHUNK_TYPE_IMAGE_OCR = "image_ocr";
    private static final String CHUNK_TYPE_IMAGE_CAPTION = "image_caption";
    private static final int MAX_EDITABLE_CHUNK_LENGTH = 200000;
    private static final String SUMMARY_STATUS_NONE = "none";

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final KnowledgeService knowledgeService;
    private final ChunkAccessGuard guard;

    public ChunkEditService(ChunkRepository chunkRepository,
                            KnowledgeMapper knowledgeMapper,
                            ChunkVectorIndexer chunkVectorIndexer,
                            KnowledgeService knowledgeService,
                            ChunkAccessGuard guard) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.knowledgeService = knowledgeService;
        this.guard = guard;
    }

    private static long mustTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant id is not in context");
        }
        return tid;
    }

    // ── 更新（乐观、版本化编辑）────────────────────────────────────────────

    /**
     * 生成问题的索引跨内容编辑
     * 保留；当前行在重索引失败时仍然落库并暴露 index_status=failed。逐段对照：
     * <ol>
     *   <li>writableChunk（AppError 直通）；非 text 块 → 500 面；关系校验；乐观锁预检；</li>
     *   <li>无变化路径：index_status=failed 时走"重试索引"（rebuildParent → processing →
     *       syncChunkIndex → ready），否则原样返回；</li>
     *   <li>有变化路径：先 {@code validateEditedChunkImages}（source_content 惰性回填之后），
     *       再写 revision 快照（快照记<b>上一个</b> editor 与旧内容）+ 乐观锁 UPDATE；</li>
     *   <li>bodyChanged 时重建父内容；bodyChanged 或 enabled 变化时同步图片子块、
     *       尝试 summary 刷新入队；最后 syncChunkIndex 定 ready/failed。</li>
     * </ol>
     * 每个失败分支的 index_status/返回语义（失败标 failed 后<b>返回 chunk 不抛</b>）。
     */
    public Chunk updateDocumentChunk(String chunkId, String content, Boolean isEnabled,
                                     Integer expectedRevision) {
        Chunk chunk = guard.writableChunk(chunkId);
        if (!CHUNK_TYPE_TEXT.equals(chunk.getChunkType())) {
            throw new IllegalStateException("only text chunks can be edited");
        }
        guard.validateDocumentChunkRelations(chunk.getTenantId(), chunk);
        if (expectedRevision != null && expectedRevision != chunk.getContentRevision()) {
            throw new ChunkRevisionConflictException();
        }

        String newContent = chunk.getContent();
        if (content != null) {
            newContent = ChunkSearchUtil.trimSpace(content);
            if (newContent.isEmpty()) {
                throw new IllegalStateException("chunk content cannot be empty");
            }
            if (newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EDITABLE_CHUNK_LENGTH) {
                throw new IllegalStateException("chunk content exceeds " + MAX_EDITABLE_CHUNK_LENGTH + " bytes");
            }
        }
        boolean newEnabled = isEnabled != null ? isEnabled : chunk.isIsEnabled();
        if (newContent.equals(chunk.getContent()) && newEnabled == chunk.isIsEnabled()) {
            // 无变化路径：只重试卡在 failed 的索引
            if ("failed".equals(chunk.getIndexStatus())) {
                if (!orEmpty(chunk.getParentChunkId()).isEmpty() && chunk.getContentRevision() > 0) {
                    try {
                        rebuildParentContent(chunk);
                    } catch (RuntimeException e) {
                        log.warn("Failed to rebuild parent chunk while retrying edit: {}", e.getMessage());
                        return chunk;
                    }
                }
                chunk.setIndexStatus("processing");
                updateChunkIgnoreError(chunk);
                try {
                    chunkVectorIndexer.syncChunkIndex(chunk);
                } catch (RuntimeException e) {
                    chunk.setIndexStatus("failed");
                    updateChunkIgnoreError(chunk);
                    return chunk;
                }
                chunk.setIndexStatus("ready");
                chunkRepository.updateChunk(chunk);
            }
            return chunk;
        }
        if (content != null) {
            String sourceContent = chunk.getSourceContent();
            if (sourceContent == null || sourceContent.isEmpty()) {
                sourceContent = chunk.getContent();
            }
            validateEditedChunkImages(sourceContent, newContent);
        }

        String actorId = TenantContext.currentUserId();
        if (actorId == null) {
            actorId = "";
        }
        OffsetDateTime now = OffsetDateTime.now();
        int oldRevision = chunk.getContentRevision();
        ChunkRevision revision = new ChunkRevision();
        revision.setId(UUID.randomUUID().toString());
        revision.setTenantId(chunk.getTenantId());
        revision.setKnowledgeBaseId(chunk.getKnowledgeBaseId());
        revision.setKnowledgeId(chunk.getKnowledgeId());
        revision.setChunkId(chunk.getId());
        revision.setRevision(oldRevision);
        revision.setContent(chunk.getContent());
        revision.setEnabled(chunk.isIsEnabled());
        revision.setEditorId(chunk.getLastEditorId() == null ? "" : chunk.getLastEditorId());
        revision.setEditSource("user");
        revision.setEditedAt(chunk.getUpdatedAt());
        revision.setCreatedAt(now);
        if (chunk.getSourceContent() == null || chunk.getSourceContent().isEmpty()) {
            chunk.setSourceContent(chunk.getContent()); // source_content 惰性回填
        }
        boolean bodyChanged = !newContent.equals(chunk.getContent());
        chunk.setContent(newContent);
        chunk.setIsEnabled(newEnabled);
        chunk.setContentRevision(oldRevision + 1);
        chunk.setLastEditorId(actorId);
        chunk.setIndexStatus("processing");
        chunk.setUpdatedAt(now);
        // 乐观锁 UPDATE + 快照 INSERT（同事务）；影响行数 != 1 抛 ChunkRevisionConflictException
        chunkRepository.saveChunkRevision(chunk, revision, oldRevision);

        if (bodyChanged && !orEmpty(chunk.getParentChunkId()).isEmpty()) {
            try {
                rebuildParentContent(chunk);
            } catch (RuntimeException e) {
                log.warn("Failed to rebuild parent chunk after edit: {}", e.getMessage());
                chunk.setIndexStatus("failed");
                updateChunkIgnoreError(chunk);
                return chunk;
            }
        }
        if (bodyChanged || newEnabled != revision.isEnabled()) {
            try {
                syncEditedChunkImages(chunk);
            } catch (RuntimeException e) {
                log.warn("Failed to synchronize image children after chunk edit: {}", e.getMessage());
                chunk.setIndexStatus("failed");
                updateChunkIgnoreError(chunk);
                return chunk;
            }
        }
        if (bodyChanged || newEnabled != revision.isEnabled()) {
            Knowledge knowledge = findKnowledgeRow(chunk.getTenantId(), chunk.getKnowledgeId());
            if (knowledge != null) {
                try {
                    enqueueSummaryRefresh(knowledge);
                } catch (RuntimeException e) {
                    log.warn("Chunk saved but summary refresh enqueue failed for {}: {}",
                            knowledge.getId(), e.getMessage());
                }
            }
        }
        try {
            chunkVectorIndexer.syncChunkIndex(chunk);
        } catch (RuntimeException e) {
            chunk.setIndexStatus("failed");
            updateChunkIgnoreError(chunk);
            log.error("Chunk {} saved but reindex failed: {}", chunk.getId(), e.getMessage());
            return chunk;
        }
        chunk.setIndexStatus("ready");
        chunkRepository.updateChunk(chunk);
        return chunk;
    }

    /**
     * 取修订快照后按其内容/启用态
     * （RevertChunk 对非 AppError 用 NewBadRequestError）——Java 直接抛同文案的
     * BizException.badRequest，HTTP 面一致。
     */
    public Chunk revertDocumentChunk(String chunkId, int revision, Integer expectedRevision) {
        long tenantId = mustTenantId();
        ChunkRevision item = chunkRepository.getChunkRevision(tenantId, chunkId, revision);
        if (item == null) {
            throw BizException.badRequest("record not found");
        }
        return updateDocumentChunk(chunkId, item.getContent(), item.isEnabled(), expectedRevision);
    }

    /** revision DESC。 */
    public List<ChunkRevision> listChunkRevisions(String chunkId) {
        return chunkRepository.listChunkRevisions(mustTenantId(), chunkId);
    }


    // ── 删除 ───────────────────────────────────────────────────────────────

    /**
     * writableChunk 失败原样上抛
     * （AppError/500 面各自的形态）；成功路径仓储软删无额外错误（不存在时静默 no-op）。
     */
    public void deleteChunk(String id) {
        guard.writableChunk(id);
        long tenantId = mustTenantId();
        chunkRepository.deleteChunk(tenantId, id);
        log.info("Chunk deleted successfully");
    }

    /**
     * 删除前经
     * loadKnowledgeWriteBatch 校验（单 ID 的批校验塌缩成一行——blank → 400
     * "resource ID cannot be empty"、缺失 → 404 "knowledge not found"、moving → 409、
     * KB 绑定校验）。requireKBWrite 略（见类注释第 5 条）。
     */
    public void deleteChunksByKnowledgeId(String knowledgeId) {
        if (knowledgeId == null || ChunkSearchUtil.trimSpace(knowledgeId).isEmpty()) {
            throw BizException.badRequest("resource ID cannot be empty");
        }
        long tenantId = writeExecutionTenant();
        Knowledge row = findKnowledgeRow(tenantId, knowledgeId);
        if (row == null) {
            throw BizException.notFound("knowledge not found");
        }
        ChunkAccessGuard.rejectMovingKnowledge(row);
        guard.knowledgeWriteKB(row);
        log.info("Start deleting all chunks by knowledge ID: {}", knowledgeId);
        chunkRepository.deleteChunksByKnowledgeId(tenantId, knowledgeId);
        log.info("All chunks under knowledge deleted successfully");
    }


    // ── 图片子块 / 父内容重建 ───────────────────────────────────────────────

    /**
     * 编辑后的 content 不得
     * 单个违规 URL 时逐字一致。
     */
    private static void validateEditedChunkImages(String sourceContent, String editedContent) {
        Set<String> allowed = ChunkSearchUtil.imageURLsInContent(sourceContent);
        for (String url : ChunkSearchUtil.imageURLsInContent(editedContent)) {
            if (!allowed.contains(url)) {
                throw new IllegalStateException(
                        "adding images to an existing chunk is not supported: " + url);
            }
        }
    }
    private static boolean imageChildMatchesContent(Chunk child, Set<String> contentUrls) {
        for (String url : ChunkSearchUtil.imageURLsFromInfo(child.getImageInfo())) {
            if (contentUrls.contains(url)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Markdown 图被删后把对应
     * 的 image_ocr / image_caption 子块停用（软停用而非硬删，回滚到历史 revision 可再启用）。
     * 子块索引同步失败 → 子块标 failed 后<b>重抛</b>（上层把主块也标 failed）。
     */
    private void syncEditedChunkImages(Chunk chunk) {
        List<Chunk> children = chunkRepository.listChunkByParentId(chunk.getTenantId(), chunk.getId());
        Set<String> contentUrls = ChunkSearchUtil.imageURLsInContent(chunk.getContent());
        for (Chunk child : children) {
            if (!CHUNK_TYPE_IMAGE_OCR.equals(child.getChunkType())
                    && !CHUNK_TYPE_IMAGE_CAPTION.equals(child.getChunkType())) {
                continue;
            }
            boolean desiredEnabled = chunk.isIsEnabled() && imageChildMatchesContent(child, contentUrls);
            if (child.isIsEnabled() == desiredEnabled && "ready".equals(child.getIndexStatus())) {
                continue;
            }
            child.setIsEnabled(desiredEnabled);
            child.setIndexStatus("processing");
            child.setUpdatedAt(OffsetDateTime.now());
            chunkRepository.updateChunk(child);
            try {
                chunkVectorIndexer.syncChunkIndex(child);
            } catch (RuntimeException e) {
                child.setIndexStatus("failed");
                updateChunkIgnoreError(child);
                throw e;
            }
            child.setIndexStatus("ready");
            chunkRepository.updateChunk(child);
        }
    }

    /**
     * 把手工编辑过的子块区间
     * 覆盖到不可变的父块原文上。按偏移<b>倒序</b>应用替换，即便编辑文本长度变化也保持
     * 解析器坐标系；互相重叠的替换无法共用同一段源区间——保留最新编辑，其余冲突的当前
     * 正文经 {@link ChunkSearchUtil#joinChunkContent} 追加（检索宁可少量重复也不静默丢内容）。
     * 偏移按 rune（Unicode code point）计。
     */
    private void rebuildParentContent(Chunk edited) {
        long tenantId = edited.getTenantId();
        Chunk parent = chunkRepository.getChunkById(tenantId, edited.getParentChunkId());
        List<Chunk> children = chunkRepository.listChunkByParentId(tenantId, parent.getId());
        String base = parent.getSourceContent() == null ? "" : parent.getSourceContent();
        if (base.isEmpty()) {
            base = parent.getContent();
            parent.setSourceContent(base);
        }
        int[] baseRunes = ChunkSearchUtil.toRunes(base);
        record Replacement(int start, int end, String content, OffsetDateTime updatedAt) {
        }
        List<Replacement> replacements = new ArrayList<>();
        for (Chunk child : children) {
            if (child.getContentRevision() == 0) {
                continue;
            }
            int start = child.getStartAt() - parent.getStartAt();
            int end = child.getEndAt() - parent.getStartAt();
            if (start >= 0 && end >= start && end <= baseRunes.length) {
                replacements.add(new Replacement(start, end, child.getContent(), child.getUpdatedAt()));
            }
        }
        replacements.sort(Comparator.comparing(Replacement::updatedAt).reversed());
        List<Replacement> selected = new ArrayList<>();
        List<Replacement> conflicts = new ArrayList<>();
        for (Replacement candidate : replacements) {
            boolean overlaps = false;
            for (Replacement existing : selected) {
                if (candidate.start() < existing.end() && candidate.end() > existing.start()) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                selected.add(candidate);
            } else {
                conflicts.add(candidate);
            }
        }
        selected.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement repl : selected) {
            int[] content = ChunkSearchUtil.toRunes(repl.content());
            int[] out = new int[(repl.start()) + content.length + (baseRunes.length - repl.end())];
            int k = 0;
            for (int i = 0; i < repl.start(); i++) {
                out[k++] = baseRunes[i];
            }
            for (int c : content) {
                out[k++] = c;
            }
            for (int i = repl.end(); i < baseRunes.length; i++) {
                out[k++] = baseRunes[i];
            }
            baseRunes = out;
        }
        parent.setContent(ChunkSearchUtil.fromRunes(baseRunes));
        for (Replacement conflict : conflicts) {
            parent.setContent(ChunkSearchUtil.joinChunkContent(parent.getContent(), conflict.content(), "\n\n"));
        }
        parent.setUpdatedAt(OffsetDateTime.now());
        chunkRepository.updateChunk(parent);
    }


    /** 未软删的 knowledge 行（租户过滤）。 */
    private Knowledge findKnowledgeRow(long tenantId, String id) {
        return knowledgeMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** null（H2 可空列）按空串处理。 */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 尽力写：失败只记 debug 日志（对齐既有"忽略更新失败"语义）。 */
    private void updateChunkIgnoreError(Chunk chunk) {
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            log.debug("ignored chunk update failure for {}: {}", chunk.getId(), e.getMessage());
        }
    }

    /** 执行租户：缺或 0 → 401 "workspace context unavailable"。 */
    private static long writeExecutionTenant() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null || tid == 0L) {
            throw BizException.unauthorized("workspace context unavailable");
        }
        return tid;
    }

    /** 已有摘要的 knowledge 在内容/启用态变化后入队摘要刷新（失败仅告警，不打断编辑）。 */
    private void enqueueSummaryRefresh(Knowledge knowledge) {
        if (knowledge == null) {
            return;
        }
        String status = knowledge.getSummaryStatus();
        if (status == null || status.isEmpty() || SUMMARY_STATUS_NONE.equals(status)) {
            return;
        }
        knowledgeService.requestKnowledgeSummaryRefresh(knowledge.getId());
    }
}
