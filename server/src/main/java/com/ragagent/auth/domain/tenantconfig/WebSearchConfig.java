package com.ragagent.auth.domain.tenantconfig;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对照 Go {@code types.WebSearchConfig}（internal/types/web_search.go L10-32）。
 *
 * <p>字段序 = Go struct 声明序；omitempty 逐字段对照（provider/api_key 与
 * rag 压缩四字段 + proxy_url 是 omitempty，其余恒输出）。
 * Go 的 {@code Filters} 字段 json:"-"，不翻。</p>
 *
 * <p>字符串字段默认 ""（Go 零值），blacklist 默认 null（Go nil slice →
 * json 输出 {@code null}；经 Effective 归一化后才变 []）。</p>
 */
@JsonPropertyOrder({
        "provider", "api_key", "max_results", "include_date", "compression_method",
        "blacklist", "embedding_model_id", "embedding_dimension", "rerank_model_id",
        "document_fragments", "proxy_url"
})
public class WebSearchConfig {

    /** 对照 DefaultWebSearchMaxResults。 */
    public static final int DEFAULT_MAX_RESULTS = 10;
    /** 对照 DefaultWebSearchCompressionMethod。 */
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

    /** Go []string 无 omitempty：null → "blacklist":null，[] → [] */
    @JsonProperty("blacklist")
    private List<String> blacklist;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("embedding_model_id")
    private String embeddingModelId = "";

    /** Go int + omitempty：0 省略 */
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
     * 对照 EffectiveWebSearchConfig（web_search.go L47-63）：
     * 原地归一化本对象（Go 是拷贝后返回，调用方语义等价）。
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
