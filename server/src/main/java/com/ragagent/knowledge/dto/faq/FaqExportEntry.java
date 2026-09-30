package com.ragagent.knowledge.dto.faq;

import java.util.List;

/** FAQ 导出条目：答案、相似问/负例、策略与开关。 */
public record FaqExportEntry(
        long id,
        String tagName,
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        String answerStrategy,
        boolean enabled,
        boolean recommended) {
}
