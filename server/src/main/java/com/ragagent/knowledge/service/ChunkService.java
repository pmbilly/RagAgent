package com.ragagent.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.AgentPromptPlaceholders;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.prompt.PromptInstructions;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.DocumentChunkMetadata;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkNotFoundException;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRevisionConflictException;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.wiki.service.WikiLanguageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * chunk service 层（对照 Go internal/application/service/chunk.go 的编辑链路
 * + chunk_write.go 的 writableChunk/validateDocumentChunkRelations +
 * knowledge_write.go 的 loadKnowledgeWrite 系 +
 * knowledge_summary_refresh.go 的 enqueueSummaryRefresh 早退分支 +
 * knowledge_process.go 的 RegenerateChunkQuestions）。
 *
 * <h2>错误形态对照（controller 尚未翻译，异常选择即 HTTP 契约）</h2>
 * <ul>
 *   <li><b>BizException（AppError 信封）</b>：writableChunk 全家
 *       （401 workspace context unavailable / 404 chunk not found / 404 knowledge not found /
 *       403 chunk does not belong to its knowledge base / 409 unfinished move）——
 *       Go 侧是 AppError，UpdateChunk/RevertChunk handler 原样透传（状态码保留）。</li>
 *   <li><b>非 BizException 的 RuntimeException（500 面）</b>：UpdateDocumentChunk 的
 *       {@code fmt.Errorf} 校验（非 text 块 / 空内容 / 超 200000 字节 / 加图）——Go 的
 *       handler 对非 AppError 一律 {@code NewInternalServerError(err.Error())}，故 Java 抛
 *       {@link IllegalStateException}，由 controller 层映射 500。</li>
 *   <li><b>{@link ChunkRevisionConflictException}（409 面）</b>：对照 Go 的
 *       {@code ErrChunkRevisionConflict}——handler 特判后回
 *       409 "Chunk was modified by another user; refresh and retry"（文案在 controller）。</li>
 *   <li><b>BizException.badRequest(Go 的 err.Error() 原文)</b>：UpsertGeneratedQuestion /
 *       DeleteGeneratedQuestion / RegenerateChunkQuestions —— Go handler 把这些方法的
 *       <b>所有</b>错误包成 400，err.Error() 原文即契约（含 AppError 的
 *       {@code "error code: N, error message: ..."} 前缀形态——这不是二次包装 bug，
 *       是 Go 的逐字行为）。</li>
 * </ul>
 *
 * <h2>已知差异 / 降级（逐条对应 Go）</h2>
 * <ol>
 *   <li><b>{@link #syncChunkIndex}（2026-09-22 走查批接线）</b>：执行体在
 *       {@link ChunkVectorIndexer#syncChunkIndex}（DeleteByChunkIDList → disabled 只删
 *       不插 → chunk 行 + 问题行 BatchIndex）；确定性分支（策略关 →
 *       {@code !NeedsEmbeddingModel()} return）不变。⚠️ 接线前「模型+引擎齐备恒 failed」
 *       的占位行为已消除，ChunkServiceTest 的相关断言随批更新。</li>
 *   <li><b>{@link #enqueueSummaryRefresh}（同批接线）</b>：委托
 *       {@link KnowledgeService#requestKnowledgeSummaryRefresh}（pending 落库 + 进程内
 *       虚拟线程刷新；无 summary model → markFailed + 400）。备案：kb 行缺失时 Go 的
 *       markFailed 会落 failed，Java 抛 notFound 不落列（该路径不可达——chunk 的 KB 必然存在）。</li>
 *   <li><b>{@link #regenerateChunkQuestions} 的 LLM 生成步（同批接线）</b>：prompt 渲染
 *       （question_count/content/context/doc_name/language）+ 业务指引包裹 + chat
 *       （temp 0.7 / max 512 / thinking=false）+ 行解析，与 Go 逐段对照；revision 冲突
 *       409、向量原子替换走 {@link ChunkVectorIndexer#updateChunkVector}。</li>
 *   <li><b>{@link #deleteGeneratedQuestion} 的向量删除（2026-09-25 接线批第 3 步全量接线）</b>：
 *       Go L832-855 三段照序——引擎创建（{@code CreateRetrieveEngineForKB}：无绑定回落租户
 *       有效引擎，绑定 store 走归属校验+注册表解析；失败 →
 *       "failed to create retrieve engine: %w" 包 400，<b>分支已可达</b>）→ 嵌入模型
 *       （{@code ModelRuntimeFactory.getEmbeddingModel}，文案逐字对照）→
 *       {@code engine.deleteBySourceIdList}（引擎口扇出；失败只警告继续）。未配
 *       {@code RETRIEVE_DRIVER} 时引擎列表为空、复合引擎为空壳，删除 no-op——与 Go 同形。</li>
 *   <li><b>requireKBWrite 未翻译</b>：Go 的 loadKnowledgeWrite 末尾还有
 *       {@code requireKBWrite(kb)}（KB 授予/能力判定，消耗中间件写入的 grant）——Java 的
 *       授权在路由层（ChunkAccessGuard/RbacInterceptor），service 层无 grant 语境，略。</li>
 *   <li><b>metadata 解析错误文案</b>：Jackson 的消息替代 Go encoding/json 的
 *       （约定 §9 阶段 1 差异 2 同族）。</li>
 * </ol>
 */
@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    /** metadata JSON 的读写 mapper（DocumentChunkMetadata 带 ignoreUnknown，容忍历史行）。 */
    private static final ObjectMapper META_MAPPER = new ObjectMapper();

    /** Go types/chunk.go 的 ChunkType 常量。 */
    private static final String CHUNK_TYPE_TEXT = "text";
    private static final String CHUNK_TYPE_IMAGE_OCR = "image_ocr";
    private static final String CHUNK_TYPE_IMAGE_CAPTION = "image_caption";

    /** Go chunk.go L68：编辑正文上限（UTF-8 字节）。 */
    private static final int MAX_EDITABLE_CHUNK_LENGTH = 200000;

    /** Go types/knowledge.go 的 SummaryStatusNone。 */
    private static final String SUMMARY_STATUS_NONE = "none";

    /** Go types/knowledge.go L339：服务端持有的转移恢复状态键。 */
    private static final String KNOWLEDGE_TRANSFER_METADATA_KEY = "_knowledge_transfer";

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final KnowledgeService knowledgeService;
    private final ConversationProperties conversationProps;
    private final com.ragagent.retrieval.engine.RetrieveEngineRegistry retrieveEngineRegistry;
    private final com.ragagent.retrieval.engine.TenantStoreOwnership storeOwnership;
    private final com.ragagent.auth.service.TenantService tenantService;

    public ChunkService(ChunkRepository chunkRepository, KnowledgeMapper knowledgeMapper,
                        KnowledgeBaseMapper kbMapper,
                        ChunkVectorIndexer chunkVectorIndexer,
                        ModelRuntimeFactory modelRuntimeFactory,
                        KnowledgeService knowledgeService,
                        ConversationProperties conversationProps,
                        com.ragagent.retrieval.engine.RetrieveEngineRegistry retrieveEngineRegistry,
                        com.ragagent.retrieval.engine.TenantStoreOwnership storeOwnership,
                        com.ragagent.auth.service.TenantService tenantService) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.knowledgeService = knowledgeService;
        this.conversationProps = conversationProps;
        this.retrieveEngineRegistry = retrieveEngineRegistry;
        this.storeOwnership = storeOwnership;
        this.tenantService = tenantService;
    }

    /** loadKnowledgeWrite 的返回（Go 的 (knowledge, kb, error) 三元）。 */
    private record KnowledgeWrite(Knowledge knowledge, KnowledgeBase kb) {
    }

    // ── 更新（乐观、版本化编辑）────────────────────────────────────────────

    /**
     * 对照 Go {@code UpdateDocumentChunk}（chunk.go L405-529）。生成问题的索引跨内容编辑
     * 保留；当前行在重索引失败时仍然落库并暴露 index_status=failed。逐段对照：
     * <ol>
     *   <li>writableChunk（AppError 直通）；非 text 块 → 500 面；关系校验；乐观锁预检；</li>
     *   <li>内容 trim（Go strings.TrimSpace 全集）/空/字节长校验（500 面）；</li>
     *   <li>无变化路径：index_status=failed 时走"重试索引"（rebuildParent → processing →
     *       syncChunkIndex → ready），否则原样返回；</li>
     *   <li>有变化路径：先 {@code validateEditedChunkImages}（source_content 惰性回填之后），
     *       再写 revision 快照（快照记<b>上一个</b> editor 与旧内容）+ 乐观锁 UPDATE；</li>
     *   <li>bodyChanged 时重建父内容；bodyChanged 或 enabled 变化时同步图片子块、
     *       尝试 summary 刷新入队；最后 syncChunkIndex 定 ready/failed。</li>
     * </ol>
     * 每个失败分支的 index_status/返回语义逐行对照 Go（失败标 failed 后<b>返回 chunk 不抛</b>）。
     *
     * @param content          null = 不改内容（Go 的 *string）
     * @param isEnabled        null = 不改启用态（Go 的 *bool）
     * @param expectedRevision null = 不做乐观锁预检（Go 的 *int）
     */
    public Chunk updateDocumentChunk(String chunkId, String content, Boolean isEnabled,
                                     Integer expectedRevision) {
        Chunk chunk = writableChunk(chunkId);
        if (!CHUNK_TYPE_TEXT.equals(chunk.getChunkType())) {
            // Go: fmt.Errorf("only text chunks can be edited") —— 非 AppError → handler 500
            throw new IllegalStateException("only text chunks can be edited");
        }
        validateDocumentChunkRelations(chunk.getTenantId(), chunk);
        if (expectedRevision != null && expectedRevision != chunk.getContentRevision()) {
            // Go: ErrChunkRevisionConflict → handler 409（文案在 controller）
            throw new ChunkRevisionConflictException();
        }

        String newContent = chunk.getContent();
        if (content != null) {
            newContent = ChunkSearchUtil.goTrimSpace(content);
            if (newContent.isEmpty()) {
                throw new IllegalStateException("chunk content cannot be empty");
            }
            if (newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EDITABLE_CHUNK_LENGTH) {
                // Go: len(newContent) 是 UTF-8 字节数
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
                    syncChunkIndex(chunk);
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

        // Go: actorID, _ := types.UserIDFromContext(ctx)——缺失时是空串，直接用
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
        // Go 的 LastEditorID 是非指针 string（无 null）——H2 列可空，读回 null 归一成零值 ""
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
            // Go: knowledge, getErr := GetKnowledgeByID; if getErr == nil { enqueue }——读失败静默跳过
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
            syncChunkIndex(chunk);
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
     * 对照 Go {@code RevertDocumentChunk}（chunk.go L590-600）：取修订快照后按其内容/启用态
     * 走一次 {@link #updateDocumentChunk}。快照不存在 → Go 把
     * {@code gorm.ErrRecordNotFound} 原文（"record not found"）交 handler 包 400
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

    /** 对照 Go {@code ListChunkRevisions}（chunk.go L586-588）：revision DESC。 */
    public List<ChunkRevision> listChunkRevisions(String chunkId) {
        return chunkRepository.listChunkRevisions(mustTenantId(), chunkId);
    }

    // ── 生成问题 ───────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code UpsertGeneratedQuestion}（chunk.go L721-774）。questionID 为空 =
     * 新建（服务端生成 UUID），否则就地更新既有问题并把 content_revision 钉到当前版本。
     * <b>所有</b>失败 Go handler 都包成 400（err.Error() 原文）——包括 writableChunk 的
     * AppError（"error code: N, error message: ..." 前缀是 Go 的逐字行为）。
     * metadata 序列化失败文案是 Jackson 的（已知差异族）。
     */
    public GeneratedQuestion upsertGeneratedQuestion(String chunkId, String questionId, String question) {
        String trimmed = ChunkSearchUtil.goTrimSpace(question);
        if (trimmed.isEmpty()) {
            throw BizException.badRequest("question cannot be empty");
        }
        Chunk chunk;
        try {
            chunk = writableChunk(chunkId);
        } catch (BizException e) {
            // Go：writableChunk 的 AppError 原样上抛 → handler NewBadRequestError(err.Error())
            throw BizException.badRequest(e.getMessage());
        }
        DocumentChunkMetadata meta;
        try {
            meta = parseDocumentMetadata(chunk.getMetadata());
        } catch (JsonProcessingException e) {
            throw BizException.badRequest(e.getMessage());
        }
        if (meta == null) {
            meta = new DocumentChunkMetadata();
        }
        int currentRevision = chunk.getContentRevision();
        List<GeneratedQuestion> questions = meta.getGeneratedQuestions();
        if (questionId == null || questionId.isEmpty()) {
            String newId = UUID.randomUUID().toString();
            if (questions == null) {
                questions = new ArrayList<>();
                meta.setGeneratedQuestions(questions);
            }
            questions.add(new GeneratedQuestion(newId, trimmed, currentRevision));
            questionId = newId;
        } else {
            boolean found = false;
            if (questions != null) {
                for (GeneratedQuestion gq : questions) {
                    if (questionId.equals(gq.getId())) {
                        gq.setQuestion(trimmed);
                        gq.setContentRevision(currentRevision);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                throw BizException.badRequest("question not found");
            }
        }
        try {
            chunk.setMetadata(writeDocumentMetadata(meta));
        } catch (JsonProcessingException e) {
            throw BizException.badRequest(e.getMessage());
        }
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            throw BizException.badRequest(e.getMessage());
        }
        try {
            syncChunkIndex(chunk);
        } catch (RuntimeException e) {
            // Go：syncChunkIndex 的 err 同样被 handler 包 400（此时元数据已落库）
            throw BizException.badRequest(e.getMessage());
        }
        for (GeneratedQuestion gq : meta.getGeneratedQuestions()) {
            if (questionId.equals(gq.getId())) {
                return gq;
            }
        }
        throw BizException.badRequest("question not found");
    }

    /**
     * 对照 Go {@code DeleteGeneratedQuestion}（chunk.go L778-878）。<b>所有</b>失败被
     * handler 包 400（err.Error() 原文，含 {@code %w} 包装链）。向量索引删除半边
     * （引擎创建 + DeleteBySourceIDList）在本部署 WARN + no-op——Go 对删除向量行失败
     * 同样只警告继续，元数据更新是真正的契约面；但嵌入模型行校验保留（Go 在引擎之后的
     * 可观测失败分支，文案逐字对照）。
     */
    public void deleteGeneratedQuestion(String chunkId, String questionId) {
        log.info("Deleting generated question, chunk ID: {}, question ID: {}", chunkId, questionId);
        long tenantId = mustTenantId();

        // 1. chunk（Go: fmt.Errorf("failed to get chunk: %w", err)——AppError 的
        //    "error code: N, ..." 前缀一并进入 400 文案，照抄）
        Chunk chunk;
        try {
            chunk = writableChunk(chunkId);
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to get chunk: " + e.getMessage());
        }

        // 2. metadata
        DocumentChunkMetadata meta;
        try {
            meta = parseDocumentMetadata(chunk.getMetadata());
        } catch (JsonProcessingException e) {
            throw BizException.badRequest("failed to parse chunk metadata: " + e.getMessage());
        }
        if (meta == null || meta.getGeneratedQuestions() == null || meta.getGeneratedQuestions().isEmpty()) {
            throw BizException.badRequest("no generated questions found for chunk " + chunkId);
        }

        // 3. 找问题
        int questionIndex = -1;
        List<GeneratedQuestion> questions = meta.getGeneratedQuestions();
        for (int i = 0; i < questions.size(); i++) {
            if (questionId != null && questionId.equals(questions.get(i).getId())) {
                questionIndex = i;
                break;
            }
        }
        if (questionIndex == -1) {
            throw BizException.badRequest("question with ID " + questionId + " not found in chunk " + chunkId);
        }

        // 4. knowledge base（Go: GetKnowledgeBaseByID 失败 → "failed to get knowledge base: %w"；
        //    ErrKnowledgeBaseNotFound 的原文是 "knowledge base not found"）
        KnowledgeBase kb = findKbRow(chunk.getKnowledgeBaseId());
        if (kb == null) {
            throw BizException.badRequest("failed to get knowledge base: knowledge base not found");
        }

        // 5. 删除该问题的向量索引。source_id 形如 {chunk_id}-q{hash24}（短 ID 直拼）。
        String sourceId = ChunkSearchUtil.generatedQuestionSourceId(chunkId, questionId);
        // 5a. 引擎创建（Go L832：CreateRetrieveEngineForKB——2026-09-25 接线批第 3 步）：
        //     无绑定回落租户有效引擎（RETRIEVE_DRIVER 驱动），绑定 store 走归属校验 +
        //     注册表解析。失败 → "failed to create retrieve engine: %w"（handler 包 400）。
        com.ragagent.retrieval.engine.CompositeRetrieveEngine engine;
        try {
            engine = com.ragagent.retrieval.engine.RetrieveEngineFactories.createForKb(
                    retrieveEngineRegistry, storeOwnership, tenantId, kb.getVectorStoreId(),
                    tenantEngines(tenantId));
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to create retrieve engine: " + e.getMessage());
        }
        // 5b. 嵌入模型（Go L841：GetEmbeddingModel，顺序在引擎之后；文案逐字对照——
        //     "model ID cannot be empty" / "model not found"，经
        //     ModelRuntimeFactory.getEmbeddingModel 的 RuntimeException 原文冒出）
        com.ragagent.embedding.Embedder embeddingModel;
        try {
            embeddingModel = modelRuntimeFactory.getEmbeddingModel(
                    kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to get embedding model: " + e.getMessage());
        }
        // 5c. 向量删除（Go L850：DeleteBySourceIDList，走引擎口扇出）——Go 删除失败
        //     （问题未被索引过）只警告继续，不阻断元数据更新。
        try {
            engine.deleteBySourceIdList(List.of(sourceId), embeddingModel.getDimensions(),
                    kb.getType());
        } catch (Exception e) {
            log.warn("Failed to delete vector index for question (may not exist): {}", e.getMessage());
        }

        // 6. 从 metadata 移除
        List<GeneratedQuestion> remaining = new ArrayList<>(questions.size() - 1);
        for (int i = 0; i < questions.size(); i++) {
            if (i != questionIndex) {
                remaining.add(questions.get(i));
            }
        }

        // 7. 更新 chunk metadata
        meta.setGeneratedQuestions(remaining);
        try {
            chunk.setMetadata(writeDocumentMetadata(meta));
        } catch (JsonProcessingException e) {
            throw BizException.badRequest("failed to set chunk metadata: " + e.getMessage());
        }
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to update chunk: " + e.getMessage());
        }
        log.info("Successfully deleted generated question {} from chunk {}", questionId, chunkId);
    }

    /**
     * 对照 Go {@code RegenerateChunkQuestions}（knowledge_process.go L2206-2290；Go 挂在
     * knowledgeService 上，controller 经 kgService 调用——Java 落在 ChunkService，controller
     * 翻译时按需转发）。确定性分支逐字对照；LLM 生成步降级见类注释第 3 条。
     */
    public List<GeneratedQuestion> regenerateChunkQuestions(String chunkId) {
        long tenantId = mustTenantId();
        Chunk chunk;
        try {
            chunk = chunkRepository.getChunkById(tenantId, chunkId);
        } catch (ChunkNotFoundException e) {
            // Go：ErrChunkNotFound 哨兵 → handler 400 err.Error() = "chunk not found"
            throw BizException.badRequest("chunk not found");
        }
        if (!CHUNK_TYPE_TEXT.equals(chunk.getChunkType())) {
            throw BizException.badRequest("questions can only be generated for text chunks");
        }
        int generationRevision = chunk.getContentRevision();
        KnowledgeWrite write;
        try {
            write = loadKnowledgeWrite(chunk.getKnowledgeId());
        } catch (BizException e) {
            // Go：loadKnowledgeWrite 的 AppError → handler 400 err.Error()
            throw BizException.badRequest(e.getMessage());
        }
        Knowledge knowledge = write.knowledge();
        KnowledgeBase kb = write.kb();
        if (!knowledge.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())
                || !Objects.equals(chunk.getTenantId(), knowledge.getTenantId())) {
            // Go：werrors.NewForbiddenError → AppError → 400 err.Error()（code 1002 前缀）
            throw BizException.badRequest(
                    BizException.forbidden("chunk does not belong to its knowledge document").getMessage());
        }
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            throw BizException.badRequest("summary model is required for question generation");
        }
        // 2026-09-22 走查批：LLM 生成步接线（Go GetChatModel 的错误在 RegenerateChunkQuestions
        // 的 handler 里统一包 400 err.Error()——"model not found" 等原文）
        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            throw BizException.badRequest(e.getMessage() == null ? "model not found" : e.getMessage());
        }
        String prevContent = resolveNeighborContent(tenantId, chunk, chunk.getPreChunkId());
        String nextContent = resolveNeighborContent(tenantId, chunk, chunk.getNextChunkId());
        // 对照 ResolveProcessConfig(kb, overrides).QuestionGenerationConfig：count 缺省 3、上限 10
        JsonNode qg = kb.getQuestionGenerationConfig();
        int questionCount = qg == null ? 0 : qg.path("question_count").asInt(0);
        if (questionCount <= 0) {
            questionCount = 3;
        }
        if (questionCount > 10) {
            questionCount = 10;
        }
        String customInstructions = qg == null ? "" : qg.path("custom_instructions").asText("");
        List<String> questions = generateQuestionsWithContext(
                chatModel, chunk.getContent(), prevContent, nextContent,
                knowledge.getTitle(), questionCount, customInstructions);
        // 对照 Go：重读 latestChunk，期间被编辑（revision 变化）则 409
        Chunk latest;
        try {
            latest = chunkRepository.getChunkById(tenantId, chunkId);
        } catch (ChunkNotFoundException e) {
            throw BizException.badRequest("chunk not found");
        }
        if (latest.getContentRevision() != generationRevision) {
            throw new ChunkRevisionConflictException();
        }
        chunk = latest;
        List<GeneratedQuestion> generated = buildGeneratedQuestions(questions, chunk);
        try {
            persistGeneratedQuestions(kb, chunk, generated);
        } catch (RuntimeException e) {
            // Go：三步（metadata/updateChunk/updateChunkVector）的 err 都被 handler 包 400 原文
            throw BizException.badRequest(e.getMessage());
        }
        log.info("Successfully regenerated {} questions for chunk {}", generated.size(), chunkId);
        return generated;
    }

    /**
     * worker 语义的"生成 + 落库 + 建索引"（对照 Go {@code processQuestionGenerationForChunks}
     * 的逐块段，knowledge_process.go:2043-2110）——导入后处理扇出的批任务走这里。
     *
     * <p>与 {@link #regenerateChunkQuestions} 共用同一条落库路径，但**没有 handler 语义**：
     * 期间分块被编辑（revision 变化）时<b>跳过</b>而不是抛 409；单块 LLM 失败只告警并跳过、
     * 不中断整批（对照 Go 的 {@code llmCallFailed++ / continue}）。</p>
     *
     * <p>模型解析失败与落库失败<b>上抛</b>——让队列按 asynq 语义重试
     * （对照 Go 的 {@code get_chat_model_failed} → 返回错误）。</p>
     *
     * @return 写入的问题数；0 = 跳过（内容空 / 生成失败 / revision 变化 / 分块已删）
     */
    int generateAndStoreQuestionsForWorker(KnowledgeBase kb, Knowledge knowledge, Chunk chunk,
            String prevContent, String nextContent, int questionCount, String customInstructions) {
        int generationRevision = chunk.getContentRevision();
        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? "model not found" : e.getMessage(), e);
        }
        List<String> questions;
        try {
            questions = generateQuestionsWithContext(chatModel, chunk.getContent(), prevContent, nextContent,
                    knowledge.getTitle(), questionCount, customInstructions);
        } catch (RuntimeException e) {
            log.warn("Failed to generate questions for chunk {}: {}", chunk.getId(), e.toString());
            return 0;
        }
        if (questions.isEmpty()) {
            return 0;
        }
        Chunk latest;
        try {
            latest = chunkRepository.getChunkById(kb.getTenantId(), chunk.getId());
        } catch (RuntimeException e) {
            return 0;
        }
        if (latest.getContentRevision() != generationRevision) {
            // 对照 Go：revision 变化 → 跳过（陈旧问题不落库），不报错
            log.info("Skipping stale generated questions for chunk {} (revision changed)", chunk.getId());
            return 0;
        }
        List<GeneratedQuestion> generated = buildGeneratedQuestions(questions, latest);
        try {
            persistGeneratedQuestions(kb, latest, generated);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? "failed to store generated questions" : e.getMessage(), e);
        }
        return generated.size();
    }

    /** 对照 SetDocumentMetadata 的构建段：GeneratedQuestion 列表（id 新 UUID、revision 取当前）。 */
    private static List<GeneratedQuestion> buildGeneratedQuestions(List<String> questions, Chunk chunk) {
        List<GeneratedQuestion> generated = new ArrayList<>(questions.size());
        Integer questionRevision = chunk.getContentRevision();
        for (String question : questions) {
            generated.add(new GeneratedQuestion(UUID.randomUUID().toString(), question, questionRevision));
        }
        return generated;
    }

    /**
     * 对照 {@code SetDocumentMetadata} + {@code updateChunk} + {@code updateChunkVector}：
     * 整体替换 metadata（仅 generated_questions 两键；空列表/0 由域类型注解的 omitempty 省略）
     * → 落库 → 重建该分块向量索引。
     */
    private void persistGeneratedQuestions(KnowledgeBase kb, Chunk chunk, List<GeneratedQuestion> generated) {
        DocumentChunkMetadata meta = new DocumentChunkMetadata();
        meta.setGeneratedQuestions(generated);
        meta.setGeneratedQuestionsRevision(chunk.getContentRevision());
        try {
            chunk.setMetadata(writeDocumentMetadata(meta));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to set chunk metadata: " + e.getMessage(), e);
        }
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to update chunk: " + e.getMessage(), e);
        }
        try {
            chunkVectorIndexer.updateChunkVector(kb.getId(), List.of(chunk));
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 对照 Go resolveNeighbor/sameChunkDocument（knowledge_process.go L2235-2244 +
     * chunk_write.go L33-36）：邻居块必须与当前块同租户/同 KB/同文档，否则按空处理；
     * 读取失败同样按空。用于问题生成的 surrounding_context。
     */
    private String resolveNeighborContent(long tenantId, Chunk chunk, String neighborId) {
        if (neighborId == null || neighborId.isEmpty()) {
            return "";
        }
        Chunk neighbor;
        try {
            neighbor = chunkRepository.getChunkById(tenantId, neighborId);
        } catch (RuntimeException e) {
            return "";
        }
        if (!Objects.equals(neighbor.getTenantId(), chunk.getTenantId())
                || !Objects.equals(neighbor.getKnowledgeBaseId(), chunk.getKnowledgeBaseId())
                || !Objects.equals(neighbor.getKnowledgeId(), chunk.getKnowledgeId())) {
            return "";
        }
        return neighbor.getContent() == null ? "" : neighbor.getContent();
    }

    /**
     * 对照 Go {@code generateQuestionsWithContext}（knowledge_process.go L2132-2204）：
     * 1) prompt 取自 config.Conversation.GenerateQuestionsPrompt（空 → err 原文）；
     * 2) context 段：preceding/following 非空才拼 surrounding_context；
     * 3) 渲染 {{question_count}}/{{content}}/{{context}}/{{doc_name}}/{{language}}；
     * 4) 业务指引包裹（AppendCustomPromptInstructions label=question_generation）；
     * 5) chat（temperature 0.7 / max_tokens 512 / thinking=false，单条 user 消息）；
     * 6) 行解析：逐行 trim → 裁前缀符号 → trim → 非空且 &gt;5 字节才收，达到 count 即止。
     */
    private List<String> generateQuestionsWithContext(LlmChatClient chatModel, String content,
                                                      String prevContent, String nextContent,
                                                      String docName, int questionCount,
                                                      String customInstructions) {
        if (content == null || content.isEmpty() || questionCount <= 0) {
            return List.of();
        }
        String prompt = ChunkRepository.goTrimSpace(conversationProps.getGenerateQuestionsPrompt());
        if (prompt.isEmpty()) {
            throw BizException.badRequest("generate questions prompt not configured");
        }
        StringBuilder contextSection = new StringBuilder();
        if ((prevContent != null && !prevContent.isEmpty())
                || (nextContent != null && !nextContent.isEmpty())) {
            contextSection.append("<surrounding_context>\n");
            if (prevContent != null && !prevContent.isEmpty()) {
                contextSection.append("<preceding_content>\n").append(prevContent)
                        .append("\n\n</preceding_content>\n\n");
            }
            if (nextContent != null && !nextContent.isEmpty()) {
                contextSection.append("<following_content>\n").append(nextContent)
                        .append("\n\n</following_content>\n\n");
            }
            contextSection.append("</surrounding_context>\n\n");
        }
        prompt = AgentPromptPlaceholders.renderPromptPlaceholders(prompt, Map.of(
                "question_count", String.valueOf(questionCount),
                "content", content,
                "context", contextSection.toString(),
                "doc_name", docName == null ? "" : docName,
                "language", WikiLanguageSupport.languageNameFromContext()));
        prompt = PromptInstructions.appendCustomPromptInstructions(
                prompt, customInstructions, "question_generation");
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.7);
        options.setMaxTokens(512);
        options.setThinking(Boolean.FALSE);
        ChatResponse response;
        try {
            response = chatModel.chat(List.of(ChatMessage.user(prompt)), options);
        } catch (BizException e) {
            throw BizException.badRequest(e.getMessage());
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to generate questions: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        String text = response == null || response.getContent() == null ? "" : response.getContent();
        return parseGeneratedQuestions(text, questionCount);
    }

    /**
     * 对照 Go {@code generateQuestionsWithContext} 的行解析段（L2186-2201）：
     * 逐行 trim → 裁前缀符号（{@code "0123456789.-*) "}）→ trim → 非空且 &gt;5 字节
     * （Go 的 len 是 UTF-8 字节数）才收，达到 count 即止。抽为纯逻辑便于单测。
     */
    static List<String> parseGeneratedQuestions(String content, int questionCount) {
        List<String> questions = new ArrayList<>(Math.max(questionCount, 0));
        if (content == null || questionCount <= 0) {
            return questions;
        }
        for (String raw : content.split("\n", -1)) {
            String line = ChunkRepository.goTrimSpace(raw);
            if (line.isEmpty()) {
                continue;
            }
            line = trimLeftCharSet(line, "0123456789.-*) ");
            line = ChunkRepository.goTrimSpace(line);
            // Go 的 len(line) > 5 是 UTF-8 字节数
            if (!line.isEmpty() && line.getBytes(StandardCharsets.UTF_8).length > 5) {
                questions.add(line);
                if (questions.size() >= questionCount) {
                    break;
                }
            }
        }
        return questions;
    }

    /** 对照 Go {@code strings.TrimLeft(s, cutset)}：裁掉开头属于字符集的字符。 */
    private static String trimLeftCharSet(String s, String cutset) {
        int i = 0;
        while (i < s.length() && cutset.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    // ── 删除 ───────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code DeleteChunk}（chunk.go L273-287）：writableChunk 失败原样上抛
     * （AppError/500 面各自的形态）；成功路径仓储软删无额外错误（不存在时静默 no-op）。
     */
    public void deleteChunk(String id) {
        writableChunk(id);
        long tenantId = mustTenantId();
        chunkRepository.deleteChunk(tenantId, id);
        log.info("Chunk deleted successfully");
    }

    /**
     * 对照 Go {@code DeleteChunksByKnowledgeID}（chunk.go L325-354）：删除前经
     * loadKnowledgeWriteBatch 校验（单 ID 的批校验塌缩成一行——blank → 400
     * "resource ID cannot be empty"、缺失 → 404 "knowledge not found"、moving → 409、
     * KB 绑定校验）。requireKBWrite 略（见类注释第 5 条）。
     */
    public void deleteChunksByKnowledgeId(String knowledgeId) {
        // Go: writeResourceIDs([]string{knowledgeID})——blank 即 400
        if (knowledgeId == null || ChunkSearchUtil.goTrimSpace(knowledgeId).isEmpty()) {
            throw BizException.badRequest("resource ID cannot be empty");
        }
        long tenantId = writeExecutionTenant();
        Knowledge row = findKnowledgeRow(tenantId, knowledgeId);
        if (row == null) {
            throw BizException.notFound("knowledge not found");
        }
        rejectMovingKnowledge(row);
        knowledgeWriteKB(row);
        log.info("Start deleting all chunks by knowledge ID: {}", knowledgeId);
        chunkRepository.deleteChunksByKnowledgeId(tenantId, knowledgeId);
        log.info("All chunks under knowledge deleted successfully");
    }

    // ── writableChunk 与写路径校验（chunk_write.go）─────────────────────────

    /**
     * 对照 Go {@code writableChunk}（chunk_write.go L10-31）：解析执行租户 → 取 chunk →
     * 加载 knowledge 写路径绑定 → 校验 chunk 挂在 knowledge 的 KB 上。返回<b>副本</b>
     * （Go 的 copyOfChunk），调用方的就地变更不回流仓储层。
     *
     * <p>错误形态（任务书锁定，全部 BizException 信封）：租户缺 → 401
     * "workspace context unavailable"；chunk 缺 → 404 "chunk not found"；knowledge 缺 →
     * 404 "knowledge not found"；KB 不匹配 → 403 "chunk does not belong to its knowledge
     * base"；moving 中 → 409。⚠️ Go 侧仓库哨兵（ErrChunkNotFound/ErrKnowledgeNotFound 是
     * 普通error，HTTP 走 500/400 原文）与 nil 检查分支（AppError）并存——这里按任务书
     * 统一取 AppError 形态（文案逐字同哨兵），controller golden 如测到分歧以实录为准。</p>
     */
    public Chunk writableChunk(String id) {
        long tenantId = writeExecutionTenant();
        Chunk chunk;
        try {
            chunk = chunkRepository.getChunkById(tenantId, id);
        } catch (ChunkNotFoundException e) {
            throw BizException.notFound("chunk not found");
        }
        if (chunk == null || !id.equals(chunk.getId()) || !Objects.equals(chunk.getTenantId(), tenantId)) {
            throw BizException.notFound("chunk not found");
        }
        KnowledgeWrite write = loadKnowledgeWrite(chunk.getKnowledgeId());
        if (!chunk.getKnowledgeBaseId().equals(write.knowledge().getKnowledgeBaseId())) {
            throw BizException.forbidden("chunk does not belong to its knowledge base");
        }
        return copyChunk(chunk);
    }

    /**
     * 对照 Go {@code loadKnowledgeWrite}（knowledge_write.go L58-89）：执行租户 → knowledge
     * （tenant 过滤）→ RejectMovingKnowledge → knowledgeWriteKB。requireKBWrite 略（类注释 5）。
     * Go 返回 knowledge 的副本；Java 侧调用方只读，省去拷贝。
     */
    private KnowledgeWrite loadKnowledgeWrite(String id) {
        long tenantId = writeExecutionTenant();
        Knowledge knowledge = findKnowledgeRow(tenantId, id);
        if (knowledge == null || !id.equals(knowledge.getId())
                || !Objects.equals(knowledge.getTenantId(), tenantId)) {
            throw BizException.notFound("knowledge not found");
        }
        rejectMovingKnowledge(knowledge);
        KnowledgeBase kb = knowledgeWriteKB(knowledge);
        return new KnowledgeWrite(knowledge, kb);
    }

    /**
     * 对照 Go {@code knowledgeWriteKB}（knowledge_write.go L42-58）：入参对象的 KB/租户绑定
     * 永远不替调用方选 scope。绑定不完整 → 404 "knowledge not found"；KB 行查不到 →
     * Go 透传 ErrKnowledgeBaseNotFound（普通 error，500 面）→ Java
     * {@link IllegalStateException}；KB 与 knowledge 跨租户 → 403
     * "knowledge does not belong to its knowledge base"。
     */
    private KnowledgeBase knowledgeWriteKB(Knowledge knowledge) {
        if (knowledge.getId() == null || knowledge.getId().isEmpty()
                || knowledge.getKnowledgeBaseId() == null || knowledge.getKnowledgeBaseId().isEmpty()
                || knowledge.getTenantId() == null || knowledge.getTenantId() == 0L) {
            throw BizException.notFound("knowledge not found");
        }
        KnowledgeBase kb = findKbRow(knowledge.getKnowledgeBaseId());
        if (kb == null) {
            // Go: lookup.GetKnowledgeBaseByID 的 ErrKnowledgeBaseNotFound 原样上抛（500 面）
            throw new IllegalStateException("knowledge base not found");
        }
        if (!kb.getId().equals(knowledge.getKnowledgeBaseId())
                || !Objects.equals(kb.getTenantId(), knowledge.getTenantId())) {
            throw BizException.forbidden("knowledge does not belong to its knowledge base");
        }
        return kb;
    }

    /**
     * 对照 Go {@code access.RejectMovingKnowledge}（access/knowledge_state.go）：metadata 的
     * {@code _knowledge_transfer} 里 operation=move 且 phase=moving → 409
     * "knowledge has an unfinished move; retry the move first"。
     * Go 的两级 json.Unmarshal 失败（metadata 非法 / transfer 值非结构）在 Java 侧
     * 对应为 TypeHandler 读失败 / 非对象值 → {@link IllegalStateException}（500 面）。
     */
    private static void rejectMovingKnowledge(Knowledge knowledge) {
        if (knowledge == null) {
            throw BizException.notFound("knowledge not found");
        }
        JsonNode fields = knowledge.getMetadata();
        if (fields == null || fields.isNull() || !fields.isObject()) {
            return;
        }
        JsonNode raw = fields.get(KNOWLEDGE_TRANSFER_METADATA_KEY);
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return;
        }
        if (!raw.isObject()) {
            // Go: json.Unmarshal(raw, &state) 失败 → err（500 面）
            throw new IllegalStateException("malformed knowledge transfer state");
        }
        JsonNode opNode = raw.get("operation");
        JsonNode phaseNode = raw.get("phase");
        // Go: 字符串类型字段的非字符串 JSON 会 unmarshal 报错（500 面）
        if (opNode != null && !opNode.isNull() && !opNode.isTextual()) {
            throw new IllegalStateException("malformed knowledge transfer state");
        }
        if (phaseNode != null && !phaseNode.isNull() && !phaseNode.isTextual()) {
            throw new IllegalStateException("malformed knowledge transfer state");
        }
        String operation = opNode == null || opNode.isNull() ? "" : opNode.asText();
        String phase = phaseNode == null || phaseNode.isNull() ? "" : phaseNode.asText();
        if ("move".equals(operation) && "moving".equals(phase)) {
            throw BizException.conflict("knowledge has an unfinished move; retry the move first");
        }
    }

    /**
     * 对照 Go {@code validateDocumentChunkRelations}（chunk_write.go L119-143）：编辑文本块
     * 可能连带改写父块与图片子块——首次落 revision 前校验持久化的父子关系。
     * 父块查不到时 Go 透传仓储错误（普通 error，500 面）——Java 让
     * {@link ChunkNotFoundException} 直通（与 writableChunk 的 404 形态刻意不同）。
     */
    private void validateDocumentChunkRelations(long tenantId, Chunk chunk) {
        List<Chunk> parents = new ArrayList<>();
        parents.add(chunk);
        if (chunk.getParentChunkId() != null && !chunk.getParentChunkId().isEmpty()) {
            Chunk parent = chunkRepository.getChunkById(tenantId, chunk.getParentChunkId());
            if (parent == null || !chunk.getParentChunkId().equals(parent.getId())
                    || !sameChunkDocument(chunk, parent)) {
                throw BizException.forbidden("parent chunk does not belong to its document");
            }
            parents.add(parent);
        }
        for (Chunk parent : parents) {
            for (Chunk child : chunkRepository.listChunkByParentId(tenantId, parent.getId())) {
                if (!sameChunkDocument(chunk, child) || !parent.getId().equals(child.getParentChunkId())) {
                    throw BizException.forbidden("child chunk does not belong to its document");
                }
            }
        }
    }

    /** 对照 Go {@code sameChunkDocument}（chunk_write.go L33-36）。⚠️ Long 比较必须 equals。 */
    private static boolean sameChunkDocument(Chunk a, Chunk b) {
        return a != null && b != null
                && Objects.equals(a.getTenantId(), b.getTenantId())
                && Objects.equals(a.getKnowledgeBaseId(), b.getKnowledgeBaseId())
                && Objects.equals(a.getKnowledgeId(), b.getKnowledgeId());
    }

    // ── 图片子块 / 父内容重建 ───────────────────────────────────────────────

    /**
     * 对照 Go {@code validateEditedChunkImages}（chunk.go L531-539）：编辑后的 content 不得
     * 引入原文没有的图片（Markdown 与 HTML 都算"已有"）。Go 的错误是 fmt.Errorf →
     * handler 500 面。多 URL 时 Go 遍历 map 顺序随机，Java 按扫描序报第一个——
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

    /** 对照 Go {@code imageChildMatchesContent}（chunk.go L541-548）。 */
    private static boolean imageChildMatchesContent(Chunk child, Set<String> contentUrls) {
        for (String url : ChunkSearchUtil.imageURLsFromInfo(child.getImageInfo())) {
            if (contentUrls.contains(url)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 Go {@code syncEditedChunkImages}（chunk.go L553-584）：Markdown 图被删后把对应
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
                syncChunkIndex(child);
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
     * 对照 Go {@code rebuildParentContent}（chunk.go L605-667）：把手工编辑过的子块区间
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
        // 最新编辑优先（Go: sort by updatedAt 降序；测试数据时间两两不同，稳定性无感）
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

    // ── 索引同步（2026-09-22 走查批：接线到 ChunkVectorIndexer）────────────────

    /**
     * 对照 Go {@code syncChunkIndex}（chunk.go L669-719）——接线
     * {@link ChunkVectorIndexer#syncChunkIndex}（与 updateChunkVector 同源的执行体）：
     * <ul>
     *   <li>{@code !kb.NeedsEmbeddingModel()}（indexing_strategy 的 vector_enabled /
     *       keyword_enabled 都 false）→ return（确定性子集，测试数据走这条）；</li>
     *   <li>kb 缺失 → {@link IllegalStateException}("knowledge base not found")、模型 id 空
     *       → "model ID cannot be empty"、模型行缺失 → "model not found"（500 面文案照旧，
     *       由 indexer 保持）；</li>
     *   <li>执行体：DeleteByChunkIDList（disabled 只删不插）→ chunk 行 + 生成问题行
     *       （GeneratedQuestionSourceID 折叠）BatchIndex——与 Go 逐段对照。</li>
     * </ul>
     * 调用方（UpdateDocumentChunk 等）与 Go 一致地在异常时标 index_status=failed。
     */
    private void syncChunkIndex(Chunk chunk) {
        chunkVectorIndexer.syncChunkIndex(chunk);
    }

    // ── summary 刷新入队（2026-09-22 走查批：接线到 KnowledgeService 全量语义）──

    /**
     * 对照 Go {@code enqueueSummaryRefresh}（knowledge_summary_refresh.go L83-151）——
     * 接线 {@link KnowledgeService#requestKnowledgeSummaryRefresh}（全量语义：早退分支 /
     * 无 summary model 的 markFailed+400 / 成功 pending 落库 + 进程内虚拟线程刷新）。
     *
     * <p>调用方（UpdateDocumentChunk 的 bodyChanged/enabled 变化分支）与 Go 一致地
     * 忽略失败（catch + WARN）。已知差异备案：kb 行缺失时 Go 的 markFailed 会落
     * summary_status=failed，Java 的 request 路径抛 notFound 不落列——kb 缺失在本
     * 路径不可达（chunk 的 KB 必然存在）。</p>
     */
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

    // ── 私有工具 ───────────────────────────────────────────────────────────

    /**
     * 对照 Go 从 ctx 取 {@code TenantInfo.GetEffectiveEngines()}：TenantContext 不携带
     * 租户载荷，按 id 现读租户行；行缺失/读失败时按 {@code EffectiveEngines.of(null)}
     * 走 RETRIEVE_DRIVER 缺省（租户行无显式配置的同款语义——请求路径上租户行恒存在，
     * "ctx 无 TenantInfo" 的哨兵分支在本仓不可达）。
     */
    private List<com.ragagent.retrieval.engine.RetrieverEngineParams> tenantEngines(long tenantId) {
        com.ragagent.auth.domain.Tenant tenant;
        try {
            tenant = tenantService.getTenantById(tenantId);
        } catch (RuntimeException e) {
            tenant = null;
        }
        return com.ragagent.retrieval.engine.EffectiveEngines.of(tenant);
    }

    /**
     * 对照 Go {@code writeExecutionTenant}（knowledge_write.go L32-38）：租户缺或 0 →
     * 401 "workspace context unavailable"。
     */
    private static long writeExecutionTenant() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null || tid == 0L) {
            throw BizException.unauthorized("workspace context unavailable");
        }
        return tid;
    }

    /**
     * 对照 Go {@code types.MustTenantIDFromContext}：缺失直接 panic——Java 侧不得降级
     * （约定 §9），抛 {@link IllegalStateException}。
     */
    private static long mustTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant id is not in context");
        }
        return tid;
    }

    /** knowledge 行（tenant + id，软删不可见；Go GetKnowledgeByID 的 First 语义）。 */
    private Knowledge findKnowledgeRow(long tenantId, String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** kb 行（仅 id，无租户过滤——Go GetKnowledgeBaseByID 同款；软删不可见）。 */
    private KnowledgeBase findKbRow(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** Go 的非指针 string 语义：null（H2 可空列）按零值 "" 处理。 */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Go 的 {@code _ = s.chunkRepository.UpdateChunk(ctx, chunk)}：失败被丢弃。 */
    private void updateChunkIgnoreError(Chunk chunk) {
        try {
            chunkRepository.updateChunk(chunk);
        } catch (RuntimeException e) {
            log.debug("ignored chunk update failure for {}: {}", chunk.getId(), e.getMessage());
        }
    }

    /** 对照 Go {@code Chunk.DocumentMetadata}：空/NULL → null；容忍未知属性。 */
    private static DocumentChunkMetadata parseDocumentMetadata(JsonNode metadata) throws JsonProcessingException {
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            return null;
        }
        return META_MAPPER.treeToValue(metadata, DocumentChunkMetadata.class);
    }

    /** 对照 Go {@code Chunk.SetDocumentMetadata}：序列化形状由域类型上的注解锁定。 */
    private static JsonNode writeDocumentMetadata(DocumentChunkMetadata meta) throws JsonProcessingException {
        if (meta == null) {
            return null;
        }
        return META_MAPPER.valueToTree(meta);
    }

    /** Go 的 {@code copyOfChunk := *chunk}：浅拷贝（各字段皆不可变类型）。 */
    private static Chunk copyChunk(Chunk c) {
        Chunk copy = new Chunk();
        copy.setId(c.getId());
        copy.setSeqId(c.getSeqId());
        copy.setTenantId(c.getTenantId());
        copy.setKnowledgeId(c.getKnowledgeId());
        copy.setKnowledgeBaseId(c.getKnowledgeBaseId());
        copy.setTagId(c.getTagId());
        copy.setContent(c.getContent());
        copy.setSourceContent(c.getSourceContent());
        copy.setContentRevision(c.getContentRevision());
        copy.setIndexStatus(c.getIndexStatus());
        copy.setLastEditorId(c.getLastEditorId());
        copy.setChunkIndex(c.getChunkIndex());
        copy.setIsEnabled(c.isIsEnabled());
        copy.setFlags(c.getFlags());
        copy.setStatus(c.getStatus());
        copy.setStartAt(c.getStartAt());
        copy.setEndAt(c.getEndAt());
        copy.setPreChunkId(c.getPreChunkId());
        copy.setNextChunkId(c.getNextChunkId());
        copy.setChunkType(c.getChunkType());
        copy.setParentChunkId(c.getParentChunkId());
        copy.setRelationChunks(c.getRelationChunks());
        copy.setIndirectRelationChunks(c.getIndirectRelationChunks());
        copy.setMetadata(c.getMetadata());
        copy.setContentHash(c.getContentHash());
        copy.setImageInfo(c.getImageInfo());
        copy.setContextHeader(c.getContextHeader());
        copy.setCreatedAt(c.getCreatedAt());
        copy.setUpdatedAt(c.getUpdatedAt());
        copy.setDeletedAt(c.getDeletedAt());
        return copy;
    }
}
