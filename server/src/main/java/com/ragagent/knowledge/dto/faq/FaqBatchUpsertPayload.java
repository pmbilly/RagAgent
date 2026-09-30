package com.ragagent.knowledge.dto.faq;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** FAQ 批量 upsert 载荷：条目 + 模式 + 目标文档/任务 + dryRun。 */
public record FaqBatchUpsertPayload(
        @NotNull(message = "entries: 不能为空")
        List<FaqEntryPayload> entries,
        @NotBlank(message = "mode: 必须为 append 或 replace")
        @Pattern(regexp = "append|replace", message = "mode: 必须为 append 或 replace")
        String mode,
        String knowledgeId,
        String taskId,
        boolean dryRun) {
}
