package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

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
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) String failedEntriesUrl,
        String displayStatus,
        long processingTime) {
}
