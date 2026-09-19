package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * IndexingStrategy（对照 Go types/indexing_strategy.go）。
 * 注意 Scan 语义：DB NULL（迁移前老行）→ DefaultIndexingStrategy()（vector+keyword=true）。
 * 该回退由 KnowledgeBaseMapper 查询侧显式处理（Java 无 GORM Scan 钩子）。
 */
@JsonPropertyOrder({"vector_enabled", "keyword_enabled", "wiki_enabled", "graph_enabled"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbIndexingStrategy {

    @JsonProperty("vector_enabled")
    private boolean vectorEnabled;
    @JsonProperty("keyword_enabled")
    private boolean keywordEnabled;
    @JsonProperty("wiki_enabled")
    private boolean wikiEnabled;
    @JsonProperty("graph_enabled")
    private boolean graphEnabled;

    public static KbIndexingStrategy defaultStrategy() {
        KbIndexingStrategy s = new KbIndexingStrategy();
        s.vectorEnabled = true;
        s.keywordEnabled = true;
        return s;
    }

    public boolean isVectorEnabled() { return vectorEnabled; }
    public void setVectorEnabled(boolean v) { vectorEnabled = v; }
    public boolean isKeywordEnabled() { return keywordEnabled; }
    public void setKeywordEnabled(boolean v) { keywordEnabled = v; }
    public boolean isWikiEnabled() { return wikiEnabled; }
    public void setWikiEnabled(boolean v) { wikiEnabled = v; }
    public boolean isGraphEnabled() { return graphEnabled; }
    public void setGraphEnabled(boolean v) { graphEnabled = v; }

    /** 对照 IsZero：全 false（新建请求体未传 strategy 时的判定依据） */
    /** Go 方法非字段，不参与 JSON */
    @JsonIgnore
    public boolean isZero() {
        return !vectorEnabled && !keywordEnabled && !wikiEnabled && !graphEnabled;
    }

    public boolean hasAnyIndexing() {
        return vectorEnabled || keywordEnabled || wikiEnabled || graphEnabled;
    }
}
