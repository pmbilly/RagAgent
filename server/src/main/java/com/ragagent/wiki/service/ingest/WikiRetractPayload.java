package com.ragagent.wiki.service.ingest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * wiki 内容撤回任务的载荷。
 *
 * <p>文档被删除时携带该文档写过的页面 slug 与它曾归属的目录 id，让 wiki 侧能把
 * 只由它支撑的页面删掉、多来源页面走 LLM 撤回、并回收可能变空的目录。</p>
 */
@JsonPropertyOrder({
        "tenant_id", "knowledge_base_id", "knowledge_id", "doc_title",
        "doc_summary", "language", "page_slugs", "folder_ids"})
public record WikiRetractPayload(
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("knowledge_id") String knowledgeId,
        @JsonProperty("doc_title") String docTitle,
        @JsonProperty("doc_summary") @JsonInclude(JsonInclude.Include.NON_EMPTY) String docSummary,
        @JsonProperty("language") @JsonInclude(JsonInclude.Include.NON_EMPTY) String language,
        @JsonProperty("page_slugs") List<String> pageSlugs,
        @JsonProperty("folder_ids") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> folderIds) {}
