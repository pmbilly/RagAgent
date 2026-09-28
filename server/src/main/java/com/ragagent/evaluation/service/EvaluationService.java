package com.ragagent.evaluation.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineBuilder;
import com.ragagent.chatpipeline.SummaryConfig;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.evaluation.domain.QaPair;
import com.ragagent.evaluation.dto.EvaluationDtos.EvaluationDetail;
import com.ragagent.evaluation.dto.EvaluationDtos.EvaluationTask;
import com.ragagent.evaluation.dto.EvaluationDtos.MetricResult;
import com.ragagent.evaluation.dto.EvaluationDtos.PipelineParams;
import com.ragagent.evaluation.dto.EvaluationDtos.SummaryConfigParams;
import com.ragagent.event.TenantContextSnapshot;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.service.KnowledgeTaskIds;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import com.ragagent.session.service.SessionKnowledgeQaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 评估服务（对照 Go internal/application/service/evaluation.go 的 Evaluation /
 * EvaluationResult；内存任务存储 evaluationMemoryStorage 的等价 ConcurrentHashMap）。
 *
 * <h2>执行步（2026-09-24 接线，对照 Go EvalDataset 全文）</h2>
 * <p>后台虚拟线程跑完整流水线：取 dataset → passages 建进临时 KB（同步建索引，
 * {@link KnowledgeService#createFromPassageSync}）→ 逐 QA 对<b>并行</b>跑
 * KnowledgeQAByEvent（rag 管线）→ {@link MetricHook} 汇总 retrieval/generation
 * metrics → 清理临时知识与 KB。失败 → status=failed + err_msg=Go 的 err.Error()
 * 语义（BizException 取 appError 原文）；全部成功 → status=success + metric 产出。</p>
 *
 * <p><b>已知差异（备案）</b>：① 指标分词走 MetricSegmenter 的降级实现（只影响
 * BLEU/ROUGE；检索类指标逐值一致）；② NDCG 的 math.Log2 以 frexp 同式仿真
 * （libm 差异或致末位分叉）；③ 清理用 deleteKnowledge + deleteKnowledgeBase，
 * 与 Go 的 deleteReferencedKnowledge 计划面（引用检查/副本保留）非逐行等价；
 * ④ TenantContext 经 capture/replay 显式传播（虚拟线程无隐式继承，约定 §5）。</p>
 *
 * <h2>Go 竞态的确定性化</h2>
 * <p>Go 在注册任务后立刻 {@code go func()} 把 status 翻成 running——HTTP 响应序列化
 * 与 goroutine 竞争（实测 0/1 两种值都出现过）。Java 侧返回<b>创建时刻的快照</b>
 * （status=pending），后台线程只改存储里的对象——GET 才能看到 running/failed。</p>
 *
 * <h2>其他照抄点</h2>
 * <ul>
 *   <li>模型类型字面量是 Go 的 {@code "Embedding"/"KnowledgeQA"/"Rerank"}
 *       （types/model.go 常量值，首字母大写）；</li>
 *   <li>KB 非空分支：读源 KB（跨租户在 Go 的 GetKnowledgeBaseByID 是**租户无关**的——
 *       Java 的 getKnowledgeBase 带租户过滤，跨租户源 KB 会落 404 同文案；已知差异）；</li>
 *   <li>"evaluation" KB 是真实创建（Go 同样泄漏——EvalDataset 的清理 defer 只在
 *       passage 建索引成功后注册，前置失败即泄漏该 KB）。</li>
 * </ul>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    public static final String ERR_NO_DEFAULT_MODELS = "no default models found for evaluation";
    public static final String ERR_NO_DEFAULT_CHAT_MODEL = "no default chat model found";
    public static final String ERR_TASK_NOT_FOUND = "task not found";
    public static final String ERR_TENANT_MISMATCH = "tenant ID does not match";

    private static final String MODEL_TYPE_EMBEDDING = "Embedding";
    private static final String MODEL_TYPE_RERANK = "Rerank";
    private static final String MODEL_TYPE_KNOWLEDGE_QA = "KnowledgeQA";

    /** Go config.yaml conversation 段的生效值（dev 部署；golden 钉住）。 */
    @Value("${conversation.max-rounds:5}")
    private int maxRounds;
    @Value("${conversation.vector-threshold:0.2}")
    private double vectorThreshold;
    @Value("${conversation.keyword-threshold:0.3}")
    private double keywordThreshold;
    @Value("${conversation.embedding-top-k:30}")
    private int embeddingTopK;
    @Value("${conversation.rerank-top-k:30}")
    private int rerankTopK;
    @Value("${conversation.rerank-threshold:0.3}")
    private double rerankThreshold;

    private final ModelService modelService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DatasetService datasetService;
    private final KnowledgeService knowledgeService;
    private final SessionKnowledgeQaService sessionKnowledgeQaService;

    /** 对照 evaluationMemoryStorage：taskID → detail。 */
    private final Map<String, EvaluationDetail> store = new ConcurrentHashMap<>();

    public EvaluationService(ModelService modelService,
                             KnowledgeBaseService knowledgeBaseService,
                             DatasetService datasetService,
                             KnowledgeService knowledgeService,
                             SessionKnowledgeQaService sessionKnowledgeQaService) {
        this.modelService = modelService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.datasetService = datasetService;
        this.knowledgeService = knowledgeService;
        this.sessionKnowledgeQaService = sessionKnowledgeQaService;
    }

    /**
     * 对照 Evaluation：KB 处理 → dataset/rerank/chat 缺省解析 → 建任务注册 + 后台执行。
     * 失败抛 IllegalStateException（handler → 500 信封 code 1007 + 原文）。
     */
    public EvaluationDetail evaluation(long tenantId, String datasetId, String knowledgeBaseId,
                                       String chatModelId, String rerankModelId) {
        String sourceEmbeddingModelId;
        String sourceSummaryModelId;
        if (knowledgeBaseId.isEmpty()) {
            // 按模型表挑默认 embedding/KnowledgeQA（Go 的 ListModels 扫描分支）
            String embeddingModelId = "";
            String llmModelId = "";
            for (Model model : modelService.listModels()) {
                if (model == null) {
                    continue;
                }
                if (MODEL_TYPE_EMBEDDING.equals(model.getType())) {
                    embeddingModelId = model.getId();
                }
                if (MODEL_TYPE_KNOWLEDGE_QA.equals(model.getType())) {
                    llmModelId = model.getId();
                }
            }
            if (embeddingModelId.isEmpty() || llmModelId.isEmpty()) {
                throw new IllegalStateException(ERR_NO_DEFAULT_MODELS);
            }
            sourceEmbeddingModelId = embeddingModelId;
            sourceSummaryModelId = llmModelId;
        } else {
            KnowledgeBase kb;
            try {
                kb = knowledgeBaseService.getKnowledgeBase(knowledgeBaseId);
            } catch (BizException e) {
                // Go: GetKnowledgeBaseByID err → handler NewInternalServerError(err.Error())
                throw new IllegalStateException(kbNotFoundMessage(e));
            }
            sourceEmbeddingModelId = kb.getEmbeddingModelId();
            sourceSummaryModelId = kb.getSummaryModelId();
        }
        KnowledgeBase created = new KnowledgeBase();
        created.setName("evaluation");
        created.setDescription("evaluation");
        created.setEmbeddingModelId(sourceEmbeddingModelId);
        created.setSummaryModelId(sourceSummaryModelId);
        KnowledgeBase newKb = knowledgeBaseService.createKnowledgeBase(created);

        if (datasetId.isEmpty()) {
            datasetId = "default";
        }
        final String dsId = datasetId;

        // rerank 缺省：模型表挑第一个 Rerank；无则跳过（Go 的 WARN 分支）
        if (rerankModelId.isEmpty()) {
            for (Model model : modelService.listModels()) {
                if (model != null && MODEL_TYPE_RERANK.equals(model.getType())) {
                    rerankModelId = model.getId();
                    break;
                }
            }
        }
        // chat 缺省：挑第一个 KnowledgeQA；无则失败
        if (chatModelId.isEmpty()) {
            for (Model model : modelService.listModels()) {
                if (model != null && MODEL_TYPE_KNOWLEDGE_QA.equals(model.getType())) {
                    chatModelId = model.getId();
                    break;
                }
            }
            if (chatModelId.isEmpty()) {
                throw new IllegalStateException(ERR_NO_DEFAULT_CHAT_MODEL);
            }
        }

        // 建任务（taskID 契约同 utils.GenerateTaskID："evaluation_<tenant>_<millis>_<8hex>_<biz>"）
        EvaluationDetail detail = new EvaluationDetail();
        EvaluationTask task = new EvaluationTask();
        task.id = KnowledgeTaskIds.generateTaskId("evaluation", tenantId, dsId);
        task.tenantId = tenantId;
        task.datasetId = dsId;
        task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_PENDING;
        task.startTime = OffsetDateTime.now(ZoneOffset.UTC);
        detail.task = task;
        detail.params = buildParams(chatModelId, rerankModelId);
        store.put(task.id, detail);

        // 后台执行（对照 Go 的 go func：running → EvalDataset → failed(err 原文)/success）
        final String evalKbId = newKb.getId();
        // 约定 §5：提交线程（HTTP 线程）capture，后台虚拟线程 replay，finally clear
        final TenantContextSnapshot tenantSnap = TenantContextSnapshot.capture();
        Thread.ofVirtual().name("evaluation-" + task.id).start(() -> {
            tenantSnap.replay();
            try {
                EvaluationDetail stored = store.get(task.id);
                if (stored == null) {
                    return;
                }
                stored.task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_RUNNING;
                log.info("Background evaluation started for task ID: {}", task.id);
                try {
                    evalDataset(stored, evalKbId);
                } catch (RuntimeException e) {
                    stored.task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_FAILED;
                    stored.task.errMsg = errorText(e);
                    log.error("Evaluation task failed: {}, task ID: {}", stored.task.errMsg, task.id);
                    return;
                }
                log.info("Evaluation task completed successfully, task ID: {}", task.id);
                stored.task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_SUCCESS;
            } finally {
                TenantContext.clear();
            }
        });
        // 返回创建时刻的快照（见类注释「Go 竞态的确定性化」）
        return snapshotOf(detail);
    }

    /**
     * 对照 Go EvalDataset（evaluation.go L333-472）：取数据集 → 段落建临时知识（同步索引）
     * → 并行逐 QA 跑 rag 管线 → 指标汇总 → 清理临时资源。
     */
    private void evalDataset(EvaluationDetail detail, String knowledgeBaseId) {
        // 1) 取数据集（Go: dataset.GetDatasetByID → PrintStats + Iterate）
        List<QaPair> dataset = datasetService.getDatasetByID(detail.task.datasetId);
        detail.task.total = dataset.size();

        // 2) 段落表（对照 getPassageList：按 PID 铺平，空洞留 ""）
        List<String> passages = getPassageList(dataset);
        // 3) 建临时知识（同步建索引；此步失败即抛出——清理 defer 尚未注册，Go 同款泄漏）
        Knowledge knowledge = knowledgeService.createFromPassageSync(knowledgeBaseId, passages, "");

        try {
            // 4) 并行评估（对照 errgroup + SetLimit(GOMAXPROCS-1)）
            int limit = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
            MetricHook.HookMetric metricHook = new MetricHook.HookMetric(dataset.size());
            AtomicInteger finished = new AtomicInteger();
            Object progressLock = new Object();
            long tenantId = detail.task.tenantId;
            // 约定 §5：提交线程（已 replay）capture，工作线程 replay，finally clear
            final TenantContextSnapshot qaSnap = TenantContextSnapshot.capture();

            ExecutorService executor = Executors.newFixedThreadPool(limit);
            try {
                List<Future<?>> futures = new ArrayList<>(dataset.size());
                for (int i = 0; i < dataset.size(); i++) {
                    final int index = i;
                    final QaPair qaPair = dataset.get(i);
                    futures.add(executor.submit(() -> {
                        qaSnap.replay();
                        try {
                            runQaPair(index, qaPair, detail, knowledgeBaseId, tenantId,
                                    metricHook, finished, progressLock);
                        } finally {
                            TenantContext.clear();
                        }
                    }));
                }
                RuntimeException firstError = null;
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        if (firstError == null) {
                            firstError = e.getCause() instanceof RuntimeException re
                                    ? re : new RuntimeException(e.getCause());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (firstError == null) {
                            firstError = new RuntimeException(e.getMessage());
                        }
                    }
                }
                if (firstError != null) {
                    throw firstError;
                }
            } finally {
                executor.shutdown();
            }

            // 5) 终态指标（对照 g.Wait 后的最终 update）
            synchronized (progressLock) {
                detail.metric = metricHook.metricResult();
                detail.task.finished = finished.get();
            }
        } finally {
            // 6) 清理（对照 defer：deleteReferencedKnowledge + DeleteKnowledgeBase）
            try {
                knowledgeService.deleteKnowledge(knowledge.getId());
            } catch (RuntimeException e) {
                log.error("Failed to delete knowledge: {}, knowledge ID: {}",
                        errorText(e), knowledge.getId());
            }
            try {
                knowledgeBaseService.deleteKnowledgeBase(knowledgeBaseId);
            } catch (RuntimeException e) {
                log.error("Failed to delete knowledge base: {}, knowledge base ID: {}",
                        errorText(e), knowledgeBaseId);
            }
        }
    }

    /** 单个 QA 对的评估（对照 errgroup 的 g.Go 体）：跑 rag 管线 → 记录全链路产物 → 进度。 */
    private void runQaPair(int index, QaPair qaPair, EvaluationDetail detail,
                           String knowledgeBaseId, long tenantId,
                           MetricHook.HookMetric metricHook, AtomicInteger finished,
                           Object progressLock) {
        ChatManage chatManage = buildChatManage(detail.params, knowledgeBaseId, qaPair, tenantId);
        sessionKnowledgeQaService.knowledgeQAByEvent(chatManage, PipelineBuilder.presets().get("rag"));

        metricHook.recordInit(index);
        metricHook.recordQaPair(index, qaPair);
        metricHook.recordSearchResult(index, chatManage.getSearchResult());
        metricHook.recordRerankResult(index, chatManage.getRerankResult());
        metricHook.recordChatResponse(index, chatManage.getChatResponse());
        metricHook.recordFinish(index);

        synchronized (progressLock) {
            finished.incrementAndGet();
            MetricResult metricResult = metricHook.metricResult();
            detail.metric = metricResult;
            detail.task.finished = finished.get();
            log.info("Updated task progress: {}/{} completed", detail.task.finished, detail.task.total);
        }
    }

    /**
     * 从创建时的 params 快照构造运行时 ChatManage（Go 侧 Params 本身就是 *ChatManage；
     * Java 的 JSON DTO 与运行时类型分离，故在此还原），并逐 QA 对设置 query/rewriteQuery/
     * knowledgeBaseIds/searchTargets（对照 EvalDataset 循环内的赋值）。
     */
    private static ChatManage buildChatManage(PipelineParams params, String knowledgeBaseId,
                                              QaPair qaPair, long tenantId) {
        ChatManage cm = new ChatManage();
        cm.setMaxRounds(params.maxRounds);
        cm.setVectorThreshold(params.vectorThreshold);
        cm.setKeywordThreshold(params.keywordThreshold);
        cm.setEmbeddingTopK(params.embeddingTopK);
        cm.setRerankModelId(params.rerankModelId);
        cm.setRerankTopK(params.rerankTopK);
        cm.setRerankThreshold(params.rerankThreshold);
        cm.setChatModelId(params.chatModelId);
        cm.setSummaryConfig(toSummaryConfig(params.summaryConfig));
        cm.setFallbackResponse(params.fallbackResponse);
        cm.setRewritePromptSystem(params.rewritePromptSystem);
        cm.setRewritePromptUser(params.rewritePromptUser);

        cm.setQuery(qaPair.question());
        cm.setRewriteQuery(qaPair.question());
        cm.setKnowledgeBaseIds(new ArrayList<>(List.of(knowledgeBaseId)));
        cm.setSearchTargets(new ArrayList<>(
                List.of(SearchTarget.wholeKb(knowledgeBaseId, tenantId))));
        return cm;
    }

    /** 对照 params 的 SummaryConfig 子集（其余字段 Go dev 配置为零值，DTO 未携带）。 */
    private static SummaryConfig toSummaryConfig(SummaryConfigParams dto) {
        SummaryConfig sc = new SummaryConfig();
        if (dto == null) {
            return sc;
        }
        sc.setRepeatPenalty(dto.repeatPenalty);
        sc.setPrompt(dto.prompt);
        sc.setContextTemplate(dto.contextTemplate);
        sc.setNoMatchPrefix(dto.noMatchPrefix);
        sc.setTemperature(dto.temperature);
        sc.setMaxCompletionTokens(dto.maxCompletionTokens);
        return sc;
    }

    /** 对照 getPassageList：PIDs → passages 铺平（maxPID+1 长，空洞留 ""）。 */
    private static List<String> getPassageList(List<QaPair> dataset) {
        Map<Integer, String> pidMap = new HashMap<>();
        int maxPid = 0;
        for (QaPair qaPair : dataset) {
            List<Integer> pids = qaPair.pids();
            List<String> passages = qaPair.passages();
            for (int i = 0; i < pids.size(); i++) {
                pidMap.put(pids.get(i), passages.get(i));
                maxPid = Math.max(maxPid, pids.get(i));
            }
        }
        List<String> passages = new ArrayList<>(java.util.Collections.nCopies(maxPid + 1, ""));
        for (int i = 0; i <= maxPid; i++) {
            if (pidMap.containsKey(i)) {
                passages.set(i, pidMap.get(i));
            }
        }
        return passages;
    }

    /** 对照 Go err.Error()：BizException 取 AppError 原文（Go AppError.Error() 语义），其余取 getMessage()。 */
    private static String errorText(RuntimeException e) {
        if (e instanceof BizException be) {
            String msg = be.appError().message();
            if (msg != null && !msg.isEmpty()) {
                return msg;
            }
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** 对照 EvaluationResult：内存查 → 租户匹配校验。失败消息 = Go 原文。 */
    public EvaluationDetail evaluationResult(long tenantId, String taskId) {
        EvaluationDetail detail = store.get(taskId);
        if (detail == null) {
            throw new IllegalStateException(ERR_TASK_NOT_FOUND);
        }
        if (tenantId != detail.task.tenantId) {
            throw new IllegalStateException(ERR_TENANT_MISMATCH);
        }
        return detail;
    }

    /** 对照 EvaluationService 构造 detail.params 的字段集（只赋 PipelineRequest 的固定子集）。 */
    private PipelineParams buildParams(String chatModelId, String rerankModelId) {
        PipelineParams params = new PipelineParams();
        params.maxRounds = maxRounds;
        params.vectorThreshold = vectorThreshold;
        params.keywordThreshold = keywordThreshold;
        params.embeddingTopK = embeddingTopK;
        params.rerankModelId = rerankModelId;
        params.rerankTopK = rerankTopK;
        params.rerankThreshold = rerankThreshold;
        params.chatModelId = chatModelId;
        SummaryConfigParams summary = new SummaryConfigParams();
        summary.repeatPenalty = 1.0;
        summary.prompt = EvaluationPromptDefaults.SUMMARY_PROMPT;
        summary.contextTemplate = EvaluationPromptDefaults.SUMMARY_CONTEXT_TEMPLATE;
        summary.noMatchPrefix = EvaluationPromptDefaults.SUMMARY_NO_MATCH_PREFIX;
        summary.temperature = 0.3;
        summary.maxCompletionTokens = 2048;
        params.summaryConfig = summary;
        params.fallbackResponse = EvaluationPromptDefaults.FALLBACK_RESPONSE;
        params.rewritePromptSystem = EvaluationPromptDefaults.REWRITE_PROMPT_SYSTEM;
        params.rewritePromptUser = EvaluationPromptDefaults.REWRITE_PROMPT_USER;
        return params;
    }

    private static EvaluationDetail snapshotOf(EvaluationDetail detail) {
        EvaluationDetail copy = new EvaluationDetail();
        EvaluationTask t = new EvaluationTask();
        t.id = detail.task.id;
        t.tenantId = detail.task.tenantId;
        t.datasetId = detail.task.datasetId;
        t.startTime = detail.task.startTime;
        t.status = detail.task.status;
        t.errMsg = detail.task.errMsg;
        t.total = detail.task.total;
        t.finished = detail.task.finished;
        copy.task = t;
        copy.params = detail.params;
        return copy;
    }

    /** Go 的 GetKnowledgeBaseByID 失败消息（"knowledge base not found"）透传。
     *  ⚠️ BizException.getMessage() 是 "error code: N, error message: …" 前缀形态
     *  （§9 波1 G1 #1），取 appError().message() 才是原文。 */
    private static String kbNotFoundMessage(BizException e) {
        String msg = e.appError().message();
        return msg == null || msg.isEmpty() ? "knowledge base not found" : msg;
    }

    /** 供契约测试直种任务（Go 的 register 等价；包内可见）。 */
    void registerForTest(EvaluationDetail detail) {
        store.put(detail.task.id, detail);
    }
}
