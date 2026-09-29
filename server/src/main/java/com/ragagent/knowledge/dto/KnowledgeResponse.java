package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.dto.KnowledgeEnums.EnableStatus;
import com.ragagent.knowledge.dto.KnowledgeEnums.ParseStatus;
import com.ragagent.knowledge.dto.KnowledgeEnums.SummaryStatus;

/**
 * 文档（Knowledge）对外视图。
 *
 * <p>契约要点（见 {@code docs/knowledge-api-contract-v1.md}）：</p>
 * <ul>
 *   <li>JSON 字段名 = Java 字段名（camelCase，零 {@code @JsonProperty}）；</li>
 *   <li>可空字段<b>显式输出 null</b>——不再用空串代替"未设置"；</li>
 *   <li>内部字段（{@code tenantId}、{@code deletedAt}、{@code filePath}）一律不下发；
 *       存储路径由下载接口按 ID 解析，前端无需也不应看到物理路径；</li>
 *   <li>状态类字段枚举化（{@link KnowledgeEnums}），取值与库内存储值一致；</li>
 *   <li><b>{@code type} 有意保留字符串</b>：它是"来源类型"（file/manual/passage/url/document/faq…），
 *       由写入路径经 String 传参产生、取值随来源扩展，强枚举会静默丢值。</li>
 * </ul>
 *
 * <p><b>有意保留空串</b>的字段：{@code folderPath}——空串表示"知识库根目录"，是有效取值而非缺失。</p>
 */
public record KnowledgeResponse(
        String id,
        String knowledgeBaseId,
        List<JsonNode> tags,
        String type,
        String title,
        String description,
        String source,
        String channel,
        ParseStatus parseStatus,
        int pendingSubtasksCount,
        SummaryStatus summaryStatus,
        EnableStatus enableStatus,
        String embeddingModelId,
        String fileName,
        String folderPath,
        String fileType,
        Long fileSize,
        String fileHash,
        long storageSize,
        JsonNode metadata,
        JsonNode customMetadata,
        JsonNode lastFaqImportResult,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime processedAt,
        String errorMessage,
        String knowledgeBaseName) {

    /** 由实体组装视图。 */
    public static KnowledgeResponse from(Knowledge k) {
        return new KnowledgeResponse(
                k.getId(),
                k.getKnowledgeBaseId(),
                k.getTags(),
                k.getType(),
                k.getTitle(),
                emptyToNull(k.getDescription()),
                emptyToNull(k.getSource()),
                k.getChannel(),
                ParseStatus.from(k.getParseStatus()),
                k.getPendingSubtasksCount(),
                SummaryStatus.from(k.getSummaryStatus()),
                EnableStatus.from(k.getEnableStatus()),
                emptyToNull(k.getEmbeddingModelId()),
                k.getFileName(),
                k.getFolderPath(),
                k.getFileType(),
                k.getFileSize(),
                k.getFileHash(),
                k.getStorageSize(),
                k.getMetadata(),
                k.getCustomMetadata(),
                k.getLastFaqImportResult(),
                k.getCreatedAt(),
                k.getUpdatedAt(),
                k.getProcessedAt(),
                emptyToNull(k.getErrorMessage()),
                emptyToNull(k.getKnowledgeBaseName()));
    }

    /** 空串按"未设置"处理（契约：不用空串代替 null）；空白串一并归一。 */
    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
