package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * FAQ 模块（波 2 第四批）的传输对象（对照 Go types/faq.go，字段序 = Go 声明序）。
 * 所有会作响应体/落 jsonb 的类型均已加入 JsonContractRoundTripTest（§7.5 第 3 条）。
 *
 * <p>omitempty 语义对照：<b>逐字段</b>标注 {@code @JsonInclude(NON_DEFAULT)}——
 * Go 的 omitempty 只省 0/""/false/nil；没有 omitempty 的字段（如 FAQExportEntry 的
 * {@code id}（golden 实录 {@code "id":0}）、FAQImportProgress 的 {@code error:""}、
 * FAQEntry 的 null 列表）恒输出，<b>不能</b>类级一刀切。可空引用（null）在
 * NON_DEFAULT 下同样省略，对齐 Go nil slice 的省略。</p>
 */
public final class FaqDtos {

    private FaqDtos() {
    }

    public static final String UNTAGGED_TAG_NAME = "未分类";

    public static final String BATCH_MODE_APPEND = "append";
    public static final String BATCH_MODE_REPLACE = "replace";

    public static final String INDEX_MODE_QUESTION_ONLY = "question_only";
    public static final String INDEX_MODE_QUESTION_ANSWER = "question_answer";

    /** 对照 types.FAQEntry（前端 FAQ 条目响应）。 */
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
     * 对照 types.FAQExportEntry（JSON 导出面，"导出→编辑→再导入"兼容格式）。
     * Go 注释明确：新增字段务必保留 omitempty；{@code id} 无 omitempty（导出实录
     * {@code "id":0}——Go 的导出查询不取 seq_id，照抄别修）。
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

    /** 对照 types.FAQEntryPayload（创建/更新/导入的 payload）。 */
    @JsonPropertyOrder({"id", "standard_question", "similar_questions", "negative_questions",
            "answers", "answer_strategy", "tag_id", "tag_name", "is_enabled", "is_recommended"})
    public record FaqEntryPayload(
            @JsonProperty("id") Long id,
            @JsonProperty("standard_question") String standardQuestion,
            @JsonProperty("similar_questions") List<String> similarQuestions,
            @JsonProperty("negative_questions") List<String> negativeQuestions,
            @JsonProperty("answers") List<String> answers,
            @JsonProperty("answer_strategy") String answerStrategy,
            @JsonProperty("tag_id") long tagId,
            @JsonProperty("tag_name") String tagName,
            @JsonProperty("is_enabled") Boolean isEnabled,
            @JsonProperty("is_recommended") Boolean isRecommended) {
    }

    /** 对照 types.FAQBatchUpsertPayload。mode 的 oneof=append replace 见 upsert 校验。 */
    @JsonPropertyOrder({"entries", "mode", "knowledge_id", "task_id", "dry_run"})
    public record FaqBatchUpsertPayload(
            @JsonProperty("entries") List<FaqEntryPayload> entries,
            @JsonProperty("mode") String mode,
            @JsonProperty("knowledge_id") String knowledgeId,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("dry_run") boolean dryRun) {
    }

    /** 对照 types.FAQSearchRequest。 */
    @JsonPropertyOrder({"query_text", "vector_threshold", "match_count",
            "first_priority_tag_ids", "second_priority_tag_ids", "only_recommended"})
    public record FaqSearchRequest(
            @JsonProperty("query_text") String queryText,
            @JsonProperty("vector_threshold") double vectorThreshold,
            @JsonProperty("match_count") int matchCount,
            @JsonProperty("first_priority_tag_ids") List<Long> firstPriorityTagIds,
            @JsonProperty("second_priority_tag_ids") List<Long> secondPriorityTagIds,
            @JsonProperty("only_recommended") boolean onlyRecommended) {
    }

