package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** FAQ 导入进度：计数 + 成功/失败/合并明细。 */
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
