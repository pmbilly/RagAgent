package com.ragagent.wiki.service.ingest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * finalize 通道里 {@code task_pending_ops} 行的 JSON 载荷。
 *
 * <p>{@code Slug} / {@code Change} / {@code FolderIDs} 三者中<b>恰好一个</b>被设置，
 * 由行的 {@code Op} 列区分（{@link WikiIngestConstants#FINALIZE_OP_SLUG} /
 * {@code _CHANGE} / {@code _FOLDER_PRUNE}）。</p>
 */
@JsonPropertyOrder({"slug", "title", "change", "folder_ids"})
public record WikiFinalizeRow(
        @JsonProperty("slug") @JsonInclude(JsonInclude.Include.NON_EMPTY) String slug,
        @JsonProperty("title") @JsonInclude(JsonInclude.Include.NON_EMPTY) String title,
        @JsonProperty("change") @JsonInclude(JsonInclude.Include.NON_EMPTY) WikiFinalizeChange change,
        @JsonProperty("folder_ids") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> folderIds) {

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
