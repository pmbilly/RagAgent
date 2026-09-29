package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * FAQ 导入域传输对象：批量 upsert 请求、失败/合并/成功明细、导入进度与持久化结果。
 * 进度对象即响应体（键序 = 既有契约序）；失败/合并明细嵌套其中。
 */
public final class FaqImportDtos {

    private FaqImportDtos() {
    }

    public static final String IMPORT_STATUS_PENDING = "pending";
    public static final String IMPORT_STATUS_PROCESSING = "processing";
    public static final String IMPORT_STATUS_COMPLETED = "completed";
    public static final String IMPORT_STATUS_FAILED = "failed";

    /** 导入受理响应：task_id 供进度轮询。 */
    public record FaqTaskStartResponse(@JsonProperty("task_id") String taskId) {
    }

    /** 批量导入（upsert）请求；mode 取值见校验注解。 */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FaqBatchUpsertPayload(
            @jakarta.validation.constraints.NotNull(message = "entries: 不能为空")
            List<com.ragagent.knowledge.dto.FaqEntryDtos.FaqEntryPayload> entries,
            @jakarta.validation.constraints.NotBlank(message = "mode: 必须为 append 或 replace")
            @jakarta.validation.constraints.Pattern(regexp = "append|replace", message = "mode: 必须为 append 或 replace")
            String mode,
            String knowledgeId,
            String taskId,
            boolean dryRun) {
    }

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
