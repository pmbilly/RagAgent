package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * IndexingStrategy。
 * 注意 Scan 语义：DB NULL（迁移前老行）→ DefaultIndexingStrategy()（vector+keyword=true）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbIndexingStrategy {

    private boolean vectorEnabled;
    private boolean keywordEnabled;
    private boolean wikiEnabled;
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

    /** 全 false（新建请求体未传 strategy 时的判定依据） */
    @JsonIgnore
    public boolean isZero() {
        return !vectorEnabled && !keywordEnabled && !wikiEnabled && !graphEnabled;
    }

    public boolean hasAnyIndexing() {
        return vectorEnabled || keywordEnabled || wikiEnabled || graphEnabled;
    }
}
