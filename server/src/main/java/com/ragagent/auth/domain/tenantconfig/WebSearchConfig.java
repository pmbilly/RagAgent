package com.ragagent.auth.domain.tenantconfig;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 联网搜索配置段。
 *
 * <p>字段序 = JSON 键序；provider/api_key 与 rag 压缩四字段 + proxy_url 为
 * 空值省略，其余恒输出。{@code Filters} 字段不在本投影（服务端不消费）。</p>
 *
 * <p>字符串字段默认 ""，blacklist 默认 null（json 输出 {@code null}；
 * 经 Effective 归一化后才变 []）。</p>
 */
@JsonPropertyOrder({
        "provider", "api_key", "max_results", "include_date", "compression_method",
        "blacklist", "embedding_model_id", "embedding_dimension", "rerank_model_id",
        "document_fragments", "proxy_url"
})
public class WebSearchConfig {

    /** max_results 的生效下限/缺省值。 */
    public static final int DEFAULT_MAX_RESULTS = 10;
    /** compression_method 的缺省值。 */
    public static final String DEFAULT_COMPRESSION_METHOD = "none";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("provider")
    private String provider = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("api_key")
    private String apiKey = "";

    @JsonProperty("max_results")
    private int maxResults;

    @JsonProperty("include_date")
    private boolean includeDate;

    @JsonProperty("compression_method")
    private String compressionMethod = "";

    /** 列表字段恒输出：null → "blacklist":null，[] → [] */
    @JsonProperty("blacklist")
    private List<String> blacklist;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("embedding_model_id")
    private String embeddingModelId = "";

    /** 数值 0 省略键 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("embedding_dimension")
    private int embeddingDimension;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("rerank_model_id")
    private String rerankModelId = "";

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("document_fragments")
    private int documentFragments;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("proxy_url")
    private String proxyUrl = "";

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v == null ? "" : v; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int v) { maxResults = v; }
    public boolean isIncludeDate() { return includeDate; }
    public void setIncludeDate(boolean v) { includeDate = v; }
    public String getCompressionMethod() { return compressionMethod; }
    public void setCompressionMethod(String v) { compressionMethod = v == null ? "" : v; }
    public List<String> getBlacklist() { return blacklist; }
    public void setBlacklist(List<String> v) { blacklist = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int v) { embeddingDimension = v; }
    public String getRerankModelId() { return rerankModelId; }
    public void setRerankModelId(String v) { rerankModelId = v == null ? "" : v; }
    public int getDocumentFragments() { return documentFragments; }
    public void setDocumentFragments(int v) { documentFragments = v; }
    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String v) { proxyUrl = v == null ? "" : v; }

    /**
     * 归一化生效值：原地修改本对象
     * （max_results≤0→10、compression_method 空→"none"、blacklist null→[]）。
     */
    public void applyEffective() {
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        if (compressionMethod.isEmpty()) {
            compressionMethod = DEFAULT_COMPRESSION_METHOD;
        }
        if (blacklist == null) {
            blacklist = List.of();
        }
    }
}
