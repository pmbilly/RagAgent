package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** KB 副本克隆进度（轮询用）。 */
public record KBCloneProgress(
        String taskId,
        String sourceId,
        String targetId,
        String status,
        int progress,
        int total,
        int processed,
        String message,
        String error,
        long createdAt,
        long updatedAt) {

    /** 同上：状态判定便捷方法，@JsonIgnore 防止 Jackson 吐出派生键。 */
    @JsonIgnore
    public boolean isTerminal() {
        return "completed".equals(status) || "failed".equals(status);
    }

    /** worker 的进度回调。 */
    public KBCloneProgress withDone(int done) {
        int pct = total > 0 ? done * 100 / total : 0;
        return new KBCloneProgress(taskId, sourceId, targetId, status, pct, total, done,
                "Processed " + done + "/" + total + " clone operations", error, createdAt,
                Instant.now().getEpochSecond());
    }
}
