package com.ragagent.event.payload;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 检索事件数据。
 *
 * <p>零值输出
 * {@code {"query":"","knowledge_base_id":"","top_k":0,"threshold":0,"retrieval_type":"","result_count":0}}
 * （results/duration_ms/extra 空则省略）。</p>
 */
@JsonPropertyOrder({"query", "knowledge_base_id", "top_k", "threshold", "retrieval_type",
        "result_count", "results", "duration_ms", "extra"})
public class RetrievalData {

    @JsonProperty("query")
    private String query = "";

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    @JsonProperty("top_k")
    private int topK;

    /** 0 也输出。用包装类型 Double：primitive double 走 Jackson 内置
     * PrimitiveDoubleSerializer，会绕过 EventJson 注册的浮点格式（0.0 ≠ 0，实测踩过） */
    @JsonProperty("threshold")
    private Double threshold = 0.0;

    /** vector, keyword, entity */
    @JsonProperty("retrieval_type")
    private String retrievalType = "";

    @JsonProperty("result_count")
    private int resultCount;

    /** null 或空省略 */
    @JsonProperty("results")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Object results;

    /** 检索耗时（毫秒）；0 省略 */
    @JsonProperty("duration_ms")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long durationMs;

    /** null 或空省略 */
    @JsonProperty("extra")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extra;

    public RetrievalData() {
    }

    public RetrievalData(String query, String knowledgeBaseId, int topK, double threshold,
                         String retrievalType, int resultCount, Object results,
                         long durationMs, Map<String, Object> extra) {
        this.query = QueryData.orEmpty(query);
        this.knowledgeBaseId = QueryData.orEmpty(knowledgeBaseId);
        this.topK = topK;
        this.threshold = threshold;
        this.retrievalType = QueryData.orEmpty(retrievalType);
        this.resultCount = resultCount;
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

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String v) {
        this.knowledgeBaseId = QueryData.orEmpty(v);
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int v) {
        this.topK = v;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double v) {
        this.threshold = v == null ? 0.0 : v;
    }

    public String getRetrievalType() {
        return retrievalType;
    }

    public void setRetrievalType(String v) {
        this.retrievalType = QueryData.orEmpty(v);
    }

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int v) {
        this.resultCount = v;
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
