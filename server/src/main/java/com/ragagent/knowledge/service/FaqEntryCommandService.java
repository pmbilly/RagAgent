package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.faq.FaqEntry;
import com.ragagent.knowledge.repository.FaqChunkRepository;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.model.domain.Model;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.knowledge.task.FaqImportTaskStore;
import com.ragagent.knowledge.security.FaqGuard;
import com.ragagent.knowledge.dto.faq.FaqEntryPayload;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsUpdate;
import com.ragagent.retrieval.engine.VectorStoreService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * FAQ 条目命令面：创建、更新、相似问追加、批量字段/标签更新与删除，
 * 附 KB 活动审计与重复问校验。写路径的域校验经 {@link FaqGuard}，
 * chunk 落库与向量索引进 {@link FaqIndexWriter}。
 */
@Service
public class FaqEntryCommandService {

    private static final Logger log = LoggerFactory.getLogger(FaqEntryCommandService.class);

    private final ChunkRepository chunkRepository;
    private final FaqChunkRepository faqChunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeTagMapper tagMapper;
    private final FaqImportTaskStore taskStore;
    private final VectorStoreService vectorStore;
    private final KnowledgeVectorWrites vectorWrites;
    private final AuditLogService auditService;
    private final FaqGuard faqGuard;
    private final FaqChunkCodec faqChunkCodec;
    private final FaqIndexWriter faqIndexWriter;

    public FaqEntryCommandService(ChunkRepository chunkRepository,
                                  KnowledgeMapper knowledgeMapper,
                                  KnowledgeTagMapper tagMapper,
                                  FaqImportTaskStore taskStore,
                                  VectorStoreService vectorStore,
                                  KnowledgeVectorWrites vectorWrites,
                                  AuditLogService auditService,
                                  FaqGuard faqGuard,
                                  FaqChunkCodec faqChunkCodec,
                                  FaqIndexWriter faqIndexWriter,
                                  FaqChunkRepository faqChunkRepository) {
        this.chunkRepository = chunkRepository;
        this.faqChunkRepository = faqChunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.tagMapper = tagMapper;
        this.taskStore = taskStore;
        this.vectorStore = vectorStore;
        this.vectorWrites = vectorWrites;
        this.auditService = auditService;
        this.faqGuard = faqGuard;
        this.faqChunkCodec = faqChunkCodec;
        this.faqIndexWriter = faqIndexWriter;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ══════════════════ 创建 ═══════════════════════════════════════════

    /**
     * 判定顺序 契约样例依赖：
     * sanitize → tag 解析 → create guard → 重复检查 → 容器 → index mode →
     * <b>GetEmbeddingModel（plain 500 分支）</b> → 建 chunk → 索引（失败回滚 chunk）。
     */
    public FaqEntry createEntry(String kbId, FaqEntryPayload payload) {
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        FaqChunkMetadata meta = faqGuard.sanitizeFAQEntryPayload(payload);
        String tagID = faqGuard.resolveTagID(kb.getId(), payload);

        String guardKey = "faq:create:" + tid + ":" + kb.getId() + ":" + sha256Hex(meta.standardQuestion);
        if (!taskStore.acquireCreateGuard(guardKey)) {
            throw new BizException(AppError.conflict("相同标准问的 FAQ 条目正在创建中，请勿重复提交"));
        }
        try {
            checkFAQQuestionDuplicate(tid, kb.getId(), "", meta);

            Knowledge faqKnowledge = faqIndexWriter.ensureFAQKnowledge(tid, kb);
            if (faqKnowledge == null) {
                throw new IllegalStateException("failed to ensure FAQ knowledge: knowledge not found");
            }

            String indexMode = faqChunkCodec.faqIndexMode(kb);

            // GetEmbeddingModel：模型行缺失/ID 空 → plain 500（handler c.Error 的非 AppError 分支）
            Model embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);

            boolean isEnabled = payload.enabled() == null || payload.enabled();
            int flags = payload.recommended() != null && !payload.recommended() ? 0 : 1;

            Chunk chunk = new Chunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setTenantId(tid);
            chunk.setKnowledgeId(faqKnowledge.getId());
            chunk.setKnowledgeBaseId(kb.getId());
            chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));
            chunk.setIsEnabled(isEnabled);
            chunk.setFlags(flags);
            chunk.setChunkType("faq");
            chunk.setTagId(tagID);
            chunk.setStatus(1); // stored
            if (payload.id() != null && payload.id() > 0) {
                chunk.setSeqId(payload.id());
            }
            faqChunkCodec.setFaqMetadata(chunk, meta);
            if (chunk.getCreatedAt() == null) {
                chunk.setCreatedAt(OffsetDateTime.now());
                chunk.setUpdatedAt(chunk.getCreatedAt());
            }
            faqIndexWriter.createChunks(List.of(chunk));

