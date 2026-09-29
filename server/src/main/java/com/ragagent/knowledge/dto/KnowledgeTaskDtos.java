package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 搬移/复制/清空等异步任务的响应体。
 *
 * <p>契约：JSON 字段名 = Java 字段名（camelCase、零注解）；受理类响应只带
 * 任务标识与计数，不再携带服务端生成的 UI 文案（文案由前端本地化）。</p>
 */
public final class KnowledgeTaskDtos {

    private KnowledgeTaskDtos() {
    }

    /** 跨 KB 搬移受理（202）。 */
    public record MoveKnowledgeResponse(
            String taskId,
            String sourceKbId,
            String targetKbId,
            int knowledgeCount) {
    }

    /** 知识库复制受理（202）。 */
    public record CopyKnowledgeBaseResponse(
            String taskId,
            String sourceId,
            String targetId) {
    }

    /** 知识库副本创建完成。 */
    public record DuplicateKnowledgeBaseResponse(
            String sourceId,
            String targetId,
            KnowledgeBaseResponse knowledgeBase) {
    }

    /** 跨 KB 搬移进度。 */
    public record KnowledgeMoveProgress(
            String taskId,
            String sourceKbId,
            String targetKbId,
            String status,
            int progress,
            int total,
            int processed,
            int failed,
            String message,
            String error,
            long createdAt,
            long updatedAt) {

        @JsonIgnore
        public boolean isTerminal() {
            return "completed".equals(status) || "failed".equals(status);
        }
    }

    /** 知识库复制进度（与搬移进度的差异：中间是 sourceId/targetId、无 failed 计数）。 */
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
                    java.time.Instant.now().getEpochSecond());
        }
    }
}
