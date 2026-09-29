package com.ragagent.knowledge.dto;

import java.util.List;

/** FAQ 导入失败条目：原因/类型/是否部分失败 + 原条目内容。 */
public record FaqFailedEntry(
        int index,
        String reason,
        String failureType,
        boolean partialFailure,
        String tagName,
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        boolean answerAll,
        boolean disabled,
        List<String> removedSimilarQuestions,
        List<String> removedNegativeQuestions) {
}
