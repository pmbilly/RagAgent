package com.ragagent.auth.domain.tenantconfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoDoubleSerializer;

/**
 * 对照 Go {@code types.RetrievalConfig}（internal/types/retrieval_config.go L15-40）。
 *
 * <p>前六个字段无 omitempty 恒输出；rrf_* 三个是 omitempty（0 / 0.0 省略）。
 * float64 一律挂 {@link GoDoubleSerializer}——Go 的零值输出 {@code 0} 而非
 * {@code 0.0}（golden ct-kv-ret-get-default 钉住）。</p>
 */
@JsonPropertyOrder({
        "embedding_top_k", "vector_threshold", "keyword_threshold",
        "rerank_top_k", "rerank_threshold", "rerank_model_id",
        "rrf_k", "rrf_vector_weight", "rrf_keyword_weight"
})
public class RetrievalConfig {

    @JsonProperty("embedding_top_k")
    private int embeddingTopK;


    @JsonProperty("vector_threshold")
    private double vectorThreshold;


    @JsonProperty("keyword_threshold")
    private double keywordThreshold;

    @JsonProperty("rerank_top_k")
    private int rerankTopK;


    @JsonProperty("rerank_threshold")
    private double rerankThreshold;

    @JsonProperty("rerank_model_id")
    private String rerankModelId = "";

    /** Go int + omitempty：0 省略 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("rrf_k")
    private int rrfK;

    /** Go float64 + omitempty：0.0 省略（NON_DEFAULT 对 primitive double 即 0.0） */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("rrf_vector_weight")
    private double rrfVectorWeight;

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("rrf_keyword_weight")
    private double rrfKeywordWeight;

    public int getEmbeddingTopK() { return embeddingTopK; }
    public void setEmbeddingTopK(int v) { embeddingTopK = v; }
    public double getVectorThreshold() { return vectorThreshold; }
    public void setVectorThreshold(double v) { vectorThreshold = v; }
    public double getKeywordThreshold() { return keywordThreshold; }
    public void setKeywordThreshold(double v) { keywordThreshold = v; }
    public int getRerankTopK() { return rerankTopK; }
    public void setRerankTopK(int v) { rerankTopK = v; }
    public double getRerankThreshold() { return rerankThreshold; }
    public void setRerankThreshold(double v) { rerankThreshold = v; }
    public String getRerankModelId() { return rerankModelId; }
    public void setRerankModelId(String v) { rerankModelId = v == null ? "" : v; }
    public int getRrfK() { return rrfK; }
    public void setRrfK(int v) { rrfK = v; }
    public double getRrfVectorWeight() { return rrfVectorWeight; }
    public void setRrfVectorWeight(double v) { rrfVectorWeight = v; }
    public double getRrfKeywordWeight() { return rrfKeywordWeight; }
    public void setRrfKeywordWeight(double v) { rrfKeywordWeight = v; }
}
