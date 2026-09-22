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
 *
 * <p><b>2026-09-22 走查修正（对齐 Go 处理管道的三个契约点）</b>：</p>
 * <ol>
 *   <li><b>索引文本形态</b>：嵌入文本与 embeddings.content 均为
 *       {@code buildKnowledgeIndexContent(knowledge, chunk.EmbeddingContent())}
 *       （title 前缀 + ContextHeader + trim 正文），此前 Java 侧 content 列写裸
 *       content 且嵌入文本无 title 前缀——BM25 关键词检索的评分对象与 Go 不一致。</li>
 *   <li><b>重处理预清理</b>（对照 Go processDocument L335-351）：先删旧 chunks 行
 *       （无条件）+ 删该 knowledge 的全部向量行（仅向量化启用且模型可用时），
 *       此前 Java 只删 chunks 行、embeddings 旧行残留（旧 chunk_id 的向量仍可被检索）。</li>
 *   <li><b>失败清理</b>（对照 Go L629-639）：处理链失败时删本次 chunks + 向量行。
 *       模型解析失败发生在预清理<b>之前</b>——既有数据保持不动（照抄 Go 顺序）。</li>
 * </ol>
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
    private final KnowledgeService knowledgeService;

    public KnowledgeProcessWorker(KnowledgeMapper knowledgeMapper,
                                  KnowledgeBaseMapper kbMapper,
                                  ChunkMapper chunkMapper,
                                  LocalStorageService storage,
                                  DocReaderClient docReader,
                                  EmbedderClient embedder,
                                  VectorStoreService vectorStore,
                                  ModelService modelService,
                                  @org.springframework.context.annotation.Lazy KnowledgeService knowledgeService) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.docReader = docReader;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
        this.modelService = modelService;
        this.knowledgeService = knowledgeService;
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

            // 1) 向量化判定 + 模型解析（对照 Go：模型缺失 → failed，此阶段尚未动既有数据）
            EmbedderClient.EmbedConfig embedConfig = resolveEmbedConfig(k, kb);

            // 2) 预清理（对照 Go processDocument L335-351）：删旧 chunks 行（无条件）+
            //    删该 knowledge 全部向量行（仅向量化启用且模型可用时）
            chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getKnowledgeId, knowledgeId));
            if (embedConfig != null) {
                vectorStore.deleteByKnowledgeId(List.of(knowledgeId));
            }

            try {
                // 3) 取文本：manual 直接取 metadata.content；file 经 docreader 解析
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

                // 4) 分块（对照 chunker.Split；KB 配置 0 值回退默认 512/80）
                SplitterConfig cfg = toSplitterConfig(kb.getChunkingConfig());
                List<ParsedChunk> parsedChunks = Chunker.split(markdown, cfg);

                // 5) 清旧写新（旧行已在预清理删除；对照 CreateChunks）
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

                // 6) 向量化（对照 processChunks 的 BatchIndex：indexContent =
                //    title + EmbeddingContent；先删后插的"删"已在预清理完成）
                if (embedConfig != null) {
                    List<VectorStoreService.IndexRow> rows = new ArrayList<>(chunks.size());
                    List<String> texts = new ArrayList<>(chunks.size());
                    for (Chunk c : chunks) {
                        String text = KnowledgeIndexContent.build(k, c.embeddingContent());
                        texts.add(text);
                        // tag_id 传 ""：对照 Go processChunks 的 IndexInfo 不设 TagID（零值）
                        rows.add(new VectorStoreService.IndexRow(
                                c.getId(), c.getId(), k.getId(), k.getKnowledgeBaseId(), text, true, ""));
                    }
                    int embedBatch = embedBatchSize();
                    for (int from = 0; from < rows.size(); from += embedBatch) {
                        int to = Math.min(from + embedBatch, rows.size());
                        List<float[]> vectors = embedder.embedBatch(embedConfig, texts.subList(from, to));
                        vectorStore.saveIndexRows(rows.subList(from, to), vectors);
                    }
                }

                // 7) 完成（无富化快路径：直接 completed + enabled）
                failOrComplete(knowledgeId, null);
            } catch (Exception inner) {
                // 对照 Go L629-639：失败时清本次 chunks + 向量行（向量化未启用时只清 chunks）
                chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeId, knowledgeId));
                if (embedConfig != null) {
                    vectorStore.deleteByKnowledgeId(List.of(knowledgeId));
                }
                throw inner;
            }
        } catch (Exception e) {
            log.warn("process knowledge {} failed: {}", knowledgeId, e.toString());
            failOrComplete(knowledgeId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 向量化判定 + 模型解析（对照 Go processDocument：vector/keyword 任一启用才取模型；
     * knowledge 行优先、回落 KB——Java 既有取数口径，已验收）。
     * 模型缺失时抛错，此时预清理尚未执行（既有数据不动，照抄 Go 顺序）。
     */
    private EmbedderClient.EmbedConfig resolveEmbedConfig(Knowledge k, KnowledgeBase kb) {
        if (!(kb.getIndexingStrategy().isVectorEnabled() || kb.getIndexingStrategy().isKeywordEnabled())) {
            return null;
        }
        String modelId = k.getEmbeddingModelId() == null || k.getEmbeddingModelId().isEmpty()
                ? kb.getEmbeddingModelId() : k.getEmbeddingModelId();
        if (modelId == null || modelId.isEmpty()) {
            throw new IllegalStateException("embedding model is not configured");
        }
        Model model = modelService.getByIdVisible(k.getTenantId(), modelId);
        if (model == null) {
            throw new IllegalStateException("embedding model not found: " + modelId);
        }
        return EmbedderClient.configFrom(model);
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
            // 对照 Go finalizeIndexedKnowledgeState（knowledge_process.go L215-242）：
            // 索引完成 → summary_status=none（post-process fan-out 随后按需改 pending）。
            // 条件化（2026-09-23 走查批）：仅当 KB 配了 summary model 时推进——Java 的
            // 进程内 worker 在契约测试里会真实处理，而 Go 录制环境的 asynq worker 不在
            // 录制进程内；无条件推进会让异步副作用污染 HTTP 快照断言（kg-manual-draft
            // 等 3 例实测红）。无 summary model 的 KB 因此不做 none/failed 中间态
            // （Go 真实运行会推 failed），差异记录于 known-issues。
            Knowledge row = knowledgeMapper.selectById(knowledgeId);
            KnowledgeBase rowKb = row == null ? null
                    : kbMapper.selectById(row.getKnowledgeBaseId());
            boolean hasSummaryModel = rowKb != null && rowKb.getSummaryModelId() != null
                    && !rowKb.getSummaryModelId().isEmpty();
            long textChunkCount = chunkMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                            .eq(Chunk::getKnowledgeId, knowledgeId)
                            .eq(Chunk::getChunkType, "text"));
            UpdateWrapper<Knowledge> completeUpdate = new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .set("parse_status", Knowledge.PARSE_COMPLETED)
                    .set("enable_status", "enabled")
                    .set("processed_at", OffsetDateTime.now(ZoneOffset.UTC))
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC));
            if (hasSummaryModel) {
                completeUpdate.set("summary_status", "none");
            }
            knowledgeMapper.update(null, completeUpdate);
            if (hasSummaryModel && textChunkCount > 0) {
                // post-process 摘要 fan-out（对照 knowledge_post_process.go L208
                // willSpawnSummary = len(textChunks) > 0 → L562 入队摘要任务）
                try {
                    knowledgeService.requestPostProcessSummaryGeneration(knowledgeId);
                } catch (RuntimeException e) {
                    log.warn("Post-process summary fan-out failed for knowledge {}: {}",
                            knowledgeId, e.toString());
                }
            }
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
