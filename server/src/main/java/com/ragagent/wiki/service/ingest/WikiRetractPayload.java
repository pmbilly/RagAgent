package com.ragagent.wiki.service.ingest;

import java.util.List;

/**
 * wiki 内容撤回任务的载荷。
 *
 * <p>文档被删除时携带该文档写过的页面 slug 与它曾归属的目录 id，让 wiki 侧能把
 * 只由它支撑的页面删掉、多来源页面走 LLM 撤回、并回收可能变空的目录。</p>
 *
 * <p><b>纯进程内参数对象</b>：字段在 {@link WikiIngestEnqueueOps#enqueueWikiRetract}
 * 里被摊平进 {@link WikiPendingOp} 落库、追踪载体进 {@link WikiIngestPayload} 进队列，
 * 本对象自身从不序列化。</p>
 */
public record WikiRetractPayload(
        long tenantId,
        String knowledgeBaseId,
        String knowledgeId,
        String docTitle,
        String docSummary,
        String language,
        List<String> pageSlugs,
        List<String> folderIds) {}
