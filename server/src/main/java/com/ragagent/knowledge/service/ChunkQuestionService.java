package com.ragagent.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.ragagent.knowledge.domain.DocumentChunkMetadata;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.ChunkNotFoundException;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.domain.ChunkRevisionConflictException;
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
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.RetrieveEngineRegistry;
import com.ragagent.retrieval.engine.RetrieverEngineParams;
import com.ragagent.retrieval.engine.TenantStoreOwnership;
import com.ragagent.retrieval.engine.EffectiveEngines;
import com.ragagent.retrieval.engine.RetrieveEngineFactories;
import com.ragagent.common.web.JsonMappers;
import com.ragagent.knowledge.security.ChunkAccessGuard;
import com.ragagent.searchutil.ChunkSearchUtil;

/**
 * chunk 生成问题面：生成问题的 upsert/删除/重生（LLM 生成 + 邻块上下文拼装 + metadata 落库）。
 * 写路径守卫经 {@link ChunkAccessGuard}，索引进 {@link ChunkVectorIndexer}。
 */
@Service
public class ChunkQuestionService {

    private static final Logger log = LoggerFactory.getLogger(ChunkQuestionService.class);

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final RetrieveEngineRegistry retrieveEngineRegistry;
    private final TenantStoreOwnership storeOwnership;
    private final TenantService tenantService;
    private final ConversationProperties conversationProps;
    private final ChunkAccessGuard guard;

