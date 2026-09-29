package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;

/** 索引策略视图：向量 / 关键词 / wiki / 图 四类索引的启用开关。 */
public record IndexingStrategyView(boolean vectorEnabled, boolean keywordEnabled,
                                   boolean wikiEnabled, boolean graphEnabled) {

    public static IndexingStrategyView from(KnowledgeBaseIndexingStrategy s) {
        return s == null ? null
                : new IndexingStrategyView(s.isVectorEnabled(), s.isKeywordEnabled(),
                        s.isWikiEnabled(), s.isGraphEnabled());
    }

    public KnowledgeBaseIndexingStrategy toDomain() {
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
        s.setVectorEnabled(vectorEnabled);
        s.setKeywordEnabled(keywordEnabled);
        s.setWikiEnabled(wikiEnabled);
        s.setGraphEnabled(graphEnabled);
        return s;
    }
}
