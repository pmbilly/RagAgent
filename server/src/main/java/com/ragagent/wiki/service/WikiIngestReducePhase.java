package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.service.SpanTracker;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.prompt.WikiPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.GoStrings;
import com.ragagent.common.wiki.WikiLanguageSupport;

/**
 * wiki 摄取 Reduce 阶段协作者:slug 更新按页归并写入(含 span 追踪与 chunk refs 合并)。
 *
 * <p>持有 {@link WikiIngestBatchHandler} 回引以访问其依赖字段;本类不得独立实例化。</p>
 */
final class WikiIngestReducePhase {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestReducePhase.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WikiIngestBatchHandler handler;

    WikiIngestReducePhase(WikiIngestBatchHandler handler) {
        this.handler = handler;
    }



    /**
     * 把一个 slug 的全部更新
     * 读-改-写成一个页面。
     *
     * <ul>
     *   <li>{@code changed}：页面是否被创建或更新；</li>
     *   <li>{@code affectedType}：{@code "ingest"} 或 {@code "retract"}——驱动下游记账；</li>
     *   <li>{@code additionFailed}：该 slug 有 entity/concept 新增排队<b>且</b>
     *       {@code WikiPageModifyUserPrompt} 的 LLM 调用失败，因此没有页面存在/被刷新。
     *       调用方据此净化别处（例如该文档摘要页里）的死 {@code [[slug]]} 链接，并把该
     *       slug 从 wiki 日志流里去掉，免得用户看到一个点了 404 的条目；</li>
     *   <li>{@code error}：持久化 upsert 的传输/仓储错误。</li>
     * </ul>
     *
     * <p><b>span 归属</b>：单个 slug 可以收到同一批次多个文档的贡献
     * （entity/concept 页面跨来源聚合）。{@code postprocess.wiki.page[slug]} 子 span
     * 挂到 updates 列表里<b>第一个</b>贡献文档的 wikiSpan 下——span 树的拓扑只允许一个父节点。
     * <b>contributors 的收集语义</b>
     * （按首次出现去重）供 span output 与下游记账使用。</p>
     */
    public WikiIngestBatchHandler.ReduceOutcome reduceSlugUpdates(LlmChatClient chatModel,
                                           String kbId,
                                           String slug,
                                           List<SlugUpdate> updates,
                                           long tenantId,
                                           WikiBatchContext batchCtx,
                                           Map<String, SpanTracker.SpanHandle> kidToWikiMap) {
        // ingest/delete 竞争的最终安全网：Map（已查过 isKnowledgeGone）与 Reduce 之间有一次
        // 很长的 LLM 调用，源文档可能在此期间被删。丢弃源知识已不存在的新增/摘要更新，
        // 免得复活一个幽灵 source_ref。retract 更新被保留——它们主动移除引用，正是文档消失
        // 时我们想要的。
        updates = handler.ingestService.filterLiveUpdates(kbId, updates);
        if (updates == null || updates.isEmpty()) {
            return new WikiIngestBatchHandler.ReduceOutcome(false, "", false, null);
        }

        List<String> contributors = new ArrayList<>();
        {
            Set<String> seen = new LinkedHashSet<>();
            for (SlugUpdate u : updates) {
                String kid = u.getKnowledgeId();
                if (kid.isEmpty() || !seen.add(kid)) {
                    continue;
                }
                contributors.add(kid);
            }
        }

        // 页级 span：
        // 挂在 updates 里第一个有 wikiSpan 的贡献文档下——span 树只允许一个父节点；
        // 完整 contributors 进 output，供追溯聚合页的多来源归属。
        SpanTracker.SpanHandle pageSpan = beginPageSpan(slug, updates, contributors, kidToWikiMap);
        WikiPage[] pageHolder = { null };
        WikiIngestBatchHandler.ReduceOutcome outcome;
        try {
            outcome = reduceSlugUpdatesBody(chatModel, kbId, slug, updates, tenantId,
                    batchCtx, pageHolder);
        } catch (RuntimeException e) {
            handler.spans.failSpan(pageSpan, "REDUCE_FAILED", e.getMessage(), e);
            throw e;
        }
        finishPageSpan(pageSpan, outcome, contributors, pageHolder[0]);
        return outcome;
    }

