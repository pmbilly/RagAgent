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
import java.util.TreeMap;
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
import com.ragagent.knowledge.dto.FaqDtos.FaqMergeDetail;
import com.ragagent.knowledge.dto.FaqDtos.FaqImportProgress;
import com.ragagent.knowledge.dto.FaqDtos.FaqImportResult;
import com.ragagent.knowledge.dto.FaqDtos.FaqSuccessEntry;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.model.mapper.ModelMapper;
import com.ragagent.model.domain.Model;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagRepository;
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
    private final KnowledgeTagRepository tagRepository;
    private final KnowledgeService knowledgeService;
    private final KnowledgeBaseService kbService;
    private final FaqImportTaskStore taskStore;
    private final ChunkMapper chunkMapper;
    private final ModelMapper modelMapper;
    private final LocalStorageService storage;
    /** A3-3 尾批：租户感知文件存储（失败明细 CSV 导出走云的临时桶；本地租户保持既有落盘）。 */
    private final TenantFileStorage fileStorage;
    private final VectorStoreService vectorStore;
    private final EmbedderClient embedder;
    private final TenantStorageService tenantStorage;
    private final KnowledgeVectorWrites vectorWrites;
    private final com.ragagent.model.service.ModelRuntimeFactory modelRuntimeFactory;
    /** FAQ 搜索的检索执行面（对照 Go kbService.HybridSearch；波 4 检索引擎批落地）。 */
    private final HybridSearchService hybridSearchService;
    /** KB 活动审计（对照 Go recordKBActivity 的 s.audit）。 */
    private final AuditLogService auditService;


    public FaqService(ChunkRepository chunkRepository,
                      KnowledgeMapper knowledgeMapper,
                      KnowledgeTagMapper tagMapper,
                      KnowledgeTagRepository tagRepository,
                      KnowledgeService knowledgeService,
                      KnowledgeBaseService kbService,
                      FaqImportTaskStore taskStore,
                      ChunkMapper chunkMapper,
                      ModelMapper modelMapper,
                      LocalStorageService storage,
                      TenantFileStorage fileStorage,
                      VectorStoreService vectorStore,
                      EmbedderClient embedder,
                      TenantStorageService tenantStorage,
                      HybridSearchService hybridSearchService,
                      AuditLogService auditService,
                      KnowledgeVectorWrites vectorWrites,
                      com.ragagent.model.service.ModelRuntimeFactory modelRuntimeFactory) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.tagMapper = tagMapper;
        this.tagRepository = tagRepository;
        this.knowledgeService = knowledgeService;
        this.kbService = kbService;
        this.taskStore = taskStore;
        this.chunkMapper = chunkMapper;
        this.modelMapper = modelMapper;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.vectorStore = vectorStore;
        this.embedder = embedder;
        this.vectorWrites = vectorWrites;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.tenantStorage = tenantStorage;
        this.hybridSearchService = hybridSearchService;
        this.auditService = auditService;
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

        KnowledgeBase kb = validateFAQKnowledgeBase(kbId);
        long effectiveTenant = resolveKBReadTenant(kb);

        Knowledge faqKnowledge = findFAQKnowledge(effectiveTenant, kb.getId());
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

            ensureDefaults(kb);
            for (Chunk chunk : result.items()) {
                FaqEntry entry = chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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
        KnowledgeBase kb = validateFAQKnowledgeBase(kbId);
        ensureDefaults(kb);
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
        FaqEntry entry = chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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
        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        ensureDefaults(kb);
        long tid = tenantId();

        FaqChunkMetadata meta = sanitizeFAQEntryPayload(payload);
        String tagID = resolveTagID(kb.getId(), payload);

        // 同标准问的进程内串行（Go 的 faqCreateInflight 兜底分支；Redis SetNX 未复刻）
        String guardKey = "faq:create:" + tid + ":" + kb.getId() + ":" + sha256Hex(meta.standardQuestion);
        if (!taskStore.acquireCreateGuard(guardKey)) {
            throw new BizException(AppError.conflict("相同标准问的 FAQ 条目正在创建中，请勿重复提交"));
        }
        try {
            checkFAQQuestionDuplicate(tid, kb.getId(), "", meta);

            Knowledge faqKnowledge = ensureFAQKnowledge(tid, kb);
            if (faqKnowledge == null) {
                throw new IllegalStateException("failed to ensure FAQ knowledge: knowledge not found");
            }

            String indexMode = faqIndexMode(kb);

            // GetEmbeddingModel：模型行缺失/ID 空 → plain 500（handler c.Error 的非 AppError 分支）
            Model embeddingModel = requireEmbeddingModel(kb);

            boolean isEnabled = payload.isEnabled() == null || payload.isEnabled();
            int flags = payload.isRecommended() != null && !payload.isRecommended() ? 0 : 1;

            Chunk chunk = new Chunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setTenantId(tid);
            chunk.setKnowledgeId(faqKnowledge.getId());
            chunk.setKnowledgeBaseId(kb.getId());
            chunk.setContent(buildFAQChunkContent(meta, indexMode));
            chunk.setIsEnabled(isEnabled);
            chunk.setFlags(flags);
            chunk.setChunkType("faq");
            chunk.setTagId(tagID);
            chunk.setStatus(1); // stored
            if (payload.id() != null && payload.id() > 0) {
                chunk.setSeqId(payload.id());
            }
            setFaqMetadata(chunk, meta);
            if (chunk.getCreatedAt() == null) {
                chunk.setCreatedAt(OffsetDateTime.now());
                chunk.setUpdatedAt(chunk.getCreatedAt());
            }
            createChunks(List.of(chunk));

            // 索引步（对照 indexFAQChunks(..., adjustStorage=true, needDelete=false)）：
            // 失败 → 按 Go 的失败路径回滚 chunk + "failed to index chunk: %w"
            try {
                indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, true);
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
            FaqEntry entry = chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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
        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        ensureDefaults(kb);
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
        FaqChunkMetadata meta = sanitizeFAQEntryPayload(payload);

        checkFAQQuestionDuplicate(tid, kb.getId(), chunk.getId(), meta);

        List<String> oldSimilarQuestions = null;
        String oldStandardQuestion = "";
        List<String> oldAnswers = null;
        String questionIndexMode = "combined";
        String qim = faqQuestionIndexMode(kb);
        if (!qim.isEmpty()) {
            questionIndexMode = qim;
        }
        FaqChunkMetadata existing = currentFaqMetadata(chunk);
        if (existing != null) {
            meta.version = existing.version + 1;
            if ("separate".equals(questionIndexMode)) {
                oldSimilarQuestions = existing.similarQuestions;
                oldStandardQuestion = existing.standardQuestion;
                oldAnswers = existing.answers;
            }
        }
        setFaqMetadata(chunk, meta);

        String indexMode = faqIndexMode(kb);
        chunk.setContent(buildFAQChunkContent(meta, indexMode));

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
        Model embeddingModel = requireEmbeddingModel(kb);
        // 对照 Go L430-450：separate 模式相似问减少时先删多余 sourceID——Java 的
        // indexFAQChunks 全删该 chunk 行后重插（净效果等价）；索引失败原样返回
        indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                tagSeqIdMap.put(tag.getId(), tag.getSeqId());
            }
        }
        FaqEntry entry = chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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
        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        ensureDefaults(kb);
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
        FaqChunkMetadata meta = currentFaqMetadata(chunk);
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
            return chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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

        setFaqMetadata(chunk, meta);

        String indexMode = faqIndexMode(kb);
        chunk.setContent(buildFAQChunkContent(meta, indexMode));
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
        Model embeddingModel = requireEmbeddingModel(kb);
        // 对照 Go L603（similar questions 追加后的全量重索引）：失败原样返回
        indexFAQChunks(kb, faqKnowledge, List.of(chunk), embeddingModel, false);

        FaqEntry entry = chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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
        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        long tid = tenantId();

        Map<String, Boolean> enabledUpdates = new LinkedHashMap<>();
        Map<String, String> tagUpdates = new LinkedHashMap<>();

        FaqFieldPlan plan = planFAQFields(kb, req);
        List<String> excludeUuids = plan.excludeIds;

        if (req.byTag() != null && !req.byTag().isEmpty()) {
            for (Long tagSeqId : sortedIds(req.byTag().keySet())) {
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

            for (Long entrySeqId : sortedIds(req.byId().keySet())) {
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
        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        long tid = tenantId();

        Map<Long, Chunk> selected = loadFAQWriteChunks(kb, entrySeqIds);
        List<Chunk> chunksToRemove = new ArrayList<>();
        Map<String, Knowledge> knowledges = new LinkedHashMap<>();
        Map<String, List<Chunk>> groups = new LinkedHashMap<>();
        for (Long id : sortedIds(selected.keySet())) {
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
            deleteFAQChunkVectors(kb, knowledges.get(e.getKey()), e.getValue());
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
        FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
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
        KnowledgeBase kb = validateFAQKnowledgeBase(kbId);
        long tid = tenantId();
        Knowledge faqKnowledge = findFAQKnowledge(tid, kb.getId());
        List<Chunk> chunks = faqKnowledge == null
                ? List.of()
                : chunkRepository.listAllFAQChunksForExport(tid, faqKnowledge.getId());
        Map<String, String> tagMap = buildTagMap(tid, kbId);
        return buildFAQCSV(chunks, tagMap);
    }

    /** 对照 ExportFAQEntriesJSON（L1352-1378）；空库输出 {@code []}。 */
    public byte[] exportJson(String kbId) {
        KnowledgeBase kb = validateFAQKnowledgeBase(kbId);
        long tid = tenantId();
        Knowledge faqKnowledge = findFAQKnowledge(tid, kb.getId());
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
            FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
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
            FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
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
        KnowledgeBase kb = validateFAQKnowledgeBase(kbId);

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
        ensureDefaults(kb);
        for (Chunk chunk : chunks) {
            if (!"faq".equals(chunk.getChunkType()) || !chunk.isIsEnabled()) {
                continue;
            }
            FaqEntry entry = chunkToFAQEntry(chunk, kb, tagSeqIdMap);
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

    // ══════════════════ 导入（Upsert）与进度 ═══════════════════════════

    /**
     * 对照 UpsertFAQEntries（knowledge_faq_import.go L30-250）。binding 校验
     * （entries required / mode oneof）在 controller；这里的判定顺序：
     * 空条目 → writable → tag scope → task_id 合法性 → running 锁 → 容器 →
     * 进度初始化 → 入队。
     */
    public String upsertEntries(String kbId, FaqDtos.FaqBatchUpsertPayload payload) {
        if (payload == null || payload.entries() == null || payload.entries().isEmpty()) {
            throw new BizException(AppError.badRequest("FAQ 条目不能为空"));
        }
        final String mode = payload.mode() == null || payload.mode().isEmpty()
                ? "append" : payload.mode();
        if (!"append".equals(mode) && !"replace".equals(mode)) {
            throw new BizException(AppError.badRequest("模式仅支持 append 或 replace"));
        }

        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        validateFAQImportTags(kb, payload.entries());
        long tid = tenantId();

        String taskId = payload.taskId() == null ? "" : payload.taskId().trim();
        final String effectiveTaskId;
        if (taskId.isEmpty()) {
            effectiveTaskId = KnowledgeService.generateTaskId("faq_import", tid, kbId);
        } else if (!validateTaskId(taskId)) {
            throw new BizException(AppError.badRequest("task_id 格式不合法"));
        } else {
            effectiveTaskId = taskId;
        }

        String runningTaskId = taskStore.getRunningTaskId(kbId);
        if (runningTaskId != null && !runningTaskId.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "该知识库已有导入任务正在进行中（任务ID: " + runningTaskId + "），请等待完成后再试"));
        }

        Knowledge faqKnowledge = ensureFAQKnowledge(tid, kb);
        if (faqKnowledge == null) {
            throw new IllegalStateException("failed to ensure FAQ knowledge: knowledge not found");
        }

        long enqueuedAt = Instant.now().getEpochSecond();
        String instanceId = UUID.randomUUID().toString();
        taskStore.setRunningInfo(kbId, new FaqImportTaskStore.RunningInfo(effectiveTaskId, enqueuedAt, instanceId));

        FaqImportProgress progress = new FaqImportProgress(
                effectiveTaskId, kbId, faqKnowledge.getId(), "pending", 0,
                payload.entries().size(), 0, 0, 0, 0, 0,
                new ArrayList<>(), null, null, null, null, 0, 0, null,
                "任务已创建，等待处理", "", Instant.now().getEpochSecond(),
                Instant.now().getEpochSecond(), payload.dryRun(),
                null, null, null, 0);
        taskStore.saveProgress(progress);

        log.info("FAQ import task initialized: {}, kb={}, total={}, dry_run={}",
                taskId, kbId, payload.entries().size(), payload.dryRun());

        // asynq → 进程内虚拟线程（既有取舍）；payload.entries 复制成可变列表（验证会就地改写）
        List<FaqDtos.FaqEntryPayload> entries = new ArrayList<>(payload.entries());
        Thread.ofVirtual().start(() -> processImport(new ImportJob(
                tid, effectiveTaskId, kbId, faqKnowledge.getId(), mode, payload.dryRun(),
                enqueuedAt, instanceId, entries)));

        if (!payload.dryRun()) {
            log.info("FAQ import started: task={}, kb={}, mode={}, total={}",
                    effectiveTaskId, kbId, mode, entries.size());
        }
        return effectiveTaskId;
    }

    /**
     * 对照 ProcessFAQImport（knowledge_faq_import.go L2242-2490）。asynq 语义不翻译：
     * 无 retry/backoff 中间态——任何失败直接落 failed 终态（波 2 第三批同款取舍）。
     * dry_run 只做验证（无 embedding 依赖，确定性）；导入模式在
     * {@link #requireEmbeddingModelForJob} 处与 Go 同位置失败（无模型 KB 的实录路径）。
     */
    void processImport(ImportJob job) {
        TenantContext.set(job.tenantId(), null, null, false, null, false);
        try {
            processImportInner(job);
        } catch (RuntimeException e) {
            // 对照 executeFAQImport 的 defer recover：panic → 任务失败（Java 无 retry，直落终态）
            log.error("FAQ import task {} crashed: {}", job.taskId(), e.getMessage(), e);
        }
    }

    private void processImportInner(ImportJob job) {
        TenantContext.set(job.tenantId(), null, null, false, null, false);
        try {
            KnowledgeBase kb;
            try {
                kb = validateFAQKnowledgeBase(job.kbId());
            } catch (BizException e) {
                log.warn("FAQ import task {} aborted: KB invalid: {}", job.taskId(), e.getMessage());
                return;
            }
            Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, job.knowledgeId())
                    .eq(Knowledge::getTenantId, job.tenantId())
                    .isNull(Knowledge::getDeletedAt)
                    .last("LIMIT 1"));
            if (knowledge == null || knowledge.getTenantId() == null
                    || knowledge.getTenantId() != job.tenantId()
                    || !job.kbId().equals(knowledge.getKnowledgeBaseId())
                    || !"faq".equals(knowledge.getType())) {
                log.warn("FAQ import task {} aborted: document does not belong to its KB", job.taskId());
                return;
            }

            // worker 侧重建 progress（Go 的 processing 初始化覆写准入时的 pending）
            FaqImportProgress progress = new FaqImportProgress(
                    job.taskId(), job.kbId(), job.knowledgeId(), "processing", 0,
                    job.entries().size(), 0, 0, 0, 0, 0,
                    new ArrayList<>(), null, new ArrayList<>(), null, null, 0, 0, null,
                    "正在验证条目...", "", Instant.now().getEpochSecond(),
                    Instant.now().getEpochSecond(), job.dryRun(),
                    null, null, null, 0);
            try {
                validateFAQImportTags(kb, job.entries());
            } catch (BizException e) {
                markImportFailed(job, progress, e.getMessage());
                return;
            }

            int originalTotalEntries = job.entries().size();
            progress = executeFAQDryRunValidation(job, progress);

            if (job.dryRun()) {
                finalizeImport(job, progress, originalTotalEntries);
                return;
            }
            if (progress.validEntryIndices() == null || progress.validEntryIndices().isEmpty()) {
                finalizeImport(job, progress, originalTotalEntries);
                return;
            }

            progress = withMessage(progress,
                    "验证完成，开始导入 " + progress.validEntryIndices().size() + " 条有效数据...");

            // 导入执行面：与 Go 同位置过 GetEmbeddingModel（无模型 KB 的实录失败点）
            Model embeddingModel;
            try {
                embeddingModel = requireEmbeddingModel(kb);
            } catch (IllegalStateException e) {
                markImportFailed(job, progress, e.getMessage());
                return;
            }
            executeImportBatches(job, kb, knowledge, embeddingModel, progress);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 对照 executeFAQDryRunValidation（knowledge_faq_import.go L454-470）：
     * append 走四阶段校验（含合并候选与后校验）、replace 走三阶段校验。
     * 就地改写 job.entries 的相似问/反例（Go 的共享切片语义）；
     * 进度对象不可变（record）——返回更新后的实例并落库。
     */
    private FaqImportProgress executeFAQDryRunValidation(ImportJob job, FaqImportProgress progress) {
        List<Integer> valid = "append".equals(job.mode())
                ? validateAppendMode(job.tenantId(), job.kbId(), job.entries(), progress)
                : validateReplaceMode(job.entries(), progress);
        // 校验内部的 withValidationResults 是不可变 record 的局部副本——
        // 以存储里的最新进度为基底挂 valid_entry_indices（对照 Go 的共享 struct 就地改写）
        progress = withValidEntryIndices(taskStore.getProgress(job.taskId()), valid);
        taskStore.saveProgress(progress);
        return progress;
    }

    /** 对照 validateEntriesForAppendModeWithProgress（knowledge_faq_import.go L485-785）。 */
    private List<Integer> validateAppendMode(long tenantId, String kbId,
                                             List<FaqDtos.FaqEntryPayload> entries,
                                             FaqImportProgress progress) {
        List<Chunk> existingChunks = chunkRepository
                .listAllFAQChunksWithMetadataByKnowledgeBaseId(tenantId, kbId);

        Map<String, Chunk> existingStdQToChunk = new LinkedHashMap<>();
        Map<String, String> existingQuestionToChunkID = new LinkedHashMap<>();
        Map<String, Set<String>> existingChunkQuestions = new LinkedHashMap<>();
        Map<String, String> existingChunkIDToStdQ = new LinkedHashMap<>();
        for (Chunk chunk : existingChunks) {
            FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            Set<String> qs = new LinkedHashSet<>();
            if (!meta.standardQuestion.isEmpty()) {
                existingStdQToChunk.put(meta.standardQuestion, chunk);
                existingQuestionToChunkID.put(meta.standardQuestion, chunk.getId());
                qs.add(meta.standardQuestion);
            }
            if (meta.similarQuestions != null) {
                for (String q : meta.similarQuestions) {
                    if (!q.isEmpty()) {
                        existingQuestionToChunkID.put(q, chunk.getId());
                        qs.add(q);
                    }
                }
            }
            existingChunkQuestions.put(chunk.getId(), qs);
            existingChunkIDToStdQ.put(chunk.getId(), meta.standardQuestion);
        }

        Map<Integer, Chunk> mergeChunkMap = new LinkedHashMap<>();

        // 第一次迭代：基本格式验证 + 文件内标准问去重 + 合并候选识别
        Map<String, Integer> batchStandardQuestions = new LinkedHashMap<>();
        List<Integer> validIndicesAfterStdQ = new ArrayList<>();
        List<FaqFailedEntry> failedEntries = new ArrayList<>(progress.failedEntries() == null
                ? List.of() : progress.failedEntries());
        int failedCount = progress.failedCount();
        for (int i = 0; i < entries.size(); i++) {
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            String basicError = validateEntryPayloadBasic(entry);
            if (basicError != null) {
                failedCount++;
                failedEntries.add(failedEntry(i, basicError, entry, "pre_validation"));
                continue;
            }
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Integer firstIdx = batchStandardQuestions.get(standardQ);
            if (firstIdx != null) {
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "标准问冲突：与批次内第 " + (firstIdx + 1) + " 条标准问重复", entry, "pre_validation"));
                continue;
            }
            Chunk mergeChunk = existingStdQToChunk.get(standardQ);
            if (mergeChunk != null) {
                mergeChunkMap.put(i, mergeChunk);
            } else if (existingQuestionToChunkID.containsKey(standardQ)) {
                String conflictChunkId = existingQuestionToChunkID.get(standardQ);
                String conflictStdQ = existingChunkIDToStdQ.getOrDefault(conflictChunkId, "");
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "标准问冲突：与知识库中标准问“" + conflictStdQ + "”的相似问“"
                                + standardQ + "”重复", entry, "pre_validation"));
                continue;
            }
            batchStandardQuestions.put(standardQ, i);
            validIndicesAfterStdQ.add(i);
        }

        // 第二次迭代：相似问冲突检测
        Map<String, Integer> batchAllQuestions = new LinkedHashMap<>();
        for (int i : validIndicesAfterStdQ) {
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            batchAllQuestions.putIfAbsent(FaqChunkMetadata.trimSpace(entry.standardQuestion()), i);
            if (entry.similarQuestions() != null) {
                for (String q : entry.similarQuestions()) {
                    String t = FaqChunkMetadata.trimSpace(q);
                    if (!t.isEmpty()) {
                        batchAllQuestions.putIfAbsent(t, i);
                    }
                }
            }
        }

        Map<Integer, List<String>> removedSimilarMap = new LinkedHashMap<>();
        Map<Integer, List<String>> removedNegativeMap = new LinkedHashMap<>();

        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Set<String> ownChunkQuestions = mergeChunkMap.containsKey(i)
                    ? existingChunkQuestions.get(mergeChunkMap.get(i).getId()) : null;

            List<String> validSimilar = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.similarQuestions() != null) {
                for (String qRaw : entry.similarQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    if (q.equals(standardQ)) {
                        removed.add("「相似问冲突」：“" + q + "”与本条“标准问”冲突");
                        continue;
                    }
                    if (existingQuestionToChunkID.containsKey(q)) {
                        if (ownChunkQuestions != null && ownChunkQuestions.contains(q)) {
                            validSimilar.add(q);
                            continue;
                        }
                        removed.add("「相似问冲突」：“" + q + "”与知识库已有“标准问/相似问”冲突");
                        continue;
                    }
                    Integer firstIdx2 = batchAllQuestions.get(q);
                    if (firstIdx2 != null && firstIdx2 != i) {
                        removed.add("「相似问冲突」：“" + q + "”与第 " + (firstIdx2 + 1)
                                + " 行“标准问/相似问”冲突");
                        continue;
                    }
                    validSimilar.add(q);
                }
            }
            if (entry.similarQuestions() != null) {
                entries.get(i).similarQuestions().clear();
                entries.get(i).similarQuestions().addAll(validSimilar);
            } else if (!validSimilar.isEmpty()) {
                entries.set(i, new FaqDtos.FaqEntryPayload(entry.id(), entry.standardQuestion(),
                        validSimilar, entry.negativeQuestions(), entry.answers(), entry.answerStrategy(),
                        entry.tagId(), entry.tagName(), entry.isEnabled(), entry.isRecommended()));
            }
            if (!removed.isEmpty()) {
                removedSimilarMap.put(i, removed);
            }
        }

        // 第三次迭代：反例冲突检测（预校验，仅检查新条目自身数据）
        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Set<String> currentQAQuestions = new LinkedHashSet<>();
            currentQAQuestions.add(standardQ);
            if (entry.similarQuestions() != null) {
                currentQAQuestions.addAll(entry.similarQuestions());
            }
            List<String> validNegative = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.negativeQuestions() != null) {
                for (String qRaw : entry.negativeQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    if (currentQAQuestions.contains(q)) {
                        removed.add("「反例冲突」：“" + q + "”与本条“标准问/相似问”冲突");
                        continue;
                    }
                    validNegative.add(q);
                }
            }
            if (entry.negativeQuestions() != null) {
                entries.get(i).negativeQuestions().clear();
                entries.get(i).negativeQuestions().addAll(validNegative);
            }
            if (!removed.isEmpty()) {
                removedNegativeMap.put(i, removed);
            }
        }

        // 第四次迭代：后校验（仅合并候选）
        Set<Integer> postValidationFailed = new LinkedHashSet<>();
        int mergeCount = 0;
        for (int i : validIndicesAfterStdQ) {
            Chunk mergeChunk = mergeChunkMap.get(i);
            if (mergeChunk == null) {
                continue;
            }
            FaqChunkMetadata existingMeta = sanitizedFaqMetadata(mergeChunk);
            if (existingMeta == null) {
                continue;
            }
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            List<String> mergedSimilar = unionStrings(existingMeta.similarQuestions, entry.similarQuestions());
            List<String> mergedNegative = unionStrings(existingMeta.negativeQuestions, entry.negativeQuestions());
            Set<String> mergedPositiveSet = new LinkedHashSet<>();
            mergedPositiveSet.add(existingMeta.standardQuestion);
            mergedPositiveSet.addAll(mergedSimilar);
            List<String> conflictingNegatives = new ArrayList<>();
            for (String q : mergedNegative) {
                if (mergedPositiveSet.contains(q)) {
                    conflictingNegatives.add(q);
                }
            }
            if (!conflictingNegatives.isEmpty()) {
                postValidationFailed.add(i);
                mergeChunkMap.remove(i);
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "后校验失败：合并后反例「" + String.join("、", conflictingNegatives) + "」与相似问冲突",
                        entry, "post_validation"));
            } else {
                mergeCount++;
            }
        }
        if (!postValidationFailed.isEmpty()) {
            validIndicesAfterStdQ.removeIf(postValidationFailed::contains);
        }

        int partialFailedCount = progress.partialFailedCount();
        for (int i : validIndicesAfterStdQ) {
            List<String> removedSimilar = removedSimilarMap.get(i);
            List<String> removedNegative = removedNegativeMap.get(i);
            if ((removedSimilar != null && !removedSimilar.isEmpty())
                    || (removedNegative != null && !removedNegative.isEmpty())) {
                failedEntries.add(partialFailedEntry(i, entries.get(i),
                        removedSimilar == null ? List.of() : removedSimilar,
                        removedNegative == null ? List.of() : removedNegative));
                partialFailedCount++;
            }
        }
        List<Integer> mergeIndices = new ArrayList<>();
        for (int i : validIndicesAfterStdQ) {
            if (mergeChunkMap.containsKey(i)) {
                mergeIndices.add(i);
            }
        }
        progress = withValidationResults(progress, failedEntries, failedCount, partialFailedCount,
                mergeIndices, null);
        taskStore.saveProgress(progress);
        log.info("Append mode validation completed: total={}, valid={}, merge_candidates={}, failed={}, partial_failed={}",
                entries.size(), validIndicesAfterStdQ.size(), mergeCount, progress.failedCount(),
                progress.partialFailedCount());
        return validIndicesAfterStdQ;
    }

    /** 对照 validateEntriesForReplaceModeWithProgress（knowledge_faq_import.go L793-957）。 */
    private List<Integer> validateReplaceMode(List<FaqDtos.FaqEntryPayload> entries,
                                              FaqImportProgress progress) {
        Map<String, Integer> batchStandardQuestions = new LinkedHashMap<>();
        List<Integer> validIndicesAfterStdQ = new ArrayList<>();
        List<FaqFailedEntry> failedEntries = new ArrayList<>(progress.failedEntries() == null
                ? List.of() : progress.failedEntries());
        int failedCount = progress.failedCount();
        for (int i = 0; i < entries.size(); i++) {
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            String basicError = validateEntryPayloadBasic(entry);
            if (basicError != null) {
                failedCount++;
                failedEntries.add(failedEntry(i, basicError, entry, null));
                continue;
            }
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Integer firstIdx = batchStandardQuestions.get(standardQ);
            if (firstIdx != null) {
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "标准问冲突：与批次内第 " + (firstIdx + 1) + " 条标准问重复", entry, null));
                continue;
            }
            batchStandardQuestions.put(standardQ, i);
            validIndicesAfterStdQ.add(i);
        }

        Map<String, Integer> batchAllQuestions = new LinkedHashMap<>();
        for (int i : validIndicesAfterStdQ) {
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            batchAllQuestions.putIfAbsent(FaqChunkMetadata.trimSpace(entry.standardQuestion()), i);
            if (entry.similarQuestions() != null) {
                for (String q : entry.similarQuestions()) {
                    String t = FaqChunkMetadata.trimSpace(q);
                    if (!t.isEmpty()) {
                        batchAllQuestions.putIfAbsent(t, i);
                    }
                }
            }
        }

        Map<Integer, List<String>> removedSimilarMap = new LinkedHashMap<>();
        Map<Integer, List<String>> removedNegativeMap = new LinkedHashMap<>();

        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            List<String> validSimilar = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.similarQuestions() != null) {
                for (String qRaw : entry.similarQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    Integer firstIdx2 = batchAllQuestions.get(q);
                    if (firstIdx2 != null && firstIdx2 != i) {
                        removed.add("「相似问冲突」：“" + q + "”与第 " + (firstIdx2 + 1)
                                + " 行“标准问/相似问”冲突");
                        continue;
                    }
                    if (q.equals(standardQ)) {
                        removed.add("「相似问冲突」：“" + q + "”与本条“标准问”冲突");
                        continue;
                    }
                    validSimilar.add(q);
                }
            }
            if (entry.similarQuestions() != null) {
                entries.get(i).similarQuestions().clear();
                entries.get(i).similarQuestions().addAll(validSimilar);
            }
            if (!removed.isEmpty()) {
                removedSimilarMap.put(i, removed);
            }
        }

        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqDtos.FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Set<String> currentQAQuestions = new LinkedHashSet<>();
            currentQAQuestions.add(standardQ);
            if (entry.similarQuestions() != null) {
                currentQAQuestions.addAll(entry.similarQuestions());
            }
            List<String> validNegative = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.negativeQuestions() != null) {
                for (String qRaw : entry.negativeQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    if (currentQAQuestions.contains(q)) {
                        removed.add("「反例冲突」：“" + q + "”与本条“标准问/相似问”冲突");
                        continue;
                    }
                    validNegative.add(q);
                }
            }
            if (entry.negativeQuestions() != null) {
                entries.get(i).negativeQuestions().clear();
                entries.get(i).negativeQuestions().addAll(validNegative);
            }
            if (!removed.isEmpty()) {
                removedNegativeMap.put(i, removed);
            }
        }

        int partialFailedCount = progress.partialFailedCount();
        for (int i : validIndicesAfterStdQ) {
            List<String> removedSimilar = removedSimilarMap.get(i);
            List<String> removedNegative = removedNegativeMap.get(i);
            if ((removedSimilar != null && !removedSimilar.isEmpty())
                    || (removedNegative != null && !removedNegative.isEmpty())) {
                failedEntries.add(partialFailedEntry(i, entries.get(i),
                        removedSimilar == null ? List.of() : removedSimilar,
                        removedNegative == null ? List.of() : removedNegative));
                partialFailedCount++;
            }
        }
        progress = withValidationResults(progress, failedEntries, failedCount, partialFailedCount,
                null, null);
        taskStore.saveProgress(progress);
        return validIndicesAfterStdQ;
    }

    /** 对照 validateFAQEntryPayloadBasic（knowledge_faq_import.go L981-1003）。 */
    private static String validateEntryPayloadBasic(FaqDtos.FaqEntryPayload entry) {
        if (entry == null) {
            return "条目不能为空";
        }
        String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
        if (standardQ.isEmpty()) {
            return "标准问不能为空";
        }
        if (entry.answers() == null || entry.answers().isEmpty()) {
            return "答案不能为空";
        }
        boolean hasValidAnswer = false;
        for (String a : entry.answers()) {
            if (!FaqChunkMetadata.trimSpace(a).isEmpty()) {
                hasValidAnswer = true;
                break;
            }
        }
        if (!hasValidAnswer) {
            return "答案不能全为空";
        }
        return null;
    }

    /** 对照 unionStrings（knowledge_faq_import.go L960-978）。 */
    private static List<String> unionStrings(List<String> a, List<String> b) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String s0 : a == null ? new String[0] : a.toArray(new String[0])) {
            String t = FaqChunkMetadata.trimSpace(s0);
            if (!t.isEmpty() && seen.add(t)) {
                result.add(t);
            }
        }
        for (String s0 : b == null ? new String[0] : b.toArray(new String[0])) {
            String t = FaqChunkMetadata.trimSpace(s0);
            if (!t.isEmpty() && seen.add(t)) {
                result.add(t);
            }
        }
        return result;
    }

    /** 对照 buildFAQFailedEntry（knowledge_faq_import.go L384-404）。 */
    private static FaqFailedEntry failedEntry(int idx, String reason, FaqDtos.FaqEntryPayload entry,
                                              String failureType) {
        boolean answerAll = FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(entry.answerStrategy());
        boolean isDisabled = entry.isEnabled() != null && !entry.isEnabled();
        return new FaqFailedEntry(idx, reason, failureType, false,
                entry.tagName(), FaqChunkMetadata.trimSpace(entry.standardQuestion()),
                entry.similarQuestions(), entry.negativeQuestions(), entry.answers(),
                answerAll, isDisabled, null, null);
    }

    /** 对照 buildFAQPartialFailedEntry（knowledge_faq_import.go L406-451）。 */
    private static FaqFailedEntry partialFailedEntry(int idx, FaqDtos.FaqEntryPayload entry,
                                                     List<String> removedSimilar, List<String> removedNegative) {
        boolean answerAll = FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(entry.answerStrategy());
        boolean isDisabled = entry.isEnabled() != null && !entry.isEnabled();
        List<String> summary = new ArrayList<>();
        if (!removedSimilar.isEmpty()) {
            summary.add(removedSimilar.size() + "条相似问被移除");
        }
        if (!removedNegative.isEmpty()) {
            summary.add(removedNegative.size() + "条反例被移除");
        }
        List<String> reasonParts = new ArrayList<>();
        reasonParts.add("部分成功：" + String.join("，", summary));
        if (!removedSimilar.isEmpty()) {
            reasonParts.add(String.join("; ", removedSimilar));
        }
        if (!removedNegative.isEmpty()) {
            reasonParts.add(String.join("; ", removedNegative));
        }
        return new FaqFailedEntry(idx, String.join(" | ", reasonParts), null, true,
                entry.tagName(), FaqChunkMetadata.trimSpace(entry.standardQuestion()),
                entry.similarQuestions(), entry.negativeQuestions(), entry.answers(),
                answerAll, isDisabled, removedSimilar, removedNegative);
    }

    /**
     * 对照 finalizeFAQValidation（knowledge_faq_import.go L2493-2595）：失败条目 CSV
     * （进度里 failed_entries 清空、failed_entries_url 接管、message 追加 CSV 提示）、
     * 计数归一、结果落库（非 dry）、replace 清理未引用标签、终态 completed。
     */
    private void finalizeImport(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        List<FaqFailedEntry> failedEntries = progress.failedEntries() == null
                ? List.of() : progress.failedEntries();
        String failedEntriesUrl = progress.failedEntriesUrl();
        String message = progress.message();
        if (!failedEntries.isEmpty()) {
            String csvUrl = generateFailedEntriesCsv(job.tenantId(), job.taskId(), failedEntries);
            if (csvUrl != null && !csvUrl.isEmpty()) {
                failedEntriesUrl = csvUrl;
                message = message + " (失败记录已导出为CSV)";
            }
        }
        progress = withValidationResults(progress,
                failedEntriesUrl == null || failedEntriesUrl.isEmpty() ? failedEntries : List.of(),
                progress.failedCount(), progress.partialFailedCount(), null, failedEntriesUrl);
        progress = withMessage(progress, message);

        progress = withStatus(progress, "completed", 100, originalTotalEntries);
        int successCount;
        if (progress.validEntryIndices() != null && !progress.validEntryIndices().isEmpty()) {
            successCount = progress.validEntryIndices().size() - progress.partialFailedCount();
        } else if (progress.successEntries() != null && !progress.successEntries().isEmpty()) {
            successCount = progress.successEntries().size() - progress.partialFailedCount();
        } else {
            successCount = originalTotalEntries - progress.failedCount() - progress.partialFailedCount();
        }
        if (successCount < 0) {
            successCount = 0;
        }
        int skippedCount = originalTotalEntries - successCount - progress.partialFailedCount() - progress.failedCount();
        if (skippedCount < 0) {
            skippedCount = 0;
        }
        int addedCount = progress.addedCount();
        if (addedCount == 0 && progress.mergedCount() > 0) {
            addedCount = Math.max(successCount - progress.mergedCount(), 0);
        } else if (addedCount == 0) {
            addedCount = successCount;
        }
        progress = withCounts(progress, successCount, skippedCount, addedCount);
        progress = withMessage(progress, buildImportResultMessage(
                job.dryRun() ? "验证完成" : "导入完成", progress));
        progress = withError(progress, "");
        taskStore.saveProgress(progress);

        if (!job.dryRun()) {
            saveImportResultToDatabase(job, progress, originalTotalEntries);
            if ("replace".equals(job.mode())) {
                int deleted = tagMapper.deleteUnusedTags(job.tenantId(), job.kbId());
                if (deleted > 0) {
                    log.info("FAQ import task {}: cleaned up {} unused tags after replace import",
                            job.taskId(), deleted);
                }
            }
        }
        // updateFAQImportProgressStatus(completed)：终态覆写 + 清 running key
        progress = withStatus(progress, "completed", 100, originalTotalEntries);
        progress = withUpdatedNow(progress);
        progress = withError(progress, "");
        taskStore.saveProgress(progress);
        taskStore.clearRunningInfoIfMatches(job.kbId(), job.taskId(), job.instanceId(), job.enqueuedAt());
        log.info("FAQ task completed: {}, dry_run={}, success: {}, added: {}, merged: {}, failed: {}, partial_failed: {}",
                job.taskId(), job.dryRun(), progress.successCount(), progress.addedCount(),
                progress.mergedCount(), progress.failedCount(), progress.partialFailedCount());
    }

    /** 对照 markImportFailed 的终态语义（asynq isLastRetry 分支的直落形态）。 */
    private void markImportFailed(ImportJob job, FaqImportProgress progress, String error) {
        progress = withStatus(progress, "failed", 0, progress.total());
        progress = withMessage(progress, "导入失败");
        progress = withError(progress, error);
        progress = withUpdatedNow(progress);
        taskStore.saveProgress(progress);
        taskStore.clearRunningInfoIfMatches(job.kbId(), job.taskId(), job.instanceId(), job.enqueuedAt());
        log.warn("FAQ import task {} failed: {}", job.taskId(), error);
    }

    /** 对照 Go knowledge.go L90：faqImportBatchSize = 50（每批处理的 FAQ 条目数）。 */
    private static final int FAQ_IMPORT_BATCH_SIZE = 50;

    /**
     * 对照 executeFAQImport 的导入执行循环（knowledge_faq_import.go L1499-1671）——
     * 2026-09-22 走查批接线（此前是「embedding runtime is not available」占位）：
     * 按 faqImportBatchSize(50) 分批 → 逐条 sanitize/resolveTagID/建 chunk → CreateChunks
     * → indexFAQChunks(adjustStorage=true) → status=2 → 收集成功条目 → 进度落库；
     * 末尾 finalizeImport（completed 终态 + 结果落库 + replace 清未引用标签）。
     *
     * <p>已知差异：Go 的 defer recover 会回滚本任务已创建的 chunks 与索引行；Java 无该
     * 事务性回滚（失败直落 failed 终态，残留行由重导/replace 清理）——与 processImport
     * 的既有取舍同款。</p>
     */
    private void executeImportBatches(ImportJob job, KnowledgeBase kb, Knowledge faqKnowledge,
                                      Model embeddingModel, FaqImportProgress progress) {
        List<Integer> valid = progress.validEntryIndices();
        int totalEntries = progress.total();
        int skippedCount = progress.skippedCount();
        int actualProcessed = skippedCount + progress.mergedCount();
        String indexMode = faqIndexMode(kb);
        List<FaqSuccessEntry> successEntries = progress.successEntries() == null
                ? new ArrayList<>() : new ArrayList<>(progress.successEntries());

        for (int i = 0; i < valid.size(); i += FAQ_IMPORT_BATCH_SIZE) {
            int end = Math.min(i + FAQ_IMPORT_BATCH_SIZE, valid.size());
            List<Chunk> chunks = new ArrayList<>(end - i);
            for (int k = i; k < end; k++) {
                int entryIdx = valid.get(k); // dry-run 校验给出的原始条目下标
                FaqDtos.FaqEntryPayload entry = job.entries().get(entryIdx);
                FaqChunkMetadata meta;
                try {
                    meta = sanitizeFAQEntryPayload(entry);
                } catch (RuntimeException e) {
                    markImportFailed(job, progress,
                            "FAQ import failed: failed to sanitize entry at index " + entryIdx
                                    + ": " + e.getMessage());
                    return;
                }
                String tagID;
                try {
                    tagID = resolveTagID(job.kbId(), entry);
                } catch (RuntimeException e) {
                    markImportFailed(job, progress,
                            "FAQ import failed: failed to resolve tag for entry at index " + entryIdx
                                    + ": " + e.getMessage());
                    return;
                }
                boolean isEnabled = entry.isEnabled() == null || entry.isEnabled();
                Chunk chunk = new Chunk();
                chunk.setId(UUID.randomUUID().toString());
                chunk.setTenantId(job.tenantId());
                chunk.setKnowledgeId(faqKnowledge.getId());
                chunk.setKnowledgeBaseId(kb.getId());
                chunk.setContent(buildFAQChunkContent(meta, indexMode));
                chunk.setIsEnabled(isEnabled);
                chunk.setChunkType("faq");
                chunk.setTagId(tagID);
                chunk.setStatus(1); // stored
                if (entry.id() != null && entry.id() > 0) {
                    chunk.setSeqId(entry.id());
                }
                setFaqMetadata(chunk, meta);
                // 对照 Go：导入建的 chunk 不设 Flags（推荐位零值）
                chunk.setCreatedAt(OffsetDateTime.now());
                chunk.setUpdatedAt(chunk.getCreatedAt());
                chunks.add(chunk);
            }
            List<String> chunkIds = new ArrayList<>(chunks.size());
            for (Chunk chunk : chunks) {
                chunkIds.add(chunk.getId());
            }
            try {
                createChunks(chunks);
            } catch (RuntimeException e) {
                markImportFailed(job, progress,
                        "FAQ import failed: failed to create chunks: " + e.getMessage());
                return;
            }
            try {
                indexFAQChunks(kb, faqKnowledge, chunks, embeddingModel, true);
            } catch (RuntimeException e) {
                markImportFailed(job, progress,
                        "FAQ import failed: failed to index chunks: " + e.getMessage());
                return;
            }
            for (Chunk chunk : chunks) {
                chunk.setStatus(2); // indexed
            }
            try {
                chunkRepository.updateChunks(chunks);
            } catch (RuntimeException e) {
                markImportFailed(job, progress,
                        "FAQ import failed: failed to update chunks status: " + e.getMessage());
                return;
            }

            // 收集成功条目（对照 Go L1606-1630：index/seq_id/tag_id/tag_name/标准问）
            for (int k = 0; k < chunks.size(); k++) {
                Chunk chunk = chunks.get(k);
                FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
                String standardQ = meta == null || meta.standardQuestion == null
                        ? "" : meta.standardQuestion;
                long tagID = 0;
                String tagName = "";
                if (chunk.getTagId() != null && !chunk.getTagId().isEmpty()) {
                    KnowledgeTag tag = tagMapper
                            .selectByTenantAndIds(job.tenantId(), List.of(chunk.getTagId()))
                            .stream().findFirst().orElse(null);
                    if (tag != null) {
                        tagID = tag.getSeqId();
                        tagName = tag.getName();
                    }
                }
                successEntries.add(new FaqSuccessEntry(valid.get(k), 
                        chunk.getSeqId() == null ? 0 : chunk.getSeqId(), tagID, tagName, standardQ));
            }

            actualProcessed += end - i;
            int prog = totalEntries == 0 ? 0 : (int) ((double) actualProcessed / totalEntries * 100);
            progress = withStatus(progress, "processing", prog, actualProcessed);
            progress = withMessage(progress,
                    "正在处理第 " + actualProcessed + "/" + totalEntries + " 条");
            progress = withSuccessEntries(progress, successEntries);
            taskStore.saveProgress(progress);
        }

        progress = withSuccessEntries(progress, successEntries);
        taskStore.saveProgress(progress);
        log.info("FAQ import task {}: all batches completed, processed: {}", job.taskId(), actualProcessed);
        finalizeImport(job, progress, totalEntries);
    }

    /** 对照 generateFailedEntriesCSV（knowledge_faq_import.go L257-318）：BOM + 8 列，
     *  落 {base}/{tenant}/exports/{name}_{UnixNano}.csv，返回 local:// URL。 */
    private String generateFailedEntriesCsv(long tenantId, String taskId, List<FaqFailedEntry> failedEntries) {
        StringBuilder buf = new StringBuilder();
        buf.append('\uFEFF');
        buf.append("错误原因,分类(必填),问题(必填),相似问题(选填-多个用##分隔),反例问题(选填-多个用##分隔),")
                .append("机器人回答(必填-多个用##分隔),是否全部回复(选填-默认FALSE),是否停用(选填-默认FALSE)")
                .append('\n');
        for (FaqFailedEntry entry : failedEntries) {
            String answerAll = entry.answerAll() ? "true" : "false";
            String isDisabled = entry.isDisabled() ? "true" : "false";
            buf.append(csvEscape(entry.reason())).append(',')
                    .append(csvEscape(entry.tagName())).append(',')
                    .append(csvEscape(entry.standardQuestion())).append(',')
                    .append(csvEscape(entry.similarQuestions() == null ? "" : String.join("##", entry.similarQuestions())))
                    .append(',')
                    .append(csvEscape(entry.negativeQuestions() == null ? "" : String.join("##", entry.negativeQuestions())))
                    .append(',')
                    .append(csvEscape(entry.answers() == null ? "" : String.join("##", entry.answers())))
                    .append(',')
                    .append(answerAll).append(',')
                    .append(isDisabled).append('\n');
        }
        String base = storage.baseDir().toString();
        java.nio.file.Path dir = java.nio.file.Path.of(base, String.valueOf(tenantId), "exports");
        String unique = "faq_dryrun_failed_" + taskId + "_" + System.nanoTime() + ".csv";
        byte[] csv = buf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (fileStorage != null) {
            // 对照 Go：fileSvc.SaveBytes(..., temp=true) + GetFileURL → 云上落临时桶、回预签名 URL
            TenantFileStorage.Exported exported =
                    fileStorage.saveExportedBytesToUrl(tenantId, unique, csv, true);
            if (exported.handled()) {
                if (exported.url() == null) {
                    log.warn("FAQ import task {}: failed to generate failed entries CSV", taskId);
                }
                return exported.url();
            }
        }
        try {
            // 本地租户：既有落盘 + local:// 引用（golden 形态）
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(unique);
            java.nio.file.Files.write(target, csv);
            return "local://" + tenantId + "/exports/" + unique;
        } catch (java.io.IOException e) {
            log.warn("FAQ import task {}: failed to generate failed entries CSV: {}", taskId, e.getMessage());
            return null;
        }
    }

    private static String csvEscape(String s) {
        if (s == null) {
            s = "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 对照 saveFAQImportResultToDatabase（knowledge_faq_import.go L330-381）。 */
    private void saveImportResultToDatabase(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, job.knowledgeId())
                .eq(Knowledge::getTenantId, job.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (knowledge == null) {
            log.warn("FAQ import task {}: knowledge not found for result save", job.taskId());
            return;
        }
        int skippedCount = originalTotalEntries - progress.successCount()
                - progress.partialFailedCount() - progress.failedCount();
        if (skippedCount < 0) {
            skippedCount = 0;
        }
        long processingTime = Instant.now().getEpochSecond() - progress.createdAt();
        FaqImportResult result = new FaqImportResult(originalTotalEntries, progress.successCount(),
                progress.failedCount(), progress.partialFailedCount(), skippedCount,
                progress.mergedCount(), progress.addedCount(), job.mode(),
                OffsetDateTime.now(), job.taskId(),
                progress.failedEntriesUrl() == null || progress.failedEntriesUrl().isEmpty()
                        ? null : progress.failedEntriesUrl(),
                "open", processingTime);
        knowledge.setLastFaqImportResult(FaqChunkMetadata.JSON.valueToTree(result));
        knowledge.setUpdatedAt(OffsetDateTime.now());
        knowledgeMapper.updateById(knowledge);
        log.info("Saved FAQ import result to database: knowledge_id={}, task={}, total={}, success={}, failed={}",
                job.knowledgeId(), job.taskId(), originalTotalEntries, progress.successCount(),
                progress.failedCount());
    }

    /** 对照 buildFAQImportResultMessage（knowledge_faq_import.go L2725-2744）。 */
    private static String buildImportResultMessage(String prefix, FaqImportProgress p) {
        List<String> parts = new ArrayList<>();
        parts.add(prefix);
        parts.add("上传 " + p.total() + " 条");
        if (p.mergedCount() > 0) {
            parts.add("新增 " + p.addedCount() + " 条");
            parts.add("合并更新 " + p.mergedCount() + " 条");
        } else {
            parts.add("成功 " + p.successCount() + " 条");
        }
        if (p.failedCount() > 0) {
            parts.add("失败 " + p.failedCount() + " 条");
        }
        if (p.partialFailedCount() > 0) {
            parts.add("部分失败 " + p.partialFailedCount() + " 条");
        }
        return String.join(" / ", parts);
    }

    // ── 不可变 record 的局部更新辅助 ─────────────────────────────────────

    private static FaqImportProgress withMessage(FaqImportProgress p, String message) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), message, p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withError(FaqImportProgress p, String error) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), error, p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withStatus(FaqImportProgress p, String status, int prog, int processed) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), status, prog,
                p.total(), processed, p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 批次成功后累积 success_entries（对照 Go 的 progress.SuccessEntries append）。 */
    private static FaqImportProgress withSuccessEntries(FaqImportProgress p,
                                                        List<FaqSuccessEntry> successEntries) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(),
                successEntries == null ? List.of() : new ArrayList<>(successEntries),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withUpdatedNow(FaqImportProgress p) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(),
                Instant.now().getEpochSecond(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withCounts(FaqImportProgress p, int successCount, int skippedCount,
                                                int addedCount) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), successCount, p.failedCount(), p.partialFailedCount(),
                skippedCount, p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), addedCount,
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withValidEntryIndices(FaqImportProgress p, List<Integer> valid) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                valid, p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 校验结果落进度（failedEntries/failedCount/partialFailedCount/mergeIndices/failedUrl 的部分覆写）。 */
    private static FaqImportProgress withValidationResults(FaqImportProgress p,
                                                           List<FaqFailedEntry> failedEntries,
                                                           Integer failedCount,
                                                           Integer partialFailedCount,
                                                           List<Integer> mergeEntryIndices,
                                                           String failedEntriesUrl) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(),
                p.successCount(),
                failedCount == null ? p.failedCount() : failedCount,
                partialFailedCount == null ? p.partialFailedCount() : partialFailedCount,
                p.skippedCount(),
                failedEntries == null ? p.failedEntries() : failedEntries,
                failedEntriesUrl == null ? p.failedEntriesUrl() : failedEntriesUrl,
                p.successEntries(),
                p.validEntryIndices(),
                mergeEntryIndices == null ? p.mergeEntryIndices() : mergeEntryIndices,
                p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 对照 GetFAQImportProgress（knowledge_faq_import.go L2779-2824）；completed 时用
     *  knowledges.last_faq_import_result 覆盖统计字段。 */
    public FaqImportProgress getImportProgress(String taskId) {
        FaqImportProgress progress = taskStore.getProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("FAQ import task not found"));
        }
        if ("completed".equals(progress.status()) && progress.knowledgeId() != null
                && !progress.knowledgeId().isEmpty()) {
            Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, progress.knowledgeId())
                    .eq(Knowledge::getTenantId, tenantId())
                    .isNull(Knowledge::getDeletedAt)
                    .last("LIMIT 1"));
            if (knowledge != null) {
                FaqImportResult result = parseImportResult(knowledge.getLastFaqImportResult());
                if (result != null) {
                    progress = new FaqImportProgress(
                            progress.taskId(), progress.kbId(), progress.knowledgeId(),
                            progress.status(), progress.progress(), progress.total(),
                            progress.processed(),
                            result.successCount(), result.failedCount(),
                            result.partialFailedCount(), result.skippedCount(),
                            progress.failedEntries(),
                            result.failedEntriesUrl() == null || result.failedEntriesUrl().isEmpty()
                                    ? progress.failedEntriesUrl() : result.failedEntriesUrl(),
                            progress.successEntries(),
                            progress.validEntryIndices(), progress.mergeEntryIndices(),
                            result.mergedCount(), result.addedCount(), progress.mergeDetails(),
                            progress.message(), progress.error(),
                            progress.createdAt(), progress.updatedAt(), progress.dryRun(),
                            result.importMode(), result.importedAt(),
                            result.displayStatus(), result.processingTime());
                }
            }
        }
        return progress;
    }

    /** 对照 UpdateLastFAQImportResultDisplayStatus（knowledge_faq_import.go L2827-2882）。 */
    public void updateLastImportResultDisplayStatus(String kbId, String displayStatus) {
        if (!"open".equals(displayStatus) && !"close".equals(displayStatus)) {
            throw new BizException(AppError.badRequest("invalid display status, must be 'open' or 'close'"));
        }
        KnowledgeBase kb = writableFAQKnowledgeBase(kbId);
        long tid = kb.getTenantId() == null ? tenantId() : kb.getTenantId();

        List<Knowledge> knowledgeList = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tid)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt));
        Knowledge faqKnowledge = null;
        for (Knowledge k : knowledgeList) {
            if ("faq".equals(k.getType())) {
                faqKnowledge = k;
                break;
            }
        }
        if (faqKnowledge == null) {
            throw new BizException(AppError.notFound("FAQ knowledge not found in this knowledge base"));
        }
        FaqImportResult result = parseImportResult(faqKnowledge.getLastFaqImportResult());
        if (result == null) {
            throw new BizException(AppError.notFound("no FAQ import result found"));
        }
        FaqImportResult updated = new FaqImportResult(result.totalEntries(), result.successCount(),
                result.failedCount(), result.partialFailedCount(), result.skippedCount(),
                result.mergedCount(), result.addedCount(), result.importMode(), result.importedAt(),
                result.taskId(), result.failedEntriesUrl(), displayStatus, result.processingTime());
        faqKnowledge.setLastFaqImportResult(FaqChunkMetadata.JSON.valueToTree(updated));
        knowledgeMapper.updateById(faqKnowledge);
    }

    // ══════════════════ 私有：KB/守卫 ══════════════════════════════════

    /** 对照 validateFAQKnowledgeBase（knowledge_faq.go L1501-1517）。 */
    private KnowledgeBase validateFAQKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("知识库 ID 不能为空"));
        }
        KnowledgeBase kb = knowledgeService.findKb(kbId);
        if (kb == null || !kb.getId().equals(kbId)) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        ensureDefaults(kb);
        if (!"faq".equals(kb.getType())) {
            throw new BizException(AppError.badRequest("仅 FAQ 知识库支持该操作"));
        }
        return kb;
    }

    /** 对照 resolveKBReadTenant（org-share 未翻译：仅同租户，越权 403——与路由守卫一致）。 */
    private long resolveKBReadTenant(KnowledgeBase kb) {
        Long current = TenantContext.currentTenantId();
        if (kb != null && current != null && current.equals(kb.getTenantId())) {
            return kb.getTenantId();
        }
        throw new BizException(AppError.forbidden("无权访问该知识库"));
    }

    /** 对照 writableFAQKnowledgeBase（requireKBWrite 未翻译，同 ChunkService 先例）。 */
    private KnowledgeBase writableFAQKnowledgeBase(String kbId) {
        return validateFAQKnowledgeBase(kbId);
    }

    /**
     * 对照 KnowledgeBase.EnsureDefaults 的 FAQ 段（types/knowledgebase.go L727-770）：
     * FAQConfig 缺失 → question_answer/combined；字段空 → 各自默认。
     * Java 的 faq_config 是 JsonNode——这里只读出有效值，不改 KB 行。
     */
    private void ensureDefaults(KnowledgeBase kb) {
        // 空实现：faqIndexMode/faqQuestionIndexMode 负责缺省值（EnsureDefaults 的读路径净效果）
    }

    private String faqIndexMode(KnowledgeBase kb) {
        JsonNode cfg = kb.getFaqConfig();
        String mode = cfg == null ? "" : cfg.path("index_mode").asText("");
        return mode.isEmpty() ? "question_answer" : mode;
    }

    private String faqQuestionIndexMode(KnowledgeBase kb) {
        JsonNode cfg = kb.getFaqConfig();
        String mode = cfg == null ? "" : cfg.path("question_index_mode").asText("");
        return mode.isEmpty() ? "combined" : mode;
    }

    // ══════════════════ 私有：条目 ↔ chunk 转换 ════════════════════════

    /** Go 的 chunk.FAQMetadata()：解析 + Sanitize。 */
    private FaqChunkMetadata sanitizedFaqMetadata(Chunk chunk) {
        FaqChunkMetadata meta = FaqChunkMetadata.fromJson(chunk.getMetadata());
        if (meta != null) {
            meta.sanitize();
        }
        return meta;
    }

    /** UpdateEntry 用：解析失败不影响（Go 的 err==nil && existing!=nil 判定）。 */
    private FaqChunkMetadata currentFaqMetadata(Chunk chunk) {
        return sanitizedFaqMetadata(chunk);
    }

    private void setFaqMetadata(Chunk chunk, FaqChunkMetadata meta) {
        meta.sanitize();
        chunk.setMetadata(meta.toJsonNode());
        chunk.setContentHash(FaqChunkMetadata.calculateContentHash(meta.normalize()));
    }

    /**
     * 对照 chunkToFAQEntry（knowledge_faq.go L1592-1631）。nil 列表保持 null
     * （Go nil slice → JSON null，FAQEntry 无 omitempty——golden 实录，别归一成 []）。
     */
    private FaqEntry chunkToFAQEntry(Chunk chunk, KnowledgeBase kb, Map<String, Long> tagSeqIdMap) {
        FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
        if (meta == null) {
            meta = new FaqChunkMetadata();
            meta.standardQuestion = chunk.getContent();
        }
        String answerStrategy = meta.answerStrategy == null || meta.answerStrategy.isEmpty()
                ? "all" : meta.answerStrategy;
        long tagSeqId = 0;
        if (!chunk.getTagId().isEmpty() && tagSeqIdMap != null) {
            tagSeqId = tagSeqIdMap.getOrDefault(chunk.getTagId(), 0L);
        }
        return new FaqEntry(
                chunk.getSeqId() == null ? 0 : chunk.getSeqId(),
                chunk.getId(),
                chunk.getKnowledgeId(),
                chunk.getKnowledgeBaseId(),
                tagSeqId,
                "",
                chunk.isIsEnabled(),
                (chunk.getFlags() & 1) != 0,
                meta.standardQuestion,
                meta.similarQuestions,
                meta.negativeQuestions,
                meta.answers,
                answerStrategy,
                faqIndexMode(kb),
                chunk.getUpdatedAt(),
                chunk.getCreatedAt(),
                0,
                0,
                chunk.getChunkType(),
                "");
    }

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

    /** 对照 buildFAQChunkContent（knowledge_faq.go L1633-1651）。 */
    private static String buildFAQChunkContent(FaqChunkMetadata meta, String mode) {
        StringBuilder builder = new StringBuilder();
        builder.append("Q: ").append(meta.standardQuestion).append('\n');
        if (meta.similarQuestions != null && !meta.similarQuestions.isEmpty()) {
            builder.append("Similar Questions:\n");
            for (String q : meta.similarQuestions) {
                builder.append("- ").append(q).append('\n');
            }
        }
        if ("question_answer".equals(mode) && meta.answers != null && !meta.answers.isEmpty()) {
            builder.append("Answers:\n");
            for (String ans : meta.answers) {
                builder.append("- ").append(ans).append('\n');
            }
        }
        return builder.toString();
    }

    // ══════════════════ 私有：payload 校验 / tag 解析 ══════════════════

    /** 对照 sanitizeFAQEntryPayload（knowledge_faq.go L1860-1888）。 */
    private FaqChunkMetadata sanitizeFAQEntryPayload(FaqDtos.FaqEntryPayload payload) {
        String answerStrategy = "all";
        if (payload.answerStrategy() != null && !payload.answerStrategy().isEmpty()) {
            if (FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(payload.answerStrategy())
                    || FaqChunkMetadata.ANSWER_STRATEGY_RANDOM.equals(payload.answerStrategy())) {
                answerStrategy = payload.answerStrategy();
            } else {
                throw new BizException(AppError.badRequest("answer_strategy 必须是 'all' 或 'random'"));
            }
        }
        FaqChunkMetadata meta = new FaqChunkMetadata();
        meta.standardQuestion = FaqChunkMetadata.trimSpace(payload.standardQuestion());
        meta.similarQuestions = payload.similarQuestions();
        meta.negativeQuestions = payload.negativeQuestions();
        meta.answers = payload.answers();
        meta.answerStrategy = answerStrategy;
        meta.version = 1;
        meta.source = "faq";
        meta.normalize();
        if (meta.standardQuestion == null || meta.standardQuestion.isEmpty()) {
            throw new BizException(AppError.badRequest("标准问不能为空"));
        }
        if (meta.answers == null || meta.answers.isEmpty()) {
            throw new BizException(AppError.badRequest("至少提供一个答案"));
        }
        return meta;
    }

    /** 对照 resolveTagID（knowledge_faq.go L1828-1858）：tag_id 优先、tag_name、未分类兜底。 */
    private String resolveTagID(String kbId, FaqDtos.FaqEntryPayload payload) {
        long tid = tenantId();
        if (payload.tagId() != 0) {
            KnowledgeTag tag = tagMapper.selectByTenantAndSeqId(tid, payload.tagId());
            if (tag == null) {
                throw new IllegalStateException("failed to find tag by seq_id " + payload.tagId() + ": record not found");
            }
            validateFAQTagScope(tag, tid, kbId);
            return tag.getId();
        }
        if (payload.tagName() != null && !payload.tagName().isEmpty()) {
            KnowledgeTag tag = findOrCreateTagByName(kbId, payload.tagName());
            return tag.getId();
        }
        return findOrCreateTagByName(kbId, FaqDtos.UNTAGGED_TAG_NAME).getId();
    }

    /** 对照 tagService.FindOrCreateTagByName（tag.go L476-508）。 */
    private KnowledgeTag findOrCreateTagByName(String kbId, String name) {
        name = FaqChunkMetadata.trimSpace(name);
        if (kbId == null || kbId.isEmpty() || name.isEmpty()) {
            throw new BizException(AppError.badRequest("知识库ID和标签名称不能为空"));
        }
        KnowledgeBase kb = knowledgeService.findKb(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        long tid = kb.getTenantId();
        KnowledgeTag existing = tagMapper.selectByTenantKbAndName(tid, kbId, name);
        if (existing != null) {
            return existing;
        }
        int sortOrder = FaqDtos.UNTAGGED_TAG_NAME.equals(name) ? -1 : 0;
        return tagRepository.createTag(tid, kbId, name, "", sortOrder);
    }

    /** 对照 validateFAQTagScope（knowledge_faq_batch.go L124-132）。 */
    private void validateFAQTagScope(KnowledgeTag tag, long tenantId, String kbId) {
        if (tag == null) {
            throw new BizException(AppError.notFound("标签不存在"));
        }
        if (tag.getTenantId() == null || tag.getTenantId().longValue() != tenantId
                || !kbId.equals(tag.getKnowledgeBaseId())) {
            throw new BizException(AppError.forbidden("标签不属于当前知识库"));
        }
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
        FaqChunkMetadata existingMeta = sanitizedFaqMetadata(dupChunk);
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

    /** 对照 loadFAQWriteChunks（knowledge_faq_batch.go L23-57）。 */
    private Map<Long, Chunk> loadFAQWriteChunks(KnowledgeBase kb, List<Long> ids) {
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new BizException(AppError.badRequest("FAQ 条目 ID 必须为正整数"));
            }
            wanted.add(id);
        }
        Map<Long, Chunk> result = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        List<Chunk> chunks = chunkRepository.listChunksBySeqId(kb.getTenantId(), new ArrayList<>(wanted));
        for (Chunk chunk : chunks) {
            if (chunk == null || !wanted.contains(chunk.getSeqId())) {
                continue;
            }
            if (chunk.getTenantId() == null || kb.getTenantId() == null
                    || chunk.getTenantId().longValue() != kb.getTenantId().longValue()
                    || !kb.getId().equals(chunk.getKnowledgeBaseId())
                    || !"faq".equals(chunk.getChunkType())) {
                throw new BizException(AppError.forbidden("FAQ 条目不属于当前知识库"));
            }
            result.put(chunk.getSeqId(), chunk);
        }
        if (result.size() != wanted.size()) {
            throw new BizException(AppError.notFound("FAQ 条目不存在"));
        }
        return result;
    }

    /** 对照 faqFieldPlan + planFAQFields（knowledge_faq_batch.go L59-122）。 */
    private static final class FaqFieldPlan {
        final Map<Long, Chunk> chunks;
        final Map<String, Chunk> chunksById = new LinkedHashMap<>();
        final Map<Long, KnowledgeTag> tags = new LinkedHashMap<>();
        final List<String> excludeIds = new ArrayList<>();

        FaqFieldPlan(Map<Long, Chunk> chunks) {
            this.chunks = chunks;
        }
    }

    private FaqFieldPlan planFAQFields(KnowledgeBase kb, FaqDtos.FaqEntryFieldsBatchUpdate req) {
        List<Long> ids = new ArrayList<>();
        if (req.byId() != null) {
            ids.addAll(sortedIds(req.byId().keySet()));
        }
        if (req.excludeIds() != null) {
            ids.addAll(req.excludeIds());
        }
        Map<Long, Chunk> chunks = loadFAQWriteChunks(kb, ids);
        FaqFieldPlan plan = new FaqFieldPlan(chunks);
        for (Chunk chunk : chunks.values()) {
            plan.chunksById.put(chunk.getId(), chunk);
        }
        if (req.excludeIds() != null) {
            for (Long id : req.excludeIds()) {
                Chunk c = chunks.get(id);
                plan.excludeIds.add(c == null ? null : c.getId());
            }
        }
        Set<Long> tagIds = new LinkedHashSet<>();
        if (req.byTag() != null) {
            for (Long id : req.byTag().keySet()) {
                if (id == null || id <= 0) {
                    throw new BizException(AppError.badRequest("标签 ID 必须为正整数"));
                }
                tagIds.add(id);
            }
        }
        if (req.byTag() != null) {
            for (FaqDtos.FaqEntryFieldsUpdate update : req.byTag().values()) {
                if (update.tagId() != null && update.tagId() > 0) {
                    tagIds.add(update.tagId());
                }
            }
        }
        if (req.byId() != null) {
            for (FaqDtos.FaqEntryFieldsUpdate update : req.byId().values()) {
                if (update.tagId() != null && update.tagId() > 0) {
                    tagIds.add(update.tagId());
                }
            }
        }
        if (!tagIds.isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndSeqIds(kb.getTenantId(), new ArrayList<>(tagIds));
            for (KnowledgeTag tag : tags) {
                if (tag == null || !tagIds.contains(tag.getSeqId())) {
                    continue;
                }
                validateFAQTagScope(tag, kb.getTenantId(), kb.getId());
                plan.tags.put(tag.getSeqId(), tag);
            }
            for (Long id : sortedIds(tagIds)) {
                if (!plan.tags.containsKey(id)) {
                    throw new BizException(AppError.notFound("标签 " + id + " 不存在"));
                }
            }
        }
        return plan;
    }

    /** 对照 validateFAQImportTags（knowledge_faq_batch.go L134-149）。 */
    private void validateFAQImportTags(KnowledgeBase kb, List<FaqDtos.FaqEntryPayload> entries) {
        Map<Long, FaqDtos.FaqEntryFieldsUpdate> byTag = new LinkedHashMap<>();
        for (FaqDtos.FaqEntryPayload entry : entries) {
            if (entry.tagId() != 0) {
                byTag.putIfAbsent(entry.tagId(), new FaqDtos.FaqEntryFieldsUpdate(null, null, null));
            }
        }
        planFAQFields(kb, new FaqDtos.FaqEntryFieldsBatchUpdate(null, byTag, null));
    }

    private static List<Long> sortedIds(Set<Long> values) {
        List<Long> ids = new ArrayList<>(values);
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    // ══════════════════ 私有：容器 / chunk 写 / embedding 门槛 ══════════

    /** 对照 findFAQKnowledge（knowledge_faq.go L1519-1534）。 */
    private Knowledge findFAQKnowledge(long tenantId, String kbId) {
        List<Knowledge> knowledges = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt));
        for (Knowledge knowledge : knowledges) {
            if ("faq".equals(knowledge.getType())) {
                return knowledge;
            }
        }
        return null;
    }

    /** 对照 ensureFAQKnowledge（knowledge_faq.go L1536-1566）。 */
    private Knowledge ensureFAQKnowledge(long tenantId, KnowledgeBase kb) {
        Knowledge existing = findFAQKnowledge(tenantId, kb.getId());
        if (existing != null) {
            return existing;
        }
        Knowledge knowledge = new Knowledge();
        knowledge.setId(UUID.randomUUID().toString());
        knowledge.setTenantId(tenantId);
        knowledge.setKnowledgeBaseId(kb.getId());
        knowledge.setType("faq");
        knowledge.setChannel("web");
        String name = FaqChunkMetadata.trimSpace(kb.getName());
        knowledge.setTitle(name.isEmpty() ? "FAQ" : name);
        knowledge.setDescription("FAQ 条目容器");
        knowledge.setSource("faq");
        knowledge.setParseStatus("completed");
        knowledge.setEnableStatus("enabled");
        knowledge.setEmbeddingModelId(kb.getEmbeddingModelId());
        OffsetDateTime now = OffsetDateTime.now();
        knowledge.setCreatedAt(now);
        knowledge.setUpdatedAt(now);
        knowledgeMapper.insert(knowledge);
        return knowledge;
    }

    /**
     * GetEmbeddingModel 门槛：ID 空 → Go 的 errors.New("model ID cannot be empty")；
     * 行缺失 → gorm 的 "record not found"。两支都包成
     * {@code failed to get embedding model: %w} 后以 plain 500 冒泡。
     */
    private Model requireEmbeddingModel(KnowledgeBase kb) {
        String modelId = kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId();
        if (modelId.isEmpty()) {
            throw new IllegalStateException("failed to get embedding model: model ID cannot be empty");
        }
        Model model = findModelRow(tenantId(), modelId);
        if (model == null) {
            throw new IllegalStateException("failed to get embedding model: record not found");
        }
        return model;
    }

    /** 模型行存在性（对照 GetModelByID：ID 空 / 行缺失两支；行存在 → 运行时降级见类注释）。 */
    private Model findModelRow(long tenantId, String modelId) {
        return modelMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Model>()
                .eq(Model::getId, modelId)
                .eq(Model::getTenantId, tenantId)
                .last("LIMIT 1"));
    }

    /**
     * 对照 indexFAQChunks（knowledge_faq_import.go L2019-2130）——2026-09-22 走查批接线：
     * 组装索引行（{@link FaqIndexRows}）→ adjustStorage 时估算大小 + 配额检查（超限 →
     * "Storage quota exceeded"）→ 删旧行（Go 的 needDelete=false 靠 EFPutDocument 覆盖，
     * Java 的 DO NOTHING 语义下显式先删（净效果等价：该 chunk 的全部新行重插））→
     * 分批 embedding → 写库 → adjustStorage 时配额累加 → UpdateKnowledge(processed_at)。
     */
    private void indexFAQChunks(KnowledgeBase kb, Knowledge knowledge, List<Chunk> chunks,
                                Model embeddingModel, boolean adjustStorage) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long tid = tenantId();
        List<VectorStoreService.IndexRow> rows = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        for (Chunk chunk : chunks) {
            rows.addAll(FaqIndexRows.build(kb, chunk));
            chunkIds.add(chunk.getId());
        }
        int dimensions = embeddingDimensions(embeddingModel);
        long size = 0;
        if (adjustStorage) {
            size = VectorStoreService.estimateStorageSize(rows, dimensions);
            com.ragagent.auth.domain.Tenant tenantInfo = tenantStorage.getTenant(tid);
            long quota = tenantInfo == null || tenantInfo.getStorageQuota() == null
                    ? 0 : tenantInfo.getStorageQuota();
            long used = tenantInfo == null || tenantInfo.getStorageUsed() == null
                    ? 0 : tenantInfo.getStorageUsed();
            if (quota > 0 && used + size > quota) {
                throw new IllegalStateException("Storage quota exceeded");
            }
        }
        // 2026-09-25 写链改道：绑定 store 的 KB 走引擎口（DeleteByChunkIDList +
        // BatchIndex——嵌入与分批/退避由 KV 引擎服务承担，照 Go 的 FAQ 索引路径）
        com.ragagent.retrieval.engine.CompositeRetrieveEngine boundEngine =
                vectorWrites.boundEngine(kb);
        if (boundEngine != null) {
            try {
                com.ragagent.embedding.Embedder emb =
                        modelRuntimeFactory.getEmbeddingModel(embeddingModel.getId());
                boundEngine.deleteByChunkIdList(chunkIds, emb.getDimensions(), kb.getType());
                boundEngine.batchIndex(emb, faqIndexInfos(kb, rows));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
            }
            if (adjustStorage && size > 0) {
                tenantStorage.adjustStorageUsed(tid, size);
                knowledge.setStorageSize(knowledge.getStorageSize() + size);
            }
            OffsetDateTime nowIndexed = OffsetDateTime.now();
            knowledge.setUpdatedAt(nowIndexed);
            knowledge.setProcessedAt(nowIndexed);
            knowledgeMapper.updateById(knowledge);
            return;
        }
        vectorStore.deleteByChunkId(chunkIds);
        EmbedderClient.EmbedConfig cfg = EmbedderClient.configFrom(embeddingModel);
        int batchSize = ChunkVectorIndexer.embedBatchSize();
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<VectorStoreService.IndexRow> batchRows = rows.subList(from, to);
            List<String> texts = new ArrayList<>(batchRows.size());
            for (VectorStoreService.IndexRow row : batchRows) {
                texts.add(row.content());
            }
            List<float[]> vectors;
            try {
                vectors = embedder.embedBatch(cfg, texts);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
            vectorStore.saveIndexRows(batchRows, vectors);
        }
        if (adjustStorage && size > 0) {
            tenantStorage.adjustStorageUsed(tid, size);
            knowledge.setStorageSize(knowledge.getStorageSize() + size);
        }
        OffsetDateTime now = OffsetDateTime.now();
        knowledge.setUpdatedAt(now);
        knowledge.setProcessedAt(now);
        knowledgeMapper.updateById(knowledge);
    }

    /**
     * 对照 deleteFAQChunkVectors（knowledge_faq_import.go L2132-2179）：GetEmbeddingModel
     * 门槛（失败原样抛）→ 估算大小 → DeleteByChunkIDList → 配额回退（storage_used 负数
     * 钳 0 在 TenantStorageService 内；knowledge.storage_size 钳 0）→ UpdateKnowledge。
     */
    private void deleteFAQChunkVectors(KnowledgeBase kb, Knowledge knowledge, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Model embeddingModel = requireEmbeddingModel(kb);
        long tid = tenantId();
        List<VectorStoreService.IndexRow> rows = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        for (Chunk chunk : chunks) {
            rows.addAll(FaqIndexRows.build(kb, chunk));
            chunkIds.add(chunk.getId());
        }
        long size = VectorStoreService.estimateStorageSize(rows, embeddingDimensions(embeddingModel));
        com.ragagent.retrieval.engine.CompositeRetrieveEngine boundEngine =
                vectorWrites.boundEngine(kb);
        if (boundEngine != null) {
            try {
                com.ragagent.embedding.Embedder emb =
                        modelRuntimeFactory.getEmbeddingModel(embeddingModel.getId());
                boundEngine.deleteByChunkIdList(chunkIds, emb.getDimensions(), kb.getType());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
            }
        } else {
            vectorStore.deleteByChunkId(chunkIds);
        }
        if (size > 0) {
            tenantStorage.adjustStorageUsed(tid, -size);
            knowledge.setStorageSize(Math.max(0, knowledge.getStorageSize() - size));
        }
        knowledge.setUpdatedAt(OffsetDateTime.now());
        knowledgeMapper.updateById(knowledge);
    }

    /** IndexRow → 引擎 IndexInfo（照 types.IndexInfo 字段集；SourceType=ChunkSourceType=0）。 */
    private static List<com.ragagent.retrieval.engine.EngineTypes.IndexInfo> faqIndexInfos(
            KnowledgeBase kb, List<VectorStoreService.IndexRow> rows) {
        List<com.ragagent.retrieval.engine.EngineTypes.IndexInfo> items =
                new ArrayList<>(rows.size());
        for (VectorStoreService.IndexRow row : rows) {
            com.ragagent.retrieval.engine.EngineTypes.IndexInfo item =
                    new com.ragagent.retrieval.engine.EngineTypes.IndexInfo();
            item.sourceId = row.sourceId();
            item.sourceType = com.ragagent.retrieval.engine.EngineTypes.SOURCE_TYPE_FILE;
            item.chunkId = row.chunkId();
            item.knowledgeId = row.knowledgeId();
            item.knowledgeBaseId = row.knowledgeBaseId();
            item.knowledgeType = kb.getType();
            item.tagId = row.tagId() == null ? "" : row.tagId();
            item.content = row.content();
            item.isEnabled = row.isEnabled();
            items.add(item);
        }
        return items;
    }

    /** 对照 Go embeddingModel.GetDimensions()：模型 embedding_parameters.dimension。 */
    private static int embeddingDimensions(Model model) {
        if (model == null || model.getParameters() == null
                || model.getParameters().getEmbeddingParameters() == null) {
            return 0;
        }
        return model.getParameters().getEmbeddingParameters().getDimension();
    }

    private void createChunks(List<Chunk> chunks) {
        // 对照 CreateChunks（chunk.go L69 Select("*").CreateInBatches）：
        // MP insert 对 null 字段省列（seq_id 省列 → DB 序列默认）；is_enabled/flags/status
        // 是原始类型恒写；时间戳由调用方显式赋值（GORM autoCreateTime 的复刻点）
        for (Chunk c : chunks) {
            chunkMapper.insert(c);
        }
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

    /** 对照 utils.ValidateTaskID（taskid.go）：≤128 且仅 [A-Za-z0-9_-]。 */
    private static boolean validateTaskId(String taskId) {
        if (taskId == null || taskId.isEmpty() || taskId.length() > 128) {
            return false;
        }
        for (int i = 0; i < taskId.length(); i++) {
            char c = taskId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private FaqImportResult parseImportResult(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        try {
            return FaqChunkMetadata.JSON.treeToValue(node, FaqImportResult.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    // ══════════════════ 导入任务（进程内） ═════════════════════════════

    /** 进程内导入任务的参数（对照 types.FAQImportPayload 的净字段）。 */
    record ImportJob(long tenantId, String taskId, String kbId, String knowledgeId,
                     String mode, boolean dryRun, long enqueuedAt, String instanceId,
                     List<FaqDtos.FaqEntryPayload> entries) {
    }

    /**
     * 导入/验证进度与 running 锁的进程内存储（对照 Go 的 redisClient==nil 兜底分支：
     * memFAQProgress / memFAQRunningImport，键形状 faq_import_progress:&lt;task&gt; 与
     * faq_import_running:&lt;kb&gt; 的单实例等价物；TTL 是 Redis 专属，内存版无）。
     */
    @org.springframework.stereotype.Component
    public static class FaqImportTaskStore {

        /** 对照 runningFAQImportInfo。 */
        public record RunningInfo(String taskId, long enqueuedAt, String instanceId) {
        }

        private final ConcurrentHashMap<String, FaqImportProgress> progress = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, RunningInfo> running = new ConcurrentHashMap<>();
        private final Set<String> createGuards = ConcurrentHashMap.newKeySet();

        public FaqImportProgress getProgress(String taskId) {
            return progress.get(taskId);
        }

        public void saveProgress(FaqImportProgress p) {
            progress.put(p.taskId(), p);
        }

        public String getRunningTaskId(String kbId) {
            RunningInfo info = running.get(kbId);
            return info == null ? "" : info.taskId();
        }

        public void setRunningInfo(String kbId, RunningInfo info) {
            running.put(kbId, info);
        }

        public void clearRunningInfoIfMatches(String kbId, String taskId, String instanceId, long enqueuedAt) {
            RunningInfo info = running.get(kbId);
            if (info != null && info.taskId().equals(taskId)
                    && (info.instanceId().isEmpty() || instanceId.isEmpty()
                    || info.instanceId().equals(instanceId))) {
                running.remove(kbId);
            }
        }

        public boolean acquireCreateGuard(String key) {
            return createGuards.add(key);
        }

        public void releaseCreateGuard(String key) {
            createGuards.remove(key);
        }
    }
}

