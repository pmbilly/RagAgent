package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotNull;

/** chunk 回滚请求：目标版本 + 乐观锁 {@code expectedRevision}。 */
public record RevertChunkRequest(
        @NotNull(message = "revision: 不能为空")
        Integer revision,
        Integer expectedRevision) {
}
