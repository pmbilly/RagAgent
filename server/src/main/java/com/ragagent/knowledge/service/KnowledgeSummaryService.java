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
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
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

/**
 * 知识摘要生成管线：同步重生（含 fallback/重试状态机）与异步刷新入队；
 * 状态常量与哨兵异常定义于此。
 */
@Service
public class KnowledgeSummaryService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSummaryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final ChunkRepository chunkRepo;
    private final KnowledgeService.KnowledgeProcessWorker worker;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final ConversationProperties conversationProps;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeService facade;
    /** 后台任务执行器（统一命名与关停）。 */
    private final KnowledgeTaskExecutor taskExecutor;

    public KnowledgeSummaryService(
        KnowledgeTaskExecutor taskExecutor,
                            KnowledgeMapper knowledgeMapper,
                            KnowledgeBaseMapper kbMapper,
                            ChunkMapper chunkMapper,
                            ChunkRepository chunkRepo,
                            @Lazy KnowledgeService.KnowledgeProcessWorker worker,
                            ModelRuntimeFactory modelRuntimeFactory,
                            ChunkVectorIndexer chunkVectorIndexer,
                            ConversationProperties conversationProps,
                            KnowledgeFileService knowledgeFileService,
                            @Lazy KnowledgeService facade) {

        this.taskExecutor = taskExecutor;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.chunkRepo = chunkRepo;
        this.worker = worker;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.conversationProps = conversationProps;
        this.knowledgeFileService = knowledgeFileService;
        this.facade = facade;
    }

    /** summary_status 的五个取值。 */
    private static final String SUMMARY_NONE = "none";
    private static final String SUMMARY_PENDING = "pending";
    private static final String SUMMARY_PROCESSING = "processing";
    private static final String SUMMARY_COMPLETED = "completed";
    private static final String SUMMARY_FAILED = "failed";

    /**
     * 三个哨兵错误（内容不足 / 空输出 /
     * ErrSummaryRefreshStale）：非 AppError → handler 包 {@code NewBadRequestError(err.Error())}
     * → 400 信封 + 原文案（既有 契约样例锁定 "summary model is not configured" 同款形态）。
     * 用实例身份（==）判别，避免文案比较。
     */
    private static final BizException ERR_INSUFFICIENT_SUMMARY_CONTENT =
            new BizException(AppError.badRequest("insufficient text content for summary generation"));
    private static final BizException ERR_EMPTY_SUMMARY_OUTPUT =
            new BizException(AppError.badRequest("summary model returned empty output"));
    private static final BizException ERR_SUMMARY_REFRESH_STALE =
            new BizException(AppError.badRequest("summary refresh superseded"));

    private static final int SUMMARY_FALLBACK_MAX_RUNES = 500;
    private static final int IMAGE_DOMINATED_TEXT_THRESHOLD = 200;
    private static final int DEFAULT_SUMMARY_MAX_INPUT_CHARS = 1024 * 24;
    /** 刷新任务最多 1 次初始 + 3 次重试（进程内虚拟线程队列）。 */
    private static final int SUMMARY_MAX_RETRY = 3;

    /**
     * HTTP 同步路径。
     * 刷新 worker 外的路径按终态失败处理。
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
            knowledgeFileService.updateSummaryColumns(knowledge);
            throw ERR_INSUFFICIENT_SUMMARY_CONTENT;
        }
        textChunks.sort(Comparator.comparingInt(Chunk::getChunkIndex));
        String metadataVersion = customMetadataVersion(knowledge);
        knowledge.setSummaryStatus(SUMMARY_PROCESSING);
        knowledgeFileService.updateSummaryColumns(knowledge);

        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            // 模型解析失败文案保持原文（下游按异常类型分支）
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
        knowledgeFileService.updateSummaryColumns(knowledge);
        if (kbNeedsEmbedding(kb)) {
            int maxIndex = 0;
            for (Chunk chunk : allChunks) {
                if (chunk.getChunkIndex() > maxIndex) {
                    maxIndex = chunk.getChunkIndex();
                }
            }
            // allChunks 是 text-only，永远不含已有 summary chunk——必须按类型另查
            // 
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
     * insufficient → 清 description + failed；
     * willRetry（队列还有重试额度）→ pending（保留既有 description）；否则终态——
     * 先做新鲜度校验（源已变 → 丢弃结果），再落首 chunk 兜底 + failed。返回原错误供抛出。
     */
    private RuntimeException failGeneration(Knowledge knowledge, List<Chunk> textChunks,
                                            String metadataVersion, boolean willRetry,
                                            RuntimeException generationErr) {
        if (generationErr == ERR_INSUFFICIENT_SUMMARY_CONTENT) {
            knowledge.setDescription("");
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            knowledgeFileService.updateSummaryColumns(knowledge);
            return generationErr;
        }
        if (willRetry) {
            applyRetryableSummaryFailureState(knowledge, textChunks, true);
            try {
                knowledgeFileService.updateSummaryColumns(knowledge);
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
                knowledgeFileService.updateSummaryColumns(knowledge);
            } catch (RuntimeException ignored) {
                // 尽力写：失败仅告警
            }
            return new BizException(AppError.badRequest("verify summary fallback freshness: "
                    + (staleErr.getMessage() == null ? staleErr.toString() : staleErr.getMessage())));
        }
        if (stale) {
            return ERR_SUMMARY_REFRESH_STALE;
        }
        applyRetryableSummaryFailureState(knowledge, textChunks, false);
        knowledgeFileService.updateSummaryColumns(knowledge);
        return generationErr;
    }

    /**
     * willRetry → pending（description 不动）；
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

    /** 首 chunk trim 后按码点截 500。 */
    private static String firstTextChunkSummaryFallback(List<Chunk> textChunks) {
        if (textChunks.isEmpty() || textChunks.get(0) == null) {
            return "";
        }
        String fallback = ChunkRepository.trimSpace(
                textChunks.get(0).getContent() == null ? "" : textChunks.get(0).getContent());
        int count = fallback.codePointCount(0, fallback.length());
        if (count > SUMMARY_FALLBACK_MAX_RUNES) {
            fallback = fallback.substring(0, fallback.offsetByCodePoints(0, SUMMARY_FALLBACK_MAX_RUNES));
        }
        return fallback;
    }

    /**
     * metadata 文本或
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
    private static String customMetadataVersion(Knowledge knowledge) {
        return knowledge.getCustomMetadata() == null ? "" : knowledge.getCustomMetadata().toString();
    }

    /**
     * 用户自撰元数据的稳定文本
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
            text = ChunkRepository.trimSpace(text);
            if (!ChunkRepository.trimSpace(key).isEmpty() && !text.isEmpty()) {
                lines.add(ChunkRepository.trimSpace(key) + ": " + text);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * 重建文档正文（编辑过按 chunk_index 拼接、否则
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
                if (chunk.isIsEnabled() && !ChunkRepository.trimSpace(
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
        options.setTemperature(0.3); // 摘要温度固定 0.3（不用 config 的 summaryTemperature）
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

    /** nil / 空白输出 → errEmptySummaryOutput。 */
    private static String validateSummaryOutput(ChatResponse response) {
        if (response == null) {
            throw ERR_EMPTY_SUMMARY_OUTPUT;
        }
        String content = ChunkRepository.trimSpace(
                response.getContent() == null ? "" : response.getContent());
        if (content.isEmpty()) {
            throw ERR_EMPTY_SUMMARY_OUTPUT;
        }
        return content;
    }

    /**
     * 有任一 chunk 被编辑过（content_revision>0）
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
     * 超限时头 60% + 中段 20% + 尾 20%
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
     * post-process 的摘要 fan-out。
     * <p>调用方 {@link KnowledgeProcessWorker} 在索引完成后调用（知识行此时已落
     * {@code summary_status=none}）。
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
            // 无 summary model → summary_status=failed（任务不抛错）
            markSummaryFailed(knowledgeId);
            return;
        }
        // pending 先落库再异步执行
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("summary_status", SUMMARY_PENDING));
        spawnSummaryRefreshWorker(knowledgeId, k.getTenantId());
    }

    /**
     * 摘要刷新入队：summary 已启用（非空非 none）才入队；
     * 无 summary model → 先落 failed（markFailed）再抛原文错误；成功 → 落 pending +
     * 进程内虚拟线程执行刷新，重试上限 MaxRetry(3)。
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
            return; // 未启用摘要 → 静默成功
        }
        KnowledgeBase kb = facade.requireKb(k.getKnowledgeBaseId());
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            markSummaryFailed(k.getId());
            throw new BizException(AppError.badRequest("summary model is not configured"));
        }
        // pending 必须先落库
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
     * 刷新分支（重试上限 MaxRetry(3）：
     * 虚拟线程内显式拷 TenantContext（本仓约定：不跨虚拟线程共享 ThreadLocal）；
     * stale / insufficient 静默丢弃（状态已由 doRegenerate 落库），其余错误按
     * 「还有重试额度 → pending」重试，耗尽后由 willRetry=false 分支落终态。
     */
    private void spawnSummaryRefreshWorker(String knowledgeId, long tenantId) {
        final String role = TenantContext.currentRole();
        final String userId = TenantContext.currentUserId();
        taskExecutor.submit("knowledge-summary-refresh", () -> {
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
     * KB.NeedsEmbeddingModel 的服务层读法（
     * L2302 的 kb 来自 {@code kbService.GetKnowledgeBaseByID} → {@code EnsureDefaults()}：
     * IsZero（4 字段全 false）→ Default），即 vector||keyword。
     * 服务层（kbService，含 EnsureDefaults 钩子）与 repo 层（kbRepository，仅 Scan：
     * NULL→Default、全 false 保持）。本方法对应服务层；{@link ChunkVectorIndexer}
     * updateImageInfo/regenerate 路径仍被判定为需要 embedding（契约样例 1007 实录）。</p>
     */
    private static boolean kbNeedsEmbedding(KnowledgeBase kb) {
        KbIndexingStrategy strategy = kb.getIndexingStrategy();
        if (strategy == null || strategy.isZero()) {
            strategy = KbIndexingStrategy.defaultStrategy();
        }
        return strategy.isVectorEnabled() || strategy.isKeywordEnabled();
    }

    /**
     * payload 为
     * handler 解析出的字段（null = 请求体 null 字面量）。@return 响应体 knowledge
     * （内存对象，metadata 为声明序键，updated_at RFC3339 秒级 UTC）。
     */
}
