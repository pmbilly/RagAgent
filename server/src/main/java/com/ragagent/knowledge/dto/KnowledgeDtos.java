package com.ragagent.knowledge.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

/**
 * 知识文档域传输对象：创建/更新/批量操作/搬移/文件夹的请求与列表响应。
 * 请求 record 用标准 {@code @JsonNaming(SnakeCaseStrategy)} + 校验注解（消息自含
 * snake_case 字段前缀，多条失败按 record 组件序以 "\n" 连接）。
 */
public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    /** 从 URL 创建：url 必填（SSRF 校验在 controller）。 */
    public record CreateFromUrlRequest(
            @NotBlank(message = "url: 不能为空")
            String url,
            String fileName,
            String fileType,
            String title,
            String channel) {
    }

    /** 手工创建：title 必填。 */
    public record CreateManualRequest(
            @NotBlank(message = "title: 不能为空")
            String title,
            String content,
            String status,
            String channel) {
    }

    /** 手工内容更新：全指针，不传 = 不变更。 */
    public record UpdateManualRequest(String title, String content, String status, String channel) {
    }

    /** 图片信息更新：缺省空串（清除语义）。 */
    public record UpdateImageInfoRequest(String imageInfo) {
    }

    /** 批量标签更新：updates 为 knowledge_id → 标签 ID 列表；kb_id 缺省时从首条推导授权。 */
    public record KnowledgeTagBatchRequest(
            @NotEmpty(message = "updates: 不能为空")
            Map<String, List<String>> updates,
            String kbId) {
    }

    /** 批量删除：ids 的 null/空数组校验在 controller（两种原文案不同）。 */
    public record BatchDeleteRequest(
            @NotBlank(message = "kbId: 不能为空")
            String kbId,
            List<String> ids) {
    }

    /** 跨库搬移（目的地不存在即创建）；knowledge_ids 空数组校验在 controller（原文案）。 */
    public record MoveToFolderRequest(
            @NotBlank(message = "kbId: 不能为空")
            String kbId,
            List<String> knowledgeIds,
            String folderPath) {
    }

    /** 文件夹重命名。 */
    public record RenameFolderRequest(
            @NotBlank(message = "from: 不能为空")
            String from,
            @NotBlank(message = "to: 不能为空")
            String to) {
    }

    /** 跨 KB 搬移：mode 二选一（reuse_vectors 保留向量 / reparse 重析）。 */
    public record MoveKnowledgeRequest(
            @NotEmpty(message = "knowledgeIds: 不能为空")
            List<String> knowledgeIds,
            @NotBlank(message = "source_kbId: 不能为空")
            String sourceKbId,
            @NotBlank(message = "target_kbId: 不能为空")
            String targetKbId,
            @NotBlank(message = "mode: 不能为空")
            @Pattern(regexp = "reuse_vectors|reparse", message = "mode: 必须为 reuse_vectors 或 reparse")
            String mode) {
    }

    /**
     * 跨库搜索响应。
     *
     * @param items   命中的文档（按相关性排序）
     * @param hasMore 是否还有下一页（前端据此决定是否继续加载）
     * @param total   命中总数
     */
    public record KnowledgeSearchResponse(List<KnowledgeResponse> items, boolean hasMore, long total) {
    }

    /** 文件夹搬移/重命名受理结果。 */
    public record FolderMoveResponse(String folderPath, long movedCount) {
    }

    /** 文档列表分页响应：{@code {items, page, pageSize, total}}。 */
    public record KnowledgeListResponse(List<KnowledgeResponse> items, long page, long pageSize, long total) {
    }

    /** 跨 KB 搬移受理响应。 */
    public record BatchTaskData(long deletedCount, String taskId) {
    }

    /** 批量重析受理响应。 */
    public record ReparseTaskData(long reparseCount, String taskId) {
    }

    /** 异步受理响应：只带任务 ID，供前端轮询进度。 */
    public record TaskIdResponse(String taskId) {
    }

    /** 清空知识库受理响应。 */
    public record ClearContentsResponse(long deletedCount) {
    }

    /**
     * 文档重复（409）时的 details 载荷。
     *
     * @param existingKnowledgeId 库内已存在的同内容文档 ID（前端可据此跳转）
     */
    public record DuplicateKnowledgeDetails(String existingKnowledgeId) {
    }

    /** 批量重解析请求（body 可整体省略）。 */
    public record BatchReparseRequest(String kbId, List<String> ids) {
    }

    /**
     * 文档更新请求（部分更新语义）。
     *
     * <p>{@code title} 为 null = 不变更；{@code description}/{@code customMetadata}
     * 用 JsonNode 承载"缺省 vs 显式 null"的区分——缺省 = 不变更，显式 null = 置空。</p>
     */
    public record UpdateKnowledgeRequest(String title, JsonNode description, JsonNode customMetadata) {
    }
}
