package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.InputSanitizer;
import com.ragagent.agent.AgentPromptPlaceholders;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.searchutil.ImageInfoEnricher;
import com.ragagent.searchutil.SearchChunkMerge;
import com.ragagent.wiki.service.WikiImageMarkup;
import com.ragagent.wiki.service.WikiLanguageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析状态机 + 摘要生成管线（对照 knowledge_process.go 摘要全链 +
 * knowledge_summary_refresh.go + manual 更新/reparse/cancel-parse/download/preview/
 * image info 等文档操作面；原 KnowledgeService「═══ 摘要生成管线 ═══」段拆分独立，
 * 段内混排的文档操作方法一并随迁）。
 *
 * <p>门面 helper（requireKb/loadKnowledgeWrite/tenantId/ensureManualFileName）经
 * {@code @Lazy} 门面复用，不复制；reset/updateKnowledgeRow 同包开放给
 * KnowledgeBatchOpsService（批量重解析/重建索引复用同一复位与全列写路径）。
 * {@link KnowledgeFileStream} 保持 public——下载/预览消费方的流类型。</p>
 */
@Service
public class KnowledgeSummaryPipelineService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSummaryPipelineService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final ChunkRepository chunkRepo;
    private final TenantFileStorage fileStorage;
    private final KnowledgeService.KnowledgeProcessWorker worker;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final ConversationProperties conversationProps;
    private final SpanTracker spanTracker;
    private final KnowledgeService facade;

    public KnowledgeSummaryPipelineService(KnowledgeMapper knowledgeMapper,
                                           KnowledgeBaseMapper kbMapper,
                                           ChunkMapper chunkMapper,
                                           ChunkRepository chunkRepo,
                                           TenantFileStorage fileStorage,
                                           @Lazy KnowledgeService.KnowledgeProcessWorker worker,
                                           ModelRuntimeFactory modelRuntimeFactory,
                                           ChunkVectorIndexer chunkVectorIndexer,
                                           ConversationProperties conversationProps,
                                           SpanTracker spanTracker,
                                           @Lazy KnowledgeService facade) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.chunkRepo = chunkRepo;
        this.fileStorage = fileStorage;
        this.worker = worker;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.conversationProps = conversationProps;
        this.spanTracker = spanTracker;
        this.facade = facade;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 摘要生成管线（对照 knowledge_process.go L2291-2443 全量 +
    // knowledge_summary_refresh.go —— 2026-09-22 走查补全，此前是阶段占位）
    // ══════════════════════════════════════════════════════════════════════

    /** Go types 的 SummaryStatus 五值（internal/types/knowledge.go L73-84）。 */
    private static final String SUMMARY_NONE = "none";
    private static final String SUMMARY_PENDING = "pending";
    private static final String SUMMARY_PROCESSING = "processing";
    private static final String SUMMARY_COMPLETED = "completed";
    private static final String SUMMARY_FAILED = "failed";

    /**
     * 对照 Go 的三个哨兵错误（errInsufficientSummaryContent / errEmptySummaryOutput /
     * ErrSummaryRefreshStale）：非 AppError → handler 包 {@code NewBadRequestError(err.Error())}
     * → 400 信封 + 原文案（既有 golden 钉住 "summary model is not configured" 同款形态）。
     * 用实例身份（==）判别，避免文案比较。
     */
    private static final BizException ERR_INSUFFICIENT_SUMMARY_CONTENT =
            new BizException(AppError.badRequest("insufficient text content for summary generation"));
    private static final BizException ERR_EMPTY_SUMMARY_OUTPUT =
            new BizException(AppError.badRequest("summary model returned empty output"));
    private static final BizException ERR_SUMMARY_REFRESH_STALE =
            new BizException(AppError.badRequest("summary refresh superseded"));

    /** Go summaryFallbackMaxRunes / imageDominatedTextThreshold / defaultMaxInputChars。 */
    private static final int SUMMARY_FALLBACK_MAX_RUNES = 500;
    private static final int IMAGE_DOMINATED_TEXT_THRESHOLD = 200;
    private static final int DEFAULT_SUMMARY_MAX_INPUT_CHARS = 1024 * 24;
    /** asynq MaxRetry(3)：刷新任务最多 1 次初始 + 3 次重试（进程内虚拟线程替代）。 */
    private static final int SUMMARY_MAX_RETRY = 3;

    /**
     * 对照 RegenerateKnowledgeSummary（knowledge_process.go L2294-2443）：HTTP 同步路径。
     * 非 asynq worker → {@code summaryTaskWillRetry}(ctx) 恒 false（终态失败处理）。
     * 失败时按 Go 语义先落库对应状态再抛错误（handler 侧非 AppError → 400 信封原文）。
     */
    public Knowledge regenerateKnowledgeSummary(String id) {
        return doRegenerateKnowledgeSummary(id, false);
    }

    private Knowledge doRegenerateKnowledgeSummary(String id, boolean willRetry) {
        Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (knowledge == null) {
            throw BizException.notFound("record not found");
        }
        KnowledgeBase kb = facade.requireKb(knowledge.getKnowledgeBaseId());
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            throw new BizException(AppError.badRequest("summary model is not configured"));
        }
        // Go ListChunksByKnowledgeID 本身 text-only；此处的类型/启用过滤是双保险（照抄）
        List<Chunk> allChunks = chunkRepo.listChunksByKnowledgeID(KnowledgeService.tenantId(), id);
        List<Chunk> textChunks = new ArrayList<>();
        for (Chunk chunk : allChunks) {
            if ("text".equals(chunk.getChunkType()) && chunk.isIsEnabled()) {
                textChunks.add(chunk);
            }
        }
        if (textChunks.isEmpty()) {
            knowledge.setDescription("");
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            updateSummaryColumns(knowledge);
            throw ERR_INSUFFICIENT_SUMMARY_CONTENT;
        }
        textChunks.sort(Comparator.comparingInt(Chunk::getChunkIndex));
        String metadataVersion = customMetadataVersion(knowledge);
        knowledge.setSummaryStatus(SUMMARY_PROCESSING);
        updateSummaryColumns(knowledge);

        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            // ModelRuntimeFactory.getModelDirect 的取数失败文案；对照 Go errors.As
            // 解出内层 AppError（404 "Model not found"）透传、其余包 "get chat model: " 前缀
            if ("model not found".equals(msg)) {
                throw failGeneration(knowledge, textChunks, metadataVersion, willRetry,
                        new BizException(AppError.notFound("Model not found")));
            }
            throw failGeneration(knowledge, textChunks, metadataVersion, willRetry,
                    new BizException(AppError.badRequest("get chat model: " + msg)));
        }
        String summary;
        try {
            summary = getSummary(chatModel, knowledge, textChunks);
        } catch (RuntimeException e) {
            throw failGeneration(knowledge, textChunks, metadataVersion, willRetry, e);
        }
        boolean stale;
        try {
            stale = summarySourceChanged(knowledge.getTenantId(), id, metadataVersion, textChunks);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(
                    "verify summary freshness: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
        }
        if (stale) {
            log.info("Discarding stale summary refresh for knowledge {}", id);
            throw ERR_SUMMARY_REFRESH_STALE;
        }
        knowledge.setDescription(summary);
        knowledge.setSummaryStatus(SUMMARY_COMPLETED);
        knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        updateSummaryColumns(knowledge);
        if (kbNeedsEmbedding(kb)) {
            int maxIndex = 0;
            for (Chunk chunk : allChunks) {
                if (chunk.getChunkIndex() > maxIndex) {
                    maxIndex = chunk.getChunkIndex();
                }
            }
            // allChunks 是 text-only，永远不含已有 summary chunk——必须按类型另查
            // （对照 Go L2404-2414 的修复：否则每次刷新都会并排追加一个新 summary chunk）
            List<Chunk> existingSummaries = chunkRepo.listChunksByKnowledgeIDAndTypes(
                    KnowledgeService.tenantId(), id, List.of("summary"));
            List<Chunk> summaryChunks = new ArrayList<>();
            for (Chunk chunk : existingSummaries) {
                chunk.setContent("# Summary\n" + summary);
                chunk.setSourceContent(chunk.getContent());
                chunk.setIsEnabled(true);
                chunk.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                chunkMapper.updateById(chunk);
                summaryChunks.add(chunk);
            }
            if (summaryChunks.isEmpty()) {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                Chunk summaryChunk = new Chunk();
                summaryChunk.setId(UUID.randomUUID().toString());
                summaryChunk.setTenantId(KnowledgeService.tenantId());
                summaryChunk.setKnowledgeId(knowledge.getId());
                summaryChunk.setKnowledgeBaseId(knowledge.getKnowledgeBaseId());
                summaryChunk.setContent("# Summary\n" + summary);
                summaryChunk.setSourceContent(summaryChunk.getContent());
                summaryChunk.setChunkIndex(maxIndex + 1);
                summaryChunk.setIsEnabled(true);
                summaryChunk.setChunkType("summary");
                summaryChunk.setParentChunkId(textChunks.get(0).getId());
                summaryChunk.setCreatedAt(now);
                summaryChunk.setUpdatedAt(now);
                chunkMapper.insert(summaryChunk);
                summaryChunks.add(summaryChunk);
            }
            chunkVectorIndexer.updateChunkVector(knowledge.getKnowledgeBaseId(), summaryChunks);
        }
        return knowledge;
    }

    /**
     * 对照 Go handleGenerationFailure（L2336-2371）：insufficient → 清 description + failed；
     * willRetry（asynq 还有重试额度）→ pending（保留既有 description）；否则终态——
     * 先做新鲜度校验（源已变 → 丢弃结果），再落首 chunk 兜底 + failed。返回原错误供抛出。
     */
    private RuntimeException failGeneration(Knowledge knowledge, List<Chunk> textChunks,
                                            String metadataVersion, boolean willRetry,
                                            RuntimeException generationErr) {
        if (generationErr == ERR_INSUFFICIENT_SUMMARY_CONTENT) {
            knowledge.setDescription("");
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            updateSummaryColumns(knowledge);
            return generationErr;
        }
        if (willRetry) {
            applyRetryableSummaryFailureState(knowledge, textChunks, true);
            try {
                updateSummaryColumns(knowledge);
            } catch (RuntimeException e) {
                log.warn("Failed to mark summary refresh pending for retry: {}", e.toString());
            }
            return generationErr;
        }
        boolean stale;
        try {
            stale = summarySourceChanged(knowledge.getTenantId(), knowledge.getId(),
                    metadataVersion, textChunks);
        } catch (RuntimeException staleErr) {
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            try {
                updateSummaryColumns(knowledge);
            } catch (RuntimeException ignored) {
                // 对照 Go 的 `_ = s.repo.UpdateKnowledge(...)`
            }
            return new BizException(AppError.badRequest("verify summary fallback freshness: "
                    + (staleErr.getMessage() == null ? staleErr.toString() : staleErr.getMessage())));
        }
        if (stale) {
            return ERR_SUMMARY_REFRESH_STALE;
        }
        applyRetryableSummaryFailureState(knowledge, textChunks, false);
        updateSummaryColumns(knowledge);
        return generationErr;
    }

    /**
     * 对照 applyRetryableSummaryFailureState（L790-805）：willRetry → pending（description 不动）；
     * 终态 → description = 首 chunk 内容（截 500 码点）+ failed + updated_at=now。
     */
    private static void applyRetryableSummaryFailureState(Knowledge knowledge,
                                                          List<Chunk> textChunks, boolean willRetry) {
        knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (willRetry) {
            knowledge.setSummaryStatus(SUMMARY_PENDING);
            return;
        }
        String fallback = firstTextChunkSummaryFallback(textChunks);
        knowledge.setDescription(fallback);
        knowledge.setSummaryStatus(SUMMARY_FAILED);
    }

    /** 对照 firstTextChunkSummaryFallback（L778-788）：首 chunk trim 后按码点截 500。 */
    private static String firstTextChunkSummaryFallback(List<Chunk> textChunks) {
        if (textChunks.isEmpty() || textChunks.get(0) == null) {
            return "";
        }
        String fallback = ChunkRepository.goTrimSpace(
                textChunks.get(0).getContent() == null ? "" : textChunks.get(0).getContent());
        int count = fallback.codePointCount(0, fallback.length());
        if (count > SUMMARY_FALLBACK_MAX_RUNES) {
            fallback = fallback.substring(0, fallback.offsetByCodePoints(0, SUMMARY_FALLBACK_MAX_RUNES));
        }
        return fallback;
    }

    /**
     * 对照 summarySourceChanged（knowledge_summary_refresh.go L29-56）：metadata 文本或
     * 任一源 chunk 的 content_revision / is_enabled 变化 → stale。仓储错误单独抛出
     * （调用方不得把瞬时读错误当成 stale 任务丢弃）。
     */
    private boolean summarySourceChanged(long tenantId, String knowledgeId,
                                         String metadataVersion, List<Chunk> sourceChunks) {
        Knowledge latest = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (latest == null) {
            throw BizException.notFound("record not found");
        }
        if (!customMetadataVersion(latest).equals(metadataVersion)) {
            return true;
        }
        for (Chunk sourceChunk : sourceChunks) {
            Chunk latestChunk = chunkRepo.getChunkById(tenantId, sourceChunk.getId());
            if (latestChunk.getContentRevision() != sourceChunk.getContentRevision()
                    || latestChunk.isIsEnabled() != sourceChunk.isIsEnabled()) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go {@code string(knowledge.CustomMetadata)}（用于新鲜度比较的版本串）。 */
    private static String customMetadataVersion(Knowledge knowledge) {
        return knowledge.getCustomMetadata() == null ? "" : knowledge.getCustomMetadata().toString();
    }

    /**
     * 对照 CustomMetadataText（types/knowledge.go L197-224）：用户自撰元数据的稳定文本
     * （键排序、跳过 null 值、"{key}: {value}" 逐行）；内部摄取元数据刻意排除。
     */
    private static String customMetadataText(Knowledge knowledge) {
        if (knowledge == null || knowledge.getCustomMetadata() == null
                || !knowledge.getCustomMetadata().isObject()) {
            return "";
        }
        JsonNode node = knowledge.getCustomMetadata();
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        java.util.Collections.sort(keys);
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.isValueNode() ? value.asText() : value.toString();
            text = ChunkRepository.goTrimSpace(text);
            if (!ChunkRepository.goTrimSpace(key).isEmpty() && !text.isEmpty()) {
                lines.add(ChunkRepository.goTrimSpace(key) + ": " + text);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * 对照 getSummary（L863-997）：重建文档正文（编辑过按 chunk_index 拼接、否则
     * StartAt 重叠合并）→ 图片富化（正文极短走 caption+OCR、否则仅 caption）→
     * 长度采样 → 充分性闸门 → custom metadata 前缀 → LLM（temperature 0.3 /
     * thinking=false / max_tokens=2048 缺省）→ 空白输出视为错误。
     */
    private String getSummary(LlmChatClient summaryModel, Knowledge knowledge, List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            throw new BizException(AppError.badRequest("no chunks provided for summary generation"));
        }
        int maxInputChars = conversationProps.getSummaryMaxInputChars();
        if (maxInputChars <= 0) {
            maxInputChars = DEFAULT_SUMMARY_MAX_INPUT_CHARS;
        }
        List<Chunk> sortedChunks = sortChunksForSummary(chunks);
        boolean hasEditedChunk = false;
        for (Chunk chunk : sortedChunks) {
            if (chunk.getContentRevision() > 0) {
                hasEditedChunk = true;
                break;
            }
        }
        String chunkContents;
        if (hasEditedChunk) {
            // 解析偏移描述不可变源文；被替换过的 chunk 长度已变，按当前内容拼接
            List<String> parts = new ArrayList<>(sortedChunks.size());
            for (Chunk chunk : sortedChunks) {
                if (chunk.isIsEnabled() && !ChunkRepository.goTrimSpace(
                        chunk.getContent() == null ? "" : chunk.getContent()).isEmpty()) {
                    parts.add(chunk.getContent());
                }
            }
            chunkContents = String.join("\n\n", parts);
        } else {
            chunkContents = SearchChunkMerge.mergeTextChunks(sortedChunks, "");
        }
        List<String> chunkIds = new ArrayList<>(sortedChunks.size());
        for (Chunk chunk : sortedChunks) {
            chunkIds.add(chunk.getId());
        }
        Map<String, String> imageInfoMap = ImageInfoEnricher.collectImageInfoByChunkIds(
                chunkRepo::listChunksByParentIDs, knowledge.getTenantId(), chunkIds);
        String mergedImageInfo = ImageInfoEnricher.mergeImageInfoJson(imageInfoMap);
        if (mergedImageInfo != null && !mergedImageInfo.isEmpty()) {
            // 图片优先文档（正文极短）：caption 信号不足，OCR 才是真内容；文本正文够长
            // 的文档走 caption-only，避免页眉/水印 OCR 噪声稀释主题
            if (WikiImageMarkup.realTextRuneCount(chunkContents) < IMAGE_DOMINATED_TEXT_THRESHOLD) {
                chunkContents = ImageInfoEnricher.enrichContentCaptionAndOcr(chunkContents, mergedImageInfo);
            } else {
                chunkContents = ImageInfoEnricher.enrichContentCaptionOnly(chunkContents, mergedImageInfo);
            }
        }
        chunkContents = sampleLongContent(chunkContents, maxInputChars);

        // LLM 调用前的充分性闸门：扫描件剥掉图片标记后没有可用文本 → 直接失败，
        // 不把文件名喂给模型（否则会按 "MX5280.pdf" 之类幻觉出扫描仪说明书）
        if (WikiImageMarkup.realTextRuneCount(chunkContents) < WikiImageMarkup.getMinTextContentRunes()) {
            log.warn("summary content check: knowledge {} has insufficient text after stripping image markup"
                    + " (real_text_runes={}, min={}); skipping LLM call",
                    knowledge.getId(), WikiImageMarkup.realTextRuneCount(chunkContents),
                    WikiImageMarkup.getMinTextContentRunes());
            throw ERR_INSUFFICIENT_SUMMARY_CONTENT;
        }
        String contentWithMetadata = chunkContents;
        String custom = customMetadataText(knowledge);
        if (!custom.isEmpty()) {
            contentWithMetadata = "Document metadata:\n" + custom + "\n\nDocument content:\n" + chunkContents;
        }
        contentWithMetadata = sampleLongContent(contentWithMetadata, maxInputChars);

        int maxTokens = conversationProps.getSummaryMaxCompletionTokens();
        if (maxTokens <= 0) {
            maxTokens = 2048;
        }
        String summaryPrompt = AgentPromptPlaceholders.renderPromptPlaceholders(
                conversationProps.getGenerateSummaryPrompt(),
                Map.of("language", WikiLanguageSupport.languageNameFromContext()));
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.3); // Go 硬编码 0.3（不用 config 的 summaryTemperature，照抄）
        options.setMaxTokens(maxTokens);
        options.setThinking(Boolean.FALSE);
        ChatResponse response;
        try {
            response = summaryModel.chat(
                    List.of(ChatMessage.system(summaryPrompt), ChatMessage.user(contentWithMetadata)),
                    options);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return validateSummaryOutput(response);
    }

    /** 对照 validateSummaryOutput（L761-773）：nil / 空白输出 → errEmptySummaryOutput。 */
    private static String validateSummaryOutput(ChatResponse response) {
        if (response == null) {
            throw ERR_EMPTY_SUMMARY_OUTPUT;
        }
        String content = ChunkRepository.goTrimSpace(
                response.getContent() == null ? "" : response.getContent());
        if (content.isEmpty()) {
            throw ERR_EMPTY_SUMMARY_OUTPUT;
        }
        return content;
    }

    /**
     * 对照 sortChunksForSummary（L841-861）：有任一 chunk 被编辑过（content_revision>0）
     * 按 chunk_index（同 index 用 id 决胜）；否则解析器 StartAt 偏移权威。
     */
    private static List<Chunk> sortChunksForSummary(List<Chunk> chunks) {
        List<Chunk> sorted = new ArrayList<>(chunks);
        boolean edited = false;
        for (Chunk chunk : sorted) {
            if (chunk.getContentRevision() > 0) {
                edited = true;
                break;
            }
        }
        final boolean editedFinal = edited;
        sorted.sort((a, b) -> {
            if (editedFinal) {
                if (a.getChunkIndex() != b.getChunkIndex()) {
                    return Integer.compare(a.getChunkIndex(), b.getChunkIndex());
                }
                return a.getId().compareTo(b.getId());
            }
            return Integer.compare(a.getStartAt(), b.getStartAt());
        });
        return sorted;
    }

    /**
     * 对照 sampleLongContent（L1003-1042）：超限时头 60% + 中段 20% + 尾 20%
     * （以 "[...content omitted...]" 标记衔接）；预算不足 100 直接截断。按码点切分。
     */
    private static String sampleLongContent(String content, int maxChars) {
        int count = content.codePointCount(0, content.length());
        if (count <= maxChars) {
            return content;
        }
        String omitMarker = "\n\n[...content omitted...]\n\n";
        int omitRunes = omitMarker.codePointCount(0, omitMarker.length());
        int usable = maxChars - 2 * omitRunes;
        if (usable < 100) {
            return content.substring(0, content.offsetByCodePoints(0, maxChars));
        }
        int headLen = usable * 60 / 100;
        int tailLen = usable * 20 / 100;
        int midLen = usable - headLen - tailLen;

        String head = content.substring(0, content.offsetByCodePoints(0, headLen));
        String tail = content.substring(content.offsetByCodePoints(0, count - tailLen));

        int midStart = count / 2 - midLen / 2;
        if (midStart < headLen) {
            midStart = headLen;
        }
        int midEnd = midStart + midLen;
        if (midEnd > count - tailLen) {
            midEnd = count - tailLen;
            midStart = midEnd - midLen;
            if (midStart < headLen) {
                midStart = headLen;
            }
        }
        String middle = content.substring(
                content.offsetByCodePoints(0, midStart), content.offsetByCodePoints(0, midEnd));
        return head + omitMarker + middle + omitMarker + tail;
    }

    /**
     * post-process 的摘要 fan-out（对照 knowledge_post_process.go L208
     * {@code willSpawnSummary = len(textChunks) > 0} + L346-390 的 summary_status 落库；
     * 任务体 = ProcessSummaryGeneration，knowledge_process.go L1121-1330——与刷新
     * 共用同一生成管线，差异仅在「错误不抛给调用方」）。
     *
     * <p>调用方 {@link KnowledgeProcessWorker} 在索引完成后调用（知识行此时已落
     * {@code summary_status=none}，对照 finalizeIndexedKnowledgeState L215-242）。
     * 本方法完成三件事：cancelled/deleting → 跳过（L1148-1156）；无 summary model
     * → 落 failed 不抛（L1133-1138）；否则 pending 落库 + 异步生成（重试/吞错语义
     * 同 {@link #spawnSummaryRefreshWorker}）。租户取知识行，不依赖调用线程的
     * TenantContext（worker 线程无上下文）。</p>
     */
    public void requestPostProcessSummaryGeneration(String knowledgeId) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            return;
        }
        String parseStatus = k.getParseStatus() == null ? "" : k.getParseStatus();
        if (Knowledge.PARSE_CANCELLED.equals(parseStatus)
                || Knowledge.PARSE_DELETING.equals(parseStatus)) {
            return;
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, k.getKnowledgeBaseId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return;
        }
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            // 对照 L1133-1138：无 summary model → summary_status=failed（任务不抛错）
            markSummaryFailed(knowledgeId);
            return;
        }
        // 对照 post_process L346 + ProcessSummaryGeneration L1159：pending 先落库再异步
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("summary_status", SUMMARY_PENDING));
        spawnSummaryRefreshWorker(knowledgeId, k.getTenantId());
    }

    /**
     * 对照 RequestKnowledgeSummaryRefresh → enqueueSummaryRefresh
     * （knowledge_summary_refresh.go L83-151）：summary 已启用（非空非 none）才入队；
     * 无 summary model → 先落 failed（markFailed）再抛原文错误；成功 → 落 pending +
     * 进程内虚拟线程执行刷新（asynq Refresh:true 的替代，重试语义对齐 MaxRetry(3)）。
     */
    public void requestKnowledgeSummaryRefresh(String id) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw BizException.notFound("record not found");
        }
        String status = k.getSummaryStatus() == null ? "" : k.getSummaryStatus();
        if (status.isEmpty() || SUMMARY_NONE.equals(status)) {
            return; // 对照 enqueueSummaryRefresh L91：未启用摘要 → 静默成功
        }
        KnowledgeBase kb = facade.requireKb(k.getKnowledgeBaseId());
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            markSummaryFailed(k.getId());
            throw new BizException(AppError.badRequest("summary model is not configured"));
        }
        // pending 必须先落库（对照 Go L132 的注释：入队可能同步执行）
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId())
                .set("summary_status", SUMMARY_PENDING));
        spawnSummaryRefreshWorker(k.getId(), k.getTenantId());
    }

    private void markSummaryFailed(String knowledgeId) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("summary_status", SUMMARY_FAILED));
    }

    /**
     * 对照 ProcessSummaryGeneration 的 Refresh 分支（L1067-1085）+ asynq MaxRetry(3)：
     * 虚拟线程内显式拷 TenantContext（§5：不跨虚拟线程共享 ThreadLocal）；
     * stale / insufficient 静默丢弃（状态已由 doRegenerate 落库），其余错误按
     * 「还有重试额度 → pending」重试，耗尽后由 willRetry=false 分支落终态。
     */
    private void spawnSummaryRefreshWorker(String knowledgeId, long tenantId) {
        final String role = TenantContext.currentRole();
        final String userId = TenantContext.currentUserId();
        Thread.ofVirtual().start(() -> {
            TenantContext.set(tenantId, null, role, false, userId, false);
            try {
                for (int attempt = 0; ; attempt++) {
                    boolean willRetry = attempt < SUMMARY_MAX_RETRY;
                    try {
                        doRegenerateKnowledgeSummary(knowledgeId, willRetry);
                        return;
                    } catch (RuntimeException e) {
                        if (e == ERR_SUMMARY_REFRESH_STALE) {
                            log.info("Discarding stale summary refresh for knowledge {}", knowledgeId);
                            return;
                        }
                        if (e == ERR_INSUFFICIENT_SUMMARY_CONTENT) {
                            return;
                        }
                        log.warn("Summary refresh failed for knowledge {} (attempt {}/{}): {}",
                                knowledgeId, attempt + 1, SUMMARY_MAX_RETRY + 1, e.getMessage());
                        if (!willRetry) {
                            return;
                        }
                    }
                }
            } finally {
                TenantContext.clear();
            }
        });
    }

    /**
     * 对照 KB.NeedsEmbeddingModel 经<b>服务层</b>读法（Go RegenerateKnowledgeSummary
     * L2302 的 kb 来自 {@code kbService.GetKnowledgeBaseByID} → {@code EnsureDefaults()}：
     * IsZero（4 字段全 false）→ Default），即 vector||keyword。
     *
     * <p><b>读层差异（2026-09-22 二次踩坑修正）</b>：Go 的判定按调用点分两层语义——
     * 服务层（kbService，含 EnsureDefaults 钩子）与 repo 层（kbRepository，仅 Scan：
     * NULL→Default、全 false 保持）。本方法对应服务层；{@link ChunkVectorIndexer}
     * 内部按调用点分别用服务层/repo 层判定。证据：全 false 策略的 KB 在 Go 的
     * updateImageInfo/regenerate 路径仍被判定为需要 embedding（golden 1007 实录）。</p>
     */
    private static boolean kbNeedsEmbedding(KnowledgeBase kb) {
        KbIndexingStrategy strategy = kb.getIndexingStrategy();
        if (strategy == null || strategy.isZero()) {
            strategy = KbIndexingStrategy.defaultStrategy();
        }
        return strategy.isVectorEnabled() || strategy.isKeywordEnabled();
    }

    /**
     * 对照 UpdateManualKnowledge（knowledge_create.go L991-1111）。payload 为
     * handler 解析出的字段（null = 请求体 null 字面量）。@return 响应体 knowledge
     * （内存对象，metadata 为声明序键，updated_at RFC3339 秒级 UTC）。
     */
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
        // Go time.Format(RFC3339)：秒恒输出（秒为 0 时 toString 会塌缩成分钟精度，掩码后仍不同）
        meta.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));

        if (!safeTitle.isEmpty()) {
            existing.setTitle(safeTitle);
        } else if (existing.getTitle() == null || existing.getTitle().isEmpty()) {
            existing.setTitle("手工知识-" + java.time.format.DateTimeFormatter
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

        // Publish：pending + 异步清理重建（对照 L1076-1090）
        existing.setParseStatus("pending");
        existing.setDescription("");
        existing.setProcessedAt(null);
        updateKnowledgeRow(existing, meta);
        existing.setMetadata(meta);
        worker.enqueue(existing.getId());
        return existing;
    }

    /**
     * 摘要路径的**窄写入**（对照 Go 摘要侧只用列级更新：{@code repo.UpdateKnowledgeColumn(…, "summary_status", …)}，
     * knowledge_summary_refresh.go L96/L132）。
     *
     * <p>❌ 原实现走 {@link #updateKnowledgeRow}（**全列写**）：它会把<b>加载时</b>的旧
     * {@code parse_status} 一并写回。摘要是在导入后处理的 finalizing 交接**之后**才跑完 LLM
     * （数十秒），回写就把 `finalizing/completed` 打回加载时的 {@code processing} ✗；而全列写按
     * Go 约定又<b>不含</b> {@code pending_subtasks_count}（保持 0）✗ ⇒ 知识永久停在"解析中"
     * （用户报障：`05.03-问题发布.md` 卡 processing ✗）。</p>
     *
     * <p>本方法只写摘要自己那几列（description / summary_status / metadata / updated_at），
     * 绝不碰 parse_status、enable_status、pending_subtasks_count。</p>
     */
    private void updateSummaryColumns(Knowledge k) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId())
                .set("description", k.getDescription())
                .set("summary_status", k.getSummaryStatus())
                .set("metadata", k.getMetadata() == null
                                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                : k.getMetadata(),
                        "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set("updated_at", k.getUpdatedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : k.getUpdatedAt()));
    }

    /**
     * 对照 repo.UpdateKnowledge（Save 全列写 + Omit DeletedAt/PendingSubtasksCount）：
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

    /** 对照 sanitizeManualDownloadFilename：换行/制表删除、斜杠转连字符、引号转单引号、
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

    /**
     * 对照 ReparseKnowledge（knowledge_process.go L2447-2728 的确定性前缀）：
     * loadKnowledgeWrite → （override 校验仅当显式传入）→ reset → 落库 → 入队。
     * @return 响应体 knowledge（内存对象，重置后的状态；时间戳掩码外逐字段一致）
     */
    public Knowledge reparseKnowledge(String id) {
        Knowledge existing = facade.loadKnowledgeWrite(id);
        KnowledgeBase kb = facade.requireKb(existing.getKnowledgeBaseId());
        resetKnowledgeForReparse(existing, kb);
        updateKnowledgeRow(existing, existing.getMetadata());
        worker.enqueue(existing.getId());
        return existing;
    }

    /** 对照 resetKnowledgeForReparse（L2733-2743）。 */
    static void resetKnowledgeForReparse(Knowledge k, KnowledgeBase kb) {
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled");
        k.setDescription("");
        k.setProcessedAt(null);
        k.setErrorMessage("");
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setPendingSubtasksCount(0);
    }

    /**
     * 对照 CancelKnowledgeParse（knowledge_process.go L2763-2845）：cancelled 幂等、
     * completed/failed → 400 "解析已结束，无法取消"、deleting → 400 "知识正在删除中，
     * 无法取消解析"、其余状态（含 unknown）放行改 cancelled。
     */
    public Knowledge cancelKnowledgeParse(String id) {
        Knowledge existing = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (existing == null) {
            throw BizException.notFound("knowledge not found");
        }
        switch (existing.getParseStatus() == null ? "" : existing.getParseStatus()) {
            case Knowledge.PARSE_CANCELLED -> {
                return existing; // 幂等
            }
            case Knowledge.PARSE_COMPLETED, Knowledge.PARSE_FAILED ->
                throw BizException.badRequest("解析已结束，无法取消");
            case Knowledge.PARSE_DELETING ->
                throw BizException.badRequest("知识正在删除中，无法取消解析");
            default -> {
                // pending/processing/finalizing/unknown → 可取消（unknown Go 仅记日志放行）
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", existing.getId())
                .set("parse_status", Knowledge.PARSE_CANCELLED)
                .set("error_message", "用户已取消解析")
                .set("pending_subtasks_count", 0)
                .set("updated_at", now));
        existing.setParseStatus(Knowledge.PARSE_CANCELLED);
        existing.setErrorMessage("用户已取消解析");
        existing.setPendingSubtasksCount(0);
        existing.setUpdatedAt(now);
        // 对照 Go CancelKnowledgeParse：LatestAttempt → AbortAttempt（平扫非终态子 span +
        // 收口 root 为 cancelled；best-effort，nil/missing attempt no-op）
        int spanAttempt = spanTracker.latestAttempt(existing.getId());
        if (spanAttempt > 0) {
            spanTracker.abortAttempt(existing.getId(), spanAttempt,
                    "USER_CANCELLED", "用户已取消解析", "用户已取消解析");
        }
        return existing;
    }

    /**
     * 对照 GetKnowledgeFile（service/knowledge.go L681-712）：manual 流 metadata.content；
     * document 走本地文件。路径解析对照 local provider：resource://（阶段 3 布局）与
     * local://{rel}（Go provider 原生）都支持；路径越界 → Go 的
     * "invalid file path: path traversal denied: ..." 原文（golden 钉住）。
     *
     * @return (opened, filename, manual)；manual = 内存流（Go 侧 NopCloser(bytes.Reader) →
     *         非 Seeker → Accept-Ranges: none + 显式 CL），document = 存储层打开
     *         （本地 *os.File 可 seek → bytes + Range；云按 provider 能力，W5γ5.4 ①b）
     */
    public record KnowledgeFileStream(String filename,
            com.ragagent.storage.fileserve.FileTransport.OpenedFile opened, boolean manual) {
    }

    /**
     * 打开知识文件流（对照 Go {@code GetKnowledgeFile} 的 io.ReadCloser 形态）。
     *
     * <p>替换先前"读满 byte[]"的实现（W5γ5.4 ①b）：大文件不再整份入堆，
     * 且下载/预览因此获得与 Go 相同的 Range 语义（本地 {@code Accept-Ranges: bytes}）。</p>
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
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // 照 Go：manual 分支是 NopCloser(bytes.Reader) —— **非 seekable** → none + 显式 CL
            return new KnowledgeFileStream(sanitizeManualDownloadFilename(knowledge.getTitle()),
                    com.ragagent.storage.fileserve.FileTransport.OpenedFile.ofStream(
                            new java.io.ByteArrayInputStream(bytes), bytes.length),
                    true);
        }
        String filePath = knowledge.getFilePath() == null ? "" : knowledge.getFilePath();
        return new KnowledgeFileStream(knowledge.getFileName(),
                fileStorage.open(KnowledgeService.tenantId(), filePath), false);
    }

    /**
     * 对照 UpdateImageInfo（knowledge_process.go L2942-3123 全链）：
     * 解析 image_info（非 JSON → Go json 原文的 500）、恰好 1 张图才动、
     * chunk 归属校验（403）、子块 caption/OCR 同步、缺块补建、
     * {@code updateChunkVector(updateChunks + addChunks)}（模型 ID 空 → 1007
     * "model ID cannot be empty"，golden 钉住）、
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
            // 对照 json.Unmarshal 失败 → 非 AppError → 500 message=原文
            throw new BizException(AppError.internal(com.ragagent.common.web.GoJsonBindError
                    .message(imageInfo, e.getMessage())));
        }
        if (!images.isArray() || images.size() != 1) {
            log.warn("Expected exactly one image info, got {}",
                    images.isArray() ? images.size() : -1);
            return; // Go 返回 nil → 200
        }
        JsonNode image = images.get(0);

        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getId, chunkId)
                .eq(Chunk::getTenantId, KnowledgeService.tenantId())
                .isNull(Chunk::getDeletedAt)
                .last("LIMIT 1"));
        if (chunk == null) {
            // 对照 chunkRepo.GetChunkByID 的 "chunk not found"（handler 包 500）
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
                continue; // Go WARN + continue
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
        // 对照 updateChunkVector(ctx, chunk.KnowledgeBaseID, append(updateChunk, addChunk...))：
        // 内部过 NeedsEmbedding（策略判定在 ChunkVectorIndexer）→ GetEmbeddingModel；
        // 模型 ID 空 → 1007 "model ID cannot be empty"（golden kg-image-update/again 钉住）
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
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
