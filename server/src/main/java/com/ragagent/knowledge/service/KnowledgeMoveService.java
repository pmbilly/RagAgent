package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository;
import com.ragagent.knowledge.dto.KnowledgeMoveProgress;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.retrieval.engine.PgVectorEngineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import com.ragagent.chatpipeline.ChatManage.NameSpace;
import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.knowledge.task.KnowledgeTaskProgressStore;
import com.ragagent.knowledge.task.KnowledgeTaskExecutor;
import com.ragagent.knowledge.storage.TenantStorageService;

/**
 * 知识库 Move（跨库搬移）worker 面。HTTP 契约 = 立即返回 + 进度查询；
 * 虚拟线程内驱动状态机，跨线程显式传值（TenantContext 不共享）。
 */
@Service
public class KnowledgeMoveService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMoveService.class);

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final ChunkRepository chunkRepo;
    private final KnowledgeTagMapper tagMapper;
    private final TenantStorageService tenantStorage;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final KnowledgeService.KnowledgeProcessWorker worker;
    private final KnowledgeTaskProgressStore progressStore;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final KnowledgeVectorWrites vectorWrites;
    private final PgVectorEngineRepository pgVectorEngineRepository;
    private final RetrieveGraphRepository graphRepository;
    /** 后台任务执行器（统一命名与关停）。 */
    private final KnowledgeTaskExecutor taskExecutor;


    public KnowledgeMoveService(KnowledgeTaskExecutor taskExecutor, KnowledgeMapper knowledgeMapper,
            KnowledgeBaseMapper kbMapper,
            ChunkMapper chunkMapper,
            ChunkRepository chunkRepo,
            KnowledgeTagMapper tagMapper,
            TenantStorageService tenantStorage,
            ModelRuntimeFactory modelRuntimeFactory,
            @Lazy KnowledgeService.KnowledgeProcessWorker worker,
            KnowledgeTaskProgressStore progressStore,
            ChunkVectorIndexer chunkVectorIndexer,
            KnowledgeVectorWrites vectorWrites,
            PgVectorEngineRepository pgVectorEngineRepository,
            RetrieveGraphRepository graphRepository) {

        this.taskExecutor = taskExecutor;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.chunkRepo = chunkRepo;
        this.tagMapper = tagMapper;
        this.tenantStorage = tenantStorage;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.worker = worker;
        this.progressStore = progressStore;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.vectorWrites = vectorWrites;
        this.pgVectorEngineRepository = pgVectorEngineRepository;
        this.graphRepository = graphRepository;
    }

    private static long epochNow() {
        return java.time.Instant.now().getEpochSecond();
    }


    /**
     * 入队 move 任务。
     * 初始进度 SetNX（键已存在不覆写）；worker 在虚拟线程里真实驱动状态机：
     * pending → processing（total=items）→ 逐条搬行（"Moved X/N knowledge items"）→
     * <p><b>已知差异（2026-09-25 写链改道 + reparse 收尾后更新）</b>：reuse_vectors 模式已搬
     * 向量行（同店 + 同模型校验后 MoveKnowledgeIndices——绑定店走引擎口、未绑定走 postgres
     * cleanupMovedSourceWiki / EnqueueWikiIngest 随 wiki 消费面）、transfer-state 续跑/重试
     * 语义不翻译（Java 无该状态机，见 {@code moveOneKnowledgeRow} 注释）；任务队列 的
     * retry/marker 语义不翻译（既有取舍）。</p>
     */
    public void startKnowledgeMove(long tenantId, String taskId, List<String> knowledgeIds,
                                   String sourceKbId, String targetKbId, String mode) {
        progressStore.saveMoveInitial(new KnowledgeMoveProgress(
                taskId, sourceKbId, targetKbId, "pending", 0, knowledgeIds.size(), 0, 0,
                "Task queued, waiting to start...", "", epochNow(), epochNow()));
        // 本仓约定：跨虚拟线程显式传值，不共享 ThreadLocal
        final String role = TenantContext.currentRole();
        final String userId = TenantContext.currentUserId();
        taskExecutor.submit("knowledge-move", () -> {
            TenantContext.set(tenantId, null, role, false, userId, false);
            try {
                runKnowledgeMove(tenantId, taskId, knowledgeIds, sourceKbId, targetKbId, mode);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void runKnowledgeMove(long tenantId, String taskId, List<String> knowledgeIds,
                                  String sourceKbId, String targetKbId, String mode) {
        int total = knowledgeIds.size();
        progressStore.saveMove(new KnowledgeMoveProgress(
                taskId, sourceKbId, targetKbId, "processing", 0, total, 0, 0, "", "", 0, epochNow()));
        int processed = 0;
        int failed = 0;
        String failures = null;
        for (String id : knowledgeIds) {
            try {
                moveOneKnowledgeRow(tenantId, id, sourceKbId, targetKbId, mode);
            } catch (RuntimeException e) {
                failed++;
                String itemFailure = "knowledge " + id + ": " + e.getMessage();
                failures = failures == null ? itemFailure : failures + "\n" + itemFailure;
            }
            processed++;
            int done = processed - failed;
            progressStore.saveMove(new KnowledgeMoveProgress(
                    taskId, sourceKbId, targetKbId, "processing", processed * 100 / total,
                    total, processed, failed,
                    "Moved " + done + "/" + total + " knowledge items", "", 0, epochNow()));
        }
        if (failures != null) {
            progressStore.saveMove(new KnowledgeMoveProgress(
                    taskId, sourceKbId, targetKbId, "failed", processed * 100 / total,
                    total, processed, failed, "Moved " + (processed - failed) + "/" + total
                            + " knowledge items", failures, 0, epochNow()));
            return;
        }
        progressStore.saveMove(new KnowledgeMoveProgress(
                taskId, sourceKbId, targetKbId, "completed", 100, total, processed, failed,
                "Moved " + (processed - failed) + "/" + total + " knowledge items", "", 0, epochNow()));
    }

    /**
     * 单条搬行：
     * <ul>
     *   <li><b>reparse</b> → {@link #moveKnowledgeReparse}：源侧资源清理 + 行改写为目标 KB 的
     *       待解析态 + 重新解析入队；</li>
     *   <li><b>reuse_vectors</b>：knowledge 行 + chunks 行换 KB，并保留既有的 vector 行
     *       先清关联；同店 + 同嵌入模型校验后 {@code MoveKnowledgeIndices(sourceKB, targetKB,
     *       knowledgeID)}——绑定店走引擎口，未绑定直接走 postgres 语义的 embeddings 改写
     * </ul>
     * 做 CAS 与重试幂等，本仓无该状态机——搬行是"尽力一次"，失败由移动任务逐条记入
     * failures（既有取舍，见 {@link #startKnowledgeMove} 注释）。</p>
     */
    private void moveOneKnowledgeRow(long tenantId, String knowledgeId, String sourceKbId,
                                     String targetKbId, String mode) {
        Knowledge row = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (row == null) {
            throw new IllegalStateException("not found");
        }
        if ("reparse".equals(mode)) {
            moveKnowledgeReparse(tenantId, row, targetKbId);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String actualSourceKbId = row.getKnowledgeBaseId();
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("knowledge_base_id", targetKbId)
                // 搬走后行落在终态 completed、错误清空
                .set("parse_status", Knowledge.PARSE_COMPLETED)
                .set("error_message", "")
                .set("updated_at", now));
        // 标签是 KB 作用域的：搬走后源 KB 的标签不该继续挂着该文档
        //
        tagMapper.deleteRelations(knowledgeId);
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("knowledge_id", knowledgeId)
                .set("knowledge_base_id", targetKbId)
                .set("updated_at", now));
        // 搬走后源 KB 的命名空间不得继续
        // 暴露该文档（失败上抛——移动任务据此重试；命名空间删除可重复执行）
        graphRepository.delGraph(List.of(
                new NameSpace(actualSourceKbId, knowledgeId)));
        if ("reuse_vectors".equals(mode)) {
            moveKnowledgeVectors(tenantId, knowledgeId, sourceKbId, targetKbId);
        }
    }

    /**
     * {@code enqueueMovedKnowledge} L1430-1510）：搬走后按目标 KB 的配置重新解析——
     * "向量不跟着走，到目标店重建"。
     * <ol>
     *   <li><b>源侧资源清理</b>（{@link #cleanupKnowledgeResourcesForReparse}）——向量行、
     *       chunks 行、源图谱命名空间；失败上抛为 {@code failed to clean up source: ...}；</li>
     *   <li><b>清标签关联</b>（照 {@code DeleteKnowledgeTagRelations}）；</li>
     *       embedding_model_id=目标 KB、parse_status=pending、error_message=""、
     *       enable_status=disabled、description=""、processed_at=NULL、storage_size=0；
     *       并按 delta 扣减租户存储用量（照 {@code UpdateKnowledgeForTransfer} 的
     *       {@code storage_used += after-before}，负数钳 0）；</li>
     *   <li><b>重新解析入队</b>——目标 KB 的 chunker/嵌入模型/多模态/问题生成在重新解析时生效
     *       净效果同一段配置）。</li>
     * </ol>
     * {@code acknowledgeMovedReparse} 收尾（Java 无该状态机）；② 图片资源回收
     * （{@code cleanupMovedSourceWiki}）与目标 KB 的 wiki 触发（{@code EnqueueWikiIngest}）
     * 本仓进程内队列无去重键（重复入队会重解析，幂等由解析本身承担）。</p>
     */
    private void moveKnowledgeReparse(long tenantId, Knowledge row, String targetKbId) {
        String knowledgeId = row.getId();
        String sourceKbId = row.getKnowledgeBaseId();
        KnowledgeBase sourceKb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, sourceKbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        KnowledgeBase targetKb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, targetKbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (sourceKb == null || targetKb == null) {
            throw new IllegalStateException("knowledge base not found");
        }
        long storageSize = row.getStorageSize();

        try {
            cleanupKnowledgeResourcesForReparse(row, sourceKb);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to clean up source: " + e.getMessage(), e);
        }
        // 2) 标签关联（标签是 KB 作用域的）
        tagMapper.deleteRelations(knowledgeId);
        // 3) 行改写到目标 KB 的待解析态
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("knowledge_base_id", targetKbId)
                .set("embedding_model_id", targetKb.getEmbeddingModelId())
                .set("parse_status", Knowledge.PARSE_PENDING)
                .set("error_message", "")
                .set("enable_status", "disabled")
                .set("description", "")
                .set("processed_at", null)
                .set("storage_size", 0L)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        if (storageSize > 0) {
            tenantStorage.adjustStorageUsed(tenantId, -storageSize);
        }
        // 4) 重新解析入队（目标 KB 的配置在 worker 按行上的 KB 现读）
        worker.enqueue(knowledgeId);
    }

    /**
     * knowledge_delete）：向量行（嵌入模型为空则整段跳过）→ chunks 行 →
     * 先置 0，扣减由随后的行改写 delta 完成（见 {@link #moveKnowledgeReparse}）；
     * 图片资源回收（{@code deleteExtractedImages}）随资源目录面处理，不在本方法内。</p>
     */
    private void cleanupKnowledgeResourcesForReparse(Knowledge row, KnowledgeBase kb) {
        String knowledgeId = row.getId();
        List<String> failures = new ArrayList<>();
        String embeddingModelId = row.getEmbeddingModelId();
        if (embeddingModelId != null && !embeddingModelId.isEmpty()) {
            try {
                deleteKnowledgeVectorRows(row, kb, embeddingModelId);
            } catch (RuntimeException e) {
                failures.add("delete knowledge index: " + e.getMessage());
            }
        }
        try {
            chunkRepo.deleteChunksByKnowledgeId(row.getTenantId(), knowledgeId);
        } catch (RuntimeException e) {
            failures.add("delete knowledge chunks: " + e.getMessage());
        }
        try {
            graphRepository.delGraph(List.of(
                    new NameSpace(kb.getId(), knowledgeId)));
        } catch (RuntimeException e) {
            failures.add("delete knowledge graph data: " + e.getMessage());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(String.join("; ", failures));
        }
    }

    /**
     * 按知识删除向量行：绑定 store 的 KB 走引擎口、未绑定走 postgres 适配器
     * （与 {@link #moveKnowledgeVectors} 同一分派、同 {@code KnowledgeProcessWorker}
     * 预清理的语义）。
     */
    private void deleteKnowledgeVectorRows(Knowledge row, KnowledgeBase kb, String embeddingModelId) {
        try {
            if (vectorWrites.boundEngine(kb) instanceof
                    CompositeRetrieveEngine engine) {
                Embedder emb =
                        modelRuntimeFactory.getEmbeddingModel(embeddingModelId);
                engine.deleteByKnowledgeIdList(List.of(row.getId()), emb.getDimensions(),
                        row.getType());
                return;
            }
            pgVectorEngineRepository.deleteByKnowledgeIdList(List.of(row.getId()), 0, row.getType());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
        }
    }

    /**
     * ①同店校验（源/目标的 vector_store_id 必须一致——两边都 NULL 视为共享 env-store）；
     * ③MoveKnowledgeIndices 原地改写 embeddings 的 knowledge_base_id（并清 tag_id）——
     */
    private void moveKnowledgeVectors(long tenantId, String knowledgeId, String sourceKbId,
                                      String targetKbId) {
        Knowledge row = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        KnowledgeBase sourceKb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, sourceKbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        KnowledgeBase targetKb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, targetKbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (row == null || sourceKb == null || targetKb == null) {
            return;
        }
        String srcStore = sourceKb.getVectorStoreId() == null ? "" : sourceKb.getVectorStoreId();
        String dstStore = targetKb.getVectorStoreId() == null ? "" : targetKb.getVectorStoreId();
        if (!srcStore.equals(dstStore)) {
            throw new IllegalStateException(
                    "reuse_vectors move across different vector stores is not supported "
                            + "(source KB " + sourceKbId + ", target KB " + targetKbId
                            + "); use reparse mode");
        }
        if (!java.util.Objects.equals(row.getEmbeddingModelId(), sourceKb.getEmbeddingModelId())) {
            throw new IllegalStateException(
                    "knowledge " + knowledgeId + " uses a different embedding model");
        }
        try {
            if (vectorWrites.boundEngine(sourceKb) instanceof
                    CompositeRetrieveEngine engine) {
                Embedder emb =
                        modelRuntimeFactory.getEmbeddingModel(row.getEmbeddingModelId());
                engine.moveKnowledgeIndices(sourceKbId, targetKbId, knowledgeId,
                        List.of(), emb.getDimensions(), sourceKb.getType());
                return;
            }
            pgVectorEngineRepository.moveKnowledgeIndices(sourceKbId, targetKbId, knowledgeId,
                    List.of(), 0, sourceKb.getType());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? String.valueOf(e) : e.getMessage(), e);
        }
    }

    public void saveKnowledgeMoveProgress(KnowledgeMoveProgress p) {
        progressStore.saveMoveInitial(p);
    }

    public KnowledgeMoveProgress getKnowledgeMoveProgress(String taskId) {
        return progressStore.getMove(taskId);
    }


}