    /**
     * 开页级 span：
     * 父 = updates 里首个有 wikiSpan 的贡献文档；都没有 → null（no-op）。
     */
    SpanTracker.SpanHandle beginPageSpan(String slug, List<SlugUpdate> updates,
                                                 List<String> contributors,
                                                 Map<String, SpanTracker.SpanHandle> kidToWikiMap) {
        if (kidToWikiMap == null || kidToWikiMap.isEmpty()) {
            return null;
        }
        for (String kid : contributors) {
            SpanTracker.SpanHandle parent = kidToWikiMap.get(kid);
            if (parent == null) {
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("slug", slug);
            input.put("updates", updates.size());
            input.put("contributors", new ArrayList<>(contributors));
            return handler.spans.beginSubSpan(parent, "postprocess.wiki.page[" + slug + "]", input);
        }
        return null;
    }

    /**
     * 页级 span 收尾：错误 → FailSpan；
     * 无变化 → SkipSpan；正常 → EndSpan，output 捕获<b>合并后</b>的页面状态
     * （title / page_type / summary / content 预览 / refs 计数 / aliases）。
     */
    void finishPageSpan(SpanTracker.SpanHandle pageSpan, WikiIngestBatchHandler.ReduceOutcome outcome,
                                List<String> contributors, WikiPage page) {
        if (pageSpan == null) {
            return;
        }
        if (outcome.error() != null) {
            handler.spans.failSpan(pageSpan, "REDUCE_FAILED",
                    outcome.error().getMessage(), outcome.error());
            return;
        }
        if (!outcome.changed()) {
            handler.spans.skipSpan(pageSpan, "no_change");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("affected_type", outcome.affectedType());
        out.put("addition_failed", outcome.additionFailed());
        out.put("contributors", contributors);
        if (page != null) {
            out.put("page_title", WikiTextUtils.previewText(page.getTitle(), 160));
            out.put("page_type", page.getPageType());
            out.put("page_summary", WikiTextUtils.previewText(page.getSummary(), 200));
            out.put("content_preview", WikiTextUtils.previewText(page.getContent(), 320));
            out.put("source_refs", page.getSourceRefs() == null ? 0 : page.getSourceRefs().size());
            out.put("chunk_refs", page.getChunkRefs() == null ? 0 : page.getChunkRefs().size());
            out.put("aliases", page.getAliases() == null ? List.of() : page.getAliases());
        }
        handler.spans.endSpan(pageSpan, out);
    }

    /** 原 reduce 主体；页面写入 {@code pageHolder[0]} 供 span 收尾读取最终状态。 */
    WikiIngestBatchHandler.ReduceOutcome reduceSlugUpdatesBody(LlmChatClient chatModel, String kbId,
                                                String slug, List<SlugUpdate> updates,
                                                long tenantId, WikiBatchContext batchCtx,
                                                WikiPage[] pageHolder) {
        try {
            // findPageBySlug 查无此页时返回 null 而不是抛错——not found 是正常路径
            // （下面合成新页），不能让它冒泡成 reduce 失败。
            WikiPage page = handler.wikiService.findPageBySlug(kbId, slug);
            pageHolder[0] = page;
            boolean exists = page != null;

            if (!exists) {
                boolean hasAdditions = false;
                for (SlugUpdate u : updates) {
                    if (SlugUpdate.TYPE_ENTITY.equals(u.getType())
                            || SlugUpdate.TYPE_CONCEPT.equals(u.getType())
                            || SlugUpdate.TYPE_SUMMARY.equals(u.getType())) {
                        hasAdditions = true;
                        break;
                    }
                }
                if (!hasAdditions) {
                    return new WikiIngestBatchHandler.ReduceOutcome(false, "", false, null);
                }

                page = new WikiPage();
                pageHolder[0] = page;
                page.setId(UUID.randomUUID().toString());
                page.setTenantId(tenantId);
                page.setKnowledgeBaseId(kbId);
                page.setSlug(slug);
                page.setStatus(WikiConstants.STATUS_DRAFT);
                page.setSourceRefs(new ArrayList<>());
                page.setAliases(new ArrayList<>());
            }

            String affectedType = "ingest";

            SlugUpdate summaryUpdate = null;
            List<SlugUpdate> retracts = new ArrayList<>();
            List<SlugUpdate> additions = new ArrayList<>();

            for (SlugUpdate u : updates) {
                if (SlugUpdate.TYPE_SUMMARY.equals(u.getType())) {
                    summaryUpdate = u;
                } else if (u.isRetractType()) {
                    retracts.add(u);
                    affectedType = "retract";
                } else if (SlugUpdate.TYPE_ENTITY.equals(u.getType())
                        || SlugUpdate.TYPE_CONCEPT.equals(u.getType())) {
                    additions.add(u);
                    affectedType = "ingest"; // 新增覆盖 retract 的类型判定
                }
            }

            if (summaryUpdate != null) {
                page.setTitle(summaryUpdate.getDocTitle() + " - Summary");
                page.setContent(summaryUpdate.getSummaryBody());
                page.setSummary(summaryUpdate.getSummaryLine());
                page.setPageType(WikiConstants.PAGE_TYPE_SUMMARY);
                page.setSourceRefs(WikiIngestBatchHandler.appendUnique(page.getSourceRefs(), summaryUpdate.getSourceRef()));
                // 摘要页不携带 chunk 级引用（它们是从整篇正文生成的文档级概要）。
                // 清理该 slug 曾经是 entity 页并被改造成摘要页时可能残留的陈旧 chunk ref。
                page.setChunkRefs(new ArrayList<>());
                if (exists) {
                    handler.wikiService.updatePage(page);
                } else {
                    handler.wikiService.createPage(page);
                }
                return new WikiIngestBatchHandler.ReduceOutcome(true, affectedType, false, null);
            }

            StringBuilder remainingSourcesContent = new StringBuilder();
            StringBuilder deletedContent = new StringBuilder();
            StringBuilder relatedSlugs = new StringBuilder();
            StringBuilder newContentBuilder = new StringBuilder();
            StringBuilder sharedSourceContexts = new StringBuilder();

            String language = WikiLanguageSupport.resolveSlugUpdateLanguage(updates);

            if (!retracts.isEmpty()) {
                for (SlugUpdate r : retracts) {
                    deletedContent.append("<document>\n<title>").append(r.getDocTitle())
                            .append("</title>\n<content>\n").append(r.getRetractDocContent())
                            .append("\n</content>\n</document>\n\n");
                }

                Set<String> retractKIDs = new LinkedHashSet<>();
                for (SlugUpdate r : retracts) {
                    retractKIDs.add(r.getKnowledgeId());
                }

                for (String ref : page.getSourceRefs()) {
                    int pipeIdx = ref.indexOf('|');
                    String refKnowledgeID;
                    String refTitle;
                    if (pipeIdx > 0) {
                        refKnowledgeID = ref.substring(0, pipeIdx);
                        refTitle = ref.substring(pipeIdx + 1);
                    } else {
                        refKnowledgeID = ref;
                        refTitle = ref;
                    }

                    if (retractKIDs.contains(refKnowledgeID)) {
                        continue;
                    }

                    String refContent = batchCtx == null
                            ? "" : batchCtx.summaryContentByKnowledgeId(refKnowledgeID);
                    if (!refContent.isEmpty()) {
                        remainingSourcesContent.append("<document>\n<title>").append(refTitle)
                                .append("</title>\n<content>\n").append(refContent)
                                .append("\n</content>\n</document>\n\n");
                    } else {
                        remainingSourcesContent.append("<document>\n<title>").append(refTitle)
                                .append("</title>\n<content>\n(summary not available)\n</content>\n")
                                .append("</document>\n\n");
                    }
                }
                if (remainingSourcesContent.length() == 0) {
                    remainingSourcesContent.append("(no remaining sources)");
                }

                List<String> newRefs = new ArrayList<>();
                for (String ref : page.getSourceRefs()) {
                    int pipeIdx = ref.indexOf('|');
                    String refKnowledgeID = pipeIdx > 0 ? ref.substring(0, pipeIdx) : ref;
                    if (!retractKIDs.contains(refKnowledgeID)) {
                        newRefs.add(ref);
                    }
                }
                page.setSourceRefs(newRefs);
            }

            if (!additions.isEmpty()) {
                // 把 SourceChunks 解析成 chunk 正文：按 knowledge ID 一批查询，让
                // <new_information> 块可以逐字引用 chunk，而不是依赖简短的 Details 改写。
                Map<String, String> chunkContentByID =
                        handler.citePipeline.resolveCitedChunks(tenantId, additions);
                // 一份文档摘要被该文档派生的每个页面共享。在任何页面专属元数据之前渲染一个
                // 确定性的、去重的块，让 provider 的前缀缓存能在并行的 reduce 调用之间复用。
                Map<String, String> sourceContextByRef = new LinkedHashMap<>();

                for (SlugUpdate add : additions) {
                    ExtractedItem item = add.getItem() == null ? new ExtractedItem() : add.getItem();
                    String cited = WikiIngestCitePipeline.collectCitedChunkContent(
                            add.sourceChunksOrEmpty(), chunkContentByID);
                    // 用文档级摘要正文把 chunk 框起来，让编辑模型既知道文档讲什么，也知道它
                    // 是哪一类文档（简历 vs 公告 vs 产品页 vs 日程）。只有一句话的标题对较长、
                    // 多主题的源文档来说太薄了。
                    String sourceCtx = add.getDocSummary().trim();
                    if (!sourceCtx.isEmpty()) {
                        String contextKey = add.getSourceRef();
                        if (contextKey.isEmpty()) {
                            contextKey = add.getKnowledgeId() + "\u0000" + add.getDocTitle();
                        }
                        sourceContextByRef.put(contextKey, "<document>\n<title>" + add.getDocTitle()
                                + "</title>\n<context>\n" + sourceCtx + "\n</context>\n</document>\n");
                    }
                    // 回落：没有引用可用（旧版路径 / 引用遍失败 / 坏的 chunk ID 被过滤掉）
                    // 时，沿用简短的 Details 摘要，让页面仍得到真实文本。
                    String body = cited.isEmpty() ? item.getDetails() : cited;
                    newContentBuilder.append("<document>\n<title>").append(add.getDocTitle())
                            .append("</title>\n<content>\n**").append(item.getName())
                            .append("**: ").append(item.getDescription())
                            .append("\n\n").append(body)
                            .append("\n</content>\n</document>\n\n");

                    for (String alias : item.getAliases()) {
                        page.setAliases(WikiIngestBatchHandler.appendUnique(page.getAliases(), alias));
                    }
                    page.setSourceRefs(WikiIngestBatchHandler.appendUnique(page.getSourceRefs(), add.getSourceRef()));

                    if (page.getTitle().isEmpty()) {
                        page.setTitle(item.getName());
                    }
                    if (page.getPageType().isEmpty()) {
                        page.setPageType(add.getType());
                    }
                }

                List<String> contextKeys = new ArrayList<>(sourceContextByRef.keySet());
                contextKeys.sort(GoStrings::compareByCodePoints);
                for (String key : contextKeys) {
                    sharedSourceContexts.append(sourceContextByRef.get(key));
                }
            }

            boolean changed = false;
            boolean additionFailed = false;

            if (!additions.isEmpty() || !retracts.isEmpty()) {
                Map<String, String> titles = batchCtx == null
                        ? Map.of() : batchCtx.slugTitleMany(new ArrayList<>(page.getOutLinks()));

                // slugHandles 把高熵 slug 藏到短引用句柄（ref-1、ref-2…）后面给编辑 LLM 用，
                // 生成之后再翻译回真实 slug（见下面的 decodeContent）。
                WikiSlugHandles slugHandles = new WikiSlugHandles();

                // 把每条出链 slug 藏到请求局部句柄后面，编辑模型因此永远不用重打真实 slug
                // ——UUID 式摘要 slug（summary/<uuid>）就是这样被弄花成 404 链接的。
                // <valid_wiki_links> 清单与 <existing_page_content> 里已有的 [[...]] 引用用的
                // 是<b>同一张</b>句柄表，因此模型看到一个一致、可安全复制的标识空间；
                // 我们在生成之后把句柄翻回真实 slug。
                Set<String> known = new LinkedHashSet<>(page.getOutLinks());
                for (String outSlug : page.getOutLinks()) {
                    slugHandles.handle(outSlug); // 预分配，让顺序稳定
                }
                for (String outSlug : page.getOutLinks()) {
                    String title = titles.get(outSlug);
                    if (title != null && !title.isEmpty()) {
                        relatedSlugs.append("- ").append(slugHandles.handle(outSlug))
                                .append(" (").append(title).append(")\n");
                    }
                }

                // 较早生成的页面可能仍含 [c003] 这类短 chunk 别名。它们是内部摄取元数据；
                // 保持编辑语境干净，让后续更新不会把它们复制进重写的正文。
                String existingContent = WikiBatchSupport.stripInlineChunkCitations(page.getContent());
                existingContent = slugHandles.encodeContent(existingContent, known);
                if (!exists || existingContent.isEmpty()) {
                    existingContent = "(New page)";
                }

                String hasAdditionsStr = additions.isEmpty() ? "" : "1";
                String hasRetractionsStr = retracts.isEmpty() ? "" : "1";

                // title/type 仍未设置时优雅回落（对良构更新不该发生——两者都在上面的
                // additions 循环里填好，而纯 retract 路径要求页面已存在——但保持防御性，
                // 免得给 LLM 喂一个空的身份块）。
                String pageTitle = page.getTitle().isEmpty() ? slug : page.getTitle();
                String pageType = page.getPageType().isEmpty()
                        ? WikiBatchConstants.PAGE_TYPE_FALLBACK : page.getPageType();
                String pageAliases = String.join(", ", page.getAliases());

                try {
                    String updatedContent = handler.ingestService.generateWithTemplate(chatModel,
                            WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT,
                            Map.ofEntries(
                                    Map.entry("HasAdditions", hasAdditionsStr),
                                    Map.entry("HasRetractions", hasRetractionsStr),
                                    Map.entry("PageSlug", slug),
                                    Map.entry("PageTitle", pageTitle),
                                    Map.entry("PageType", pageType),
                                    Map.entry("PageAliases", pageAliases),
                                    Map.entry("ExistingContent", existingContent),
                                    Map.entry("SharedSourceContexts", sharedSourceContexts.toString()),
                                    Map.entry("NewContent", newContentBuilder.toString()),
                                    Map.entry("DeletedContent", deletedContent.toString()),
                                    Map.entry("RemainingSourcesContent", remainingSourcesContent.toString()),
                                    Map.entry("AvailableSlugs", relatedSlugs.toString()),
                                    Map.entry("Language", language == null ? "" : language),
                                    Map.entry("CustomInstructions",
                                            batchCtx == null ? "" : batchCtx.getContentInstructions()),
                                    Map.entry("InstructionScope", WikiBatchConstants.INSTRUCTION_SCOPE_CONTENT)));

                    if (updatedContent != null && !updatedContent.isEmpty()) {
                        // 在正文被解析/存储<b>之前</b>，把模型从编码语境里抄来的请求局部句柄
                        // （ref-N）翻回真实 slug，让 out_links 重新反映真实页面。
                        updatedContent = slugHandles.decodeContent(updatedContent);
                        WikiTextUtils.SummaryLine updated = WikiTextUtils.splitSummaryLine(updatedContent);
                        page.setContent(updated.content().isEmpty() ? updatedContent : updated.content());
                        if (!updated.summary().isEmpty()) {
                            page.setSummary(updated.summary());
                        }
                        changed = true;
                    }
                } catch (RuntimeException genErr) {
                    log.warn("wiki ingest: update/retract failed for slug {}: {}",
                            slug, genErr.getMessage());
                    // 标记新增失败，让批次能净化该文档摘要页里陈旧的 [[slug]] 引用，并把缺失
                    // 的页面排除出 finalize 处理。纯 retract 的失败不会毒化任何东西
                    // （页面保持原样），因此不标记。
                    if (!additions.isEmpty()) {
                        additionFailed = true;
                    }
                    // <b>不</b>把 LLM 错误向上传播：它已经记过日志，再抛出去只会
                    // 让上层再记一次 "reduce failed for slug"。
                }
            }

            // 应用批次的 handler.taxonomy 计划，但只对尚未归档的页面——新页面因此得到连贯的目录，
            // 而先前已归档或用户移动过的页面保持原位（手工编辑是权威的）。
            // 页面的 category_path 缓存由 CreatePage/UpdatePage 从 folder_id 派生，
            // 因此这里只赋 folder_id 就够了。
            if (page.getFolderId().isEmpty() && batchCtx != null) {
                String fid = batchCtx.getPlannedFolderId().get(slug);
                if (fid != null && !fid.isEmpty()) {
                    page.setFolderId(fid);
                }
            }

            if (changed) {
                // 就地刷新 chunk ref，让它们随行的其余部分一起持久化。纯 retract 更新
                // （无新增）保留既有 refs；新增轮次把新引用的 chunk 追加到已有之上（去重）。
                page.setChunkRefs(mergeChunkRefs(page.getChunkRefs(), additions));
                if (exists) {
                    handler.wikiService.updatePage(page);
                } else {
                    handler.wikiService.createPage(page);
                }
                return new WikiIngestBatchHandler.ReduceOutcome(true, affectedType, additionFailed, null);
            }

            return new WikiIngestBatchHandler.ReduceOutcome(false, "", additionFailed, null);
        } catch (RuntimeException e) {
            return new WikiIngestBatchHandler.ReduceOutcome(false, "", false, e);
        }
    }

    /**
     * 把页面当前的 chunk ID 与本批次
     * additions 引用的 chunk ID 求并集，保留插入顺序并丢弃重复项。空串被过滤掉，免得畸形
     * source_chunks 数组在列里留下垃圾。
     *
     * <p>没有 additions 的 retract 轮次保持当前 refs 不变——纯 retract 路径不携带 chunk ID
     * （只有 knowledge ID），没有那个信息我们无法做精细过滤。下次该 slug 经 additions 重新
     * 物化时，新的 chunk 会覆盖上去。</p>
     */
    public static List<String> mergeChunkRefs(List<String> current, List<SlugUpdate> additions) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (current != null) {
            for (String id : current) {
                if (id == null || id.isEmpty() || !seen.add(id)) {
                    continue;
                }
                out.add(id);
            }
        }
        if (additions != null) {
            for (SlugUpdate add : additions) {
                for (String chunkID : add.sourceChunksOrEmpty()) {
                    if (chunkID == null || chunkID.isEmpty() || !seen.add(chunkID)) {
                        continue;
                    }
                    out.add(chunkID);
                }
            }
        }
        return out;
    }
}