    /** 对照 types.FAQEntryFieldsUpdate（单个条目的字段更新，全指针）。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonPropertyOrder({"is_enabled", "is_recommended", "tag_id"})
    public record FaqEntryFieldsUpdate(
            @JsonProperty("is_enabled") Boolean isEnabled,
            @JsonProperty("is_recommended") Boolean isRecommended,
            @JsonProperty("tag_id") Long tagId) {
    }

    /** 对照 types.FAQEntryFieldsBatchUpdate（by_id / by_tag / exclude_ids 三态可并存）。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonPropertyOrder({"by_id", "by_tag", "exclude_ids"})
    public record FaqEntryFieldsBatchUpdate(
            @JsonProperty("by_id") Map<Long, FaqEntryFieldsUpdate> byId,
            @JsonProperty("by_tag") Map<Long, FaqEntryFieldsUpdate> byTag,
            @JsonProperty("exclude_ids") List<Long> excludeIds) {
    }

    /** 对照 handler.faqDeleteRequest。 */
    @JsonPropertyOrder({"ids"})
    public record FaqDeleteRequest(@JsonProperty("ids") List<Long> ids) {
    }

    /** 对照 handler.faqEntryTagBatchRequest（value null = 移除标签）。 */
    @JsonPropertyOrder({"updates"})
    public record FaqEntryTagBatchRequest(@JsonProperty("updates") Map<Long, Long> updates) {
    }

    /** 对照 handler.addSimilarQuestionsRequest。 */
    @JsonPropertyOrder({"similar_questions"})
    public record AddSimilarQuestionsRequest(
            @JsonProperty("similar_questions") List<String> similarQuestions) {
    }

    /** 对照 handler.updateLastFAQImportResultDisplayStatusRequest。 */
    @JsonPropertyOrder({"display_status"})
    public record UpdateLastImportDisplayStatusRequest(
            @JsonProperty("display_status") String displayStatus) {
    }

    // ── 导入/验证进度（FAQImportProgress 落 Redis / Java 进程内 map；响应体同形） ──

    public static final String IMPORT_STATUS_PENDING = "pending";
    public static final String IMPORT_STATUS_PROCESSING = "processing";
    public static final String IMPORT_STATUS_COMPLETED = "completed";
    public static final String IMPORT_STATUS_FAILED = "failed";

    /** 对照 types.FAQFailedEntry（index/reason/standard_question 无 omitempty，恒输出）。 */
    @JsonPropertyOrder({"index", "reason", "failure_type", "is_partial_failure", "tag_name",
            "standard_question", "similar_questions", "negative_questions", "answers",
            "answer_all", "is_disabled", "removed_similar_questions", "removed_negative_questions"})
    public record FaqFailedEntry(
            @JsonProperty("index") int index,
            @JsonProperty("reason") String reason,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("failure_type") String failureType,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("is_partial_failure") boolean isPartialFailure,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("tag_name") String tagName,
            @JsonProperty("standard_question") String standardQuestion,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("similar_questions") List<String> similarQuestions,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("negative_questions") List<String> negativeQuestions,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("answers") List<String> answers,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("answer_all") boolean answerAll,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("is_disabled") boolean isDisabled,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("removed_similar_questions") List<String> removedSimilarQuestions,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("removed_negative_questions") List<String> removedNegativeQuestions) {
    }

    /** 对照 types.FAQMergeDetail（全部恒输出）。 */
    @JsonPropertyOrder({"index", "standard_question", "answer_changed",
            "new_similar_count", "new_negative_count"})
    public record FaqMergeDetail(
            @JsonProperty("index") int index,
            @JsonProperty("standard_question") String standardQuestion,
            @JsonProperty("answer_changed") boolean answerChanged,
            @JsonProperty("new_similar_count") int newSimilarCount,
            @JsonProperty("new_negative_count") int newNegativeCount) {
    }

