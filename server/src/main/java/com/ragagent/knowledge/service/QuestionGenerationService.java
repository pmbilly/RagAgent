package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.tracing.langfuse.LangfuseTaskScope;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;
import com.ragagent.knowledge.domain.QuestionBatchPayload;

/**
 * 问题生成**批** worker。
 * metadata 并重建向量索引——这是"导入文档后，新建提问能看到推荐问题"的**数据来源**。
 * 本仓此前只有手动路径（{@code POST /chunks/by-id/{id}/questions/regenerate}），
 * 自动路径在 {@code KnowledgeService} 里备案为"未翻" ⇒ 刚导入的 KB 推荐问题恒为空。</p>
 * {@link ChunkQuestionService#generateAndStoreQuestionsForWorker}）→ 终态递减 finalizing 槽
 * （{@code finalizeSubtaskDetached} 的等价物 {@link DefaultWikiKnowledgeFinalizer#finalizeSubtask}）。</p>
 */
@Service
public class QuestionGenerationService {

    public static final String TASK_TYPE_QUESTION_GENERATION = "question:generation";

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final SpanTracker spanTracker;
    private final DefaultWikiKnowledgeFinalizer finalizer;
    private final ChunkQuestionService chunkService;

    @Autowired
    public QuestionGenerationService(ChunkRepository chunkRepository,
                                     KnowledgeMapper knowledgeMapper,
                                     KnowledgeBaseMapper kbMapper,
                                     SpanTracker spanTracker,
                                     DefaultWikiKnowledgeFinalizer finalizer,
                                     ChunkQuestionService chunkService) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.spanTracker = spanTracker;
        this.finalizer = finalizer;
        this.chunkService = chunkService;
    }

    /** 队列入口：JSON 载荷 → 任务作用域→ 处理（默认按终态处理）。 */
    public void handleJson(String payloadJson) {
        handleJson(payloadJson, true);
    }

    /**
     * @param terminal 本次是否是该任务的**最后一次**尝试（队列侧按 {@code attempt > MAX_RETRY} 传入）。
     *                 （{@code willDrain = retErr == nil || final}）——失败且还会重试时递减会让父知识
     *                 在问题落库前就完成。
     */
    public void handleJson(String payloadJson, boolean terminal) {
        QuestionBatchPayload p = QuestionBatchPayload.fromJson(payloadJson);
        try (LangfuseTaskScope scope = LangfuseTaskScope.start(TASK_TYPE_QUESTION_GENERATION, p.tracing(),
                Map.of("knowledge_id", p.knowledgeId(),
                        "batch_index", String.valueOf(p.batchIndex())),
                LangfuseTaskScope.previewPayload(payloadJson))) {
            handle(p, terminal);
        }
    }

    public void handle(QuestionBatchPayload p) {
        handle(p, true);
    }

    /** @param terminal 见 {@link #handleJson(String, boolean)}。 */
    public void handle(QuestionBatchPayload p, boolean terminal) {
        if (spanTracker.isAttemptSuperseded(p.knowledgeId(), p.attempt())) {
            log.info("question generation: attempt {} superseded for {}, skipping stale enrichment",
                    p.attempt(), p.knowledgeId());
            return;
        }
        // 绑定批任务的租户上下文。
        // 进程内 worker 线程**没有** HTTP 请求上下文，而模型工厂/仓储的可见性判定都读
        // TenantContext（空 ⇒ tid=0 ⇒ 解析得 "model not found"）——不绑定这条链路必失败。
        // 绑定/恢复样式照 WikiBatchSupport 的同款纪律（clear 后仅在原值非空时恢复）。
        var prevPrincipal = TenantContext.currentPrincipal();
        Long prevTenant = TenantContext.currentTenantId();
        String prevRole = TenantContext.currentRole();
        boolean prevSysAdmin = TenantContext.isSystemAdmin();
        String prevUser = TenantContext.currentUserId();
        boolean prevAccessAll = TenantContext.canAccessAllTenants();
        TenantContext.set(p.tenantId(), null, null, false, null, false);
        boolean succeeded = false;
        try {
            runBatch(p);
            succeeded = true;
        } finally {
            TenantContext.clear();
            if (prevTenant != null || prevPrincipal != null || prevRole != null
                    || prevUser != null || prevSysAdmin || prevAccessAll) {
                TenantContext.set(prevTenant, prevPrincipal, prevRole, prevSysAdmin, prevUser, prevAccessAll);
            }
            // 终态释放槽位
            if (succeeded || terminal) {
                drainSubtask(p.knowledgeId(), "question_batch[" + p.batchIndex() + "]");
            }
        }
    }

    /** @return 成功写入问题的分块数（0 = 全部跳过/为空） */
    int runBatch(QuestionBatchPayload p) {
        List<String> batchIds = p.chunkIds();
        if (batchIds.isEmpty()) {
            log.info("question generation: empty batch for knowledge {}", p.knowledgeId());
            return 0;
        }

        Knowledge k = knowledgeMapper.selectById(p.knowledgeId());
        if (k == null) {
            log.warn("question generation: knowledge {} not found (old in-flight task?)", p.knowledgeId());
            return 0;
        }
        // 取消/删除短路：批式扇出让每个批都白拿一次检查，
        // 取消即可停掉剩余批次的 LLM 配额消耗。
        if (k.isAborted()) {
            log.info("question generation: knowledge {} aborted ({}), skipping batch {}",
                    p.knowledgeId(), k.getParseStatus(), p.batchIndex());
            return 0;
        }

        KnowledgeBase kb = kbMapper.selectById(p.knowledgeBaseId());
        if (kb == null) {
            log.warn("question generation: knowledge base {} not found", p.knowledgeBaseId());
            return 0;
        }

        int questionCount = clampQuestionCount(p.questionCount());
        JsonNode qg = kb.getQuestionGenerationConfig();
        String customInstructions = qg == null ? "" : qg.path("custom_instructions").asText("");

        List<Chunk> batch = new ArrayList<>(batchIds.size());
        for (String id : batchIds) {
            batch.add(getChunk(p.tenantId(), id));
        }
        Chunk prevBoundary = getChunk(p.tenantId(), p.prevChunkId());
        Chunk nextBoundary = getChunk(p.tenantId(), p.nextChunkId());

        int processed = 0;
        int generated = 0;
        for (int i = 0; i < batch.size(); i++) {
            Chunk chunk = batch.get(i);
            if (chunk == null || chunk.getContent() == null || chunk.getContent().trim().isEmpty()) {
                continue;
            }
            String prevContent = i > 0 ? contentOf(batch.get(i - 1)) : contentOf(prevBoundary);
            String nextContent = i < batch.size() - 1 ? contentOf(batch.get(i + 1)) : contentOf(nextBoundary);
            int n = chunkService.generateAndStoreQuestionsForWorker(kb, k, chunk,
                    prevContent, nextContent, questionCount, customInstructions);
            if (n > 0) {
                processed++;
                generated += n;
            }
        }
        log.info("Question generation (batch): knowledge={} batch={} chunks_in_batch={} processed={} generated={}",
                p.knowledgeId(), p.batchIndex(), batch.size(), processed, generated);
        return processed;
    }

    static int clampQuestionCount(int count) {
        if (count <= 0) {
            return 3;
        }
        return Math.min(count, 10);
    }

    private Chunk getChunk(long tenantId, String chunkId) {
        if (chunkId == null || chunkId.isEmpty()) {
            return null;
        }
        try {
            return chunkRepository.getChunkById(tenantId, chunkId);
        } catch (RuntimeException e) {
            // 消失的分块优雅降级
            return null;
        }
    }

    private static String contentOf(Chunk c) {
        return c == null || c.getContent() == null ? "" : c.getContent();
    }

    /** knowledge 为空则空转（旧版在飞任务）。 */
    private void drainSubtask(String knowledgeId, String source) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        try {
            finalizer.finalizeSubtask(knowledgeId);
        } catch (RuntimeException e) {
            // best-effort：递减失败不破坏任务语义（行由 housekeeping sweep 兜底）
            log.warn("finalize subtask decrement failed source={} knowledge={} err={}",
                    source, knowledgeId, e.toString());
        }
    }
}
