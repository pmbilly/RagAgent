package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import com.ragagent.common.web.GoDoubleSerializer;

/**
 * 混合检索参数（对照 Go {@code types.SearchParams}，internal/types/search.go:230-252）。
 *
 * <p>管线侧载体：HybridSearch seam（{@link PipelinePorts.KnowledgeBaseService}）的入参。
 * 字段语义逐条对照 Go 注释。</p>
 */
public final class SearchParams {

    /** 检索词。query_embedding 提供且两种匹配未被禁用时可为空。 */
    @JsonProperty("query_text")
    private String queryText = "";
    /** 查询向量（nil = 由实现侧现算）。 */
    @JsonProperty("query_embedding")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private float[] queryEmbedding;
    @JsonProperty("vector_threshold")
    @JsonSerialize(using = GoDoubleSerializer.class)
    private double vectorThreshold;
    @JsonProperty("keyword_threshold")
    @JsonSerialize(using = GoDoubleSerializer.class)
    private double keywordThreshold;
    @JsonProperty("match_count")
    private int matchCount;
    @JsonProperty("disable_keywords_match")
    private boolean disableKeywordsMatch;
    @JsonProperty("disable_vector_match")
    private boolean disableVectorMatch;
    /** 文档级过滤。 */
    @JsonProperty("knowledge_ids")
    private List<String> knowledgeIds;
    /** 标签过滤（FAQ 优先过滤用）。 */
    @JsonProperty("tag_ids")
    private List<String> tagIds;
    /** 用户选择的逻辑标签范围（文档库仅作 tracing）。 */
    @JsonProperty("scope_tag_ids")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> scopeTagIds;
    @JsonProperty("only_recommended")
    private boolean onlyRecommended;
    /** 覆盖 HybridSearch 的单个 KB ID 参数，实现一次跨多 KB 检索。 */
    @JsonProperty("knowledge_base_ids")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> knowledgeBaseIds;
    /** 跳过实现侧的父块/邻块/关系块补全（chat 管线的上下文组装在 merge 阶段）。 */
    @JsonProperty("skip_context_enrichment")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean skipContextEnrichment;

    public String getQueryText() { return queryText; }
    public void setQueryText(String v) { queryText = v == null ? "" : v; }
    public float[] getQueryEmbedding() { return queryEmbedding; }
    public void setQueryEmbedding(float[] v) { queryEmbedding = v; }
    public double getVectorThreshold() { return vectorThreshold; }
    public void setVectorThreshold(double v) { vectorThreshold = v; }
    public double getKeywordThreshold() { return keywordThreshold; }
    public void setKeywordThreshold(double v) { keywordThreshold = v; }
    public int getMatchCount() { return matchCount; }
    public void setMatchCount(int v) { matchCount = v; }
    public boolean isDisableKeywordsMatch() { return disableKeywordsMatch; }
    public void setDisableKeywordsMatch(boolean v) { disableKeywordsMatch = v; }
    public boolean isDisableVectorMatch() { return disableVectorMatch; }
    public void setDisableVectorMatch(boolean v) { disableVectorMatch = v; }
    public List<String> getKnowledgeIds() { return knowledgeIds; }
    public void setKnowledgeIds(List<String> v) { knowledgeIds = v; }
    public List<String> getTagIds() { return tagIds; }
    public void setTagIds(List<String> v) { tagIds = v; }
    public List<String> getScopeTagIds() { return scopeTagIds; }
    public void setScopeTagIds(List<String> v) { scopeTagIds = v; }
    public boolean isOnlyRecommended() { return onlyRecommended; }
    public void setOnlyRecommended(boolean v) { onlyRecommended = v; }
    public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<String> v) { knowledgeBaseIds = v; }
    public boolean isSkipContextEnrichment() { return skipContextEnrichment; }
    public void setSkipContextEnrichment(boolean v) { skipContextEnrichment = v; }
}
