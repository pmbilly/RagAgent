package com.ragagent.knowledge.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.FaqDtos;
import com.ragagent.knowledge.dto.FaqDtos.FaqEntry;
import com.ragagent.knowledge.dto.FaqDtos.FaqExportEntry;
import com.ragagent.knowledge.dto.FaqDtos.FaqFailedEntry;
import com.ragagent.knowledge.dto.FaqDtos.FaqImportProgress;
import com.ragagent.knowledge.dto.FaqDtos.FaqImportResult;
import com.ragagent.knowledge.dto.FaqDtos.FaqSuccessEntry;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.model.domain.Model;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FAQ 模块服务（波 2 第四批，对照 Go knowledge_faq*.go + knowledgebase_search_faq.go +
 * knowledge_faq_create_guard.go；FAQ 条目 = chunks 表 chunk_type='faq' 的行）。
 *
 * <h2>错误形态分层（本轮最重要的一条）</h2>
 * <p>Go 的 FAQ handler 对 service 错误统一 {@code c.Error(err)}，由全局 ErrorHandler
 * 分两支（golden 实测确认）：</p>
 * <ul>
 *   <li><b>AppError</b>（NewBadRequestError/NewNotFoundError/...）→ 信封
 *       {@code {"error":{code,message,details},"success":false}}——Java 侧抛
 *       {@link BizException}；</li>
 *   <li><b>普通 error</b>（fmt.Errorf 族，如 GetEmbeddingModel 链）→
 *       <b>500 code=1007 固定文案 "Internal server error"、无 details 键</b>——
 *       与 chunk 波「handler 亲手 NewInternalServerError(err.Error())」的
 *       <b>message=原文</b>形态刻意不同！FAQ controller 的 catch-all 单独处理，
 *       不能复用 ChunkController 的映射。</li>
 * </ul>
 *
 * <h2>已知差异（Go 有、Java 未接线，均记在对应方法注释）</h2>
 * <ol>
 *   <li><b>asynq → 进程内虚拟线程</b>（既有取舍）：retry/backoff 中间态不翻译，
 *       导入失败直接落 failed 终态；running 锁与进度是进程内 map（对照 Go 的
 *       redisClient==nil 内存兜底分支，单实例语义一致）。</li>
 *   <li><b>faqCreateIndexBudget / embedding 真实调用</b>：模型行存在时 Java 无法
 *       真嵌入——CreateEntry 走 Go 的「索引失败→回滚 chunk」路径并以 plain 500 收场
 *       （文案见 {@link #embeddingUnavailable}）；golden 数据全部命中空模型分支。</li>
 * </ol>
 *
 * <p>2026-09-23 第二轮走查收口：SearchFAQ 的 HybridSearch 执行面 + TagName 批补
 * （原「随波 4 收口」备案，检索引擎已随 3cb4b2e 落地）与 KB 活动审计五处
 * （created/updated×2/fields 批量/batch_deleted，对照 Go recordKBActivity 调用点）
 * 全部接线；向量索引族已于走查第十三处接线。</p>
 */
@Service
public class FaqService {

    private static final Logger log = LoggerFactory.getLogger(FaqService.class);

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeTagMapper tagMapper;
    private final KnowledgeService knowledgeService;
    private final KnowledgeBaseService kbService;
    private final FaqImportTaskStore taskStore;
    private final LocalStorageService storage;
    /** A3-3 尾批：租户感知文件存储（失败明细 CSV 导出走云的临时桶；本地租户保持既有落盘）。 */
    private final TenantFileStorage fileStorage;
    private final VectorStoreService vectorStore;
    private final KnowledgeVectorWrites vectorWrites;
    /** FAQ 搜索的检索执行面（对照 Go kbService.HybridSearch；波 4 检索引擎批落地）。 */
    private final HybridSearchService hybridSearchService;
    /** KB 活动审计（对照 Go recordKBActivity 的 s.audit）。 */
    private final AuditLogService auditService;
    private final FaqGuard faqGuard;
    private final FaqChunkCodec faqChunkCodec;
    private final FaqIndexWriter faqIndexWriter;
    private final FaqImportService faqImportService;


    public FaqService(ChunkRepository chunkRepository,
                      KnowledgeMapper knowledgeMapper,
                      KnowledgeTagMapper tagMapper,
                      KnowledgeService knowledgeService,
                      KnowledgeBaseService kbService,
                      FaqImportTaskStore taskStore,
                      LocalStorageService storage,
                      TenantFileStorage fileStorage,
                      VectorStoreService vectorStore,
                      HybridSearchService hybridSearchService,
                      AuditLogService auditService,
                      FaqGuard faqGuard,
                      FaqChunkCodec faqChunkCodec,
                      FaqIndexWriter faqIndexWriter,
                      FaqImportService faqImportService,
                      KnowledgeVectorWrites vectorWrites) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.tagMapper = tagMapper;
        this.knowledgeService = knowledgeService;
        this.kbService = kbService;
        this.taskStore = taskStore;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.vectorStore = vectorStore;
        this.vectorWrites = vectorWrites;
        this.hybridSearchService = hybridSearchService;
        this.auditService = auditService;
        this.faqGuard = faqGuard;
        this.faqChunkCodec = faqChunkCodec;
        this.faqIndexWriter = faqIndexWriter;
        this.faqImportService = faqImportService;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ══════════════════ 列表 ═══════════════════════════════════════════

    /**
     * 对照 ListFAQEntries（knowledge_faq.go L23-103）。分页已由 handler 解析钳位。
     */
    public Map<String, Object> listEntries(String kbId, int page, int pageSize,
                                           List<String> tagUuids, long legacyTagSeqId,
                                           String keyword, String searchField,
                                           String sortOrder, Boolean isEnabled) {
        keyword = FaqChunkMetadata.trimSpace(keyword);

        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        long effectiveTenant = faqGuard.resolveKBReadTenant(kb);

        Knowledge faqKnowledge = faqIndexWriter.findFAQKnowledge(effectiveTenant, kb.getId());
        List<FaqEntry> entries = new ArrayList<>();
        long total = 0;
        if (faqKnowledge != null) {
            List<String> tags = tagUuids == null ? new ArrayList<>() : new ArrayList<>(tagUuids);
            if (tags.isEmpty() && legacyTagSeqId > 0) {
                KnowledgeTag tag = tagMapper.selectByTenantAndSeqId(effectiveTenant, legacyTagSeqId);
                if (tag == null) {
                    throw new BizException(AppError.notFound("标签不存在"));
                }
                tags = List.of(tag.getId());
            }
            ChunkRepository.ChunkPage result = chunkRepository.listPagedChunksByKnowledgeId(
                    effectiveTenant, faqKnowledge.getId(), (page - 1) * pageSize, pageSize,
                    List.of("faq"), tags, keyword, searchField, sortOrder, "faq", isEnabled);
            total = result.total();

            Map<String, String> tagNameMap = new LinkedHashMap<>();
            Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
            LinkedHashSet<String> tagIds = new LinkedHashSet<>();
            for (Chunk chunk : result.items()) {
                if (!chunk.getTagId().isEmpty()) {
                    tagIds.add(chunk.getTagId());
                }
            }
            if (!tagIds.isEmpty()) {
                List<KnowledgeTag> tags2 = tagMapper.selectByTenantAndIds(effectiveTenant, new ArrayList<>(tagIds));
                for (KnowledgeTag t : tags2) {
                    tagNameMap.put(t.getId(), t.getName());
                    tagSeqIdMap.put(t.getId(), t.getSeqId());
                }
            }

            faqGuard.ensureDefaults(kb);
            for (Chunk chunk : result.items()) {
                FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
                if (!chunk.getTagId().isEmpty()) {
                    entry = withTagName(entry, tagNameMap.get(chunk.getTagId()));
                }
                entries.add(entry);
            }
        }
        Map<String, Object> pageResult = new LinkedHashMap<>();
        pageResult.put("total", total);
        pageResult.put("page", page);
        pageResult.put("page_size", pageSize);
        pageResult.put("data", entries);
        return pageResult;
    }

    // ══════════════════ 详情 ═══════════════════════════════════════════

    /** 对照 GetFAQEntry（knowledge_faq.go L260-314）。 */
    public FaqEntry getEntry(String kbId, long entrySeqId) {
        if (entrySeqId <= 0) {
            throw new BizException(AppError.badRequest("条目ID不能为空"));
        }
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = chunkRepository.getChunkBySeqId(tid, entrySeqId);
        if (chunk == null) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!kb.getId().equals(chunk.getKnowledgeBaseId()) || chunk.getTenantId() == null
                || chunk.getTenantId() != tid) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!"faq".equals(chunk.getChunkType())) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
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
                entry = withTagName(entry, tag.getName());
            }
        }
        return entry;
    }

    // ══════════════════ 创建 ═══════════════════════════════════════════

    /**
     * 对照 CreateFAQEntry（knowledge_faq.go L115-257）。判定顺序 golden 依赖：
     * sanitize → tag 解析 → create guard → 重复检查 → 容器 → index mode →
     * <b>GetEmbeddingModel（plain 500 分支）</b> → 建 chunk → 索引（失败回滚 chunk）。
     */
    public FaqEntry createEntry(String kbId, FaqDtos.FaqEntryPayload payload) {
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        FaqChunkMetadata meta = faqGuard.sanitizeFAQEntryPayload(payload);
        String tagID = faqGuard.resolveTagID(kb.getId(), payload);

        // 同标准问的进程内串行（Go 的 faqCreateInflight 兜底分支；Redis SetNX 未复刻）
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

            boolean isEnabled = payload.isEnabled() == null || payload.isEnabled();
            int flags = payload.isRecommended() != null && !payload.isRecommended() ? 0 : 1;

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

            // 索引步（对照 faqIndexWriter.indexFAQChunks(..., adjustStorage=true, needDelete=false)）：
            // 失败 → 按 Go 的失败路径回滚 chunk + "failed to index chunk: %w"
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
                    entry = withTagName(entry, tag.getName());
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
     * 对照 UpdateFAQEntry（knowledge_faq.go L317-480）。
     * <b>先落库后失败</b>：UpdateChunk 在 GetEmbeddingModel 之前——无模型 KB 上
     * 返回 plain 500 但变更已持久化（golden faq-get-after-update 钉住，照抄别修）。
     */
    public FaqEntry updateEntry(String kbId, long entrySeqId, FaqDtos.FaqEntryPayload payload) {
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = chunkRepository.getChunkBySeqId(tid, entrySeqId);
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

        List<String> oldSimilarQuestions = null;
        String oldStandardQuestion = "";
        List<String> oldAnswers = null;
        String questionIndexMode = "combined";
        String qim = faqChunkCodec.faqQuestionIndexMode(kb);
        if (!qim.isEmpty()) {
            questionIndexMode = qim;
        }
        FaqChunkMetadata existing = faqChunkCodec.currentFaqMetadata(chunk);
        if (existing != null) {
            meta.version = existing.version + 1;
            if ("separate".equals(questionIndexMode)) {
                oldSimilarQuestions = existing.similarQuestions;
                oldStandardQuestion = existing.standardQuestion;
                oldAnswers = existing.answers;
            }
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
        if (payload.isEnabled() != null) {
            chunk.setIsEnabled(payload.isEnabled());
        }
        if (payload.isRecommended() != null) {
            if (payload.isRecommended()) {
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

        // 增量索引（separate 模式）/ 增量删除 + 全量索引——索引执行面在 Go 也先过
        // GetEmbeddingModel；无模型的 KB 在这里 plain 500（变更已持久化）
        Model embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);
        // 对照 Go L430-450：separate 模式相似问减少时先删多余 sourceID——Java 的
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
                entry = withTagName(entry, tag.getName());
            }
        }
        log.info("FAQ entry updated: kb={}, entry={}", kb.getId(), chunk.getSeqId());
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", chunk.getId(),
                Map.of("entry_id", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                        "source_type", "faq"));
        return entry;
    }

    /** 对照 AddSimilarQuestions（knowledge_faq.go L484-630）；同款先落库后 500。 */
    public FaqEntry addSimilarQuestions(String kbId, long entrySeqId, List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new BizException(AppError.badRequest("相似问列表不能为空"));
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = chunkRepository.getChunkBySeqId(tid, entrySeqId);
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

        List<String> oldSimilarQuestions = meta.similarQuestions;
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
        // 对照 Go L603（similar questions 追加后的全量重索引）：失败原样返回
        faqIndexWriter.indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                entry = withTagName(entry, tag.getName());
            }
        }
        recordKbActivity(tid, kb.getId(), AuditAction.KNOWLEDGE_UPDATED,
                "faq_entry", chunk.getId(),
                Map.of("entry_id", chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
                        "source_type", "faq"));
        return entry;
    }

    // ══════════════════ 批量字段 / 标签 ════════════════════════════════

    /** 对照 UpdateFAQEntryTagBatch（knowledge_faq.go L909-919）：nil tag = 0 = 移除标签。 */
    public void updateEntryTagBatch(String kbId, Map<Long, Long> updates) {
        Map<Long, FaqDtos.FaqEntryFieldsUpdate> byId = new LinkedHashMap<>();
        if (updates != null) {
            updates.forEach((id, tag) -> {
                long value = tag == null ? 0 : tag;
                byId.put(id, new FaqDtos.FaqEntryFieldsUpdate(null, null, value));
            });
        }
        updateEntryFieldsBatch(kbId, new FaqDtos.FaqEntryFieldsBatchUpdate(byId, null, null));
    }

    /** 对照 UpdateFAQEntryFieldsBatch（knowledge_faq.go L679-858）。 */
    public void updateEntryFieldsBatch(String kbId, FaqDtos.FaqEntryFieldsBatchUpdate req) {
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
                FaqDtos.FaqEntryFieldsUpdate update = req.byTag().get(tagSeqId);
                KnowledgeTag tag = plan.tags.get(tagSeqId);

                int setFlags = 0;
                int clearFlags = 0;
                if (update.isRecommended() != null) {
                    if (update.isRecommended()) {
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
                List<String> affectedIds = chunkRepository.updateChunkFieldsByTagId(
                        tid, kb.getId(), tag.getId(), update.isEnabled(),
                        setFlags, clearFlags, newTagUuid, excludeUuids);

                for (String id : affectedIds) {
                    Chunk chunk = plan.chunksById.get(id);
                    if (chunk != null) {
                        if (update.isEnabled() != null) {
                            chunk.setIsEnabled(update.isEnabled());
                        }
                        chunk.setFlags((chunk.getFlags() | setFlags) & ~clearFlags);
                        if (newTagUuid != null) {
                            chunk.setTagId(newTagUuid);
                        }
                    }
                }
                if (!affectedIds.isEmpty()) {
                    if (update.isEnabled() != null) {
                        for (String id : affectedIds) {
                            enabledUpdates.put(id, update.isEnabled());
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
                FaqDtos.FaqEntryFieldsUpdate update = req.byId().get(entrySeqId);
                Chunk chunk = chunkBySeqId.get(entrySeqId);

                boolean needUpdate = false;
                if (update.isEnabled() != null && chunk.isIsEnabled() != update.isEnabled()) {
                    chunk.setIsEnabled(update.isEnabled());
                    enabledUpdates.put(chunk.getId(), update.isEnabled());
                    needUpdate = true;
                }
                if (update.isRecommended() != null) {
                    boolean currentRecommended = (chunk.getFlags() & 1) != 0;
                    if (currentRecommended != update.isRecommended()) {
                        if (update.isRecommended()) {
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
                chunkRepository.updateChunks(chunksToUpdate);
            }
            if (!setFlags.isEmpty() || !clearFlags.isEmpty()) {
                chunkRepository.updateChunkFlagsBatch(tid, kb.getId(), setFlags, clearFlags);
            }
        }

        // 检索引擎同步（对照 knowledge_faq.go L838-852）：失败 → 原样上抛（阻断；
        // chunk 行已在上方落库——与 Go 的顺序一致）。
        // 2026-09-22 走查批接线：此前为 WARN + no-op 占位。
        // 2026-09-25 写链改道：绑定 store 的 KB 走引擎口（照 knowledge_faq.go L838-852）
        com.ragagent.retrieval.engine.CompositeRetrieveEngine boundEngine =
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
     * 对照 DeleteFAQEntries（knowledge_faq.go L1256-1312）。授权先行、逐条软删，
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

    /** 对照 faqChunkQuestion（knowledge_faq.go L1580-1592）：标准问（trim）。 */
    private String faqChunkQuestion(Chunk chunk) {
        FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
        if (meta == null) {
            return "";
        }
        String question = meta.standardQuestion == null ? "" : meta.standardQuestion.trim();
        return question;
    }

    /** 对照 kbActivityAppendSampleTitles（kb_activity.go L49-79）：去空去重、上限 5、
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
     * 对照 recordKBActivity（kb_activity.go L91-160）：尽力而为的 KB 活动审计。
     * ScopeType=knowledge_base、ScopeID=kbID、Outcome=success、details 按字母序。
     */
    private void recordKbActivity(long tenantId, String kbId, String action,
                                  String targetType, String targetId, Map<String, Object> details) {
        if (kbId == null || kbId.isEmpty()) {
            return;
        }
        long tid = tenantId;
        if (tid == 0) {
            Long ctxTenant = com.ragagent.common.context.TenantContext.currentTenantId();
            tid = ctxTenant == null ? 0L : ctxTenant;
        }
        if (tid == 0) {
            return;
        }
        String actorId = com.ragagent.common.context.TenantContext.currentUserId() == null
                ? "" : com.ragagent.common.context.TenantContext.currentUserId();
        String actorRole = actorId.isEmpty() ? ""
                : com.ragagent.common.context.TenantContext.currentRole() == null
                ? "" : com.ragagent.common.context.TenantContext.currentRole();

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
        com.fasterxml.jackson.databind.node.ObjectNode detailsNode =
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        details.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEachOrdered(e -> detailsNode.set(e.getKey(),
                        com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(e.getValue())));
        entry.setDetails(detailsNode);
        auditService.logBestEffort(entry);
    }

    // ══════════════════ 导出 ═══════════════════════════════════════════

    /** 对照 ExportFAQEntries（CSV，8 列 + BOM；knowledge_faq.go L1319-1348, L1434-1482）。 */
    public byte[] exportCsv(String kbId) {
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        long tid = tenantId();
        Knowledge faqKnowledge = faqIndexWriter.findFAQKnowledge(tid, kb.getId());
        List<Chunk> chunks = faqKnowledge == null
                ? List.of()
                : chunkRepository.listAllFAQChunksForExport(tid, faqKnowledge.getId());
        Map<String, String> tagMap = buildTagMap(tid, kbId);
        return buildFAQCSV(chunks, tagMap);
    }

    /** 对照 ExportFAQEntriesJSON（L1352-1378）；空库输出 {@code []}。 */
    public byte[] exportJson(String kbId) {
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        long tid = tenantId();
        Knowledge faqKnowledge = faqIndexWriter.findFAQKnowledge(tid, kb.getId());
        List<Chunk> chunks = faqKnowledge == null
                ? List.of()
                : chunkRepository.listAllFAQChunksForExport(tid, faqKnowledge.getId());
        Map<String, String> tagMap = buildTagMap(tid, kbId);
        return buildFAQJSON(chunks, tagMap);
    }

    private Map<String, String> buildTagMap(long tenantId, String kbId) {
        Map<String, String> tagMap = new LinkedHashMap<>();
        int pageSize = 1000;
        for (int pageNum = 1; ; pageNum++) {
            List<KnowledgeTag> tags = tagMapper.listByKB(tenantId, kbId, pageSize, (pageNum - 1) * pageSize);
            for (KnowledgeTag tag : tags) {
                tagMap.put(tag.getId(), tag.getName());
            }
            if (tags.size() < pageSize) {
                break;
            }
        }
        return tagMap;
    }

    private byte[] buildFAQCSV(List<Chunk> chunks, Map<String, String> tagMap) {
        // BOM 由 handler 的 c.Data append（faq.go L454），service 侧不带——别两头写
        StringBuilder buf = new StringBuilder();
        buf.append("分类(必填),问题(必填),相似问题(选填-多个用##分隔),反例问题(选填-多个用##分隔),")
                .append("机器人回答(必填-多个用##分隔),是否全部回复(选填-默认FALSE),")
                .append("是否停用(选填-默认FALSE),是否禁止被推荐(选填-默认False 可被推荐)")
                .append('\n');
        for (Chunk chunk : chunks) {
            FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            String tagName = "";
            if (!chunk.getTagId().isEmpty() && tagMap != null) {
                tagName = tagMap.getOrDefault(chunk.getTagId(), "");
            }
            String[] row = {
                    escapeCSVField(tagName),
                    escapeCSVField(meta.standardQuestion),
                    escapeCSVField(String.join("##", meta.similarQuestions == null ? List.of() : meta.similarQuestions)),
                    escapeCSVField(String.join("##", meta.negativeQuestions == null ? List.of() : meta.negativeQuestions)),
                    escapeCSVField(String.join("##", meta.answers == null ? List.of() : meta.answers)),
                    boolToCSV(FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(meta.answerStrategy)),
                    boolToCSV(!chunk.isIsEnabled()),
                    boolToCSV((chunk.getFlags() & 1) == 0),
            };
            buf.append(String.join(",", row)).append('\n');
        }
        return buf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] buildFAQJSON(List<Chunk> chunks, Map<String, String> tagMap) {
        List<FaqExportEntry> entries = new ArrayList<>();
        for (Chunk chunk : chunks) {
            FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            String tagName = "";
            if (!chunk.getTagId().isEmpty() && tagMap != null) {
                tagName = tagMap.getOrDefault(chunk.getTagId(), "");
            }
            entries.add(new FaqExportEntry(
                    chunk.getSeqId() == null ? 0 : chunk.getSeqId(),
                    tagName,
                    meta.standardQuestion,
                    meta.similarQuestions,
                    meta.negativeQuestions,
                    meta.answers,
                    meta.answerStrategy,
                    chunk.isIsEnabled(),
                    (chunk.getFlags() & 1) != 0));
        }
        try {
            return FaqChunkMetadata.JSON.writeValueAsBytes(entries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escapeCSVField(String field) {
        if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    private static String boolToCSV(boolean b) {
        return b ? "TRUE" : "FALSE";
    }

    // ══════════════════ 搜索 ═══════════════════════════════════════════

    /**
     * 对照 SearchFAQEntries（knowledge_faq.go L922-1253）。参数归一化与
     * searchResults 为空 → {@code []} 的出口逐行保留；<b>HybridSearch 的执行面
     * （向量/关键词检索）属波 4</b>——Go 在无 embedding 绑定的 dev KB 上同样落到
     * 空结果出口（golden faq-search-embed-missing 钉住 {@code data:[]}）。
     */
    public List<FaqEntry> searchEntries(String kbId, FaqDtos.FaqSearchRequest req) {
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);

        double vectorThreshold = req.vectorThreshold();
        if (vectorThreshold <= 0) {
            vectorThreshold = 0.7;
        }
        int matchCount = req.matchCount();
        if (matchCount <= 0) {
            matchCount = 10;
        }
        if (matchCount > 50) {
            matchCount = 50;
        }
        long tid = tenantId();

        List<String> firstPriorityTagUuids = new ArrayList<>();
        List<String> secondPriorityTagUuids = new ArrayList<>();
        Set<Long> firstPrioritySeqIdSet = new LinkedHashSet<>();
        Set<Long> secondPrioritySeqIdSet = new LinkedHashSet<>();
        if (req.firstPriorityTagIds() != null && !req.firstPriorityTagIds().isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndSeqIds(tid, req.firstPriorityTagIds());
            for (KnowledgeTag tag : tags) {
                firstPriorityTagUuids.add(tag.getId());
                firstPrioritySeqIdSet.add(tag.getSeqId());
            }
        }
        if (req.secondPriorityTagIds() != null && !req.secondPriorityTagIds().isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndSeqIds(tid, req.secondPriorityTagIds());
            for (KnowledgeTag tag : tags) {
                secondPriorityTagUuids.add(tag.getId());
                secondPrioritySeqIdSet.add(tag.getSeqId());
            }
        }

        boolean hasPriorityFilter = !firstPriorityTagUuids.isEmpty() || !secondPriorityTagUuids.isEmpty();

        // HybridSearch 执行面（2026-09-23 走查批接线——原「随波 4 收口」备案，
        // 检索引擎已随 3cb4b2e 落地）：优先级过滤时按 Go 语义两级检索、
        // FirstPriority 先结果后 SecondPriority 按 chunkID 去重合并；
        // 空结果 → Go L1069-1071 的 {@code data:[]} 出口（golden 钉住）。
        List<SearchResult> searchResults = searchFaqChunks(kbId, req.queryText(),
                vectorThreshold, matchCount, req.onlyRecommended(),
                hasPriorityFilter, firstPriorityTagUuids, secondPriorityTagUuids);
        if (searchResults.isEmpty()) {
            return new ArrayList<>();
        }

        // SearchResult.ID = chunkID；分数/命中类型/命中内容按 chunk 回填（Go L1073-1089）
        List<String> chunkIds = new ArrayList<>(searchResults.size());
        Map<String, Double> chunkScores = new LinkedHashMap<>();
        Map<String, Integer> chunkMatchTypes = new LinkedHashMap<>();
        Map<String, String> chunkMatchedContents = new LinkedHashMap<>();
        for (SearchResult result : searchResults) {
            chunkIds.add(result.getId());
            chunkScores.put(result.getId(), result.getScore());
            chunkMatchTypes.put(result.getId(), result.getMatchType());
            chunkMatchedContents.put(result.getId(),
                    result.getMatchedContent() == null ? "" : result.getMatchedContent());
        }

        List<Chunk> chunks = chunkRepository.listChunksById(tid, chunkIds);
        List<FaqEntry> entries = convertSearchResults(kb, chunks, tid, matchCount,
                hasPriorityFilter, firstPriorityTagUuids, secondPriorityTagUuids,
                chunkScores, chunkMatchTypes, chunkMatchedContents);

        // 批量补 TagName（L1214-1250）：entry.TagID(seq) → tag 名
        if (!entries.isEmpty()) {
            List<Long> tagSeqIds = new ArrayList<>();
            for (FaqEntry entry : entries) {
                if (entry.tagId() != 0 && !tagSeqIds.contains(entry.tagId())) {
                    tagSeqIds.add(entry.tagId());
                }
            }
            if (!tagSeqIds.isEmpty()) {
                Map<Long, String> tagNameMap = new LinkedHashMap<>();
                try {
                    for (KnowledgeTag tag : tagMapper.selectByTenantAndSeqIds(tid, tagSeqIds)) {
                        tagNameMap.put(tag.getSeqId(), tag.getName());
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to batch query tags: {}", e.toString());
                }
                if (!tagNameMap.isEmpty()) {
                    List<FaqEntry> filled = new ArrayList<>(entries.size());
                    for (FaqEntry entry : entries) {
                        String name = entry.tagId() != 0 ? tagNameMap.get(entry.tagId()) : null;
                        filled.add(name == null ? entry : withTagName(entry, name));
                    }
                    entries = filled;
                }
            }
        }
        return entries;
    }

    /**
     * 对照 Go L1012-1067 的检索步：优先级过滤时两级各自检索（Go 用 goroutine，Java
     * 顺序执行——合并序固定为先 First 后 Second，结果序等价）；无过滤单次全量检索。
     * 参数逐字段对照：DisableKeywordsMatch=true（关键词在 messages/FAQ 自身层面）、
     * TagIDs=优先级标签、OnlyRecommended 透传。任一失败原样上抛（Go 同形）。
     */
    private List<SearchResult> searchFaqChunks(String kbId, String queryText,
            double vectorThreshold, int matchCount, boolean onlyRecommended,
            boolean hasPriorityFilter, List<String> firstPriorityTagUuids,
            List<String> secondPriorityTagUuids) {
        if (!hasPriorityFilter) {
            SearchParams params = new SearchParams();
            params.setQueryText(LogSanitizer.sanitize(queryText));
            params.setVectorThreshold(vectorThreshold);
            params.setMatchCount(matchCount);
            params.setDisableKeywordsMatch(true);
            // Java 引擎以 null 表示「无可检索管线」（Go 的 nil,nil）——按空集处理
            List<SearchResult> results = hybridSearchService.hybridSearch(kbId, params);
            return results == null ? List.of() : results;
        }
        Map<String, List<SearchResult>> byLevel = new LinkedHashMap<>();
        fillPriorityLevel(kbId, queryText, vectorThreshold, matchCount, onlyRecommended,
                firstPriorityTagUuids, byLevel, "first");
        fillPriorityLevel(kbId, queryText, vectorThreshold, matchCount, onlyRecommended,
                secondPriorityTagUuids, byLevel, "second");
        // 合并：FirstPriority 先、SecondPriority 后，chunkID 去重（Go L1048-1058）
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seenChunkIds = new LinkedHashSet<>();
        for (String level : new String[] {"first", "second"}) {
            for (SearchResult result : byLevel.getOrDefault(level, List.of())) {
                if (seenChunkIds.add(result.getId())) {
                    merged.add(result);
                }
            }
        }
        return merged;
    }

    private void fillPriorityLevel(String kbId, String queryText, double vectorThreshold,
            int matchCount, boolean onlyRecommended, List<String> tagUuids,
            Map<String, List<SearchResult>> out, String level) {
        if (tagUuids == null || tagUuids.isEmpty()) {
            return; // Go：该优先级未提供时不发起检索
        }
        SearchParams params = new SearchParams();
        params.setQueryText(LogSanitizer.sanitize(queryText));
        params.setVectorThreshold(vectorThreshold);
        params.setMatchCount(matchCount);
        params.setDisableKeywordsMatch(true);
        params.setTagIds(tagUuids);
        params.setOnlyRecommended(onlyRecommended);
        List<SearchResult> results = hybridSearchService.hybridSearch(kbId, params);
        out.put(level, results == null ? List.of() : results);
    }

    /** 对照 L1073-1252 的命中转换/优先级排序/限流（score/matchType/matchedQuestion
     *  在转换时按 chunkID 回填，排序与截断在前，TagName 批补在调用方末尾）。 */
    private List<FaqEntry> convertSearchResults(KnowledgeBase kb, List<Chunk> chunks, long tid,
                                                int matchCount, boolean hasPriorityFilter,
                                                List<String> firstPriority, List<String> secondPriority,
                                                Map<String, Double> chunkScores,
                                                Map<String, Integer> chunkMatchTypes,
                                                Map<String, String> chunkMatchedContents) {
        List<FaqEntry> entries = new ArrayList<>();
        Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
        LinkedHashSet<String> tagIds = new LinkedHashSet<>();
        for (Chunk chunk : chunks) {
            if (!chunk.getTagId().isEmpty()) {
                tagIds.add(chunk.getTagId());
            }
        }
        if (!tagIds.isEmpty()) {
            for (KnowledgeTag t : tagMapper.selectByTenantAndIds(tid, new ArrayList<>(tagIds))) {
                tagSeqIdMap.put(t.getId(), t.getSeqId());
            }
        }
        faqGuard.ensureDefaults(kb);
        for (Chunk chunk : chunks) {
            if (!"faq".equals(chunk.getChunkType()) || !chunk.isIsEnabled()) {
                continue;
            }
            FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
            // Preserve score and match type from search results（Go L1117-1129；
            // 负例问题过滤已在 HybridSearch 内处理）
            Double score = chunkScores.get(chunk.getId());
            Integer matchType = chunkMatchTypes.get(chunk.getId());
            String matched = chunkMatchedContents.get(chunk.getId());
            if (score != null || matchType != null || (matched != null && !matched.isEmpty())) {
                entry = withSearchHit(entry,
                        score == null ? entry.score() : score,
                        matchType == null ? entry.matchType() : matchType,
                        matched == null || matched.isEmpty() ? entry.matchedQuestion() : matched);
            }
            entries.add(entry);
        }
        if (hasPriorityFilter) {
            Set<String> firstSet = new LinkedHashSet<>(firstPriority);
            Set<String> secondSet = new LinkedHashSet<>(secondPriority);
            entries.sort((a, b) -> {
                int aPriority = priorityOf(a, firstSet, secondSet);
                int bPriority = priorityOf(b, firstSet, secondSet);
                if (aPriority != bPriority) {
                    return aPriority - bPriority;
                }
                return Double.compare(b.score(), a.score());
            });
        } else {
            entries.sort((a, b) -> Double.compare(b.score(), a.score()));
        }
        if (entries.size() > matchCount) {
            entries = new ArrayList<>(entries.subList(0, matchCount));
        }
        // 批量补 TagName（L1214-1250；检索未接线时 entries 恒空，骨架保留）
        return entries;
    }

    private static int priorityOf(FaqEntry entry, Set<String> firstSet, Set<String> secondSet) {
        // Go 按 chunk.TagID（UUID）比对；entry.tagId 是 seq_id——收口时改为携带 chunk
        return firstSet.contains(entry.chunkId()) ? 0 : secondSet.contains(entry.chunkId()) ? 1 : 2;
    }

    // ══════════════════ 导入（Upsert）与进度（委托 FaqImportService） ══

    public String upsertEntries(String kbId, FaqDtos.FaqBatchUpsertPayload payload) {
        return faqImportService.upsertEntries(kbId, payload);
    }

    public FaqImportProgress getImportProgress(String taskId) {
        return faqImportService.getImportProgress(taskId);
    }

    public void updateLastImportResultDisplayStatus(String kbId, String displayStatus) {
        faqImportService.updateLastImportResultDisplayStatus(kbId, displayStatus);
    }


    // ══════════════════ 私有：条目视图重建（record 变换，随 Task 3 迁 DTO） ════


    /** 用检索命中覆盖 score/matchType/matchedQuestion（record 重建，Go 的值拷贝同形）。 */
    private static FaqEntry withSearchHit(FaqEntry entry, double score, int matchType,
            String matchedQuestion) {
        return new FaqEntry(entry.id(), entry.chunkId(), entry.knowledgeId(), entry.knowledgeBaseId(),
                entry.tagId(), entry.tagName(), entry.isEnabled(), entry.isRecommended(),
                entry.standardQuestion(), entry.similarQuestions(), entry.negativeQuestions(),
                entry.answers(), entry.answerStrategy(), entry.indexMode(), entry.updatedAt(),
                entry.createdAt(), score, matchType, entry.chunkType(),
                matchedQuestion);
    }

    private static FaqEntry withTagName(FaqEntry entry, String tagName) {
        return new FaqEntry(entry.id(), entry.chunkId(), entry.knowledgeId(), entry.knowledgeBaseId(),
                entry.tagId(), tagName == null ? "" : tagName, entry.isEnabled(), entry.isRecommended(),
                entry.standardQuestion(), entry.similarQuestions(), entry.negativeQuestions(),
                entry.answers(), entry.answerStrategy(), entry.indexMode(), entry.updatedAt(),
                entry.createdAt(), entry.score(), entry.matchType(), entry.chunkType(),
                entry.matchedQuestion());
    }

    // ══════════════════ 私有：重复检查 / 批量计划 ══════════════════════

    /** 对照 checkFAQQuestionDuplicate（knowledge_faq.go L1656-1753）；1-3 步本地判定、
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

        Chunk dupChunk = chunkRepository.findFAQChunkWithDuplicateQuestion(
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
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 32); // Go 只取前 16 字节
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }




}