    /** 对照 types.FAQSuccessEntry（index/seq_id/standard_question 恒输出）。 */
    @JsonPropertyOrder({"index", "seq_id", "tag_id", "tag_name", "standard_question"})
    public record FaqSuccessEntry(
            @JsonProperty("index") int index,
            @JsonProperty("seq_id") long seqId,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("tag_id") long tagId,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("tag_name") String tagName,
            @JsonProperty("standard_question") String standardQuestion) {
    }

    /**
     * 对照 types.FAQImportProgress（进度存储 + 响应体同形；键序 = Go struct 序）。
     * {@code message}/{@code error} 无 omitempty（恒输出，含 ""）；
     * result 字段（import_mode/imported_at/display_status/processing_time）在
     * completed 且能读到持久化 FAQImportResult 时被覆盖。
     */
    @JsonPropertyOrder({"task_id", "kb_id", "knowledge_id", "status", "progress", "total",
            "processed", "success_count", "failed_count", "partial_failed_count",
            "skipped_count", "failed_entries", "failed_entries_url", "success_entries",
            "valid_entry_indices", "merge_entry_indices", "merged_count", "added_count",
            "merge_details", "message", "error", "created_at", "updated_at", "dry_run",
            "import_mode", "imported_at", "display_status", "processing_time"})
    public record FaqImportProgress(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("kb_id") String kbId,
            @JsonProperty("knowledge_id") String knowledgeId,
            @JsonProperty("status") String status,
            @JsonProperty("progress") int progress,
            @JsonProperty("total") int total,
            @JsonProperty("processed") int processed,
            @JsonProperty("success_count") int successCount,
            @JsonProperty("failed_count") int failedCount,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("partial_failed_count") int partialFailedCount,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("skipped_count") int skippedCount,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("failed_entries") List<FaqFailedEntry> failedEntries,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("failed_entries_url") String failedEntriesUrl,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("success_entries") List<FaqSuccessEntry> successEntries,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("valid_entry_indices") List<Integer> validEntryIndices,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("merge_entry_indices") List<Integer> mergeEntryIndices,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("merged_count") int mergedCount,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("added_count") int addedCount,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("merge_details") List<FaqMergeDetail> mergeDetails,
            @JsonProperty("message") String message,
            @JsonProperty("error") String error,
            @JsonProperty("created_at") long createdAt,
            @JsonProperty("updated_at") long updatedAt,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("dry_run") boolean dryRun,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("import_mode") String importMode,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonSerialize(using = GoTimeSerializer.class)
            @JsonDeserialize(using = GoTimeDeserializer.class) @JsonProperty("imported_at") OffsetDateTime importedAt,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("display_status") String displayStatus,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("processing_time") long processingTime) {
    }

    /**
     * 对照 types.FAQImportResult（持久化在 knowledges.last_faq_import_result 的 jsonb；
     * 除 failed_entries_url 外全部无 omitempty，恒输出）。
     */
    @JsonPropertyOrder({"total_entries", "success_count", "failed_count", "partial_failed_count",
            "skipped_count", "merged_count", "added_count", "import_mode", "imported_at",
            "task_id", "failed_entries_url", "display_status", "processing_time"})
    public record FaqImportResult(
            @JsonProperty("total_entries") int totalEntries,
            @JsonProperty("success_count") int successCount,
            @JsonProperty("failed_count") int failedCount,
            @JsonProperty("partial_failed_count") int partialFailedCount,
            @JsonProperty("skipped_count") int skippedCount,
            @JsonProperty("merged_count") int mergedCount,
            @JsonProperty("added_count") int addedCount,
            @JsonProperty("import_mode") String importMode,
            @JsonSerialize(using = GoTimeSerializer.class)
            @JsonDeserialize(using = GoTimeDeserializer.class) @JsonProperty("imported_at") OffsetDateTime importedAt,
            @JsonProperty("task_id") String taskId,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("failed_entries_url") String failedEntriesUrl,
            @JsonProperty("display_status") String displayStatus,
            @JsonProperty("processing_time") long processingTime) {
    }
}
