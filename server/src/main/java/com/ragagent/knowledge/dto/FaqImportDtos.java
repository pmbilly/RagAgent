package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ragagent.knowledge.dto.FaqEntryDtos.FaqEntryPayload;

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

    /** 导入受理响应：{@code taskId} 供进度轮询。 */
    public record FaqTaskStartResponse(String taskId) {
    }

    /** 批量导入（upsert）请求；mode 取值见校验注解。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FaqBatchUpsertPayload(
            @jakarta.validation.constraints.NotNull(message = "entries: 不能为空")
            List<FaqEntryPayload> entries,
            @jakarta.validation.constraints.NotBlank(message = "mode: 必须为 append 或 replace")
            @jakarta.validation.constraints.Pattern(regexp = "append|replace", message = "mode: 必须为 append 或 replace")
            String mode,
            String knowledgeId,
            String taskId,
            boolean dryRun) {
    }

/**
 * 导入失败的条目明细。
 *
 * @param partialFailure 部分失败（如答案写入成功但索引失败）
 * @param disabled       该条目被禁用（replace 模式下已存在且被停用）
 */
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

/** 导入时与既有条目合并的明细。 */
public record FaqMergeDetail(
        int index,
        String standardQuestion,
        boolean answerChanged,
        int newSimilarCount,
        int newNegativeCount) {
}

/** 导入成功的条目摘要。 */
public record FaqSuccessEntry(
        int index,
        long seqId,
        long tagId,
        String tagName,
        String standardQuestion) {
}

/**
 * 导入进度（轮询面）。
 *
 * <p>内存态、纯响应体（{@code FaqImportTaskStore} 里的 {@code ConcurrentHashMap}），
 * 因此按新契约输出：camelCase、<b>无条件键</b>——历史上靠 {@code omitempty} 省略的
 * 计数与明细，现在恒输出（未发生时为 0 / 空列表 / null），前端不必再判键是否存在。</p>
 *
 * <p>{@code importMode}/{@code importedAt}/{@code displayStatus}/{@code processingTime}
 * 四个字段在 completed 且能读到持久化的 {@link FaqImportResult} 时被覆盖。</p>
 */
public record FaqImportProgress(
        String taskId,
        String kbId,
        String knowledgeId,
        String status,
        int progress,
        int total,
        int processed,
        int successCount,
        int failedCount,
        int partialFailedCount,
        int skippedCount,
        List<FaqFailedEntry> failedEntries,
        String failedEntriesUrl,
        List<FaqSuccessEntry> successEntries,
        List<Integer> validEntryIndices,
        List<Integer> mergeEntryIndices,
        int mergedCount,
        int addedCount,
        List<FaqMergeDetail> mergeDetails,
        String message,
        String error,
        long createdAt,
        long updatedAt,
        boolean dryRun,
        String importMode,
        OffsetDateTime importedAt,
        String displayStatus,
        long processingTime) {
}

/**
 * 导入结果（**落 jsonb**：写入文档的 {@code last_faq_import_result} 列）。
 *
 * <p><b>有意保留 snake_case</b>：它的字段名就是数据库里 JSON 的键名
 * （{@code FaqChunkMetadata.JSON.valueToTree(result)} 写入、{@code treeToValue} 读回），
 * 改名等于改存量数据格式。对外暴露时走文档视图的 {@code lastFaqImportResult} 字段，
 * 按"jsonb 不透明"约定原样透传。</p>
 */
public record FaqImportResult(
        @JsonProperty("total_entries") int totalEntries,
        @JsonProperty("success_count") int successCount,
        @JsonProperty("failed_count") int failedCount,
        @JsonProperty("partial_failed_count") int partialFailedCount,
        @JsonProperty("skipped_count") int skippedCount,
        @JsonProperty("merged_count") int mergedCount,
        @JsonProperty("added_count") int addedCount,
        @JsonProperty("import_mode") String importMode,
        @JsonProperty("imported_at") OffsetDateTime importedAt,
        @JsonProperty("task_id") String taskId,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("failed_entries_url") String failedEntriesUrl,
        @JsonProperty("display_status") String displayStatus,
        @JsonProperty("processing_time") long processingTime) {
}
}
