package com.ragagent.evaluation.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 评估响应 DTO（对照 Go internal/types/evaluation.go + chat_manage.go 里被
 * EvaluationService 实际赋值的 PipelineRequest/SummaryConfig 子集）。
 *
 * <p>键序 = Go struct 字段声明序；double 字段挂 {@link GoDoubleSerializer}
 * （Go 专用编码器：整数值不带 .0，如 repeat_penalty=1、vector_threshold=0.2）。
 * metric 字段（检索/生成指标）由执行步（MetricHook + evaluation.metric 包）产出；
 * 未产出前为 null → 与 Go 的 {@code *MetricResult} + omitempty 同形（整键省略）。</p>
 */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    /** 对照 types.EvaluationStatue：0=pending 1=running 2=success 3=failed。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    /** 对照 types.EvaluationTask（err_msg/total/finished omitempty）。 */
    @JsonPropertyOrder({"id", "tenant_id", "dataset_id", "start_time", "status",
            "err_msg", "total", "finished"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class EvaluationTask {
        @JsonProperty("id") public String id = "";
        @JsonProperty("tenant_id") public long tenantId;
        @JsonProperty("dataset_id") public String datasetId = "";
        /** Go time.Time 零值语义：字段默认值即 Go 零值（null 序列化不走自定义序列化器）。 */
        @JsonProperty("start_time")
        public OffsetDateTime startTime = GoTimeSerializer.GO_ZERO_DATE_TIME;
        @JsonProperty("status") public int status;
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("err_msg") public String errMsg = "";
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("total") public int total;
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("finished") public int finished;
    }

    /** 对照 types.SummaryConfig（全部无 omitempty → 恒输出，thinking null 也输出）。 */
    @JsonPropertyOrder({"max_tokens", "repeat_penalty", "top_k", "top_p",
            "frequency_penalty", "presence_penalty", "prompt", "context_template",
            "no_match_prefix", "temperature", "seed", "max_completion_tokens", "thinking"})
    public static final class SummaryConfigParams {
        @JsonProperty("max_tokens") public int maxTokens;
        @JsonProperty("repeat_penalty") public double repeatPenalty;
        @JsonProperty("top_k") public int topK;
        @JsonProperty("top_p") public double topP;
        @JsonProperty("frequency_penalty") public double frequencyPenalty;
        @JsonProperty("presence_penalty") public double presencePenalty;
        @JsonProperty("prompt") public String prompt = "";
        @JsonProperty("context_template") public String contextTemplate = "";
        @JsonProperty("no_match_prefix") public String noMatchPrefix = "";
        @JsonProperty("temperature") public double temperature;
        @JsonProperty("seed") public int seed;
        @JsonProperty("max_completion_tokens") public int maxCompletionTokens;
        /** *bool 无 omitempty：nil → "thinking":null */
        @JsonProperty("thinking") public Boolean thinking;
    }

    /**
     * 对照 ChatManage 序列化里来自 PipelineRequest 的字段（SearchTargets 是 json:"-"、
     * PipelineState/PipelineContext 全部 json:"-" 或 omitempty 空值 → 不出现在响应）。
     */
    @JsonPropertyOrder({"session_id", "user_id", "query", "max_rounds",
            "knowledge_base_ids", "knowledge_ids", "vector_threshold", "keyword_threshold",
            "embedding_top_k", "vector_database", "rerank_model_id", "rerank_top_k",
            "rerank_threshold", "chat_model_id", "summary_config", "fallback_strategy",
            "fallback_response", "fallback_prompt", "citation_enabled",
            "enable_rewrite", "enable_query_expansion", "rewrite_prompt_system",
            "rewrite_prompt_user", "query_understand_model_id"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PipelineParams {
        @JsonProperty("session_id") public String sessionId = "";
        @JsonProperty("user_id") public String userId = "";
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("query") public String query = "";
        @JsonProperty("max_rounds") public int maxRounds;
        /** 无 omitempty：null 输出 null（评估路径从不赋值）——字段级 ALWAYS 覆盖类级 NON_NULL */
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonProperty("knowledge_base_ids") public List<String> knowledgeBaseIds;
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("knowledge_ids") public List<String> knowledgeIds;
        @JsonProperty("vector_threshold") public double vectorThreshold;
        @JsonProperty("keyword_threshold") public double keywordThreshold;
        @JsonProperty("embedding_top_k") public int embeddingTopK;
        @JsonProperty("vector_database") public String vectorDatabase = "";
        @JsonProperty("rerank_model_id") public String rerankModelId = "";
        @JsonProperty("rerank_top_k") public int rerankTopK;
        @JsonProperty("rerank_threshold") public double rerankThreshold;
        @JsonProperty("chat_model_id") public String chatModelId = "";
        @JsonProperty("summary_config") public SummaryConfigParams summaryConfig;
        @JsonProperty("fallback_strategy") public String fallbackStrategy = "";
        @JsonProperty("fallback_response") public String fallbackResponse = "";
        @JsonProperty("fallback_prompt") public String fallbackPrompt = "";
        /** *bool omitempty：nil → 键省略 */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("citation_enabled") public Boolean citationEnabled;
        @JsonProperty("enable_rewrite") public boolean enableRewrite;
        @JsonProperty("enable_query_expansion") public boolean enableQueryExpansion;
        @JsonProperty("rewrite_prompt_system") public String rewritePromptSystem = "";
        @JsonProperty("rewrite_prompt_user") public String rewritePromptUser = "";
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonProperty("query_understand_model_id") public String queryUnderstandModelId = "";
    }

    /**
     * 对照 types.EvaluationDetail：metric 指针 + omitempty → null（未产出）时整键省略
     * （执行步产出前恒 null；产出后为全字段的 MetricResult）。
     */
    @JsonPropertyOrder({"task", "params", "metric"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class EvaluationDetail {
        @JsonProperty("task") public EvaluationTask task;
        @JsonProperty("params") public PipelineParams params;
        @JsonProperty("metric") public MetricResult metric;
    }

    /** 对照 types.MetricResult（无 omitempty → 全字段恒输出；嵌套序照 Go）。 */
    @JsonPropertyOrder({"retrieval_metrics", "generation_metrics"})
    public static final class MetricResult {
        @JsonProperty("retrieval_metrics")
        public RetrievalMetrics retrievalMetrics = new RetrievalMetrics();
        @JsonProperty("generation_metrics")
        public GenerationMetrics generationMetrics = new GenerationMetrics();
    }

    /** 对照 types.RetrievalMetrics（六项检索指标，键名含 ndcg3/ndcg10）。 */
    @JsonPropertyOrder({"precision", "recall", "ndcg3", "ndcg10", "mrr", "map"})
    public static final class RetrievalMetrics {

        @JsonProperty("precision") public double precision;
        @JsonProperty("recall") public double recall;
        @JsonProperty("ndcg3") public double ndcg3;
        @JsonProperty("ndcg10") public double ndcg10;
        @JsonProperty("mrr") public double mrr;
        @JsonProperty("map") public double map;
    }

    /** 对照 types.GenerationMetrics（BLEU-1/2/4 + ROUGE-1/2/L；rougel 键名照 Go）。 */
    @JsonPropertyOrder({"bleu1", "bleu2", "bleu4", "rouge1", "rouge2", "rougel"})
    public static final class GenerationMetrics {

        @JsonProperty("bleu1") public double bleu1;
        @JsonProperty("bleu2") public double bleu2;
        @JsonProperty("bleu4") public double bleu4;
        @JsonProperty("rouge1") public double rouge1;
        @JsonProperty("rouge2") public double rouge2;
        @JsonProperty("rougel") public double rougel;
    }
}
