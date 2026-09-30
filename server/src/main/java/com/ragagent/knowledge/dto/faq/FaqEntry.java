package com.ragagent.knowledge.dto.faq;

import java.time.OffsetDateTime;
import java.util.List;

/** 未打标签的 FAQ 条目统一显示名。 */
public record FaqEntry(

        long id,
        String chunkId,
        String knowledgeId,
        String knowledgeBaseId,
        long tagId,
        String tagName,
        boolean enabled,
        boolean recommended,
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        String answerStrategy,
        String indexMode,
        OffsetDateTime updatedAt,
        OffsetDateTime createdAt,
        String chunkType,
        FaqMatch match) {

    /** 未打标签的 FAQ 条目统一显示名（无 tag 时使用）。 */
    public static final String UNTAGGED_TAG_NAME = "未分类";

    /**
     * 检索命中信息。
     *
     * @param score           相似度分数
     * @param type            命中方式（0 = 关键词，1 = 向量）
     * @param matchedQuestion 命中的问题原文（命中相似问法时非空）
     */
    public record FaqMatch(double score, int type, String matchedQuestion) {
    }

    /** 覆盖检索命中信息（列表/详情场景传 {@code null} 表示无命中信息）。 */
    public FaqEntry withMatch(FaqMatch hit) {
        return new FaqEntry(id, chunkId, knowledgeId, knowledgeBaseId, tagId, tagName, enabled,
                recommended, standardQuestion, similarQuestions, negativeQuestions, answers,
                answerStrategy, indexMode, updatedAt, createdAt, chunkType, hit);
    }

    /** 覆盖 tagName 的视图重建。 */
    public FaqEntry withTagName(String newTagName) {
        return new FaqEntry(id, chunkId, knowledgeId, knowledgeBaseId, tagId,
                newTagName == null ? "" : newTagName, enabled, recommended, standardQuestion,
                similarQuestions, negativeQuestions, answers, answerStrategy, indexMode,
                updatedAt, createdAt, chunkType, match);
    }
}
