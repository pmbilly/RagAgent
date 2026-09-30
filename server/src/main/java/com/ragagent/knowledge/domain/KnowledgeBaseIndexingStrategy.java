package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.common.web.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * IndexingStrategy。
 * 注意 Scan 语义：DB NULL（迁移前老行）→ DefaultIndexingStrategy()（vector+keyword=true）。
 */
public class KnowledgeBaseIndexingStrategy {

    private boolean vectorEnabled;
    private boolean keywordEnabled;
    private boolean wikiEnabled;
    private boolean graphEnabled;

    public static KnowledgeBaseIndexingStrategy defaultStrategy() {
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
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

    /** 从 KB 配置 jsonb 读取（null/解析失败 → 默认值）。 */
    public static KnowledgeBaseIndexingStrategy from(JsonNode node) {
        if (node == null || node.isNull()) {
            return KnowledgeBaseIndexingStrategy.defaultStrategy();
        }
        try {
            KnowledgeBaseIndexingStrategy parsed = JsonMappers.lenient().convertValue(node, KnowledgeBaseIndexingStrategy.class);
            return parsed == null ? KnowledgeBaseIndexingStrategy.defaultStrategy() : parsed;
        } catch (IllegalArgumentException e) {
            return KnowledgeBaseIndexingStrategy.defaultStrategy();
        }
    }
}
