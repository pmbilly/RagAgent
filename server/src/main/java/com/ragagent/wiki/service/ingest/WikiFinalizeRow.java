package com.ragagent.wiki.service.ingest;

import java.util.List;

/**
 * finalize 通道里 {@code task_pending_ops} 行的 JSON 载荷。
 *
 * <p>{@code Slug} / {@code Change} / {@code FolderIDs} 三者中<b>恰好一个</b>被设置，
 * 由行的 {@code Op} 列区分（{@link WikiIngestConstants#FINALIZE_OP_SLUG} /
 * {@code _CHANGE} / {@code _FOLDER_PRUNE}）；未设置的两个键显式输出 {@code null}。</p>
 */
public record WikiFinalizeRow(
        String slug,
        String title,
        WikiFinalizeChange change,
        List<String> folderIds) {

    /** slug 变更行 */
    public static WikiFinalizeRow slug(String slug, String title) {
        return new WikiFinalizeRow(slug, title, null, null);
    }

    /** 变更描述行 */
    public static WikiFinalizeRow change(WikiFinalizeChange change) {
        return new WikiFinalizeRow(null, null, change, null);
    }

    /** 目录剪枝行（folderIds 需已去重） */
    public static WikiFinalizeRow folderIds(List<String> folderIds) {
        return new WikiFinalizeRow(null, null, null, folderIds);
    }
}
