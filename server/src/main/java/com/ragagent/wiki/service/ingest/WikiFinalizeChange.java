package com.ragagent.wiki.service.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 供索引导语的"变更描述"使用的文档级增/删条目，作为 finalize 通道行的载荷持久化。
 *
 * @param action {@link WikiIngestConstants#FINALIZE_ADDED} 或
 *               {@link WikiIngestConstants#FINALIZE_REMOVED}
 */
@JsonPropertyOrder({"action", "doc_title", "doc_summary"})
public record WikiFinalizeChange(
        @JsonProperty("action") String action,
        @JsonProperty("doc_title") @JsonInclude(JsonInclude.Include.NON_EMPTY) String docTitle,
        @JsonProperty("doc_summary") @JsonInclude(JsonInclude.Include.NON_EMPTY) String docSummary) {

    /** 新增文档条目 */
    public static WikiFinalizeChange added(String docTitle, String docSummary) {
        return new WikiFinalizeChange(WikiIngestConstants.FINALIZE_ADDED, docTitle, docSummary);
    }

    /** 移除文档条目 */
    public static WikiFinalizeChange removed(String docTitle, String docSummary) {
        return new WikiFinalizeChange(WikiIngestConstants.FINALIZE_REMOVED, docTitle, docSummary);
    }
}
