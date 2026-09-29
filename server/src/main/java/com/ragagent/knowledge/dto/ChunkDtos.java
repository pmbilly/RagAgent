package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;

/**
 * chunk 域传输对象：编辑/回滚/生成问题的请求 record 与列表/更新响应。
 *
 * <p>请求 record 仍用 {@code @JsonNaming(SnakeCaseStrategy)} + 校验注解（请求侧 camelCase
 * 属独立批次）；<b>响应 record 已按新契约</b>：camelCase、无注解、可空字段显式 null。</p>
 */
public final class ChunkDtos {

    private ChunkDtos() {
    }

    /** chunk 编辑请求：全指针字段，三态（不传 = 不变更）。 */
    public record UpdateChunkRequest(
            String content,
            Boolean enabled,
            Integer expectedRevision) {
    }

    /** chunk 回滚请求：目标修订号必填。 */
    public record RevertChunkRequest(
            @NotNull(message = "revision: 不能为空")
            Integer revision,
            Integer expectedRevision) {
    }

    /** question 的 null 判定在 controller（空白串放行，由 service 落域文案）。 */
    public record UpsertGeneratedQuestionRequest(
            String questionId,
            String question) {
    }

    /** 删除生成问题请求（body 可整体省略）。 */
    public record DeleteGeneratedQuestionRequest(String questionId) {
    }

    /**
     * chunk 列表分页响应：{@code {items, page, pageSize, total}}。
     */
    public record ChunkPageResponse(List<ChunkResponse> items, int page, int pageSize, long total) {
    }

    /**
     * chunk 更新/回滚响应：新分块视图 + 所属文档的摘要信息。
     *
     * @param chunk         更新后的分块
     * @param description   所属文档的描述（重载失败时为 {@code null}）
     * @param summaryStatus 所属文档的摘要状态（重载失败时为 {@code null}）
     */
    public record ChunkUpdateResponse(ChunkResponse chunk, String description, String summaryStatus) {
    }

    /**
     * chunk 修订版本视图（历史留档，只读）。
     *
     * <p>内部字段 {@code tenantId} 不下发；空串归一为 {@code null}。</p>
     */
    public record ChunkRevisionResponse(
            String id,
            String knowledgeBaseId,
            String knowledgeId,
            String chunkId,
            int revision,
            String content,
            boolean enabled,
            String editorId,
            String editSource,
            OffsetDateTime editedAt,
            OffsetDateTime createdAt) {

        public static ChunkRevisionResponse from(ChunkRevision r) {
            return new ChunkRevisionResponse(
                    r.getId(),
                    r.getKnowledgeBaseId(),
                    r.getKnowledgeId(),
                    r.getChunkId(),
                    r.getRevision(),
                    r.getContent(),
                    r.isEnabled(),
                    emptyToNull(r.getEditorId()),
                    emptyToNull(r.getEditSource()),
                    r.getEditedAt(),
                    r.getCreatedAt());
        }
    }

    /**
     * 分块生成的问题视图。
     *
     * @param contentRevision 问题所基于的内容修订号（可为 {@code null} = 与修订无关）
     */
    public record GeneratedQuestionResponse(String id, String question, Integer contentRevision) {

        public static GeneratedQuestionResponse from(GeneratedQuestion q) {
            return new GeneratedQuestionResponse(q.getId(), q.getQuestion(), q.getContentRevision());
        }
    }

    /** 空串按"未设置"处理（契约：不用空串代替 null）。 */
    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
