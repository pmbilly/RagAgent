package com.ragagent.event.payload;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 合并事件数据。
 */
@JsonPropertyOrder({"input_count", "output_count", "merge_type", "results", "duration_ms", "extra"})
public class MergeData {

    @JsonProperty("input_count")
    private int inputCount;

    @JsonProperty("output_count")
    private int outputCount;

    /** dedup, fusion, etc. */
    @JsonProperty("merge_type")
    private String mergeType = "";

    /** null 或空省略 */
    @JsonProperty("results")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Object results;

    /** 0 省略 */
    @JsonProperty("duration_ms")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long durationMs;

    /** null 或空省略 */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public MergeData() {
    }

    public MergeData(int inputCount, int outputCount, String mergeType, Object results,
                     long durationMs, Map<String, Object> extra) {
        this.inputCount = inputCount;
        this.outputCount = outputCount;
        this.mergeType = QueryData.orEmpty(mergeType);
        this.results = results;
        this.durationMs = durationMs;
        this.extra = extra;
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

    public String getMergeType() {
        return mergeType;
    }

    public void setMergeType(String v) {
        this.mergeType = QueryData.orEmpty(v);
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
