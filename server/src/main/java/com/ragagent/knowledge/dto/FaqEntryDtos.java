package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * FAQ 条目域传输对象：条目视图、导出、载荷与批量更新请求。
 * 响应 record 的键序/键名由 @JsonProperty/@JsonPropertyOrder 锁定（契约面）；
 * 请求 record 用标准 @JsonNaming(SnakeCaseStrategy) + jakarta.validation 校验，
 * 校验消息自含 snake_case 字段前缀（错误 details 的统一形态）。
 */
public final class FaqEntryDtos {

    private FaqEntryDtos() {
    }

    public static final String UNTAGGED_TAG_NAME = "未分类";

    public static final String BATCH_MODE_APPEND = "append";
    public static final String BATCH_MODE_REPLACE = "replace";

    public static final String INDEX_MODE_QUESTION_ONLY = "question_only";
    public static final String INDEX_MODE_QUESTION_ANSWER = "question_answer";

@JsonPropertyOrder({"id", "chunk_id", "knowledge_id", "knowledge_base_id", "tag_id",
        "tag_name", "is_enabled", "is_recommended", "standard_question",
        "similar_questions", "negative_questions", "answers", "answer_strategy",
        "index_mode", "updated_at", "created_at", "score", "match_type",
        "chunk_type", "matched_question"})
public record FaqEntry(
        @JsonProperty("id") long id,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("knowledge_id") String knowledgeId,
        @JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("tag_id") long tagId,
        @JsonProperty("tag_name") String tagName,
        @JsonProperty("is_enabled") boolean isEnabled,
        @JsonProperty("is_recommended") boolean isRecommended,
        @JsonProperty("standard_question") String standardQuestion,
        @JsonProperty("similar_questions") List<String> similarQuestions,
        @JsonProperty("negative_questions") List<String> negativeQuestions,
        @JsonProperty("answers") List<String> answers,
        @JsonProperty("answer_strategy") String answerStrategy,
        @JsonProperty("index_mode") String indexMode,
        @JsonSerialize(using = GoTimeSerializer.class)
        @JsonDeserialize(using = GoTimeDeserializer.class) @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonSerialize(using = GoTimeSerializer.class)
        @JsonDeserialize(using = GoTimeDeserializer.class) @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        @JsonSerialize(using = GoDoubleSerializer.class) @JsonProperty("score") double score,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("match_type") int matchType,
        @JsonProperty("chunk_type") String chunkType,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("matched_question") String matchedQuestion) {

    /** 以检索命中覆盖 score/matchType/matchedQuestion 的视图重建。 */
    public static FaqEntry withSearchHit(FaqEntry entry, double score, int matchType,
            String matchedQuestion) {
        return new FaqEntry(entry.id(), entry.chunkId(), entry.knowledgeId(), entry.knowledgeBaseId(),
                entry.tagId(), entry.tagName(), entry.isEnabled(), entry.isRecommended(),
                entry.standardQuestion(), entry.similarQuestions(), entry.negativeQuestions(),
                entry.answers(), entry.answerStrategy(), entry.indexMode(), entry.updatedAt(),
                entry.createdAt(), score, matchType, entry.chunkType(),
                matchedQuestion);
    }

    /** 覆盖 tagName 的视图重建。 */
    public static FaqEntry withTagName(FaqEntry entry, String tagName) {
        return new FaqEntry(entry.id(), entry.chunkId(), entry.knowledgeId(), entry.knowledgeBaseId(),
                entry.tagId(), tagName == null ? "" : tagName, entry.isEnabled(), entry.isRecommended(),
                entry.standardQuestion(), entry.similarQuestions(), entry.negativeQuestions(),
                entry.answers(), entry.answerStrategy(), entry.indexMode(), entry.updatedAt(),
                entry.createdAt(), entry.score(), entry.matchType(), entry.chunkType(),
                entry.matchedQuestion());
    }
}

/**
 */
@JsonPropertyOrder({"id", "tag_name", "standard_question", "similar_questions",
        "negative_questions", "answers", "answer_strategy", "is_enabled", "is_recommended"})
public record FaqExportEntry(
        @JsonProperty("id") long id,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("tag_name") String tagName,
        @JsonProperty("standard_question") String standardQuestion,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("similar_questions") List<String> similarQuestions,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("negative_questions") List<String> negativeQuestions,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("answers") List<String> answers,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("answer_strategy") String answerStrategy,
        @JsonProperty("is_enabled") boolean isEnabled,
        @JsonProperty("is_recommended") boolean isRecommended) {
}

    /** 创建/更新条目的请求载荷（创建时 standard_question 必填；更新路径同形复用）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FaqEntryPayload(
            Long id,
            @jakarta.validation.constraints.NotBlank(message = "standard_question: 不能为空")
            String standardQuestion,
            List<String> similarQuestions,
            List<String> negativeQuestions,
            List<String> answers,
            String answerStrategy,
            long tagId,
            String tagName,
            Boolean isEnabled,
            Boolean isRecommended) {
    }

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({"is_enabled", "is_recommended", "tag_id"})
public record FaqEntryFieldsUpdate(
        @JsonProperty("is_enabled") Boolean isEnabled,
        @JsonProperty("is_recommended") Boolean isRecommended,
        @JsonProperty("tag_id") Long tagId) {
}

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({"by_id", "by_tag", "exclude_ids"})
public record FaqEntryFieldsBatchUpdate(
        @JsonProperty("by_id") Map<Long, FaqEntryFieldsUpdate> byId,
        @JsonProperty("by_tag") Map<Long, FaqEntryFieldsUpdate> byTag,
        @JsonProperty("exclude_ids") List<Long> excludeIds) {
}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FaqDeleteRequest(
            @jakarta.validation.constraints.NotEmpty(message = "ids: 不能为空")
            List<Long> ids) {
    }

    /** updates 的 value null = 移除标签。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FaqEntryTagBatchRequest(
            @jakarta.validation.constraints.NotEmpty(message = "updates: 不能为空")
            Map<Long, Long> updates) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AddSimilarQuestionsRequest(
            @jakarta.validation.constraints.NotEmpty(message = "similar_questions: 不能为空")
            List<String> similarQuestions) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateLastImportDisplayStatusRequest(
            @jakarta.validation.constraints.NotBlank(message = "display_status: 不能为空")
            @jakarta.validation.constraints.Pattern(regexp = "open|close", message = "display_status: 必须为 open 或 close")
            String displayStatus) {
    }
}
