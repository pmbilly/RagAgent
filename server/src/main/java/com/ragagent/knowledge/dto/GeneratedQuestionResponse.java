package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/** 生成问题视图：id / 问题 / 所属内容版本。 */
public record GeneratedQuestionResponse(String id, String question, Integer contentRevision) {

    public static GeneratedQuestionResponse from(GeneratedQuestion q) {
        return new GeneratedQuestionResponse(q.getId(), q.getQuestion(), q.getContentRevision());
    }
}
