package com.ragagent.event;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 排序事件数据（对照 Go {@code event.RerankData}，internal/event/event_data.go:30-39）。
 */
@JsonPropertyOrder({"query", "input_count", "output_count", "model_id", "threshold",
        "results", "duration_ms", "extra"})
public class RerankData {

    @JsonProperty("query")
    private String query = "";

    /** 输入的候选数量 */
    @JsonProperty("input_count")
    private int inputCount;

    /** 输出的结果数量 */
    @JsonProperty("output_count")
    private int outputCount;

    @JsonProperty("model_id")
    private String modelId = "";

    /** 同 RetrievalData.threshold：包装类型防 Jackson 原生 primitive 序列化器绕过 Go 浮点格式 */
    @JsonProperty("threshold")
    private Double threshold = 0.0;

    /** Go omitempty */
    @JsonProperty("results")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Object results;

    /** 排序耗时（毫秒）；Go omitempty */
    @JsonProperty("duration_ms")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long durationMs;

    /** Go omitempty */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public RerankData() {
    }

    public RerankData(String query, int inputCount, int outputCount, String modelId,
                      double threshold, Object results, long durationMs, Map<String, Object> extra) {
        this.query = QueryData.orEmpty(query);
        this.inputCount = inputCount;
        this.outputCount = outputCount;
        this.modelId = QueryData.orEmpty(modelId);
        this.threshold = threshold;
        this.results = results;
        this.durationMs = durationMs;
        this.extra = extra;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String v) {
        this.query = QueryData.orEmpty(v);
    }

    public int getInputCount() {
        return inputCount;
    }

    public void setInputCount(int v) {
        this.inputCount = v;
    }

    public int getOutputCount() {
        return outputCount;
    }

    public void setOutputCount(int v) {
        this.outputCount = v;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String v) {
        this.modelId = QueryData.orEmpty(v);
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double v) {
        this.threshold = v == null ? 0.0 : v;
    }

    public Object getResults() {
        return results;
    }

    public void setResults(Object v) {
        this.results = v;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long v) {
        this.durationMs = v;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> v) {
        this.extra = v;
    }
}
