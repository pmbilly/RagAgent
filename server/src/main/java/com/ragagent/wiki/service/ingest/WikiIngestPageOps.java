package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.service.page.WikiCrossLinker;
import com.ragagent.wiki.service.page.WikiDeadLinks;
import com.ragagent.wiki.service.page.WikiTextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reduce 后的页面运维协作者:死链摘要清理、死链清理与交叉链接注入。
 *
 * <p>持有 {@link WikiIngestService} 回引以访问其依赖与队列原语;本类不得独立实例化。</p>
 */
public final class WikiIngestPageOps {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestPageOps.class);

    private final WikiIngestService service;

    WikiIngestPageOps(WikiIngestService service) {
        this.service = service;
    }

    /**
     * 重写<b>本批次</b>产出的
     * 摘要页，修掉那些指向 reduce 阶段生成失败的 entity/concept 页面的
     * {@code [[slug]]} / {@code [[slug|display]]} 引用。
     *
     * <p>纯文本替换，不调用 LLM。作用域限定在本批次的文档摘要 slug
     * （{@code summary/<slugify(knowledgeID)>}），让工作量与批次大小成正比。</p>
     */
    public void sanitizeDeadSummaryLinks(String kbId,
                                         List<DocIngestResult> docResults,
                                         Set<String> failedSlugs,
                                         WikiBatchContext batchCtx) {
        if (failedSlugs == null || failedSlugs.isEmpty()
                || docResults == null || docResults.isEmpty()) {
            return;
        }
        for (DocIngestResult r : docResults) {
            if (r == null || r.getKnowledgeId().isEmpty()) {
                continue;
            }
            String summarySlug = "summary/" + WikiTextUtils.slugify(r.getKnowledgeId());
            WikiPage page = service.wikiService.findPageBySlug(kbId, summarySlug);
            if (page == null) {
                continue;
            }
            // 收集这份摘要实际链接到的 slug（让 resolver 有非空的候选池），
            // 加上同一文档里成功写出的兄弟页面。这两个集合合起来覆盖了
            // "LLM 说的" vs "实际存在的" 不匹配，又不必为一次全量扫描付费。
            Set<String> candidateSlugs = new LinkedHashSet<>(page.getOutLinks());
            for (DocIngestResult.PageRef ref : r.getPages()) {
                if (failedSlugs.contains(ref.slug())) {
                    continue;
                }
                candidateSlugs.add(ref.slug());
            }
            WikiDeadLinks.ResolvedLiveSlugs resolved =
                    WikiDeadLinks.resolveLiveSlugs(batchCtx, candidateSlugs);
            WikiDeadLinks.Result stripped = WikiDeadLinks.stripDeadWikiLinks(
                    page.getContent(), failedSlugs, resolved.liveSlugs(), resolved.titleToSlug());
            if (!stripped.changed()) {
                continue;
            }
            page.setContent(stripped.content());
            try {
                service.wikiService.updateAutoLinkedContent(page);
            } catch (Exception e) {
                log.warn("wiki ingest: failed to sanitize dead links in summary {}: {}",
                        summarySlug, e.getMessage());
                continue;
            }
            log.info("wiki ingest: sanitized dead [[slug]] refs in summary {}", summarySlug);
        }
    }

    /**
     * 重写本批次受影响页面里指向
     * 已不存在（或已归档）目标的 {@code [[slug]]}。纯文本清理，不调用 LLM。
     *
     * <p>作用域刻意限定在本批次触碰过的 slug：4 万文档规模下，"扫全表页面"的历史路径
     * 是批次后阶段的主要尾巴，而长尾的历史死链更适合交给 lint AutoFix 管线
     * （它跑在带外，承担得起全表遍历）。</p>
     *
     * <p>流程：取页面 → 用一次批量 {@code existsSlugs} 把出链分类成活/死 →
     * 对每条死链先试 {@code resolveDeadSlug}，能安全还原就改写，否则剥离成纯文本 →
     * 用 {@code updateAutoLinkedContent} 持久化（版本号不变——这是维护性写入，
     * 不是用户可见的编辑）。</p>
     */
    public void cleanDeadLinks(String kbId, List<String> affectedSlugs, WikiBatchContext batchCtx) {
        if (affectedSlugs == null || affectedSlugs.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (String slug : affectedSlugs) {
            WikiPage page = service.wikiService.findPageBySlug(kbId, slug);
            if (page == null) {
                continue;
            }
            if (WikiConstants.STATUS_ARCHIVED.equals(page.getStatus())) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                continue;
            }
            if (page.getOutLinks().isEmpty()) {
                continue;
            }
            Map<String, Boolean> liveMap;
            try {
                liveMap = service.wikiService.existsSlugs(kbId, new ArrayList<>(page.getOutLinks()));
            } catch (Exception e) {
                log.warn("wiki: ExistsSlugs failed during dead-link cleanup for {}: {}",
                        slug, e.getMessage());
                continue;
            }
            Set<String> deadSlugs = new LinkedHashSet<>();
            Set<String> liveSlugs = new LinkedHashSet<>();
            for (Map.Entry<String, Boolean> e : liveMap.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    liveSlugs.add(e.getKey());
                } else {
                    deadSlugs.add(e.getKey());
                }
            }
            if (deadSlugs.isEmpty()) {
                continue;
            }
            // 只为活跃 slug 取标题——它们才是一条死引用可能被重映射到的候选
            Map<String, String> titles = batchCtx == null
                    ? Map.of() : batchCtx.slugTitleMany(new ArrayList<>(page.getOutLinks()));
            Map<String, String> titleToSlug = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : titles.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    titleToSlug.put(e.getValue(), e.getKey());
                }
            }
            WikiDeadLinks.Result stripped =
                    WikiDeadLinks.stripDeadWikiLinks(page.getContent(), deadSlugs, liveSlugs, titleToSlug);
            if (!stripped.changed()) {
                continue;
            }
            page.setContent(stripped.content());
            try {
                service.wikiService.updateAutoLinkedContent(page);
            } catch (Exception e) {
                log.warn("wiki: failed to clean dead links in page {}: {}", page.getSlug(), e.getMessage());
                continue;
            }
            cleaned++;
        }
        if (cleaned > 0) {
            log.info("wiki: cleaned dead links in {} pages", cleaned);
        }
    }

    /**
     * 扫描本批次受影响的页面，
     * 为正文里提到的其它页面标题 / 别名注入 {@code [[wiki-links]]}。
     * 纯文本替换，不调用 LLM。
     *
     * <p>作用域刻意限定在两批 slug：</p>
     * <ol>
     *   <li>受影响的页面本身——我们只重写它们的正文；</li>
     *   <li>候选 ref 来自 (a) 这些页面既有的出链（已通过先前的 linkify 或人工编辑
     *       证明其相关性）加上 (b) 调用方通过 {@code freshRefs} 传入的、本批次刚写出的
     *       兄弟 slug。</li>
     * </ol>
     *
     * <p>较之"只为了找链接候选就加载 10 万+ 页面"，这是 O(批次大小) 的查询。
     * 代价是长尾召回略降（本批次新出现的实体，要等到相关页面被重新编辑时才会
     * 被链进去），而 lint AutoFix 才是处理那种情况的正道。</p>
     *
     * <p>实际匹配（含代码块 / 既有链接 / 词边界排除）由 {@link WikiCrossLinker} 完成。</p>
     */
    public void injectCrossLinks(String kbId,
                                 List<String> affectedSlugs,
                                 List<WikiCrossLinker.LinkRef> freshRefs,
                                 WikiBatchContext batchCtx) {
        if (affectedSlugs == null || affectedSlugs.isEmpty()) {
            return;
        }
        WikiCrossLinker linker = service.crossLinker.getIfAvailable(WikiCrossLinker.Noop::new);
        int updated = 0;
        for (String slug : affectedSlugs) {
            WikiPage page = service.wikiService.findPageBySlug(kbId, slug);
            if (page == null) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                continue;
            }
            // 逐页候选 ref 集合：既有出链（经批次的标题 fetcher 解析，
            // 顺带跳过归档 / 系统页面）加上本批次刚写出的兄弟 slug。
            List<WikiCrossLinker.LinkRef> refs = new ArrayList<>();
            if (!page.getOutLinks().isEmpty()) {
                Map<String, String> titles = batchCtx == null
                        ? Map.of() : batchCtx.slugTitleMany(new ArrayList<>(page.getOutLinks()));
                for (Map.Entry<String, String> e : titles.entrySet()) {
                    if (e.getValue() == null || e.getValue().isEmpty()) {
                        continue;
                    }
                    if (e.getKey().equals(slug)) {
                        continue;
                    }
                    refs.add(new WikiCrossLinker.LinkRef(e.getKey(), e.getValue()));
                }
            }
            if (freshRefs != null) {
                for (WikiCrossLinker.LinkRef fr : freshRefs) {
                    if (fr.slug().equals(slug)) {
                        continue;
                    }
                    refs.add(fr);
                }
            }
            if (refs.isEmpty()) {
                continue;
            }
            WikiCrossLinker.LinkifyResult result = linker.linkify(page.getContent(), refs, page.getSlug());
            if (!result.changed()) {
                continue;
            }
            page.setContent(result.content());
            try {
                service.wikiService.updateAutoLinkedContent(page);
            } catch (Exception e) {
                log.warn("wiki ingest: cross-link injection failed for {}: {}",
                        page.getSlug(), e.getMessage());
                continue;
            }
            updated++;
        }
        if (updated > 0) {
            log.info("wiki ingest: injected cross-links in {} pages", updated);
        }
    }
}