            // 索引步：
            try {
                faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, true);
            } catch (RuntimeException indexErr) {
                chunkRepository.deleteChunk(tid, chunk.getId());
                throw new IllegalStateException("failed to index chunk: " + indexErr.getMessage(), indexErr);
            }

            chunk.setStatus(2); // indexed
            chunkRepository.updateChunk(chunk);

            Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
            if (!chunk.getTagId().isEmpty()) {
                KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                        .stream().findFirst().orElse(null);
                if (tag != null) {
                    tagSeqIdMap.put(tag.getId(), tag.getSeqId());
                }
            }
            FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
            if (!chunk.getTagId().isEmpty()) {
                KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                        .stream().findFirst().orElse(null);
                if (tag != null) {
                    entry = entry.withTagName(tag.getName());
                }
            }
            log.info("FAQ entry created: kb={}, entry={}", kb.getId(), chunk.getSeqId());
            recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_CREATED,
                    "faq_entry", chunk.getId(),
                    Map.of("entry_id", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                            "source_type", "faq"));
            return entry;
        } finally {
            taskStore.releaseCreateGuard(guardKey);
        }
    }

    // ══════════════════ 更新 / 相似问 ══════════════════════════════════

    /**
     * * <b>先落库后失败</b>：UpdateChunk 在 GetEmbeddingModel 之前——无模型 KB 上
     * 返回 plain 500 但变更已持久化（契约样例 faq-get-after-update 钉住，照抄别修）。
     */
    public FaqEntry updateEntry(String kbId, long entrySeqId, FaqEntryPayload payload) {
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = faqChunkRepository.getChunkBySeqId(tid, entrySeqId);
        if (chunk == null) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!kb.getId().equals(chunk.getKnowledgeBaseId())) {
            throw new BizException(AppError.forbidden("无权操作该 FAQ 条目"));
        }
        if (!"faq".equals(chunk.getChunkType())) {
            throw new BizException(AppError.badRequest("仅支持更新 FAQ 条目"));
        }
        FaqChunkMetadata meta = faqGuard.sanitizeFAQEntryPayload(payload);

        checkFAQQuestionDuplicate(tid, kb.getId(), chunk.getId(), meta);

        FaqChunkMetadata existing = faqChunkCodec.currentFaqMetadata(chunk);
        if (existing != null) {
            meta.version = existing.version + 1;
        }
        faqChunkCodec.setFaqMetadata(chunk, meta);

        String indexMode = faqChunkCodec.faqIndexMode(kb);
        chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));

        if (payload.tagId() > 0) {
            KnowledgeTag tag = tagMapper.selectByTenantAndSeqId(tid, payload.tagId());
            if (tag == null) {
                throw new BizException(AppError.notFound("标签不存在"));
            }
            chunk.setTagId(tag.getId());
        } else {
            chunk.setTagId("");
        }
        if (payload.enabled() != null) {
            chunk.setIsEnabled(payload.enabled());
        }
        if (payload.recommended() != null) {
            if (payload.recommended()) {
                chunk.setFlags(chunk.getFlags() | 1);
            } else {
                chunk.setFlags(chunk.getFlags() & ~1);
            }
        }
        chunk.setUpdatedAt(OffsetDateTime.now());
        chunkRepository.updateChunk(chunk);

        Knowledge faqKnowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, chunk.getKnowledgeId())
                .eq(Knowledge::getTenantId, tid)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (faqKnowledge == null) {
            throw new IllegalStateException("failed to get knowledge: record not found");
        }

        // GetEmbeddingModel；无模型的 KB 在这里 plain 500（变更已持久化）
        Model embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);
        // separate 模式相似问减少时先删多余 sourceID——Java 的
        // indexFAQChunks 全删该 chunk 行后重插（净效果等价）；索引失败原样返回
        faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                tagSeqIdMap.put(tag.getId(), tag.getSeqId());
            }
        }
        FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                entry = entry.withTagName(tag.getName());
            }
        }
        log.info("FAQ entry updated: kb={}, entry={}", kb.getId(), chunk.getSeqId());
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", chunk.getId(),
                Map.of("entry_id", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                        "source_type", "faq"));
        return entry;
    }

    /** ；同款先落库后 500。 */
    public FaqEntry addSimilarQuestions(String kbId, long entrySeqId, List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new BizException(AppError.badRequest("相似问列表不能为空"));
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = faqChunkRepository.getChunkBySeqId(tid, entrySeqId);
        if (chunk == null) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!kb.getId().equals(chunk.getKnowledgeBaseId())) {
            throw new BizException(AppError.forbidden("无权操作该 FAQ 条目"));
        }
        if (!"faq".equals(chunk.getChunkType())) {
            throw new BizException(AppError.badRequest("仅支持更新 FAQ 条目"));
        }
        FaqChunkMetadata meta = faqChunkCodec.currentFaqMetadata(chunk);
        if (meta == null) {
            throw new BizException(AppError.badRequest("获取 FAQ 元数据失败"));
        }

        Set<String> existingSet = new LinkedHashSet<>();
        if (meta.similarQuestions != null) {
            existingSet.addAll(meta.similarQuestions);
        }
        existingSet.add(meta.standardQuestion);

        List<String> newQuestions = new ArrayList<>();
        for (String q : questions) {
            q = q == null ? "" : FaqChunkMetadata.trimSpace(q);
            if (q.isEmpty() || existingSet.contains(q)) {
                continue;
            }
            existingSet.add(q);
            newQuestions.add(q);
        }

        Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                tagSeqIdMap.put(tag.getId(), tag.getSeqId());
            }
        }
        if (newQuestions.isEmpty()) {
            return faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
        }

        FaqChunkMetadata tempMeta = new FaqChunkMetadata();
        tempMeta.standardQuestion = meta.standardQuestion;
        tempMeta.similarQuestions = new ArrayList<>();
        if (meta.similarQuestions != null) {
            tempMeta.similarQuestions.addAll(meta.similarQuestions);
        }
        tempMeta.similarQuestions.addAll(newQuestions);
        checkFAQQuestionDuplicate(tid, kb.getId(), chunk.getId(), tempMeta);

        if (meta.similarQuestions == null) {
            meta.similarQuestions = new ArrayList<>();
        }
        meta.similarQuestions.addAll(newQuestions);
        meta.version++;

        faqChunkCodec.setFaqMetadata(chunk, meta);

        String indexMode = faqChunkCodec.faqIndexMode(kb);
        chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));
        chunk.setUpdatedAt(OffsetDateTime.now());
        chunkRepository.updateChunk(chunk);

        Knowledge faqKnowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, chunk.getKnowledgeId())
                .eq(Knowledge::getTenantId, tid)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (faqKnowledge == null) {
            throw new IllegalStateException("failed to get knowledge: record not found");
        }
        Model embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);
        // 相似问追加后全量重索引；失败原样返回
        faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                entry = entry.withTagName(tag.getName());
            }
        }
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", chunk.getId(),
                Map.of("entry_id", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                        "source_type", "faq"));
        return entry;
    }

    // ══════════════════ 批量字段 / 标签 ════════════════════════════════

    /** nil tag = 0 = 移除标签。 */
    public void updateEntryTagBatch(String kbId, Map<Long, Long> updates) {
        Map<Long, FaqEntryFieldsUpdate> byId = new LinkedHashMap<>();
        if (updates != null) {
            updates.forEach((id, tag) -> {
                long value = tag == null ? 0 : tag;
                byId.put(id, new FaqEntryFieldsUpdate(null, null, value));
            });
        }
        updateEntryFieldsBatch(kbId, new FaqEntryFieldsBatchUpdate(byId, null, null));
    }
    public void updateEntryFieldsBatch(String kbId, FaqEntryFieldsBatchUpdate req) {
        if (req == null || ((req.byId() == null || req.byId().isEmpty())
                && (req.byTag() == null || req.byTag().isEmpty()))) {
            return;
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        long tid = tenantId();

        Map<String, Boolean> enabledUpdates = new LinkedHashMap<>();
        Map<String, String> tagUpdates = new LinkedHashMap<>();

        FaqGuard.FaqFieldPlan plan = faqGuard.planFAQFields(kb, req);
        List<String> excludeUuids = plan.excludeIds;

        if (req.byTag() != null && !req.byTag().isEmpty()) {
            for (Long tagSeqId : FaqGuard.sortedIds(req.byTag().keySet())) {
                FaqEntryFieldsUpdate update = req.byTag().get(tagSeqId);
                KnowledgeTag tag = plan.tags.get(tagSeqId);

                int setFlags = 0;
                int clearFlags = 0;
                if (update.recommended() != null) {
                    if (update.recommended()) {
                        setFlags = 1;
                    } else {
                        clearFlags = 1;
                    }
                }
                String newTagUuid = null;
                if (update.tagId() != null) {
                    newTagUuid = update.tagId() > 0
                            ? plan.tags.get(update.tagId()).getId()
                            : "";
                }
                List<String> affectedIds = faqChunkRepository.updateChunkFieldsByTagId(
                        tid, kb.getId(), tag.getId(), update.enabled(),
                        setFlags, clearFlags, newTagUuid, excludeUuids);

                for (String id : affectedIds) {
                    Chunk chunk = plan.chunksById.get(id);
                    if (chunk != null) {
                        if (update.enabled() != null) {
                            chunk.setIsEnabled(update.enabled());
                        }
                        chunk.setFlags((chunk.getFlags() | setFlags) & ~clearFlags);
                        if (newTagUuid != null) {
                            chunk.setTagId(newTagUuid);
                        }
                    }
                }
                if (!affectedIds.isEmpty()) {
                    if (update.enabled() != null) {
                        for (String id : affectedIds) {
                            enabledUpdates.put(id, update.enabled());
                        }
                    }
                    if (newTagUuid != null) {
                        for (String id : affectedIds) {
                            tagUpdates.put(id, newTagUuid);
                        }
                    }
                }
            }
        }

        if (req.byId() != null && !req.byId().isEmpty()) {
            Map<Long, Chunk> chunkBySeqId = plan.chunks;

            Map<String, Integer> setFlags = new LinkedHashMap<>();
            Map<String, Integer> clearFlags = new LinkedHashMap<>();
            List<Chunk> chunksToUpdate = new ArrayList<>();

            for (Long entrySeqId : FaqGuard.sortedIds(req.byId().keySet())) {
                FaqEntryFieldsUpdate update = req.byId().get(entrySeqId);
                Chunk chunk = chunkBySeqId.get(entrySeqId);

                boolean needUpdate = false;
                if (update.enabled() != null && chunk.isIsEnabled() != update.enabled()) {
                    chunk.setIsEnabled(update.enabled());
                    enabledUpdates.put(chunk.getId(), update.enabled());
                    needUpdate = true;
                }
                if (update.recommended() != null) {
                    boolean currentRecommended = (chunk.getFlags() & 1) != 0;
                    if (currentRecommended != update.recommended()) {
                        if (update.recommended()) {
                            setFlags.put(chunk.getId(), 1);
                        } else {
                            clearFlags.put(chunk.getId(), 1);
                        }
                    }
                }
                if (update.tagId() != null) {
                    String newTagId = "";
                    if (update.tagId() > 0) {
                        newTagId = plan.tags.get(update.tagId()).getId();
                    }
                    if (!chunk.getTagId().equals(newTagId)) {
                        chunk.setTagId(newTagId);
                        tagUpdates.put(chunk.getId(), newTagId);
                        needUpdate = true;
                    }
                }
                if (needUpdate) {
                    chunk.setUpdatedAt(OffsetDateTime.now());
                    chunksToUpdate.add(chunk);
                }
            }
            if (!chunksToUpdate.isEmpty()) {
                faqChunkRepository.updateChunks(chunksToUpdate);
            }
            if (!setFlags.isEmpty() || !clearFlags.isEmpty()) {
                faqChunkRepository.updateChunkFlagsBatch(tid, kb.getId(), setFlags, clearFlags);
            }
        }

        // 检索引擎同步：失败 → 原样上抛（阻断；
        // 2026-09-22 走查批接线：此前为 WARN + no-op 占位。
        // 绑定 store 的 KB 走引擎口（deleteByChunkIdList + batchIndex）
        CompositeRetrieveEngine boundEngine =
                (!enabledUpdates.isEmpty() || !tagUpdates.isEmpty())
                        ? vectorWrites.boundEngine(kb) : null;
        if (boundEngine != null) {
            try {
                if (!enabledUpdates.isEmpty()) {
                    boundEngine.batchUpdateChunkEnabledStatus(enabledUpdates);
                }
                if (!tagUpdates.isEmpty()) {
                    boundEngine.batchUpdateChunkTagID(tagUpdates);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
            }
        } else {
            if (!enabledUpdates.isEmpty()) {
                vectorStore.batchUpdateChunkEnabledStatus(enabledUpdates);
            }
            if (!tagUpdates.isEmpty()) {
                vectorStore.batchUpdateChunkTagId(tagUpdates);
            }
        }
        log.info("FAQ fields batch updated: kb={}, by_id={}, by_tag={}",
                kb.getId(), req.byId() == null ? 0 : req.byId().size(),
                req.byTag() == null ? 0 : req.byTag().size());
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", "",
                Map.of("count", req.byId() == null ? 0 : req.byId().size(),
                        "tag_groups", req.byTag() == null ? 0 : req.byTag().size(),
                        "batch", true));
    }

    // ══════════════════ 删除 ═══════════════════════════════════════════

    /**
     * 授权先行、逐条软删，
     * 然后 deleteFAQChunkVectors 的 GetEmbeddingModel 失败 → plain 500（行已删）。
     */
    public void deleteEntries(String kbId, List<Long> entrySeqIds) {
        if (entrySeqIds == null || entrySeqIds.isEmpty()) {
            throw new BizException(AppError.badRequest("请选择需要删除的 FAQ 条目"));
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        long tid = tenantId();

        Map<Long, Chunk> selected = faqGuard.loadFAQWriteChunks(kb, entrySeqIds);
        List<Chunk> chunksToRemove = new ArrayList<>();
        Map<String, Knowledge> knowledges = new LinkedHashMap<>();
        Map<String, List<Chunk>> groups = new LinkedHashMap<>();
        for (Long id : FaqGuard.sortedIds(selected.keySet())) {
            Chunk chunk = selected.get(id);
            if (!knowledges.containsKey(chunk.getKnowledgeId())) {
                Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                        .eq(Knowledge::getId, chunk.getKnowledgeId())
                        .eq(Knowledge::getTenantId, tid)
                        .isNull(Knowledge::getDeletedAt)
                        .last("LIMIT 1"));
                if (knowledge == null || knowledge.getTenantId() == null
                        || knowledge.getTenantId() != tid
                        || !kb.getId().equals(knowledge.getKnowledgeBaseId())
                        || !"faq".equals(knowledge.getType())) {
                    throw new BizException(AppError.forbidden("FAQ 文档不属于当前知识库"));
                }
                knowledges.put(chunk.getKnowledgeId(), knowledge);
            }
            groups.computeIfAbsent(chunk.getKnowledgeId(), k -> new ArrayList<>()).add(chunk);
            chunksToRemove.add(chunk);
        }
        for (Chunk chunk : chunksToRemove) {
            chunkRepository.deleteChunk(tid, chunk.getId());
        }
        for (Map.Entry<String, List<Chunk>> e : groups.entrySet()) {
            faqIndexWriter.deleteFAQChunkVectors(kb, knowledges.get(e.getKey()), e.getValue());
        }
        log.info("FAQ entries deleted: kb={}, count={}", kb.getId(), chunksToRemove.size());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", chunksToRemove.size());
        details.put("source_type", "faq");
        List<String> titles = new ArrayList<>(chunksToRemove.size());
        for (Chunk chunk : chunksToRemove) {
            titles.add(faqChunkQuestion(chunk));
        }
        appendSampleTitles(details, titles);
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_BATCH_DELETED,
                "faq_entry", "", details);
    }

    /** 标准问（trim）。 */
    private String faqChunkQuestion(Chunk chunk) {
        FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
        if (meta == null) {
            return "";
        }
        String question = meta.standardQuestion == null ? "" : meta.standardQuestion.trim();
        return question;
    }

    /** 去空去重、上限 5、
     *  单条落 title、多条落 titles。 */
    private static void appendSampleTitles(Map<String, Object> details, List<String> titles) {
        List<String> samples = new ArrayList<>(5);
        Set<String> seen = new LinkedHashSet<>(5);
        for (String title : titles) {
            String t = title == null ? "" : title.trim();
            if (t.isEmpty() || samples.size() >= 5 || !seen.add(t)) {
                continue;
            }
            samples.add(t);
        }
        if (samples.isEmpty()) {
            return;
        }
        details.put("title", samples.get(0));
        if (samples.size() > 1) {
            details.put("titles", samples);
        }
    }

    /**
     * 尽力而为的 KB 活动审计。
     * ScopeType=knowledge_base、ScopeID=kbID、Outcome=success、details 按字母序。
     */
    private void recordKbActivity(long tenantId, String kbId, String action,
                                  String targetType, String targetId, Map<String, Object> details) {
        if (kbId == null || kbId.isEmpty()) {
            return;
        }
        long tid = tenantId;
        if (tid == 0) {
            Long ctxTenant = TenantContext.currentTenantId();
            tid = ctxTenant == null ? 0L : ctxTenant;
        }
        if (tid == 0) {
            return;
        }
        String actorId = TenantContext.currentUserId() == null
                ? "" : TenantContext.currentUserId();
        String actorRole = actorId.isEmpty() ? ""
                : TenantContext.currentRole() == null
                ? "" : TenantContext.currentRole();

        AuditLog entry = new AuditLog();
        entry.setTenantId(tid);
        entry.setActorUserId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setScopeType("knowledge_base");
        entry.setScopeId(kbId);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setOutcome(AuditOutcome.SUCCESS);
        ObjectNode detailsNode =
                JsonMapper.builder().build().createObjectNode();
        details.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEachOrdered(e -> detailsNode.set(e.getKey(),
                        JsonMapper.builder().build().valueToTree(e.getValue())));
        entry.setDetails(detailsNode);
        auditService.logBestEffort(entry);
    }


    // ══════════════════ 私有：重复检查 ══════════════════════

    /** ；1-3 步本地判定、
     *  4 步一条 DB 查询、5-7 步报错语义照抄。 */
    private void checkFAQQuestionDuplicate(long tenantId, String kbId, String excludeChunkId,
                                           FaqChunkMetadata meta) {
        List<String> similar = meta.similarQuestions == null ? List.of() : meta.similarQuestions;
        List<String> negative = meta.negativeQuestions == null ? List.of() : meta.negativeQuestions;

        for (String q : similar) {
            if (q.equals(meta.standardQuestion)) {
                throw new BizException(AppError.badRequest("相似问「" + q + "」不能与标准问相同"));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String q : similar) {
            if (!seen.add(q)) {
                throw new BizException(AppError.badRequest("相似问「" + q + "」重复"));
            }
        }
        Set<String> positiveQuestions = new LinkedHashSet<>();
        positiveQuestions.add(meta.standardQuestion);
        positiveQuestions.addAll(similar);
        Set<String> negativeSeen = new LinkedHashSet<>();
        for (String q : negative) {
            if (q.isEmpty()) {
                continue;
            }
            if (q.equals(meta.standardQuestion)) {
                throw new BizException(AppError.badRequest("反例问题「" + q + "」不能与标准问相同"));
            }
            if (positiveQuestions.contains(q)) {
                throw new BizException(AppError.badRequest("反例问题「" + q + "」不能与相似问相同"));
            }
            if (!negativeSeen.add(q)) {
                throw new BizException(AppError.badRequest("反例问题「" + q + "」重复"));
            }
        }

        List<String> allQuestions = new ArrayList<>();
        allQuestions.add(meta.standardQuestion);
        allQuestions.addAll(similar);

        Chunk dupChunk = faqChunkRepository.findFAQChunkWithDuplicateQuestion(
                tenantId, kbId, excludeChunkId, allQuestions);
        if (dupChunk == null) {
            return;
        }
        FaqChunkMetadata existingMeta = faqChunkCodec.sanitizedFaqMetadata(dupChunk);
        if (existingMeta == null) {
            throw new BizException(AppError.badRequest("标准问或相似问与已有条目重复"));
        }
        Set<String> existingSimilarSet = new LinkedHashSet<>();
        if (existingMeta.similarQuestions != null) {
            for (String q : existingMeta.similarQuestions) {
                if (!q.isEmpty()) {
                    existingSimilarSet.add(q);
                }
            }
        }
        if (!meta.standardQuestion.isEmpty()) {
            if (meta.standardQuestion.equals(existingMeta.standardQuestion)
                    || existingSimilarSet.contains(meta.standardQuestion)) {
                throw new BizException(AppError.badRequest("标准问「" + meta.standardQuestion + "」已存在"));
            }
        }
        for (String q : similar) {
            if (q.isEmpty()) {
                continue;
            }
            if (q.equals(existingMeta.standardQuestion) || existingSimilarSet.contains(q)) {
                throw new BizException(AppError.badRequest("相似问「" + q + "」已存在"));
            }
        }
        throw new BizException(AppError.badRequest("标准问或相似问与已有条目重复"));
    }




    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(input.getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 32); // 契约：内容哈希取前 16 字节
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }




}
