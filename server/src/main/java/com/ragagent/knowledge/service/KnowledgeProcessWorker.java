package com.ragagent.knowledge.service;

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
 * <p><b>wiki 交接（2026-09-24 接线，对照 Go knowledge_post_process.go）</b>：
 * KB 的 indexing_strategy.wiki_enabled 且产出文本 chunk 时，processing 原子翻到
 * {@code finalizing}（pending_subtasks_count=1 由 wiki 子任务持有）并入队 ingest；
 * wiki 生成完成后由 {@code DefaultWikiKnowledgeFinalizer} 递减并晋升 completed。
 * 此前该分支缺失——文档直接 completed，wiki 任务从不入队（除目录占位页外无产出）。</p>
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

    /** 对照 Go {@code types.TypeDocumentProcess}：任务观测的 span/根名（{@code asynq.<type>}）。 */
    static final String TASK_TYPE_DOCUMENT_PROCESS = "document:process";

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
    /** A3-3 尾批：租户感知文件存储（读 provider 引用；本地契约不变）。 */
    private final TenantFileStorage fileStorage;
    private final DocReaderClient docReader;
    private final EmbedderClient embedder;
    private final VectorStoreService vectorStore;
    private final ModelService modelService;
    private final KnowledgeVectorWrites vectorWrites;
    private final com.ragagent.model.service.ModelRuntimeFactory modelRuntimeFactory;
    private final KnowledgeService knowledgeService;
    private final SpanTracker spanTracker;
    /** 图库仓储（D 批）：重处理前清旧图谱（对照 Go processDocument L354-359）。 */
    private final com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository graphRepository;
    /** wiki 交接（对照 Go knowledge_post_process.go 的 willSpawnWiki 分支）；
     *  ObjectProvider 装配：wiki 域与 knowledge 域互不反向依赖，延迟解析更稳。 */
    private final org.springframework.beans.factory.ObjectProvider<
            com.ragagent.wiki.service.WikiIngestService> wikiIngestService;
    private final org.springframework.beans.factory.ObjectProvider<
            com.ragagent.wiki.service.WikiKnowledgeFinalizer> wikiKnowledgeFinalizer;
    /** 分块图抽取队列（D 批；未接线时 fan-out 直接释放槽位，行不搁浅）。 */
    private final org.springframework.beans.factory.ObjectProvider<
            com.ragagent.knowledge.service.ChunkExtractTaskQueue> chunkExtractQueue;
    /** 问题生成批队列（W5γ5.19 导入后自动生成；未接线时 fan-out 直接释放槽位，行不搁浅）。 */
    private final org.springframework.beans.factory.ObjectProvider<
            com.ragagent.knowledge.service.QuestionGenerationTaskQueue> questionGenerationQueue;

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
                                  com.ragagent.model.service.ModelRuntimeFactory modelRuntimeFactory,
                                  @org.springframework.context.annotation.Lazy KnowledgeService knowledgeService,
                                  SpanTracker spanTracker,
                                  com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository graphRepository,
                                  org.springframework.beans.factory.ObjectProvider<
                                          com.ragagent.wiki.service.WikiIngestService> wikiIngestService,
                                  org.springframework.beans.factory.ObjectProvider<
                                          com.ragagent.wiki.service.WikiKnowledgeFinalizer> wikiKnowledgeFinalizer,
                                  org.springframework.beans.factory.ObjectProvider<
                                          com.ragagent.knowledge.service.ChunkExtractTaskQueue> chunkExtractQueue,
                                  org.springframework.beans.factory.ObjectProvider<
                                          com.ragagent.knowledge.service.QuestionGenerationTaskQueue> questionGenerationQueue) {
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
        // 入队侧注入（对照 Go 的 langfuse.InjectTracing(ctx, &taskPayload)）：在提交线程
        // （HTTP 请求线程）capture 当前 traceparent，随任务带到 worker 线程续接同一棵树。
        com.ragagent.common.context.TracingContext tracing =
                com.ragagent.tracing.langfuse.LangfuseTracing.inject();
        executor.submit(() -> process(knowledgeId, tracing));
    }

    /**
     * 任务入口（对照 Go asynq 的 {@code AsynqMiddleware}）：续接上游 trace（无则开独立根）
     * + 包一个 {@code asynq.document:process} span；worker 线程归还前由 scope.close() 清上下文（§5）。
     */
    private void process(String knowledgeId,
                         com.ragagent.common.context.TracingContext tracing) {
        com.ragagent.tracing.langfuse.LangfuseTaskScope scope =
                com.ragagent.tracing.langfuse.LangfuseTaskScope.start(
                        TASK_TYPE_DOCUMENT_PROCESS, tracing,
                        java.util.Map.of("knowledge_id", knowledgeId),
                        com.ragagent.tracing.langfuse.LangfuseTaskScope.previewPayload(knowledgeId));
        try {
            processInner(knowledgeId, scope);
        } finally {
            scope.close();
        }
    }

    private void processInner(String knowledgeId,
                              com.ragagent.tracing.langfuse.LangfuseTaskScope scope) {
        // CAS pending → processing（对照 markKnowledgeProcessing 的条件更新语义）
        int updated = knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .eq("parse_status", Knowledge.PARSE_PENDING)
                .set("parse_status", Knowledge.PARSE_PROCESSING)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            return; // 已被抢或已取消
        }
        // 对照 Go processDocument L3355-3365：分配本 attempt 的 span 树（payload.Attempt
        // 缺省时 OpenAttempt；best-effort——追踪器绝不阻断处理）。trace id 取续接后的
        // 活跃帧（C 批接线前恒 ""），使落库的 span 树与 Langfuse 走同一棵。
        int attempt = 0;
        try {
            attempt = spanTracker.openAttempt(knowledgeId,
                    com.ragagent.tracing.langfuse.LangfuseTracing.currentTraceId()).attempt();
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

            // 1) 向量化判定 + 模型解析（对照 Go：模型缺失 → failed，此阶段尚未动既有数据）
            EmbedderClient.EmbedConfig embedConfig = resolveEmbedConfig(k, kb);

            // 2) 预清理（对照 Go processDocument L335-351）：删旧 chunks 行（无条件）+
            //    删该 knowledge 全部向量行（仅向量化启用且模型可用时）
            chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getKnowledgeId, knowledgeId));
            deleteKnowledgeVectors(k, kb, embedConfig, knowledgeId);
            // 3) 旧图谱数据（对照 Go processDocument L354-359：DelGraph 失败只记警告，
            //    不阻断重处理——图里可能本来就没有这条知识）
            deleteGraphData(k.getKnowledgeBaseId(), knowledgeId);

            try {
                // 3) 取文本：manual 直接取 metadata.content；file 经 docreader 解析
                //    （对照 Go 的 docreader 阶段埋点 L3712-3827：仅文件路径记录该阶段）
                String markdown;
                SpanTracker.SpanHandle docSpan;
                if ("manual".equals(k.getType())) {
                    markdown = k.getMetadata() != null && k.getMetadata().hasNonNull("content")
                            ? k.getMetadata().get("content").asText() : "";
                } else {
                    docSpan = beginStageSpan(attempt, knowledgeId,
                            KnowledgeProcessingSpan.STAGE_DOC_READER,
                            java.util.Map.of(
                                    "file_type", k.getFileType() == null ? "" : k.getFileType(),
                                    "file_name", k.getFileName() == null ? "" : k.getFileName()));
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
                }
                if (knowledgeMapper.selectById(knowledgeId).isAborted()) {
                    return; // 检查点（对照 processChunks 内 4 检查点的精简）
                }

                // 4) 分块（对照 chunker.Split；KB 配置 0 值回退默认 512/80）
                //    + 5) 清旧写新（对照 Go 的 chunking 阶段埋点 L532-548）
                SpanTracker.SpanHandle chunkSpan = beginStageSpan(attempt, knowledgeId,
                        KnowledgeProcessingSpan.STAGE_CHUNKING, null);
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
                int totalChars = 0;
                for (Chunk c : chunks) {
                    totalChars += c.getContent() == null ? 0 : c.getContent().length();
                }
                endStageSpan(chunkSpan, java.util.Map.of(
                        "chunks_written", chunks.size(), "total_text_chars", totalChars));

                // 6) 向量化（对照 processChunks 的 BatchIndex：indexContent =
                //    title + EmbeddingContent；先删后插的"删"已在预清理完成）
                //    + embedding / multimodal 阶段埋点（对照 Go L564-705）
                if (embedConfig != null) {
                    SpanTracker.SpanHandle embedSpan = beginStageSpan(attempt, knowledgeId,
                            KnowledgeProcessingSpan.STAGE_EMBEDDING,
                            java.util.Map.of(
                                    "chunks_to_embed", chunks.size(),
                                    "model_id", k.getEmbeddingModelId() == null
                                            ? "" : k.getEmbeddingModelId()));
                    // 绑定外部 store 的 KB 走引擎口（Go processChunks 经 retriever 路由）：
                    // embedding + 40/10 分批重试由引擎内部承担；本地 pg 直连只服务未绑定 KB。
                    // 缺了这个分支，绑定店的导入"完成"但店中无数据，永远不可检索。
                    com.ragagent.retrieval.engine.CompositeRetrieveEngine boundEngine =
                            vectorWrites.boundEngine(kb);
                    if (boundEngine != null) {
                        com.ragagent.embedding.Embedder embedderRuntime =
                                modelRuntimeFactory.getEmbeddingModel(kb.getEmbeddingModelId());
                        List<com.ragagent.retrieval.engine.EngineTypes.IndexInfo> items =
                                new ArrayList<>(chunks.size());
                        for (Chunk c : chunks) {
                            com.ragagent.retrieval.engine.EngineTypes.IndexInfo item =
                                    new com.ragagent.retrieval.engine.EngineTypes.IndexInfo();
                            item.sourceId = c.getId();
                            item.sourceType = com.ragagent.retrieval.engine.EngineTypes.SOURCE_TYPE_FILE;
                            item.chunkId = c.getId();
                            item.knowledgeId = k.getId();
                            item.knowledgeBaseId = k.getKnowledgeBaseId();
                            item.knowledgeType = kb.getType();
                            // tag_id 传 ""：对照 Go processChunks 的 IndexInfo 不设 TagID（零值）
                            item.tagId = "";
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
                } else {
                    // 对照 Go L674：向量/关键词都关 → embedding 阶段 skip
                    skipStageSpan(attempt, knowledgeId,
                            KnowledgeProcessingSpan.STAGE_EMBEDDING, "skipped");
                }
                // multimodal：Java 无多模态处理管线 → 对照 Go L698-705 的 skip 分支
                skipStageSpan(attempt, knowledgeId,
                        KnowledgeProcessingSpan.STAGE_MULTIMODAL, "skipped");

                // 7) 完成 / wiki 交接（对照 Go knowledge_post_process.go L211 判定 +
                //    L311-340 原子交接 + L436-446 入队）：wiki 启用且有文本 chunk →
                //    先把 processing 原子翻到 finalizing（pending_subtasks_count=1，
                //    由 wiki 子任务持有），再入队 ingest；wiki 完成后由
                //    DefaultWikiKnowledgeFinalizer 递减并晋升 completed。
                //    未启用 → 无富化快路径：直接 completed（既有行为）。
                boolean willSpawnWiki = kb.getIndexingStrategy() != null
                        && kb.getIndexingStrategy().isWikiEnabled()
                        && !chunks.isEmpty();
                // 图抽取 fan-out 的分块选择（对照 Go L191 selectGraphChunks + L243-246：
                // 仅 eff.GraphEnabled 时计数；与 wiki 是**独立**判定——wiki 关、图开也要跑）
                List<Chunk> graphChunks = kb.getIndexingStrategy() != null
                        && kb.getIndexingStrategy().isGraphEnabled()
                                ? GraphChunkSelector.selectGraphChunks(chunks)
                                : List.of();
                boolean willSpawnGraph = !graphChunks.isEmpty();
                // 问题生成 fan-out（对照 Go L209-234）：willSpawnSummary（有文本 chunk）
                // && 需要 embedding 模型 && question_generation_config.enabled；只取文本且仍可
                // 抽取散文的分块（StartAt 排序），按 20 分批。
                boolean questionEnabled = kb.getQuestionGenerationConfig() != null
                        && kb.getQuestionGenerationConfig().path("enabled").asBoolean(false);
                // 对照 Go kb.NeedsEmbeddingModel() = indexing_strategy 的 vector_enabled || keyword_enabled
                boolean kbNeedsEmbedding = kb.getIndexingStrategy() != null
                        && (kb.getIndexingStrategy().isVectorEnabled()
                                || kb.getIndexingStrategy().isKeywordEnabled());
                List<Chunk> questionChunks = questionEnabled && kbNeedsEmbedding
                        && !chunks.isEmpty()
                                ? QuestionBatchPlanner.selectQuestionChunks(chunks)
                                : List.of();
                int questionBatchCount = QuestionBatchPlanner.batchCount(questionChunks.size());
                boolean willSpawnQuestion = questionBatchCount > 0;
                if (willSpawnWiki || willSpawnGraph || willSpawnQuestion) {
                    // 对照 Go finalizeIndexedKnowledgeState（knowledge_process.go
                    // L215-242，在 post-process 之前）：索引完成即推进
                    // enable_status/processed_at —— 非 wiki 路径由 failOrComplete 完成
                    // 同样的写；wiki 路径此前整段跳过，文档停在 disabled。
                    markIndexedEnabled(knowledgeId);
                    // postprocess span 覆盖摘要 fan-out 与 wiki 交接（对照 Go
                    // post_process.go L142 的 BeginStage(postprocess)）
                    SpanTracker.SpanHandle postSpan = beginStageSpan(attempt, knowledgeId,
                            KnowledgeProcessingSpan.STAGE_POST_PROCESS, null);
                    // 摘要 fan-out 独立于 wiki（Go willSpawnSummary 与 willSpawnWiki
                    // 是两个判定）；此前 wiki 路径整段跳过，摘要永不生成。
                    if (hasSummaryModel(kb)) {
                        // Go finalizeIndexedKnowledgeState：有文本 chunk → summary_status=none
                        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                                .eq("id", knowledgeId)
                                .set("summary_status", "none")
                                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
                        spawnSummaryFanOut(knowledgeId);
                    }
                    // 对照 Go L247-255：expectedSubtasks =（wiki 1）+ 问题批数 + 图分块数
                    // （摘要的那 1 个槽 Java 侧沿用既有路径，未并入——其 fan-out 不计入
                    //  finalizing 计数；这是既有差异，本批只并入问题批次）
                    int pendingSubtasks = (willSpawnWiki ? 1 : 0) + questionBatchCount
                            + graphChunks.size();
                    if (promoteFinalizing(knowledgeId, pendingSubtasks)) {
                        if (willSpawnWiki) {
                            enqueueWikiIngest(knowledgeId, k);
                        }
                        if (willSpawnGraph) {
                            enqueueGraphExtracts(knowledgeId, k, kb, graphChunks, attempt);
                        }
                        if (willSpawnQuestion) {
                            enqueueQuestionBatches(knowledgeId, k, kb, questionChunks, attempt);
                        }
                    }
                    // promote 失败 = 行已被 cancel/delete 抢走 → 跳过富化（对照 Go
                    // default 分支的 else：不得覆盖状态、不得标 completed）
                    endStageSpan(postSpan, null);
                    // root 收口：wiki 交由 finalizing 计数兜底，但 post-process 这个
                    // attempt 已完成（对照 Go PostProcess → FinalizeAttempt L751-818）
                    // ——不收口会让 trace 的「知识处理」永远显示计时中。
                    if (attempt > 0) {
                        spanTracker.finalizeAttempt(knowledgeId, attempt,
                                KnowledgeProcessingSpan.STATUS_DONE, null, "", "");
                    }
                } else {
                    failOrComplete(knowledgeId, attempt, null);
                }
            } catch (Exception inner) {
                // 对照 Go L629-639：失败时清本次 chunks + 向量行（向量化未启用时只清 chunks）
                chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeId, knowledgeId));
                deleteKnowledgeVectors(k, kb, embedConfig, knowledgeId);
                throw inner;
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            // 对照 AsynqMiddleware：处理体抛错 → span/根记 outcome=error
            // （scope.finish 幂等，随后的 close 不会覆盖成 success）
            scope.finish("error", message);
            log.warn("process knowledge {} failed: {}", knowledgeId, e.toString());
            failOrComplete(knowledgeId, attempt, message);
        }
    }

    /** 对照 Go processDocument L354-359：清该知识在源 KB 命名空间下的旧图谱（失败仅告警）。 */
    private void deleteGraphData(String knowledgeBaseId, String knowledgeId) {
        try {
            graphRepository.delGraph(List.of(new com.ragagent.chatpipeline.ChatManage.NameSpace(
                    knowledgeBaseId, knowledgeId)));
        } catch (RuntimeException e) {
            log.warn("Failed to delete existing graph data (may not exist): {}", e.toString());
        }
    }

    /**
     * 向量化判定 + 模型解析（对照 Go processDocument：vector/keyword 任一启用才取模型；
     * knowledge 行优先、回落 KB——Java 既有取数口径，已验收）。
     * 模型缺失时抛错，此时预清理尚未执行（既有数据不动，照抄 Go 顺序）。
     */
    /**
     * 知识删除/重处理的向量行清理——2026-09-25 写链改道：绑定 store 的 KB 走引擎口
     * （照 Go knowledge_delete.go L499-514：CreateRetrieveEngineForKB →
     * GetEmbeddingModel → DeleteByKnowledgeIDList）；未绑定保持 pg 直连（模型缺失时
     * 跳过清理，与 Go "Skipping vector store cleanup without embedding model" 同形）。
     */
    private void deleteKnowledgeVectors(Knowledge k, KnowledgeBase kb,
                                        EmbedderClient.EmbedConfig embedConfig, String knowledgeId) {
        com.ragagent.retrieval.engine.CompositeRetrieveEngine boundEngine = vectorWrites.boundEngine(kb);
        if (boundEngine != null) {
            try {
                com.ragagent.embedding.Embedder emb =
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

    /** KB 是否配置了摘要模型（对照 Go 的 hasSummaryModel 判定）。 */
    private static boolean hasSummaryModel(KnowledgeBase kb) {
        return kb != null && kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty();
    }

    /**
     * 摘要 fan-out（对照 Go knowledge_post_process.go L208 的
     * {@code willSpawnSummary = len(textChunks) > 0} → L562 入队摘要任务）：
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
     * 对照 Go {@code finalizeIndexedKnowledgeState} 的无条件部分
     * （knowledge_process.go L236-239）：索引完成 → {@code enable_status=enabled}
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
     * 对照 Go {@code SetFinalizing} / {@code SeedKnowledgeFinalizingWithPendingOp} 的
     * 状态翻转部分：一次条件更新把 {@code processing} 原子翻到 {@code finalizing}，
     * 并置 {@code pending_subtasks_count=1}（wiki 子任务占用的那个槽）。
     *
     * <p>返回 false = 行已不在 processing（cancel/delete 抢走）——调用方必须跳过
     * 富化且不得覆盖状态（对照 Go default 分支的 else 注释）。</p>
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
     * 图抽取 fan-out（对照 Go L414-431）：逐块入队 {@code chunk:extract}，{@code model_id}
     * 取 KB 的 {@code SummaryModelID}（照 Go）。入队侧注入追踪载体（C 批约定），
     * worker 侧续接同一棵树。
     *
     * <p>入队失败的槽位<b>立即释放</b>（对照 NewChunkExtractTask 的注释：没入队的槽
     * 不释放会让父知识永远停在 finalizing）。</p>
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
        com.ragagent.common.context.TracingContext tracing =
                com.ragagent.tracing.langfuse.LangfuseTracing.inject();
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
     * 问题生成 fan-out（对照 Go {@code enqueueQuestionGenerationTasks}，
     * knowledge_post_process.go:604-690）：按 {@link QuestionBatchPlanner#BATCH_SIZE} 分批入队
     * {@code question:generation}，载荷只带 chunk id（+ 边界邻块 id），worker 运行时装读内容。
     *
     * <p>入队失败的批<b>立即释放</b>该批占用的槽位（对照 Go 的 shortfall-release：没入队的槽
     * 不释放会让父知识永远停在 finalizing）。</p>
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
        com.ragagent.common.context.TracingContext tracing =
                com.ragagent.tracing.langfuse.LangfuseTracing.inject();
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
     * 对照 Go L436-446：op 落库 + 防抖触发。op 未被接受（如 KB 已删）或入队异常时
     * 释放 finalizing 槽（Go 由 shortfall 释放；这里复用 finalizer 的递减+晋升），
     * 避免行搁浅在 finalizing。触发失败只记警告——op 已落库，不从重追加
     * （对照 Go「触发错误可与 accepted=true 同时返回」的注释）。
     */
    private void enqueueWikiIngest(String knowledgeId, Knowledge k) {
        com.ragagent.wiki.service.WikiIngestService service = wikiIngestService.getIfAvailable();
        if (service == null) {
            log.warn("[KnowledgePostProcess] Wiki ingest service unavailable, releasing slot for {}",
                    knowledgeId);
            releaseWikiSlot(knowledgeId);
            return;
        }
        try {
            com.ragagent.wiki.service.WikiIngestService.EnqueueResult result = service
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
     * 释放 wiki 槽（finalizer 的"递减+晋升"两步写，对照 Go 的 shortfall 释放）。
     * finalizer 缺席时静默——行由 finalizing housekeeping sweep 兜底。
     */
    private void releaseWikiSlot(String knowledgeId) {
        com.ragagent.wiki.service.WikiKnowledgeFinalizer finalizer =
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
            // postprocess 阶段埋点（对照 Go post_process.go L142 的 BeginStage(postprocess)：
            // 覆盖摘要 fan-out 与收口）
            SpanTracker.SpanHandle postSpan = beginStageSpan(attempt, knowledgeId,
                    KnowledgeProcessingSpan.STAGE_POST_PROCESS, null);
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
            // 对照 Go 的 PostProcess → FinalizeAttempt（L751-818）：root 幂等收口 done
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

    // ── span 埋点辅助（对照 Go knowledge.go L248-299 的 by-name shims；attempt<=0 时全 no-op） ──

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

    /** 对照 Go skipStage（L286-299）：无 begin 记录时先合成一行再 skip（保 schema 不变量）。 */
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
