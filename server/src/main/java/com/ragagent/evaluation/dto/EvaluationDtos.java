package com.ragagent.evaluation.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评估任务详情响应（task + params + metric 三层结构）。
 *
 * <p>字段名即 JSON 键名（camelCase，无命名注解）；Jackson 按字段声明序输出；
 * 可空字段显式输出 {@code null}（未产出的 metric 为 null，不退化为缺键）。
 * metric 由执行步（MetricHook + evaluation.metric 包）产出。</p>
 */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    /** 任务状态：0=pending 1=running 2=success 3=failed。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    /** 评估任务（含进度计数；errMsg/total/finished 未更新时为零值）。 */
    public static final class EvaluationTask {
        public String id = "";
        public long tenantId;
        public String datasetId = "";
        /** 任务开始时间；未开始时为 null。 */
        public OffsetDateTime startTime;
        public int status;
        public String errMsg = "";
        public int total;
        public int finished;
    }

    /** 评估运行的管线参数快照（summaryConfig.thinking 未设置时输出 null）。 */
    public static final class SummaryConfigParams {
        public int maxTokens;
        public double repeatPenalty;
        public int topK;
        public double topP;
        public double frequencyPenalty;
        public double presencePenalty;
        public String prompt = "";
        public String contextTemplate = "";
        public String noMatchPrefix = "";
        public double temperature;
        public int seed;
        public int maxCompletionTokens;
        public Boolean thinking;
    }

    /** 评估用检索管线参数（评估路径只赋固定子集，其余保持零值/null）。 */
    public static final class PipelineParams {
        public String sessionId = "";
        public String userId = "";
        public String query = "";
        public int maxRounds;
        public List<String> knowledgeBaseIds;
        public List<String> knowledgeIds;
        public double vectorThreshold;
        public double keywordThreshold;
        public int embeddingTopK;
        public String vectorDatabase = "";
        public String rerankModelId = "";
        public int rerankTopK;
        public double rerankThreshold;
        public String chatModelId = "";
        public SummaryConfigParams summaryConfig;
        public String fallbackStrategy = "";
        public String fallbackResponse = "";
        public String fallbackPrompt = "";
        public Boolean citationEnabled;
        public boolean enableRewrite;
        public boolean enableQueryExpansion;
        public String rewritePromptSystem = "";
        public String rewritePromptUser = "";
        public String queryUnderstandModelId = "";
    }

    /** 评估详情：任务 + 参数快照 + 指标（metric 未产出时为 null）。 */
    public static final class EvaluationDetail {
        public EvaluationTask task;
        public PipelineParams params;
        public MetricResult metric;
    }

    /** 指标汇总（检索 + 生成两组，全字段恒输出）。 */
    public static final class MetricResult {
        public RetrievalMetrics retrievalMetrics = new RetrievalMetrics();
        public GenerationMetrics generationMetrics = new GenerationMetrics();
    }

    /** 六项检索指标（键名含 ndcg3/ndcg10，与指标算法同名）。 */
    public static final class RetrievalMetrics {

        public double precision;
        public double recall;
        public double ndcg3;
        public double ndcg10;
        public double mrr;
        public double map;
    }

    /** 六项生成指标（BLEU-1/2/4 + ROUGE-1/2/L；rougel 名称的历史拼写保留）。 */
    public static final class GenerationMetrics {

        public double bleu1;
        public double bleu2;
        public double bleu4;
        public double rouge1;
        public double rouge2;
        public double rougel;
    }
}
