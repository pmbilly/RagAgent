package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.DocumentChunkMetadata;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * chunk 向量行的重建执行体（2026-09-22 走查批：把「路由在、执行体占位」的两处
 * 索引缺口收敛到同一实现）：
 *
 * <ul>
 *   <li>{@link #updateChunkVector} —— 对照 Go {@code knowledgeService.updateChunkVector}
 *       （knowledge_process.go L2859-2940）：多 chunk 版（摘要 chunk 维护 / 问题重生成）；</li>
 *   <li>{@link #syncChunkIndex} —— 对照 Go {@code chunkService.syncChunkIndex}
 *       （chunk.go L669-719）：单 chunk 版（chunk 编辑链路；disabled 块删旧不重建）。</li>
 * </ul>
 *
 * <p><b>source_id 契约</b>：chunk 行 = chunkID（无前缀）；生成问题行 =
 * {@link #generatedQuestionSourceId}（chunkID-qID；超 64 字节折叠
 * {@code chunkID-q<sha256 前 12 字节 hex>}）。索引文本 =
 * {@code title + "\n" + EmbeddingContent}（KnowledgeIndexContent.build）。</p>
 */
@Service
public class ChunkVectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChunkVectorIndexer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ModelService modelService;
    private final EmbedderClient embedder;
    private final VectorStoreService vectorStore;

    public ChunkVectorIndexer(KnowledgeBaseMapper kbMapper,
                              KnowledgeMapper knowledgeMapper,
                              ModelService modelService,
                              EmbedderClient embedder,
                              VectorStoreService vectorStore) {
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.modelService = modelService;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
    }

    /**
     * 对照 Go {@code knowledgeService.updateChunkVector}：删该批 chunk 的全部旧向量行
     * （含生成问题行）→ 批量 embedding → 插入 chunk 行与问题行。KB 缺失 →
     * 404 "knowledge base not found"（知识库链路语义）。
     */
    public void updateChunkVector(String kbId, List<Chunk> chunks) {
        KnowledgeBase kb = findKbRow(kbId);
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        if (!needsEmbedding(kb)) {
            return;
        }
        Model embeddingModel;
        try {
            embeddingModel = modelService.getModelByID(
                    kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        } catch (ModelNotFoundException e) {
            throw new BizException(AppError.notFound("Model not found"));
        }
        indexAndStore(kb, embeddingModel, chunks);
    }

    /**
     * 对照 Go {@code chunkService.syncChunkIndex}（chunk.go L669-719）：KB 缺失/模型
     * 缺失的错误形态是 500 面（{@link IllegalStateException}，调用方
     * UpdateDocumentChunk 吞成 index_status=failed）；删除旧行后按 enabled 决定是否重建。
     */
    public void syncChunkIndex(Chunk chunk) {
        KnowledgeBase kb = findKbRow(chunk.getKnowledgeBaseId());
        if (kb == null) {
            throw new IllegalStateException("knowledge base not found");
        }
        if (!needsEmbedding(kb)) {
            return;
        }
        String modelId = kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId();
        if (modelId.isEmpty()) {
            throw new IllegalStateException("model ID cannot be empty");
        }
        Model embeddingModel;
        try {
            embeddingModel = modelService.getModelByID(modelId);
        } catch (ModelNotFoundException e) {
            throw new IllegalStateException("model not found");
        } catch (BizException e) {
            throw new IllegalStateException(e.appError().message());
        }
        indexAndStore(kb, embeddingModel, List.of(chunk));
    }

    /** 共享主体：ids 全删 → （enabled 且非 parent_text 的）chunk 行 + 问题行重建。 */
    private void indexAndStore(KnowledgeBase kb, Model embeddingModel, List<Chunk> chunks) {
        EmbedderClient.EmbedConfig cfg = EmbedderClient.configFrom(embeddingModel);
        List<VectorStoreService.IndexRow> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Map<String, Knowledge> knowledgeCache = new HashMap<>();
        for (Chunk chunk : chunks) {
            if (chunk.getKnowledgeBaseId() == null
                    || !chunk.getKnowledgeBaseId().equals(kb.getId())) {
                log.warn("Knowledge base ID mismatch: {} != {}", chunk.getKnowledgeBaseId(), kb.getId());
                continue;
            }
            ids.add(chunk.getId());
            if (!chunk.isIsEnabled() || "parent_text".equals(chunk.getChunkType())) {
                continue;
            }
            Knowledge knowledge = knowledgeCache.get(chunk.getKnowledgeId());
            if (knowledge == null) {
                knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                        .eq(Knowledge::getId, chunk.getKnowledgeId())
                        .eq(Knowledge::getTenantId, chunk.getTenantId())
                        .isNull(Knowledge::getDeletedAt)
                        .last("LIMIT 1"));
                if (knowledge == null) {
                    throw BizException.notFound("record not found");
                }
                knowledgeCache.put(chunk.getKnowledgeId(), knowledge);
            }
            rows.add(new VectorStoreService.IndexRow(chunk.getId(), chunk.getId(),
                    chunk.getKnowledgeId(), chunk.getKnowledgeBaseId(),
                    KnowledgeIndexContent.build(knowledge, chunk.embeddingContent()),
                    chunk.isIsEnabled()));
            DocumentChunkMetadata meta = chunkDocumentMetadata(chunk);
            if (meta != null && meta.getGeneratedQuestions() != null) {
                for (GeneratedQuestion question : meta.getGeneratedQuestions()) {
                    if (question.getQuestion() == null
                            || ChunkRepository.goTrimSpace(question.getQuestion()).isEmpty()) {
                        continue;
                    }
                    rows.add(new VectorStoreService.IndexRow(
                            ChunkSearchUtil.generatedQuestionSourceId(chunk.getId(), question.getId()),
                            chunk.getId(), chunk.getKnowledgeId(), chunk.getKnowledgeBaseId(),
                            KnowledgeIndexContent.build(knowledge, question.getQuestion()), true));
                }
            }
        }
        vectorStore.deleteByChunkId(ids);
        int embedBatch = embedBatchSize();
        for (int from = 0; from < rows.size(); from += embedBatch) {
            int to = Math.min(from + embedBatch, rows.size());
            List<VectorStoreService.IndexRow> batchRows = rows.subList(from, to);
            List<String> texts = new ArrayList<>(batchRows.size());
            for (VectorStoreService.IndexRow row : batchRows) {
                texts.add(row.content());
            }
            List<float[]> vectors;
            try {
                vectors = embedder.embedBatch(cfg, texts);
            } catch (Exception e) {
                throw new BizException(AppError.badRequest(
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            vectorStore.saveIndexRows(batchRows, vectors);
        }
    }

    /**
     * 对照 KB.NeedsEmbeddingModel（types/knowledgebase.go L848）：
     * {@code IndexingStrategy.NeedsEmbedding() = vector_enabled || keyword_enabled}。
     * Go 的 IndexingStrategy 是值类型 struct：DB NULL 列 Scan 跳过后为零值（全 false）→
     * false，<b>没有</b> isZero→Default 钩子（那是服务读路径 EnsureDefaults 的独立语义）。
     * 2026-09-22 走查批踩坑：误套钩子会把「显式全 false 的策略」翻成 Default（vector+
     * keyword 全开），让策略关的 KB 走进真实出站。
     */
    private static boolean needsEmbedding(KnowledgeBase kb) {
        KbIndexingStrategy strategy = kb.getIndexingStrategy();
        return strategy != null && (strategy.isVectorEnabled() || strategy.isKeywordEnabled());
    }

    /** kb 行（仅 id，无租户过滤——Go GetKnowledgeBaseByID 同款；软删不可见）。 */
    private KnowledgeBase findKbRow(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 解析 chunk.metadata 的 generated_questions（失败 → null，对照 Go 的 err 忽略）。 */
    private static DocumentChunkMetadata chunkDocumentMetadata(Chunk chunk) {
        JsonNode meta = chunk.getMetadata();
        if (meta == null || meta.isNull()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(meta, DocumentChunkMetadata.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 对照 Go batch.go 的 BatchEmbedSize（BATCH_EMBED_SIZE env，默认 5，非法值照抄 Atoi 文案）。 */
    static int embedBatchSize() {
        String env = System.getenv("BATCH_EMBED_SIZE");
        if (env == null || env.isEmpty()) {
            return 5;
        }
        try {
            return Integer.parseInt(env.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "strconv.Atoi: parsing \"" + env + "\": invalid syntax");
        }
    }
}
