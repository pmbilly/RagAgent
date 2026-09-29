package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 波 2 第三批「搜索与移动/复制」的响应体。
 * <p>这些类型只作 HTTP 响应体、不落 jsonb；但仍是响应契约，
 * 已加入 JsonContractRoundTripTest（§7.5 第 3 条）。进度对象里的
 */
public final class KnowledgeTaskDtos {

    private KnowledgeTaskDtos() {
    }

    @JsonPropertyOrder({"task_id", "source_kb_id", "target_kb_id", "knowledge_count", "message"})
    public record MoveKnowledgeResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("source_kb_id") String sourceKbId,
            @JsonProperty("target_kb_id") String targetKbId,
            @JsonProperty("knowledge_count") int knowledgeCount,
            @JsonProperty("message") String message) {
    }

    @JsonPropertyOrder({"task_id", "source_id", "target_id", "message"})
    public record CopyKnowledgeBaseResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("target_id") String targetId,
            @JsonProperty("message") String message) {
    }

    @JsonPropertyOrder({"source_id", "target_id", "message", "knowledge_base"})
    public record DuplicateKnowledgeBaseResponse(
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("target_id") String targetId,
            @JsonProperty("message") String message,
            @JsonProperty("knowledge_base") Object knowledgeBase) {
    }

    /**
     */
    @JsonPropertyOrder({"task_id", "source_kb_id", "target_kb_id", "status", "progress",
            "total", "processed", "failed", "message", "error", "created_at", "updated_at"})
    public record KnowledgeMoveProgress(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("source_kb_id") String sourceKbId,
            @JsonProperty("target_kb_id") String targetKbId,
            @JsonProperty("status") String status,
            @JsonProperty("progress") int progress,
            @JsonProperty("total") int total,
            @JsonProperty("processed") int processed,
            @JsonProperty("failed") int failed,
            @JsonProperty("message") String message,
            @JsonProperty("error") String error,
            @JsonProperty("created_at") long createdAt,
            @JsonProperty("updated_at") long updatedAt) {

        @JsonIgnore
        public boolean isTerminal() {
            return "completed".equals(status) || "failed".equals(status);
        }
    }

    /**
     * 的差异：中间是 {@code source_id/target_id}、没有 {@code failed} 计数。
     */
    @JsonPropertyOrder({"task_id", "source_id", "target_id", "status", "progress",
            "total", "processed", "message", "error", "created_at", "updated_at"})
    public record KBCloneProgress(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("target_id") String targetId,
            @JsonProperty("status") String status,
            @JsonProperty("progress") int progress,
            @JsonProperty("total") int total,
            @JsonProperty("processed") int processed,
            @JsonProperty("message") String message,
            @JsonProperty("error") String error,
            @JsonProperty("created_at") long createdAt,
            @JsonProperty("updated_at") long updatedAt) {

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
