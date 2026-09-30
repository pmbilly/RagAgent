package com.ragagent.evaluation.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

import com.ragagent.evaluation.domain.QaPair;
import com.ragagent.evaluation.dto.EvaluationDtos.MetricResult;
import com.ragagent.evaluation.metric.BleuMetric;
import com.ragagent.evaluation.metric.MapMetric;
import com.ragagent.evaluation.metric.MetricInput;
import com.ragagent.evaluation.metric.Metrics;
import com.ragagent.evaluation.metric.MrrMetric;
import com.ragagent.evaluation.metric.NdcgMetric;
import com.ragagent.evaluation.metric.PrecisionMetric;
import com.ragagent.evaluation.metric.RecallMetric;
import com.ragagent.evaluation.metric.RougeMetric;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.common.retrieval.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对照 Go metric_hook.go 全文：{@code MetricList}（逐条计算 + 均值）与
 * {@code HookMetric}（按 QA 对记录检索/重排/生成产物，recordFinish 时把命中分块
 * 反查回 passage ID 并计入指标）。
 *
 * <p>照抄要点：检索源<b>优先 rerank 结果、空则回退 search 结果</b>；命中 ID 靠
 * 「分块内容与真值 passage 互为包含」反查（ChunkIndex 与数据集 PID 无对应关系）；
 * {@code Append} 的日志在 Go 用 Background ctx（与调用 ctx 无关）。</p>
 */
public final class MetricHook {

    private MetricHook() {
    }

    /** 单个指标项（对照 Go metricCalculators 的匿名 struct {calc, getField}）。 */
    private record Slot(Metrics calc,
                        ToDoubleFunction<MetricResult> getter,
                        ObjDoubleConsumer<MetricResult> setter) {
    }

    /** 对照 metricCalculators：6 检索 + 6 生成，顺序/参数照 Go（BLEU 用 1/2/4 三档权重，smoothing=true）。 */
    private static final List<Slot> CALCULATORS = List.of(
            new Slot(new PrecisionMetric(),
                    r -> r.retrievalMetrics.precision,
                    (r, v) -> r.retrievalMetrics.precision = v),
            new Slot(new RecallMetric(),
                    r -> r.retrievalMetrics.recall,
                    (r, v) -> r.retrievalMetrics.recall = v),
            new Slot(new NdcgMetric(3),
                    r -> r.retrievalMetrics.ndcg3,
                    (r, v) -> r.retrievalMetrics.ndcg3 = v),
            new Slot(new NdcgMetric(10),
                    r -> r.retrievalMetrics.ndcg10,
                    (r, v) -> r.retrievalMetrics.ndcg10 = v),
            new Slot(new MrrMetric(),
                    r -> r.retrievalMetrics.mrr,
                    (r, v) -> r.retrievalMetrics.mrr = v),
            new Slot(new MapMetric(),
                    r -> r.retrievalMetrics.map,
                    (r, v) -> r.retrievalMetrics.map = v),
            new Slot(new BleuMetric(true, BleuMetric.BLEU1_GRAM),
                    r -> r.generationMetrics.bleu1,
                    (r, v) -> r.generationMetrics.bleu1 = v),
            new Slot(new BleuMetric(true, BleuMetric.BLEU2_GRAM),
                    r -> r.generationMetrics.bleu2,
                    (r, v) -> r.generationMetrics.bleu2 = v),
            new Slot(new BleuMetric(true, BleuMetric.BLEU4_GRAM),
                    r -> r.generationMetrics.bleu4,
                    (r, v) -> r.generationMetrics.bleu4 = v),
            new Slot(new RougeMetric(true, "rouge-1", "f"),
                    r -> r.generationMetrics.rouge1,
                    (r, v) -> r.generationMetrics.rouge1 = v),
            new Slot(new RougeMetric(true, "rouge-2", "f"),
                    r -> r.generationMetrics.rouge2,
                    (r, v) -> r.generationMetrics.rouge2 = v),
            new Slot(new RougeMetric(true, "rouge-l", "f"),
                    r -> r.generationMetrics.rougel,
                    (r, v) -> r.generationMetrics.rougel = v));

    /** 对照 MetricList：逐条 Append 计算，Avg 取每字段均值（空 → 全零）。 */
    public static final class MetricList {

        private static final Logger log = LoggerFactory.getLogger(MetricList.class);

        private final List<MetricResult> results = new ArrayList<>();