    public ChunkQuestionService(ChunkRepository chunkRepository,
                                KnowledgeMapper knowledgeMapper,
                                KnowledgeBaseMapper kbMapper,
                                ChunkVectorIndexer chunkVectorIndexer,
                                ModelRuntimeFactory modelRuntimeFactory,
                                RetrieveEngineRegistry retrieveEngineRegistry,
                                TenantStoreOwnership storeOwnership,
                                TenantService tenantService,
                                ConversationProperties conversationProps,
                                ChunkAccessGuard guard) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.retrieveEngineRegistry = retrieveEngineRegistry;
        this.storeOwnership = storeOwnership;
        this.tenantService = tenantService;
        this.conversationProps = conversationProps;
        this.guard = guard;
    }

    /** 元数据可能含未知键（历史行/新增字段）→ 宽松读。 */
    private static final ObjectMapper META_MAPPER = JsonMappers.lenient();
    private static final String CHUNK_TYPE_TEXT = "text";

    private static long mustTenantId() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            throw new IllegalStateException("tenant id is not in context");
        }
        return tid;
    }


    // ── 生成问题 ───────────────────────────────────────────────────────────

    /**
     * questionID 为空 =
     * 新建（服务端生成 UUID），否则就地更新既有问题并把 content_revision 钉到当前版本。
     * metadata 序列化失败文案是 Jackson 的（已知差异族）。
     */
    public GeneratedQuestion upsertGeneratedQuestion(String chunkId, String questionId, String question) {
        String trimmed = ChunkSearchUtil.trimSpace(question);
        if (trimmed.isEmpty()) {
            throw BizException.badRequest("question cannot be empty");
        }
        Chunk chunk;
        try {
            chunk = guard.writableChunk(chunkId);
        } catch (BizException e) {
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
            chunkVectorIndexer.syncChunkIndex(chunk);
        } catch (RuntimeException e) {
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
     * <b>所有</b>失败被
     * handler 包 400（err.Error() 原文，含 {@code %w} 包装链）。向量索引删除半边
     * 可观测失败分支，文案逐字对照）。
     */
    public void deleteGeneratedQuestion(String chunkId, String questionId) {
        log.info("Deleting generated question, chunk ID: {}, question ID: {}", chunkId, questionId);
        long tenantId = mustTenantId();

        //    "error code: N, ..." 前缀一并进入 400 文案，照抄）
        Chunk chunk;
        try {
            chunk = guard.writableChunk(chunkId);
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

        //    ErrKnowledgeBaseNotFound 的原文是 "knowledge base not found"）
        KnowledgeBase kb = findKbRow(chunk.getKnowledgeBaseId());
        if (kb == null) {
            throw BizException.badRequest("failed to get knowledge base: knowledge base not found");
        }

        // 5. 删除该问题的向量索引。source_id 形如 {chunk_id}-q{hash24}（短 ID 直拼）。
        String sourceId = ChunkSearchUtil.generatedQuestionSourceId(chunkId, questionId);
        //     无绑定回落租户有效引擎（RETRIEVE_DRIVER 驱动），绑定 store 走归属校验 +
        //     注册表解析。失败 → "failed to create retrieve engine: %w"（handler 包 400）。
        CompositeRetrieveEngine engine;
        try {
            engine = RetrieveEngineFactories.createForKb(
                    retrieveEngineRegistry, storeOwnership, tenantId, kb.getVectorStoreId(),
                    tenantEngines(tenantId));
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to create retrieve engine: " + e.getMessage());
        }
        //     "model ID cannot be empty" / "model not found"，经
        //     ModelRuntimeFactory.getEmbeddingModel 的 RuntimeException 原文冒出）
        Embedder embeddingModel;
        try {
            embeddingModel = modelRuntimeFactory.getEmbeddingModel(
                    kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        } catch (RuntimeException e) {
            throw BizException.badRequest("failed to get embedding model: " + e.getMessage());
        }
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
     * 确定性分支逐字对照；LLM 生成步降级见类注释第 3 条。
     */
    public List<GeneratedQuestion> regenerateChunkQuestions(String chunkId) {
        long tenantId = mustTenantId();
        Chunk chunk;
        try {
            chunk = chunkRepository.getChunkById(tenantId, chunkId);
        } catch (ChunkNotFoundException e) {
            throw BizException.badRequest("chunk not found");
        }
        if (!CHUNK_TYPE_TEXT.equals(chunk.getChunkType())) {
            throw BizException.badRequest("questions can only be generated for text chunks");
        }
        int generationRevision = chunk.getContentRevision();
        ChunkAccessGuard.KnowledgeWrite write;
        try {
            write = guard.loadKnowledgeWrite(chunk.getKnowledgeId());
        } catch (BizException e) {
            throw BizException.badRequest(e.getMessage());
        }
        Knowledge knowledge = write.knowledge();
        KnowledgeBase kb = write.kb();
        if (!knowledge.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())
                || !Objects.equals(chunk.getTenantId(), knowledge.getTenantId())) {
            throw BizException.badRequest(
                    BizException.forbidden("chunk does not belong to its knowledge document").getMessage());
        }
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            throw BizException.badRequest("summary model is required for question generation");
        }
        // 的 handler 里统一包 400 err.Error()——"model not found" 等原文）
        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            throw BizException.badRequest(e.getMessage() == null ? "model not found" : e.getMessage());
        }
        String prevContent = resolveNeighborContent(tenantId, chunk, chunk.getPreChunkId());
        String nextContent = resolveNeighborContent(tenantId, chunk, chunk.getNextChunkId());
        // 生成问题配置缺省：count=3、上限 10
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
        // 重读 latestChunk，期间被编辑（revision 变化）则 409
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
            throw BizException.badRequest(e.getMessage());
        }
        log.info("Successfully regenerated {} questions for chunk {}", generated.size(), chunkId);
        return generated;
    }

    /**
     * worker 语义的"生成 + 落库 + 建索引"——导入后处理扇出的批任务走这里。
     * <p>与 {@link #regenerateChunkQuestions} 共用同一条落库路径，但**没有 handler 语义**：
     * 期间分块被编辑（revision 变化）时<b>跳过</b>而不是抛 409；单块 LLM 失败只告警并跳过、
     * 不中断整批。</p>
     * <p>模型解析失败与落库失败<b>上抛</b>——让队列按队列语义重试
     * 。</p>
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
            // revision 变化 → 跳过（陈旧问题不落库），不报错
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

    /** GeneratedQuestion 列表构建（id 新 UUID、revision 取当前）。 */
    private static List<GeneratedQuestion> buildGeneratedQuestions(List<String> questions, Chunk chunk) {
        List<GeneratedQuestion> generated = new ArrayList<>(questions.size());
        Integer questionRevision = chunk.getContentRevision();
        for (String question : questions) {
            generated.add(new GeneratedQuestion(UUID.randomUUID().toString(), question, questionRevision));
        }
        return generated;
    }

    /**
     * 语义（metadata 整写 + chunk 更新 + 向量同步三联动）：
     * 整体替换 metadata（仅 generated_questions 两键；空列表/0 由域类型注解省略）
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
     * 邻居块必须与当前块同租户/同 KB/同文档，否则按空处理；
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
     * * 1) prompt 取自 config.Conversation.GenerateQuestionsPrompt（空 → err 原文）；
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
        String prompt = ChunkRepository.trimSpace(conversationProps.getGenerateQuestionsPrompt());
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
     *  的行解析段（L2186-2201）：
     * 逐行 trim → 裁前缀符号（{@code "0123456789.-*) "}）→ trim → 非空且 &gt;5 字节
     */
    static List<String> parseGeneratedQuestions(String content, int questionCount) {
        List<String> questions = new ArrayList<>(Math.max(questionCount, 0));
        if (content == null || questionCount <= 0) {
            return questions;
        }
        for (String raw : content.split("\n", -1)) {
            String line = ChunkRepository.trimSpace(raw);
            if (line.isEmpty()) {
                continue;
            }
            line = trimLeftCharSet(line, "0123456789.-*) ");
            line = ChunkRepository.trimSpace(line);
            if (!line.isEmpty() && line.getBytes(StandardCharsets.UTF_8).length > 5) {
                questions.add(line);
                if (questions.size() >= questionCount) {
                    break;
                }
            }
        }
        return questions;
    }

    /** 裁掉开头属于字符集的字符。 */
    private static String trimLeftCharSet(String s, String cutset) {
        int i = 0;
        while (i < s.length() && cutset.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }


    private List<RetrieverEngineParams> tenantEngines(long tenantId) {
        Tenant tenant;
        try {
            tenant = tenantService.getTenantById(tenantId);
        } catch (RuntimeException e) {
            tenant = null;
        }
        return EffectiveEngines.of(tenant);
    }

    /**
     * 租户缺或 0 →
     * 401 "workspace context unavailable"。
     */

    private KnowledgeBase findKbRow(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }


    private static DocumentChunkMetadata parseDocumentMetadata(JsonNode metadata) throws JsonProcessingException {
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            return null;
        }
        return META_MAPPER.treeToValue(metadata, DocumentChunkMetadata.class);
    }

    /** 序列化形状由域类型上的注解锁定。 */

    private static JsonNode writeDocumentMetadata(DocumentChunkMetadata meta) throws JsonProcessingException {
        if (meta == null) {
            return null;
        }
        return META_MAPPER.valueToTree(meta);
    }

}
