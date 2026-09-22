package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.chunker.Chunker;
import com.ragagent.knowledge.chunker.ParsedChunk;
import com.ragagent.knowledge.chunker.SplitterConfig;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 文档处理 worker（对照 Go knowledge_process.go ProcessDocument 主链路的
 * 进程内实现：asynq → 虚拟线程队列；富化扇出/多模态/图谱/问题生成随后续阶段）。
 *
 * 状态机（对照状态图）：
 *   pending →(CAS)→ processing →（无富化子任务快路径，对照 postprocess expectedSubtasks==0）
 *   → completed + enable_status=enabled + processed_at
 *   任一步失败 → failed + error_message
 *   deleting/cancelled 检查点短路（对照 isKnowledgeAborted）
 */
@Service
public class KnowledgeProcessWorker implements KnowledgeService.KnowledgeProcessWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProcessWorker.class);

    /**
     * 对照 Go batch.go：BATCH_EMBED_SIZE env，空 → 5，非法值 → 报错（照抄
     * strconv.Atoi 文案——会落进 knowledge 的 error_message）。走查实案：
     * 硬编码 40 会被 dashscope 拒（batch size 上限 20），Go 默认 5 无此问题。
     */
    private static int embedBatchSize() {
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

    private final java.util.concurrent.ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final LocalStorageService storage;
    private final DocReaderClient docReader;
    private final EmbedderClient embedder;
    private final VectorStoreService vectorStore;
    private final ModelService modelService;

    public KnowledgeProcessWorker(KnowledgeMapper knowledgeMapper,
                                  KnowledgeBaseMapper kbMapper,
                                  ChunkMapper chunkMapper,
                                  LocalStorageService storage,
                                  DocReaderClient docReader,
                                  EmbedderClient embedder,
                                  VectorStoreService vectorStore,
                                  ModelService modelService) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.docReader = docReader;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
        this.modelService = modelService;
    }

    @Override
    public void enqueue(String knowledgeId) {
        executor.submit(() -> process(knowledgeId));
    }

    private void process(String knowledgeId) {
        // CAS pending → processing（对照 markKnowledgeProcessing 的条件更新语义）
        int updated = knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .eq("parse_status", Knowledge.PARSE_PENDING)
                .set("parse_status", Knowledge.PARSE_PROCESSING)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            return; // 已被抢或已取消
        }
        try {
            Knowledge k = knowledgeMapper.selectById(knowledgeId);
            if (k == null || k.isAborted()) {
                return;
            }
            KnowledgeBase kb = kbMapper.selectById(k.getKnowledgeBaseId());
            if (kb == null) {
                throw new IllegalStateException("knowledge base not found");
            }

            // 1. 取文本：manual 直接取 metadata.content；file 经 docreader 解析
            String markdown;
            if ("manual".equals(k.getType())) {
                markdown = k.getMetadata() != null && k.getMetadata().hasNonNull("content")
                        ? k.getMetadata().get("content").asText() : "";
            } else {
                byte[] content = storage.read(k.getFilePath());
                DocReaderClient.ParseResult parsed = docReader.read(
                        content, k.getFileName(), k.getFileType(), k.getTitle(), null);
                markdown = parsed.markdown();
            }
            if (knowledgeMapper.selectById(knowledgeId).isAborted()) {
                return; // 检查点（对照 processChunks 内 4 检查点的精简）
            }

            // 2. 分块（对照 chunker.Split；KB 配置 0 值回退默认 512/80）
            SplitterConfig cfg = toSplitterConfig(kb.getChunkingConfig());
            List<ParsedChunk> parsedChunks = Chunker.split(markdown, cfg);

            // 3. 清旧写新（对照 DeleteChunksByKnowledgeID + CreateChunks）
            chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getKnowledgeId, knowledgeId));
            List<Chunk> chunks = new ArrayList<>(parsedChunks.size());
            String prevId = null;
            for (int i = 0; i < parsedChunks.size(); i++) {
                ParsedChunk pc = parsedChunks.get(i);
                Chunk c = new Chunk();
                c.setId(java.util.UUID.randomUUID().toString());
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                c.setCreatedAt(now);
                c.setUpdatedAt(now);
                c.setTenantId(k.getTenantId());
                c.setKnowledgeId(knowledgeId);
                c.setKnowledgeBaseId(k.getKnowledgeBaseId());
                c.setContent(pc.getContent());
                c.setSourceContent(pc.getContent());
                c.setContextHeader(pc.getContextHeader());
                c.setChunkIndex(i);
                c.setStartAt(pc.getStart());
                c.setEndAt(pc.getEnd());
                c.setChunkType("text");
                c.setPreChunkId(prevId);
                chunks.add(c);
                prevId = c.getId();
            }
            for (int i = 0; i < chunks.size(); i++) {
                if (i + 1 < chunks.size()) {
                    chunks.get(i).setNextChunkId(chunks.get(i + 1).getId());
                }
                chunkMapper.insert(chunks.get(i));
            }

            // 4. 向量化（对照 retrieveEngine.BatchIndex；无 embedding 模型 → failed）
            if (kb.getIndexingStrategy().isVectorEnabled() || kb.getIndexingStrategy().isKeywordEnabled()) {
                String modelId = k.getEmbeddingModelId().isEmpty()
                        ? kb.getEmbeddingModelId() : k.getEmbeddingModelId();
                if (modelId.isEmpty()) {
                    throw new IllegalStateException("embedding model is not configured");
                }
                Model model = modelService.getByIdVisible(k.getTenantId(), modelId);
                if (model == null) {
                    throw new IllegalStateException("embedding model not found: " + modelId);
                }
                EmbedderClient.EmbedConfig embedConfig = EmbedderClient.configFrom(model);
                int embedBatch = embedBatchSize();
                for (int from = 0; from < chunks.size(); from += embedBatch) {
                    List<Chunk> batch = chunks.subList(from, Math.min(from + embedBatch, chunks.size()));
                    List<String> texts = new ArrayList<>(batch.size());
                    for (Chunk c : batch) {
                        String header = c.getContextHeader();
                        texts.add(header == null || header.isEmpty() ? c.getContent() : header + "\n\n" + c.getContent());
                    }
                    List<float[]> vectors = embedder.embedBatch(embedConfig, texts);
                    vectorStore.batchSave(k, batch, vectors);
                }
            }

            // 5. 完成（无富化快路径：直接 completed + enabled）
            failOrComplete(knowledgeId, null);
        } catch (Exception e) {
            log.warn("process knowledge {} failed: {}", knowledgeId, e.toString());
            failOrComplete(knowledgeId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** KB 配置 → chunker 配置（0 值回退对照 Go 默认：512/80/separators） */
    private static SplitterConfig toSplitterConfig(
            com.ragagent.knowledge.domain.KbChunkingConfig kbc) {
        SplitterConfig cfg = new SplitterConfig();
        cfg.setChunkSize(kbc.getChunkSize() <= 0 ? SplitterConfig.DEFAULT_CHUNK_SIZE : kbc.getChunkSize());
        int overlap = kbc.getChunkOverlap() <= 0 ? SplitterConfig.DEFAULT_CHUNK_OVERLAP : kbc.getChunkOverlap();
        cfg.setChunkOverlap(Math.min(overlap, cfg.getChunkSize() / 2));
        cfg.setSeparators(kbc.getSeparators() == null ? SplitterConfig.DEFAULT_SEPARATORS : kbc.getSeparators());
        cfg.setStrategy(kbc.getStrategy() == null || kbc.getStrategy().isEmpty() ? null : kbc.getStrategy());
        cfg.setTokenLimit(kbc.getTokenLimit());
        cfg.setLanguages(kbc.getLanguages());
        return cfg;
    }

    private void failOrComplete(String knowledgeId, String error) {
        if (error == null) {
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .set("parse_status", Knowledge.PARSE_COMPLETED)
                    .set("enable_status", "enabled")
                    .set("processed_at", OffsetDateTime.now(ZoneOffset.UTC))
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        } else {
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .set("parse_status", Knowledge.PARSE_FAILED)
                    .set("error_message", abbreviate(error))
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
