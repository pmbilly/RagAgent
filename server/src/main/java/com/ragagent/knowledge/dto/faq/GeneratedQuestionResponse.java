package com.ragagent.knowledge.dto.faq;

import com.ragagent.knowledge.domain.GeneratedQuestion;

/** 生成问题视图：id / 问题 / 所属内容版本。 */
public record GeneratedQuestionResponse(String id, String question, Integer contentRevision) {

    public static GeneratedQuestionResponse from(GeneratedQuestion q) {
        return new GeneratedQuestionResponse(q.getId(), q.getQuestion(), q.getContentRevision());
    }
}
