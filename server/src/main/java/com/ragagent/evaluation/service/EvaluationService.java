package com.ragagent.evaluation.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ragagent.common.error.BizException;
import com.ragagent.evaluation.dto.EvaluationDtos.EvaluationDetail;
import com.ragagent.evaluation.dto.EvaluationDtos.EvaluationTask;
import com.ragagent.evaluation.dto.EvaluationDtos.PipelineParams;
import com.ragagent.evaluation.dto.EvaluationDtos.SummaryConfigParams;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 评估服务（对照 Go internal/application/service/evaluation.go 的 Evaluation /
 * EvaluationResult；内存任务存储 evaluationMemoryStorage 的等价 ConcurrentHashMap）。
 *
 * <h2>执行步降级（已知差异，Javadoc 即契约）</h2>
 * <p>Go 的 EvalDataset 在后台 goroutine 里跑完整流水线：取 dataset → 把 passages
 * 建进临时 KB → 逐 QA 对跑 KnowledgeQAByEvent（LLM+检索）→ 汇总
 * retrieval/generation metrics → 删除临时资源。Java 侧的检索执行（波 4）与
 * LLM 运行时工厂（阶段 7）未接线，dataset 服务亦未翻译——执行步在标记 running 后
 * 以 Go 的<b>失败形态</b>收场：status=failed + err_msg=
 * {@code evaluation execution is not available in this deployment}。
 * 前置的确定性分支（模型存在性 / KB 存在性 / 任务注册 / 结果查询与租户隔离）逐字翻译；
 * golden 的 ev-get.json 记录的是 Go dev 全执行的终态（status=2 + metrics），
 * 属<b>部署能力差异</b>，A/B 时按部署各自断言。</p>
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
    public static final String ERR_EXECUTION_DEGRADED =
            "evaluation execution is not available in this deployment";

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

    /** 对照 evaluationMemoryStorage：taskID → detail。 */
    private final Map<String, EvaluationDetail> store = new ConcurrentHashMap<>();

    public EvaluationService(ModelService modelService, KnowledgeBaseService knowledgeBaseService) {
        this.modelService = modelService;
        this.knowledgeBaseService = knowledgeBaseService;
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
        task.id = KnowledgeService.generateTaskId("evaluation", tenantId, dsId);
        task.tenantId = tenantId;
        task.datasetId = dsId;
        task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_PENDING;
        task.startTime = OffsetDateTime.now(ZoneOffset.UTC);
        detail.task = task;
        detail.params = buildParams(chatModelId, rerankModelId);
        store.put(task.id, detail);

        // 后台执行（降级：running → failed；语义见类注释）
        Thread.ofVirtual().name("evaluation-" + task.id).start(() -> {
            EvaluationDetail stored = store.get(task.id);
            if (stored == null) {
                return;
            }
            stored.task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_RUNNING;
            log.info("evaluation task {} degraded: {}", task.id, ERR_EXECUTION_DEGRADED);
            stored.task.status = com.ragagent.evaluation.dto.EvaluationDtos.STATUS_FAILED;
            stored.task.errMsg = ERR_EXECUTION_DEGRADED;
        });
        // 返回创建时刻的快照（见类注释「Go 竞态的确定性化」）
        return snapshotOf(detail);
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
