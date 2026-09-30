package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;

/** FAQ 导入结果汇总（写入分块元数据的最终形状）。 */
public record FaqImportResult(
        int totalEntries,
        int successCount,
        int failedCount,
        int partialFailedCount,
        int skippedCount,
        int mergedCount,
        int addedCount,
        String importMode,
        OffsetDateTime importedAt,
        String taskId,
        String failedEntriesUrl,
        String displayStatus,
        long processingTime) {
}
