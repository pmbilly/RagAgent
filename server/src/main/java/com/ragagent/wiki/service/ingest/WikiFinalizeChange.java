package com.ragagent.wiki.service.ingest;

/**
 * 供索引导语的"变更描述"使用的文档级增/删条目，作为 finalize 通道行的载荷持久化。
 *
 * @param action {@link WikiIngestConstants#FINALIZE_ADDED} 或
 *               {@link WikiIngestConstants#FINALIZE_REMOVED}
 */
public record WikiFinalizeChange(
        String action,
        String docTitle,
        String docSummary) {

    /** 新增文档条目 */
    public static WikiFinalizeChange added(String docTitle, String docSummary) {
        return new WikiFinalizeChange(WikiIngestConstants.FINALIZE_ADDED, docTitle, docSummary);
    }

    /** 移除文档条目 */
    public static WikiFinalizeChange removed(String docTitle, String docSummary) {
        return new WikiFinalizeChange(WikiIngestConstants.FINALIZE_REMOVED, docTitle, docSummary);
    }
}
