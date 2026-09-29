package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * FAQ 条目域传输对象：条目视图、导出、载荷与批量更新请求。
 *
 * <p><b>响应侧已按新契约</b>（{@link FaqEntry} / {@link FaqEntryPage}）：camelCase、零注解、
 * 条件键收敛为可空字段。</p>
 *
 * <p><b>请求侧仍为 snake_case</b>（{@code @JsonNaming(SnakeCaseStrategy)}），与
 * {@link FaqExportEntry} 同批处理：导出 JSON 与 {@code FaqEntryPayload} 是**同一套交换格式**
 * （导出 → 改动 → 再导入），必须同时改名，否则破坏往返。</p>
 */
public final class FaqEntryDtos {

    private FaqEntryDtos() {
    }

    public static final String UNTAGGED_TAG_NAME = "未分类";

    public static final String BATCH_MODE_APPEND = "append";
    public static final String BATCH_MODE_REPLACE = "replace";

    public static final String INDEX_MODE_QUESTION_ONLY = "question_only";
    public static final String INDEX_MODE_QUESTION_ANSWER = "question_answer";

/**
 * FAQ 条目对外视图。
 *
 * <p>契约要点：camelCase、零注解、布尔不带 {@code is} 前缀（{@code enabled}/{@code recommended}）；
 * 检索命中信息收敛为嵌套的 {@link FaqMatch}——列表/详情场景为 {@code null}，
 * 避免历史上"score/match_type/matched_question 有时出现有时消失"的条件键。</p>
 *
 * @param indexMode 索引模式（{@code question_only} / {@code question_answer}），取值由常量收敛
 * @param chunkType 底层分块类型（数据驱动，保持字符串）
 */
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

/** FAQ 条目分页结果。 */
public record FaqEntryPage(List<FaqEntry> items, int page, int pageSize, long total) {
}

/**
 * 导出条目（JSON 导出文件的行）。
 *
 * <p><b>有意保留 snake_case</b>：它与导入用的 {@code FaqEntryPayload} 是同一套交换格式
 * （导出 → 编辑 → 再导入），必须同批改名以保持往返一致，属"请求侧 camelCase"批次。</p>
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
