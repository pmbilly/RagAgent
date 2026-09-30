package com.ragagent.knowledge.dto.faq;


/** FAQ 合并明细：命中的标准问及变化量（答案/相似问/负例）。 */
public record FaqMergeDetail(
        int index,
        String standardQuestion,
        boolean answerChanged,
        int newSimilarCount,
        int newNegativeCount) {
}
