package com.ragagent.wiki.service.page;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiLintIssue;
import com.ragagent.wiki.domain.WikiLintReport;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * wiki 健康体检。
 *
 * <p><b>依赖面</b>：{@link WikiPageService} 加两个只读窄查询——取 KB（判 wiki 开关）
 * 与按 id 判文档是否还活着。两者都带 {@code deleted_at IS NULL}
 * （软删除的默认谓词）。</p>
 *
 * <p><b>实现说明</b>：4 万文档规模下，旧的「一次性载入所有页面」是该方法的主要
 * 长尾（生产上偶发 OOM）。现在用 {@code ListPagesCursor} 以
 * {@value #LINT_CURSOR_BATCH} 条为一窗走页面集合，增量累积问题——无论 KB 多大，
 * 内存都有界。同时去掉了旧路径用来算活跃 slug 集合的全图调用：
 * {@code ListAllSlugs} 是同一谓词（kbID + status&lt;&gt;archived）上的单列投影，
 * 答案一样而代价只有零头。</p>
 */
@Service
public class WikiLintService {

    private static final Logger log = LoggerFactory.getLogger(WikiLintService.class);

    /**
     * 流式走页的每批条数。取 200 是因为
     * wiki 页面可能带多 KB 正文，200 行 × 约 20KB ≈ 4MB 常驻，仍在跑逐页检查时
     * 可以接受的范围内。
     */
    static final int LINT_CURSOR_BATCH = 200;

    /** "正文过短"判定的阈值（UTF-8 <b>字节</b>数） */
    static final int EMPTY_CONTENT_THRESHOLD_BYTES = 50;

    private final WikiPageService wikiService;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;

    public WikiLintService(WikiPageService wikiService,
                           KnowledgeBaseMapper kbMapper,
                           KnowledgeMapper knowledgeMapper) {
        this.wikiService = wikiService;
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 对 wiki 知识库做一次完整健康检查。
     */
    public WikiLintReport runLint(String kbId) {
        // 校验 KB
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .last("LIMIT 1"));
        if (kb == null) {
            throw new WikiException("get KB: knowledge base not found");
        }
        if (!kb.getIndexingStrategy().isWikiEnabled()) {
            throw new WikiException("KB " + kbId + " is not a wiki type");
        }

        WikiStats stats;
        try {
            stats = wikiService.getStats(kbId);
        } catch (RuntimeException e) {
            throw new WikiException("get stats: " + e.getMessage(), e);
        }

        // 用廉价的单列投影算出活跃 slug 集合。这取代了整次 GetGraph 调用
        // （那会把每个节点 + 每条边都物化出来），换成一条 Pluck("slug") 查询。
        List<String> liveSlugs;
        try {
            liveSlugs = wikiService.listAllSlugs(kbId);
        } catch (RuntimeException e) {
            throw new WikiException("list all slugs: " + e.getMessage(), e);
        }
        Set<String> slugSet = new HashSet<>(liveSlugs);

        // ⚠️ 一条问题都没有时 issues 保持 null
        // （JSON 输出 "issues":null，不是 []，见 WikiLintReport）。只在首次追加时分配。
        List<WikiLintIssue> issues = null;
        int healthScore = 100;
        Map<String, Boolean> knowledgeLive = new LinkedHashMap<>(); // kid -> 是否存在；跨页缓存

        // 第一趟：孤儿页 / 死链 / 空内容 / 陈旧引用。每项检查彼此独立于顺序；
        // 逐批累积问题，游标走法让内存有界。
        //
        // 这一趟同时收集 entity / concept 的标题，好让「缺失交叉引用」检查
        // （本质是 实体数 × 页面数 的 O(N×M)）不必为了找候选而再走一趟。
        // 检查本身仍在第二趟跑，因为它需要完整的实体标题集合来对比任意页面。
        Map<String, String> entitySlugs = new LinkedHashMap<>(); // slug -> title

        String cursor = "";
        while (true) {
            WikiPageService.CursorPage batch;
            try {
                batch = wikiService.listPagesCursor(kbId, cursor, LINT_CURSOR_BATCH);
            } catch (RuntimeException e) {
                throw new WikiException("list pages cursor: " + e.getMessage(), e);
            }
            List<WikiPage> pages = batch.pages();
            if (pages.isEmpty()) {
                break;
            }
            for (WikiPage page : pages) {
                // 为第二趟记录 entity/concept 的标题
                if (WikiConstants.PAGE_TYPE_ENTITY.equals(page.getPageType())
                        || WikiConstants.PAGE_TYPE_CONCEPT.equals(page.getPageType())) {
                    entitySlugs.put(page.getSlug(), page.getTitle());
                }

                // 检查 1：孤儿页（没有入链，排除系统页）
                if (!WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                    if (page.getInLinks().isEmpty()) {
                        issues = appendIssue(issues, new WikiLintIssue(
                                WikiLintIssue.ORPHAN_PAGE, WikiLintIssue.SEVERITY_WARNING,
                                page.getSlug(), "",
                                "Page '" + page.getTitle()
                                        + "' has no inbound links — it's disconnected from the wiki",
                                false));
                    }
                }

                // 检查 2：死链——出链指向活跃集合里不存在的 slug
                for (String outLink : page.getOutLinks()) {
                    if (!slugSet.contains(outLink)) {
                        issues = appendIssue(issues, new WikiLintIssue(
                                WikiLintIssue.BROKEN_LINK, WikiLintIssue.SEVERITY_ERROR,
                                page.getSlug(), outLink,
                                "Page '" + page.getTitle() + "' links to [[" + outLink
                                        + "]] which does not exist",
                                true));
                    }
                }

                // 检查 3：正文过短
                // ⚠️ 阈值按 UTF-8 <b>字节</b>数比较（不是 char 数），否则 CJK 正文会
                //    被系统性高估。
                String content = page.getContent().trim();
                int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                if (contentBytes < EMPTY_CONTENT_THRESHOLD_BYTES) {
                    issues = appendIssue(issues, new WikiLintIssue(
                            WikiLintIssue.EMPTY_CONTENT, WikiLintIssue.SEVERITY_WARNING,
                            page.getSlug(), "",
                            "Page '" + page.getTitle() + "' has very little content ("
                                    + contentBytes + " chars)",
                            true));
                }

                // 检查 4：陈旧引用——source_refs 指向已软删的文档。缓存
                // knowledgeLive 让每批里遇到过的 kid 之后都是 O(1)。
                if (!WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                    for (String ref : page.getSourceRefs()) {
                        String kid = ref;
                        int i = ref.indexOf('|');
                        if (i > 0) {
                            kid = ref.substring(0, i);
                        }
                        if (kid.isEmpty()) {
                            continue;
                        }
                        Boolean live = knowledgeLive.get(kid);
                        if (live == null) {
                            live = knowledgeExistsByIdOnly(kid);
                            knowledgeLive.put(kid, live);
                        }
                        if (!live) {
                            issues = appendIssue(issues, new WikiLintIssue(
                                    WikiLintIssue.STALE_REF, WikiLintIssue.SEVERITY_ERROR,
                                    page.getSlug(), kid,
                                    "Page '" + page.getTitle()
                                            + "' references deleted knowledge " + kid,
                                    true));
                        }
                    }
                }
            }
            if (batch.nextCursor() == null || batch.nextCursor().isEmpty()) {
                break;
            }
            cursor = batch.nextCursor();
        }

        // 第二趟：缺失交叉引用。它需要第一趟建好的完整 entitySlugs 映射，所以
        // 必须单独一趟——但依然是流式的。
        cursor = "";
        while (true) {
            WikiPageService.CursorPage batch;
            try {
                batch = wikiService.listPagesCursor(kbId, cursor, LINT_CURSOR_BATCH);
            } catch (RuntimeException e) {
                throw new WikiException("list pages cursor (pass 2): " + e.getMessage(), e);
            }
            List<WikiPage> pages = batch.pages();
            if (pages.isEmpty()) {
                break;
            }
            for (WikiPage page : pages) {
                String lowerContent = page.getContent().toLowerCase(Locale.ROOT);
                Set<String> outLinkSet = new HashSet<>(page.getOutLinks());
                for (Map.Entry<String, String> e : entitySlugs.entrySet()) {
                    String slug = e.getKey();
                    String title = e.getValue();
                    if (slug.equals(page.getSlug()) || title.isEmpty()) {
                        continue;
                    }
                    if (!lowerContent.contains(title.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    if (outLinkSet.contains(slug)) {
                        continue;
                    }
                    issues = appendIssue(issues, new WikiLintIssue(
                            WikiLintIssue.MISSING_CROSS_REF, WikiLintIssue.SEVERITY_INFO,
                            page.getSlug(), slug,
                            "Page '" + page.getTitle() + "' mentions '" + title
                                    + "' but doesn't link to [[" + slug + "]]",
                            false));
                }
            }
            if (batch.nextCursor() == null || batch.nextCursor().isEmpty()) {
                break;
            }
            cursor = batch.nextCursor();
        }

        // 计算健康分
        if (stats.getTotalPages() > 0) {
            // 孤儿页扣分
            double orphanPct = (double) stats.getOrphanCount()
                    / (double) stats.getTotalPages() * 100;
            if (orphanPct > 50) {
                healthScore -= 25;
            } else if (orphanPct > 25) {
                healthScore -= 10;
            }

            // 死链扣分
            int brokenCount = 0;
            if (issues != null) {
                for (WikiLintIssue issue : issues) {
                    if (WikiLintIssue.BROKEN_LINK.equals(issue.getType())) {
                        brokenCount++;
                    }
                }
            }
            healthScore -= brokenCount * 5;

            // 完全没有链接也要扣分
            if (stats.getTotalLinks() == 0 && stats.getTotalPages() > 2) {
                healthScore -= 15;
            }

            // 空页面扣分
            int emptyCount = 0;
            if (issues != null) {
                for (WikiLintIssue issue : issues) {
                    if (WikiLintIssue.EMPTY_CONTENT.equals(issue.getType())) {
                        emptyCount++;
                    }
                }
            }
            healthScore -= emptyCount * 3;
        }

        if (healthScore < 0) {
            healthScore = 0;
        }

        // 生成摘要
        int errorCount = 0;
        int warningCount = 0;
        int infoCount = 0;
        if (issues != null) {
            for (WikiLintIssue issue : issues) {
                if (WikiLintIssue.SEVERITY_ERROR.equals(issue.getSeverity())) {
                    errorCount++;
                } else if (WikiLintIssue.SEVERITY_WARNING.equals(issue.getSeverity())) {
                    warningCount++;
                } else if (WikiLintIssue.SEVERITY_INFO.equals(issue.getSeverity())) {
                    infoCount++;
                }
            }
        }

        String summary;
        if (issues == null || issues.isEmpty()) {
            summary = "Wiki is healthy! No issues found.";
        } else {
            summary = "Found " + issues.size() + " issues: " + errorCount + " errors, "
                    + warningCount + " warnings, " + infoCount + " suggestions.";
        }

        WikiLintReport report = new WikiLintReport();
        report.setKnowledgeBaseId(kbId);
        report.setIssues(issues);
        report.setHealthScore(healthScore);
        report.setStats(stats);
        report.setSummary(summary);

        log.info("wiki lint: KB {} — health score {}/100, {} issues", kbId, healthScore,
                issues == null ? 0 : issues.size());
        return report;
    }

    /**
     * 尝试自动修复可修的问题。
     *
     * @return 实际修掉的问题数
     */
    public int autoFix(String kbId) {
        WikiLintReport report = runLint(kbId);

        int fixed = 0;
        List<WikiLintIssue> issues = report.getIssues();
        if (issues == null) {
            return 0;
        }
        for (WikiLintIssue issue : issues) {
            if (!issue.isAutoFixable()) {
                continue;
            }

            switch (issue.getType()) {
                case WikiLintIssue.BROKEN_LINK -> {
                    // 把 [[broken-slug]] 换成纯文本，保住引用文字但不再渲染成悬空 wiki 链接
                    if (issue.getTargetSlug().isEmpty()) {
                        continue;
                    }
                    WikiPage page;
                    try {
                        page = wikiService.getPageBySlug(kbId, issue.getPageSlug());
                    } catch (RuntimeException e) {
                        continue;
                    }
                    String target = issue.getTargetSlug();
                    page.setContent(page.getContent().replace("[[" + target + "]]", target));
                    try {
                        wikiService.updateAutoLinkedContent(page);
                        fixed++;
                    } catch (RuntimeException ignored) {
                        // 修复失败不计入 fixed
                    }
                }
                case WikiLintIssue.EMPTY_CONTENT -> {
                    // 内容极少的页面归档而不是删除
                    WikiPage page;
                    try {
                        page = wikiService.getPageBySlug(kbId, issue.getPageSlug());
                    } catch (RuntimeException e) {
                        continue;
                    }
                    // 不要把索引页归档掉
                    if (WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                        continue;
                    }
                    page.setStatus(WikiConstants.STATUS_ARCHIVED);
                    try {
                        wikiService.updatePage(page);
                        fixed++;
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
                case WikiLintIssue.STALE_REF -> {
                    // 剥掉指向已软删文档的 source_refs。若页面没有其他存活来源，
                    // 直接删掉它——留一个孤儿摘要页比删掉更糟：模型仍会从别的页面
                    // 链过去，而 wiki_read_source_doc 的下钻永远失败。
                    if (issue.getTargetSlug().isEmpty()) {
                        continue;
                    }
                    WikiPage page;
                    try {
                        page = wikiService.getPageBySlug(kbId, issue.getPageSlug());
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (page == null) {
                        continue;
                    }
                    if (WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                        continue;
                    }
                    List<String> remaining = removeSourceRef(page.getSourceRefs(),
                            issue.getTargetSlug());
                    if (remaining.isEmpty()) {
                        try {
                            wikiService.deletePage(kbId, page.getSlug());
                            fixed++;
                        } catch (RuntimeException ignored) {
                            // 同上
                        }
                    } else if (remaining.size() != page.getSourceRefs().size()) {
                        page.setSourceRefs(remaining);
                        try {
                            wikiService.updatePageMeta(page);
                            fixed++;
                        } catch (RuntimeException ignored) {
                            // 同上
                        }
                    }
                }
                default -> {
                    // 其余类型（orphan_page / missing_cross_ref / duplicate_slug）
                    // 的 AutoFixable 恒为 false，走不到这里
                }
            }
        }

        // 修复后重建链接
        if (fixed > 0) {
            try {
                wikiService.rebuildLinks(kbId);
            } catch (RuntimeException ignored) {
                // 重建链接失败不阻断返回
            }
        }

        log.info("wiki auto-fix: KB {} — fixed {} issues", kbId, fixed);
        return fixed;
    }

    /**
     * 摘掉等于
     * {@code knowledgeID} 或以其 + "|" 开头的 source_ref 条目。
     */
    static List<String> removeSourceRef(List<String> refs, String knowledgeID) {
        List<String> result = new ArrayList<>(refs == null ? 0 : refs.size());
        if (refs == null) {
            return result;
        }
        String prefix = knowledgeID + "|";
        for (String ref : refs) {
            if (ref.equals(knowledgeID) || ref.startsWith(prefix)) {
                continue;
            }
            result.add(ref);
        }
        return result;
    }

    /**
     * 按 id 查文档是否仍存活（<b>不带租户过滤</b>，排除软删）。
     *
     * @return 该文档是否仍存活
     */
    private boolean knowledgeExistsByIdOnly(String kid) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, kid)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        return k != null;
    }

    /** issues 延迟分配：保持"零问题 = null"的出口契约 */
    private static List<WikiLintIssue> appendIssue(List<WikiLintIssue> issues,
                                                   WikiLintIssue issue) {
        if (issues == null) {
            issues = new ArrayList<>();
        }
        issues.add(issue);
        return issues;
    }
}
