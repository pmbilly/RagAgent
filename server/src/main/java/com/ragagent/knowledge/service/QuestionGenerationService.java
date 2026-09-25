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
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;

/**
 * 问题生成**批** worker（对照 Go {@code processQuestionGenerationForChunks}，
 * internal/application/service/knowledge_process.go:1812-2110）。
 *
 * <p><b>它补的是哪个洞</b>：Go 在导入后处理里按批扇出 {@code TypeQuestionGeneration} 任务
 * （{@code knowledge_post_process.go:209-238,604-690}），每块生成 Doc2Query 式问题写回 chunk
 * metadata 并重建向量索引——这是"导入文档后，新建提问能看到推荐问题"的**数据来源**。
 * 本仓此前只有手动路径（{@code POST /chunks/by-id/{id}/questions/regenerate}），
 * 自动路径在 {@code KnowledgeService} 里备案为"未翻" ⇒ 刚导入的 KB 推荐问题恒为空。</p>
 *
 * <p>逐批顺序照 Go：supersede 跳过 → 知识中止短路 → 取 KB → 逐块生成（复用
 * {@link ChunkService#generateAndStoreQuestionsForWorker}）→ 终态递减 finalizing 槽
 * （{@code finalizeSubtaskDetached} 的等价物 {@link DefaultWikiKnowledgeFinalizer#finalizeSubtask}）。</p>
 */
@Service
public class QuestionGenerationService {

    /** 对照 Go {@code types.TypeQuestionGeneration = "question:generation"}。 */
    public static final String TASK_TYPE_QUESTION_GENERATION = "question:generation";

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final SpanTracker spanTracker;
    private final DefaultWikiKnowledgeFinalizer finalizer;
    private final ChunkService chunkService;

    @Autowired
    public QuestionGenerationService(ChunkRepository chunkRepository,
                                     KnowledgeMapper knowledgeMapper,
                                     KnowledgeBaseMapper kbMapper,
                                     SpanTracker spanTracker,
                                     DefaultWikiKnowledgeFinalizer finalizer,
                                     ChunkService chunkService) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.spanTracker = spanTracker;
        this.finalizer = finalizer;
        this.chunkService = chunkService;
    }

    /** 队列入口：JSON 载荷 → 任务作用域（对照 Go 的 asynq 中间件）→ 处理。 */
    public void handleJson(String payloadJson) {
        QuestionBatchPayload p = QuestionBatchPayload.fromJson(payloadJson);
        try (LangfuseTaskScope scope = LangfuseTaskScope.start(TASK_TYPE_QUESTION_GENERATION, p.tracing(),
                Map.of("knowledge_id", p.knowledgeId(),
                        "batch_index", String.valueOf(p.batchIndex())),
                LangfuseTaskScope.previewPayload(payloadJson))) {
            handle(p);
        }
    }

    /** 对照 {@code processQuestionGenerationForChunks} 的批次入口。 */
    public void handle(QuestionBatchPayload p) {
        if (spanTracker.isAttemptSuperseded(p.knowledgeId(), p.attempt())) {
            log.info("question generation: attempt {} superseded for {}, skipping stale enrichment",
                    p.attempt(), p.knowledgeId());
            return;
        }
        try {
            runBatch(p);
        } finally {
            // 终态释放槽位（对照 finalizeSubtaskDetached；Java 的进程内队列无重试 → final 恒真）
            drainSubtask(p.knowledgeId(), "question_batch[" + p.batchIndex() + "]");
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
        // 取消/删除短路（对照 Go 的 knowledge_<status> 跳过）：批式扇出让每个批都白拿一次检查，
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

    /** 对照 Go 的 count 归一：缺省 3、上限 10。 */
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
            // 消失的分块优雅降级（对照 Go getChunk 的 nil）
            return null;
        }
    }

    private static String contentOf(Chunk c) {
        return c == null || c.getContent() == null ? "" : c.getContent();
    }

    /** 对照 Go 的 {@code finalizeSubtaskDetached}：knowledge 为空则空转（旧版在飞任务）。 */
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
