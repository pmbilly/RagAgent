package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * FAQ 条目域传输对象：条目视图、导出、载荷与批量更新请求。
 *
 * <p><b>响应侧已按新契约</b>（{@link FaqEntry} / {@link FaqEntryPage}）：camelCase、零注解、
 * 条件键收敛为可空字段。</p>
 *
 * <p>线格式 = Java 字段名（camelCase）。{@link FaqExportEntry} 与 {@code FaqEntryPayload}
 * 是**同一套交换格式**（导出 → 改动 → 再导入），故两者同批改名以保持往返一致。</p>
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

    /** 创建/更新条目的请求载荷（创建时 standard_question 必填；更新路径同形复用）。 */
    public record FaqEntryPayload(
            Long id,
            @jakarta.validation.constraints.NotBlank(message = "standardQuestion: 不能为空")
            String standardQuestion,
            List<String> similarQuestions,
            List<String> negativeQuestions,
            List<String> answers,
            String answerStrategy,
            long tagId,
            String tagName,
            Boolean enabled,
            Boolean recommended) {
    }

/** 单条字段更新（三态：不传 = 不变更）。 */
public record FaqEntryFieldsUpdate(Boolean enabled, Boolean recommended, Long tagId) {
}

/** 按条目 / 按标签的批量字段更新。 */
public record FaqEntryFieldsBatchUpdate(
        Map<Long, FaqEntryFieldsUpdate> byId,
        Map<Long, FaqEntryFieldsUpdate> byTag,
        List<Long> excludeIds) {
}

    public record FaqDeleteRequest(
            @jakarta.validation.constraints.NotEmpty(message = "ids: 不能为空")
            List<Long> ids) {
    }

    /** updates 的 value null = 移除标签。 */
    public record FaqEntryTagBatchRequest(
            @jakarta.validation.constraints.NotEmpty(message = "updates: 不能为空")
            Map<Long, Long> updates) {
    }

    public record AddSimilarQuestionsRequest(
            @jakarta.validation.constraints.NotEmpty(message = "similarQuestions: 不能为空")
            List<String> similarQuestions) {
    }

    public record UpdateLastImportDisplayStatusRequest(
            @jakarta.validation.constraints.NotBlank(message = "displayStatus: 不能为空")
            @jakarta.validation.constraints.Pattern(regexp = "open|close", message = "displayStatus: 必须为 open 或 close")
            String displayStatus) {
    }
}