        /** 对照 Append：一次算全 12 项并记录（日志形态非契约）。 */
        public void append(MetricInput metricInput) {
            MetricResult result = new MetricResult();
            for (Slot c : CALCULATORS) {
                c.setter().accept(result, c.calc().compute(metricInput));
            }
            log.info("metric: retrieval=(p={}, r={}, ndcg3={}, ndcg10={}, mrr={}, map={}) "
                            + "generation=(bleu1={}, bleu2={}, bleu4={}, rouge1={}, rouge2={}, rougel={})",
                    result.retrievalMetrics.precision, result.retrievalMetrics.recall,
                    result.retrievalMetrics.ndcg3, result.retrievalMetrics.ndcg10,
                    result.retrievalMetrics.mrr, result.retrievalMetrics.map,
                    result.generationMetrics.bleu1, result.generationMetrics.bleu2,
                    result.generationMetrics.bleu4, result.generationMetrics.rouge1,
                    result.generationMetrics.rouge2, result.generationMetrics.rougel);
            results.add(result);
        }

        /** 对照 Avg：空列表 → 全零（Go 返回 &MetricResult{}）。 */
        public MetricResult avg() {
            MetricResult avgResult = new MetricResult();
            if (results.isEmpty()) {
                return avgResult;
            }
            double count = (double) results.size();
            for (Slot config : CALCULATORS) {
                double sum = 0.0;
                for (MetricResult r : results) {
                    sum += config.getter().applyAsDouble(r);
                }
                config.setter().accept(avgResult, sum / count);
            }
            return avgResult;
        }
    }

    /** 对照 HookMetric：按 QA 对收集产物 → recordFinish 计指标（RWMutex → synchronized）。 */
    public static final class HookMetric {

        private final QaPairMetric[] qaPairMetricList;
        private final MetricList metricResults = new MetricList();
        private final Object mu = new Object();

        /** 对照 qaPairMetric（运行中数据，clone 不复制）。 */
        private static final class QaPairMetric {
            QaPair qaPair;
            List<SearchResult> searchResult;
            List<SearchResult> rerankResult;
            ChatResponse chatResponse;
        }

        public HookMetric(int capacity) {
            this.qaPairMetricList = new QaPairMetric[capacity];
        }

        public void recordInit(int index) {
            qaPairMetricList[index] = new QaPairMetric();
        }

        public void recordQaPair(int index, QaPair qaPair) {
            qaPairMetricList[index].qaPair = qaPair;
        }

        public void recordSearchResult(int index, List<SearchResult> searchResult) {
            qaPairMetricList[index].searchResult = searchResult;
        }

        public void recordRerankResult(int index, List<SearchResult> rerankResult) {
            qaPairMetricList[index].rerankResult = rerankResult;
        }

        public void recordChatResponse(int index, ChatResponse chatResponse) {
            qaPairMetricList[index].chatResponse = chatResponse;
        }

        /**
         * 对照 recordFinish：检索源优先 rerank、空则回退 search；分块内容与真值 passage
         * 互为包含 → 记该 passage 的 PID（去重）；生成文本取 chatResponse.Content；
         * RetrievalGT=[qaPair.PIDs]、GeneratedGT=qaPair.Answer；加锁 Append。
         */
        public void recordFinish(int index) {
            List<SearchResult> retrievalSource = qaPairMetricList[index].rerankResult;
            if (retrievalSource == null || retrievalSource.isEmpty()) {
                retrievalSource = qaPairMetricList[index].searchResult;
            }

            QaPair qaPair = qaPairMetricList[index].qaPair;
            List<Integer> retrievalIDs = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            if (retrievalSource != null) {
                for (SearchResult r : retrievalSource) {
                    String content = r.getContent();
                    if (content == null || content.isEmpty()) {
                        continue;
                    }
                    List<String> passages = qaPair.passages();
                    for (int i = 0; i < passages.size(); i++) {
                        String passage = passages.get(i);
                        if (passage == null || passage.isEmpty()) {
                            continue;
                        }
                        if (passage.contains(content) || content.contains(passage)) {
                            int pid = qaPair.pids().get(i);
                            if (seen.add(pid)) {
                                retrievalIDs.add(pid);
                            }
                            break;
                        }
                    }
                }
            }

            String generatedTexts = "";
            if (qaPairMetricList[index].chatResponse != null) {
                generatedTexts = qaPairMetricList[index].chatResponse.getContent();
            }

            MetricInput metricInput = MetricInput.retrieval(List.of(qaPair.pids()), retrievalIDs);
            metricInput.generatedTexts = generatedTexts == null ? "" : generatedTexts;
            metricInput.generatedGT = qaPair.answer();

            synchronized (mu) {
                metricResults.append(metricInput);
            }
        }

        /** 对照 MetricResult()：加锁取均值。 */
        public MetricResult metricResult() {
            synchronized (mu) {
                return metricResults.avg();
            }
        }
    }
}
