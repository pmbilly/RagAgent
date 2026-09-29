package com.ragagent.knowledge.task;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
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
import com.ragagent.chatpipeline.ChatManage.NameSpace;
import com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository;
import com.ragagent.common.context.TracingContext;
import com.ragagent.embedding.Embedder;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.tracing.langfuse.LangfuseTaskScope;
import com.ragagent.wiki.service.WikiIngestService;
import com.ragagent.wiki.service.WikiIngestService.EnqueueResult;
import com.ragagent.wiki.service.WikiKnowledgeFinalizer;
import com.ragagent.tracing.langfuse.LangfuseTracing;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import com.ragagent.knowledge.domain.QuestionBatchPayload;
import com.ragagent.knowledge.task.ChunkExtractTaskQueue;
import com.ragagent.knowledge.task.QuestionGenerationTaskQueue;
import com.ragagent.knowledge.support.GraphChunkSelector;
import com.ragagent.knowledge.support.KnowledgeIndexContent;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.service.KnowledgeVectorWrites;
import com.ragagent.knowledge.support.QuestionBatchPlanner;
import com.ragagent.knowledge.service.SpanTracker;
import com.ragagent.knowledge.service.VectorStoreService;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.knowledge.client.EmbedderClient;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.storage.TenantFileStorage;

/**
 * 知识处理后台 worker：虚拟线程队列消费 knowledge 的解析主链路
 * （接管 → 预清理 → 取文本 → 分块落库 → 向量化 → 富化 fan-out / 完成），对外仅暴露
 * {@link #enqueue(String)}。跨线程显式传值（TenantContext 不共享，各阶段自行解析租户）。
 * <p>状态机：pending →(CAS)→ processing →（无富化子任务）→ completed +
 * enable_status=enabled + processed_at；任一步失败 → failed + error_message；
 * deleting/cancelled 检查点短路。</p>
 * <p>三个关键不变量：</p>
 * <ol>
 *   <li><b>索引文本形态</b>：嵌入文本与 embeddings.content 一致，均为
 *       title 前缀 + ContextHeader + trim 正文（BM25 与向量评分对象必须同源）；</li>
 *   <li><b>重处理预清理</b>：先删旧 chunks 行（无条件）+ 全部向量行（仅向量化启用
 *       且模型可用时），失败清理只清本次产物；模型解析失败发生在预清理之前——
 *       既有数据保持不动；</li>
 *   <li><b>wiki 交接</b>：wiki_enabled 且有文本 chunk 时 processing 原子晋升
 *       finalizing（pending_subtasks_count 由子任务持有）再入队 ingest，
 *       由 DefaultWikiKnowledgeFinalizer 递减并晋升 completed。</li>
 * </ol>
 */
