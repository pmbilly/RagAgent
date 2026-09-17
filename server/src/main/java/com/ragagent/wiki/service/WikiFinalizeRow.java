package com.ragagent.wiki.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * finalize 通道里 {@code task_pending_ops} 行的 JSON 载荷
 * （对照 Go {@code wikiFinalizeRow}，wiki_ingest.go L271-279）。
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

    /** 对照 Go {@code wikiFinalizeRow{Slug: slug, Title: freshTitleBySlug[slug]}} */
    public static WikiFinalizeRow slug(String slug, String title) {
        return new WikiFinalizeRow(slug, title, null, null);
    }

    /** 对照 Go {@code wikiFinalizeRow{Change: &changes[i]}} */
    public static WikiFinalizeRow change(WikiFinalizeChange change) {
        return new WikiFinalizeRow(null, null, change, null);
    }

    /** 对照 Go {@code wikiFinalizeRow{FolderIDs: uniqueWikiFolderIDs(folderIDs)}} */
    public static WikiFinalizeRow folderIds(List<String> folderIds) {
        return new WikiFinalizeRow(null, null, null, folderIds);
    }
}