@Service
public class KnowledgeProcessWorker implements KnowledgeService.KnowledgeProcessWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProcessWorker.class);

    /** 任务观测的 span/根名。 */
    static final String TASK_TYPE_DOCUMENT_PROCESS = "document:process";

    /**
     * 批大小来自 BATCH_EMBED_SIZE env，空 → 5，非法值 → 报错。
     * strconv.Atoi 文案——会落进 knowledge 的 error_message）。走查实案：
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
    /** A3-3 尾批：租户感知文件存储（读 provider 引用；本地契约不变）。 */
    private final TenantFileStorage fileStorage;
    private final DocReaderClient docReader;
    private final EmbedderClient embedder;
    private final VectorStoreService vectorStore;
    private final ModelService modelService;
    private final KnowledgeVectorWrites vectorWrites;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final KnowledgeService knowledgeService;
    private final SpanTracker spanTracker;
    /** 图库仓储（D 批）：重处理前清旧图谱。 */
    private final RetrieveGraphRepository graphRepository;
    /** wiki 交接；
     *  ObjectProvider 装配：wiki 域与 knowledge 域互不反向依赖，延迟解析更稳。 */
    private final org.springframework.beans.factory.ObjectProvider<
            WikiIngestService> wikiIngestService;
    private final org.springframework.beans.factory.ObjectProvider<
            WikiKnowledgeFinalizer> wikiKnowledgeFinalizer;
    /** 分块图抽取队列（D 批；未接线时 fan-out 直接释放槽位，行不搁浅）。 */
    private final org.springframework.beans.factory.ObjectProvider<
            ChunkExtractTaskQueue> chunkExtractQueue;
    /** 问题生成批队列（W5γ5.19 导入后自动生成；未接线时 fan-out 直接释放槽位，行不搁浅）。 */
    private final org.springframework.beans.factory.ObjectProvider<
            QuestionGenerationTaskQueue> questionGenerationQueue;

    public KnowledgeProcessWorker(KnowledgeMapper knowledgeMapper,
                                  KnowledgeBaseMapper kbMapper,
                                  ChunkMapper chunkMapper,
                                  LocalStorageService storage,
                                  TenantFileStorage fileStorage,
                                  DocReaderClient docReader,
                                  EmbedderClient embedder,
                                  VectorStoreService vectorStore,
                                  ModelService modelService,
                                  KnowledgeVectorWrites vectorWrites,
                                  ModelRuntimeFactory modelRuntimeFactory,
                                  @org.springframework.context.annotation.Lazy KnowledgeService knowledgeService,
                                  SpanTracker spanTracker,
                                  RetrieveGraphRepository graphRepository,
                                  org.springframework.beans.factory.ObjectProvider<
                                          WikiIngestService> wikiIngestService,
                                  org.springframework.beans.factory.ObjectProvider<
                                          WikiKnowledgeFinalizer> wikiKnowledgeFinalizer,
                                  org.springframework.beans.factory.ObjectProvider<
                                          ChunkExtractTaskQueue> chunkExtractQueue,
                                  org.springframework.beans.factory.ObjectProvider<
                                          QuestionGenerationTaskQueue> questionGenerationQueue) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.docReader = docReader;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
        this.modelService = modelService;
        this.vectorWrites = vectorWrites;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.knowledgeService = knowledgeService;
        this.spanTracker = spanTracker;
        this.graphRepository = graphRepository;
        this.wikiIngestService = wikiIngestService;
        this.wikiKnowledgeFinalizer = wikiKnowledgeFinalizer;
        this.chunkExtractQueue = chunkExtractQueue;
        this.questionGenerationQueue = questionGenerationQueue;
    }

    @Override
    public void enqueue(String knowledgeId) {
        // 入队侧注入：在提交线程
        // （HTTP 请求线程）capture 当前 traceparent，随任务带到 worker 线程续接同一棵树。
        TracingContext tracing =
                LangfuseTracing.inject();
        executor.submit(() -> process(knowledgeId, tracing));
    }

    /**
     * 任务入口：续接上游 trace（无则开独立根）
     * + 包一个 document:process span；worker 线程归还前由 scope.close() 清上下文。
     */
    private void process(String knowledgeId,
                         TracingContext tracing) {
        LangfuseTaskScope scope =
                LangfuseTaskScope.start(
                        TASK_TYPE_DOCUMENT_PROCESS, tracing,
                        java.util.Map.of("knowledge_id", knowledgeId),
                        LangfuseTaskScope.previewPayload(knowledgeId));
        try {
            processInner(knowledgeId, scope);
        } finally {
            scope.close();
        }
    }

    private void processInner(String knowledgeId,
                              LangfuseTaskScope scope) {
        // CAS pending → processing：被抢/已取消则静默退出
        int updated = knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .eq("parse_status", Knowledge.PARSE_PENDING)
                .set("parse_status", Knowledge.PARSE_PROCESSING)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            return;
        }
        // 分配本 attempt 的 span 树（best-effort——追踪器绝不阻断处理）
        int attempt = 0;
        try {
            attempt = spanTracker.openAttempt(knowledgeId,
                    LangfuseTracing.currentTraceId()).attempt();
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] openAttempt failed kid={}: {}", knowledgeId, e.toString());
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
            EmbedderClient.EmbedConfig embedConfig = resolveEmbedConfig(k, kb);
            preCleanKnowledge(k, kb, embedConfig, knowledgeId);
            try {
                String markdown = extractMarkdown(k, attempt, knowledgeId);
                if (knowledgeMapper.selectById(knowledgeId).isAborted()) {
                    return; // 中途取消检查点
                }
                List<Chunk> chunks = chunkAndPersist(k, kb, knowledgeId, markdown, attempt);
                embedChunks(k, kb, embedConfig, chunks, knowledgeId, attempt);
                skipStageSpan(attempt, knowledgeId, KnowledgeProcessingSpan.STAGE_MULTIMODAL, "skipped");
                finalizeProcessing(k, kb, knowledgeId, attempt, chunks);
            } catch (Exception inner) {
                // 失败清场：本次 chunks + 向量行（向量化未启用时只清 chunks）
                chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeId, knowledgeId));
                deleteKnowledgeVectors(k, kb, embedConfig, knowledgeId);
                throw inner;
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            // 处理体抛错 → span/根记 outcome=error（scope.finish 幂等，随后的 close 不会覆盖）
            scope.finish("error", message);
            log.warn("process knowledge {} failed: {}", knowledgeId, e.toString());
            failOrComplete(knowledgeId, attempt, message);
        }
    }

    /** 预清理：删旧 chunks 行（无条件）+ 该知识全部向量行（仅向量化启用且模型可用）+ 旧图谱（失败仅告警）。 */
    private void preCleanKnowledge(Knowledge k, KnowledgeBase kb,
            EmbedderClient.EmbedConfig embedConfig, String knowledgeId) {
        chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeId, knowledgeId));
        deleteKnowledgeVectors(k, kb, embedConfig, knowledgeId);
        deleteGraphData(k.getKnowledgeBaseId(), knowledgeId);
    }

    /** 取文本：manual 直接读 metadata.content；file 经 docreader 解析（阶段 span 记录文件信息与字符量）。 */
    private String extractMarkdown(Knowledge k, int attempt, String knowledgeId) throws Exception {
        if ("manual".equals(k.getType())) {
            return k.getMetadata() != null && k.getMetadata().hasNonNull("content")
                    ? k.getMetadata().get("content").asText() : "";
        }
        SpanTracker.SpanHandle docSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_DOC_READER,
                java.util.Map.of(
                        "file_type", k.getFileType() == null ? "" : k.getFileType(),
                        "file_name", k.getFileName() == null ? "" : k.getFileName()));
        String markdown;
        try {
            byte[] content = fileStorage.read(
                    k.getTenantId() == null ? 0L : k.getTenantId(), k.getFilePath());
            DocReaderClient.ParseResult parsed = docReader.read(
                    content, k.getFileName(), k.getFileType(), k.getTitle(), null);
            markdown = parsed.markdown();
        } catch (RuntimeException e) {
            failStageSpan(docSpan, "DOCREADER_FAILED",
                    e.getMessage() == null ? e.toString() : e.getMessage(), e);
            throw e;
        }
        endStageSpan(docSpan, java.util.Map.of("chars", markdown.length()));
        return markdown;
    }

    /** 分块并落库（KB 配置 0 值回退默认 512/80；行间 pre/next 双向链；阶段 span 记录写出量）。 */
    private List<Chunk> chunkAndPersist(Knowledge k, KnowledgeBase kb, String knowledgeId,
            String markdown, int attempt) {
        SpanTracker.SpanHandle chunkSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_CHUNKING, null);
        SplitterConfig cfg = toSplitterConfig(kb.getChunkingConfig());
        List<ParsedChunk> parsedChunks = Chunker.split(markdown, cfg);
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
        int totalChars = 0;
        for (Chunk c : chunks) {
            totalChars += c.getContent() == null ? 0 : c.getContent().length();
        }
        endStageSpan(chunkSpan, java.util.Map.of(
                "chunks_written", chunks.size(), "total_text_chars", totalChars));
        return chunks;
    }

    /**
     * 向量化：绑定外部 store 的 KB 走引擎口（embedding 与分批重试由引擎承担，本地 pg
     * 直连只服务未绑定 KB）；index 内容 = title + chunk 的 embeddingContent；向量化
     * 未启用 → embedding 阶段记 skip。
     */
    private void embedChunks(Knowledge k, KnowledgeBase kb, EmbedderClient.EmbedConfig embedConfig,
            List<Chunk> chunks, String knowledgeId, int attempt) throws Exception {
        if (embedConfig == null) {
            skipStageSpan(attempt, knowledgeId, KnowledgeProcessingSpan.STAGE_EMBEDDING, "skipped");
            return;
        }
        SpanTracker.SpanHandle embedSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_EMBEDDING,
                java.util.Map.of(
                        "chunks_to_embed", chunks.size(),
                        "model_id", k.getEmbeddingModelId() == null
                                ? "" : k.getEmbeddingModelId()));
        CompositeRetrieveEngine boundEngine =
                vectorWrites.boundEngine(kb);
        if (boundEngine != null) {
            Embedder embedderRuntime =
                    modelRuntimeFactory.getEmbeddingModel(kb.getEmbeddingModelId());
            List<IndexInfo> items =
                    new ArrayList<>(chunks.size());
            for (Chunk c : chunks) {
                IndexInfo item =
                        new IndexInfo();
                item.sourceId = c.getId();
                item.sourceType = EngineTypes.SOURCE_TYPE_FILE;
                item.chunkId = c.getId();
                item.knowledgeId = k.getId();
                item.knowledgeBaseId = k.getKnowledgeBaseId();
                item.knowledgeType = kb.getType();
                item.tagId = ""; // 不设 TagID（零值语义）
                item.content = KnowledgeIndexContent.build(k, c.embeddingContent());
                item.isEnabled = true;
                items.add(item);
            }
            try {
                boundEngine.batchIndex(embedderRuntime, items);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
            }
        } else {
            List<VectorStoreService.IndexRow> rows = new ArrayList<>(chunks.size());
            List<String> texts = new ArrayList<>(chunks.size());
            for (Chunk c : chunks) {
                String text = KnowledgeIndexContent.build(k, c.embeddingContent());
                texts.add(text);
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
        endStageSpan(embedSpan, java.util.Map.of(
                "chunks_embedded", chunks.size()));
    }

    /** 富化 fan-out 的判定结果（三项独立判定：wiki 关、图开也要跑）。 */
    private record FinalizingPlan(boolean wiki, boolean graph, boolean question,
            List<Chunk> graphChunks, List<Chunk> questionChunks, int questionBatchCount) {
    }

    private FinalizingPlan planFinalizing(KnowledgeBase kb, List<Chunk> chunks) {
        boolean wiki = kb.getIndexingStrategy() != null
                && kb.getIndexingStrategy().isWikiEnabled()
                && !chunks.isEmpty();
        List<Chunk> graphChunks = kb.getIndexingStrategy() != null
                && kb.getIndexingStrategy().isGraphEnabled()
                        ? GraphChunkSelector.selectGraphChunks(chunks)
                        : List.of();
        boolean questionEnabled = kb.getQuestionGenerationConfig() != null
                && kb.getQuestionGenerationConfig().path("enabled").asBoolean(false);
        boolean kbNeedsEmbedding = kb.getIndexingStrategy() != null
                && (kb.getIndexingStrategy().isVectorEnabled()
                        || kb.getIndexingStrategy().isKeywordEnabled());
        List<Chunk> questionChunks = questionEnabled && kbNeedsEmbedding
                && !chunks.isEmpty()
                        ? QuestionBatchPlanner.selectQuestionChunks(chunks)
                        : List.of();
        int questionBatchCount = QuestionBatchPlanner.batchCount(questionChunks.size());
        return new FinalizingPlan(wiki, !graphChunks.isEmpty(),
                questionBatchCount > 0, graphChunks, questionChunks, questionBatchCount);
    }

    /**
     * 收尾：有富化（wiki/图谱/问题生成任一）→ 索引完成推进启用态、（有摘要模型时）
     * 摘要 fan-out、processing 原子晋升 finalizing（pending_subtasks_count = wiki 槽 +
     * 问题批数 + 图分块数，摘要槽不计入）后入队各子任务；promote 失败 = 行被 cancel/
     * delete 抢走 → 跳过富化不覆盖状态。无富化 → 直接完成/失败收口。
     */
    private void finalizeProcessing(Knowledge k, KnowledgeBase kb, String knowledgeId,
            int attempt, List<Chunk> chunks) {
        FinalizingPlan plan = planFinalizing(kb, chunks);
        if (!plan.wiki() && !plan.graph() && !plan.question()) {
            failOrComplete(knowledgeId, attempt, null);
            return;
        }
        markIndexedEnabled(knowledgeId);
        SpanTracker.SpanHandle postSpan = beginStageSpan(attempt, knowledgeId,
                KnowledgeProcessingSpan.STAGE_POST_PROCESS, null);
        if (hasSummaryModel(kb)) {
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .set("summary_status", "none")
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
            spawnSummaryFanOut(knowledgeId);
        }
        int pendingSubtasks = (plan.wiki() ? 1 : 0) + plan.questionBatchCount()
                + plan.graphChunks().size();
        if (promoteFinalizing(knowledgeId, pendingSubtasks)) {
            if (plan.wiki()) {
                enqueueWikiIngest(knowledgeId, k);
            }
            if (plan.graph()) {
                enqueueGraphExtracts(knowledgeId, k, kb, plan.graphChunks(), attempt);
            }
            if (plan.question()) {
                enqueueQuestionBatches(knowledgeId, k, kb, plan.questionChunks(), attempt);
            }
        }
        endStageSpan(postSpan, null);
        // root 收口：wiki 交由 finalizing 计数兜底，但本 attempt 的 post-process 已完成
        // ——不收口会让 trace 的「知识处理」永远显示计时中
        if (attempt > 0) {
            spanTracker.finalizeAttempt(knowledgeId, attempt,
                    KnowledgeProcessingSpan.STATUS_DONE, null, "", "");
        }
    }

    /** 清该知识在源 KB 命名空间下的旧图谱（失败仅告警——图里可能本来就没有这条知识）。 */
    private void deleteGraphData(String knowledgeBaseId, String knowledgeId) {
        try {
            graphRepository.delGraph(List.of(new NameSpace(
                    knowledgeBaseId, knowledgeId)));
        } catch (RuntimeException e) {
            log.warn("Failed to delete existing graph data (may not exist): {}", e.toString());
        }
    }

    /**
     * 向量化判定 + 模型解析。
     */
    /**
     * 知识删除/重处理的向量行清理——2026-09-25 写链改道：绑定 store 的 KB 走引擎口
     * （经
     * GetEmbeddingModel → DeleteByKnowledgeIDList）；未绑定保持 pg 直连（模型缺失时
     */
    private void deleteKnowledgeVectors(Knowledge k, KnowledgeBase kb,
                                        EmbedderClient.EmbedConfig embedConfig, String knowledgeId) {
        CompositeRetrieveEngine boundEngine = vectorWrites.boundEngine(kb);
        if (boundEngine != null) {
            try {
                Embedder emb =
                        modelRuntimeFactory.getEmbeddingModel(kb.getEmbeddingModelId());
                boundEngine.deleteByKnowledgeIdList(List.of(knowledgeId),
                        emb.getDimensions(), kb.getType());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
            }
            return;
        }
        if (embedConfig != null) {
            vectorStore.deleteByKnowledgeId(List.of(knowledgeId));
        }
    }

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

    /** KB 配置 → chunker 配置（0 值回退默认：512/80/separators） */
    private static SplitterConfig toSplitterConfig(
            KnowledgeBaseChunkingConfig kbc) {
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

    /** KB 是否配置了摘要模型。 */
    private static boolean hasSummaryModel(KnowledgeBase kb) {
        return kb != null && kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty();
    }

    /**
     * 摘要 fan-out：
     * 仅当有文本 chunk 时派发。条件化派发的背景见 {@code failOrComplete} 注释
     * （Java 进程内 worker 会真实处理，无条件派发会污染契约测试的 HTTP 快照）。
     */
    private void spawnSummaryFanOut(String knowledgeId) {
        long textChunkCount = chunkMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeId, knowledgeId)
                        .eq(Chunk::getChunkType, "text"));
        if (textChunkCount <= 0) {
            return;
        }
        try {
            knowledgeService.requestPostProcessSummaryGeneration(knowledgeId);
        } catch (RuntimeException e) {
            log.warn("Post-process summary fan-out failed for knowledge {}: {}",
                    knowledgeId, e.toString());
        }
    }

    /**
     * 索引完成的无条件推进：{@code enable_status=enabled}
     * + {@code processed_at}。{@code storage_size} 的 Java 侧计算未接线，保持既有形态。
     */
    private void markIndexedEnabled(String knowledgeId) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("enable_status", "enabled")
                .set("processed_at", OffsetDateTime.now(ZoneOffset.UTC))
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * processing 原子晋升 finalizing 并写入子任务计数的
     * 状态翻转部分：一次条件更新把 {@code processing} 原子翻到 {@code finalizing}，
     * 并置 {@code pending_subtasks_count=1}（wiki 子任务占用的那个槽）。
     * <p>返回 false = 行已不在 processing（cancel/delete 抢走）——调用方必须跳过
     * 富化且不得覆盖状态。</p>
     */
    private boolean promoteFinalizing(String knowledgeId, int pendingSubtasks) {
        int promoted = knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .eq("parse_status", Knowledge.PARSE_PROCESSING)
                .set("parse_status", Knowledge.PARSE_FINALIZING)
                .set("pending_subtasks_count", pendingSubtasks)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        if (promoted > 0) {
            log.info("[KnowledgePostProcess] Knowledge {} entered finalizing ({} subtask(s) pending)",
                    knowledgeId, pendingSubtasks);
        }
        return promoted > 0;
    }

    /**
     * 图抽取 fan-out：逐块入队 {@code chunk:extract}，{@code model_id}
     * worker 侧续接同一棵树。
     * <p>入队失败的槽位<b>立即释放</b>。</p>
     */
    private void enqueueGraphExtracts(String knowledgeId, Knowledge k, KnowledgeBase kb,
                                      List<Chunk> graphChunks, int attempt) {
        ChunkExtractTaskQueue queue = chunkExtractQueue.getIfAvailable();
        if (queue == null) {
            log.warn("[KnowledgePostProcess] chunk extract queue unavailable, releasing {} slot(s) for {}",
                    graphChunks.size(), knowledgeId);
            releaseSlots(knowledgeId, graphChunks.size());
            return;
        }
        TracingContext tracing =
                LangfuseTracing.inject();
        int index = 0;
        for (Chunk chunk : graphChunks) {
            try {
                queue.enqueue(ExtractChunkPayload.withTracing(k.getTenantId(), chunk.getId(),
                        kb.getSummaryModelId() == null ? "" : kb.getSummaryModelId(),
                        knowledgeId, attempt, index, tracing));
            } catch (RuntimeException e) {
                log.error("[KnowledgePostProcess] Failed to create chunk extract task for {}: {}",
                        chunk.getId(), e.toString());
                releaseSlots(knowledgeId, 1);
            }
            index++;
        }
    }

    /**
     * 问题生成 fan-out：按 {@link QuestionBatchPlanner#BATCH_SIZE} 分批入队
     * {@code question:generation}，载荷只带 chunk id（+ 边界邻块 id），worker 运行时装读内容。
     * <p>入队失败的批<b>立即释放</b>该批占用的槽位。</p>
     */
    private void enqueueQuestionBatches(String knowledgeId, Knowledge k, KnowledgeBase kb,
                                        List<Chunk> questionChunks, int attempt) {
        List<QuestionBatchPlanner.Batch> batches = QuestionBatchPlanner.planBatches(questionChunks);
        QuestionGenerationTaskQueue queue = questionGenerationQueue.getIfAvailable();
        if (queue == null) {
            log.warn("[KnowledgePostProcess] question generation queue unavailable, releasing {} slot(s) for {}",
                    batches.size(), knowledgeId);
            releaseSlots(knowledgeId, batches.size());
            return;
        }
        TracingContext tracing =
                LangfuseTracing.inject();
        int questionCount = kb.getQuestionGenerationConfig() == null ? 0
                : kb.getQuestionGenerationConfig().path("question_count").asInt(0);
        for (QuestionBatchPlanner.Batch batch : batches) {
            try {
                queue.enqueue(QuestionBatchPayload.withTracing(k.getTenantId(), kb.getId(), knowledgeId,
                        questionCount, "", attempt, batch.chunkIds(), batch.index(),
                        batch.prevChunkId(), batch.nextChunkId(), tracing));
            } catch (RuntimeException e) {
                log.error("[KnowledgePostProcess] Failed to enqueue question batch {} for {}: {}",
                        batch.index(), knowledgeId, e.toString());
                releaseSlots(knowledgeId, 1);
            }
        }
    }

    /** 释放 n 个 finalizing 槽（逐次递减+晋升，幂等到计数归零）。 */
    private void releaseSlots(String knowledgeId, int count) {
        for (int i = 0; i < count; i++) {
            releaseWikiSlot(knowledgeId);
        }
    }

    /**
     * op 落库 + 防抖触发。op 未被接受（如 KB 已删）或入队异常时
     * 避免行搁浅在 finalizing。触发失败只记警告——op 已落库，不从重追加
     * 。
     */
    private void enqueueWikiIngest(String knowledgeId, Knowledge k) {
        WikiIngestService service = wikiIngestService.getIfAvailable();
        if (service == null) {
            log.warn("[KnowledgePostProcess] Wiki ingest service unavailable, releasing slot for {}",
                    knowledgeId);
            releaseWikiSlot(knowledgeId);
            return;
        }
        try {
            EnqueueResult result = service
                    .enqueueWikiIngest(k.getTenantId(), k.getKnowledgeBaseId(), knowledgeId);
            if (result.accepted()) {
                log.info("[KnowledgePostProcess] Enqueued wiki ingest task for {}", knowledgeId);
                if (result.error() != null) {
                    log.warn("[KnowledgePostProcess] Wiki trigger enqueue failed for {}: {}",
                            knowledgeId, result.error().toString());
                }
            } else {
                log.warn("[KnowledgePostProcess] Wiki pending op not accepted for {}: {}",
                        knowledgeId, result.error() == null ? "" : result.error().toString());
                releaseWikiSlot(knowledgeId);
            }
        } catch (RuntimeException e) {
            log.warn("[KnowledgePostProcess] Wiki ingest enqueue failed for {}: {}",
                    knowledgeId, e.toString());
            releaseWikiSlot(knowledgeId);
        }
    }

    /**
     * 释放 wiki 槽（finalizer 的"递减+晋升"两步写）。
     * finalizer 缺席时静默——行由 finalizing housekeeping sweep 兜底。
     */
    private void releaseWikiSlot(String knowledgeId) {
        WikiKnowledgeFinalizer finalizer =
                wikiKnowledgeFinalizer.getIfAvailable();
        if (finalizer == null) {
            return;
        }
        try {
            finalizer.finalizeWikiSubtask(knowledgeId);
        } catch (RuntimeException e) {
            log.warn("[KnowledgePostProcess] Release wiki slot failed for {}: {}",
                    knowledgeId, e.toString());
        }
    }

    private void failOrComplete(String knowledgeId, int attempt, String error) {
        if (error == null) {
            // postprocess 阶段埋点
            SpanTracker.SpanHandle postSpan = beginStageSpan(attempt, knowledgeId,
                    KnowledgeProcessingSpan.STAGE_POST_PROCESS, null);
            // 索引完成即推进启用态（finalizing 前置）：
            // 索引完成 → summary_status=none（post-process fan-out 随后按需改 pending）。
            // 条件化（2026-09-23 走查批）：仅当 KB 配了 summary model 时推进——Java 的
            // 进程内 worker 在契约测试里会真实处理：
            // 录制进程内；无条件推进会让异步副作用污染 HTTP 快照断言（kg-manual-draft
            // 等 3 例实测红）。无 summary model 的 KB 因此不做 none/failed 中间态
            Knowledge row = knowledgeMapper.selectById(knowledgeId);
            KnowledgeBase rowKb = row == null ? null
                    : kbMapper.selectById(row.getKnowledgeBaseId());
            boolean summaryModelConfigured = hasSummaryModel(rowKb);
            UpdateWrapper<Knowledge> completeUpdate = new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .set("parse_status", Knowledge.PARSE_COMPLETED)
                    .set("enable_status", "enabled")
                    .set("processed_at", OffsetDateTime.now(ZoneOffset.UTC))
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC));
            if (summaryModelConfigured) {
                completeUpdate.set("summary_status", "none");
            }
            knowledgeMapper.update(null, completeUpdate);
            if (summaryModelConfigured) {
                spawnSummaryFanOut(knowledgeId);
            }
            endStageSpan(postSpan, null);
            // root 幂等收口 done
            if (attempt > 0) {
                spanTracker.finalizeAttempt(knowledgeId, attempt,
                        KnowledgeProcessingSpan.STATUS_DONE, null, "", "");
            }
        } else {
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", knowledgeId)
                    .set("parse_status", Knowledge.PARSE_FAILED)
                    .set("error_message", abbreviate(error))
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
            // 非阶段失败（模型解析/预清理等）没有 failSpan 收口 → 显式 finalize（幂等）
            if (attempt > 0) {
                spanTracker.finalizeAttempt(knowledgeId, attempt,
                        KnowledgeProcessingSpan.STATUS_FAILED, null, "", abbreviate(error));
            }
        }
    }

    // ── span 埋点辅助 ──

    private SpanTracker.SpanHandle beginStageSpan(int attempt, String knowledgeId,
                                                 String stage, java.util.Map<String, Object> input) {
        if (attempt <= 0) {
            return null;
        }
        return spanTracker.beginStage(knowledgeId, attempt, stage, input);
    }

    private void endStageSpan(SpanTracker.SpanHandle span, java.util.Map<String, Object> output) {
        if (span != null) {
            spanTracker.endSpan(span, output);
        }
    }

    private void failStageSpan(SpanTracker.SpanHandle span, String code, String message,
                               Throwable error) {
        if (span != null) {
            spanTracker.failSpan(span, code, message, error);
        }
    }

    /** 无 begin 记录时先合成一行再 skip（保 schema 不变量）。 */
    private void skipStageSpan(int attempt, String knowledgeId, String stage, String reason) {
        if (attempt <= 0) {
            return;
        }
        SpanTracker.SpanHandle span = spanTracker.lookupStage(knowledgeId, attempt, stage);
        if (span == null) {
            span = spanTracker.beginStage(knowledgeId, attempt, stage, null);
        }
        if (span != null) {
            spanTracker.skipSpan(span, reason);
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
