package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.tools.IndexEntryView;
import com.ragagent.agent.tools.IndexGroupView;
import com.ragagent.agent.tools.RepairResult;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.agent.tools.IndexOverviewView;
import com.ragagent.agent.tools.IssueView;
import com.ragagent.agent.tools.PageView;
import com.ragagent.agent.tools.WikiPages;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.service.page.WikiEditContext;
import com.ragagent.wiki.service.page.WikiPageService;

/**
 * {@code AgentToolBackends} 的**wiki 工具簇**（§14 步骤 2）：WikiPages 工具端口的实现与
 * wiki 实体 ↔ 工具视图的转换（页面 / 版本 / 问题 / 索引概览 / Go 时间文本）。
 *
 * <p>为什么单独一类：这一簇只服务 wiki 类工具（读页、列版本、报问题、索引概览），
 * 与 KB 检索、web、datasource 各簇无交集；门面保留 {@code createWikiTool} 装配点与
 * {@code wikiPages()} 薄委托（契约测试直调面）。共享项：{@code wikiPageService}
 * 门面装配也在用 → 经构造参数传入。</p>
 */
final class AgentToolWikiBackends {

    private final WikiPageService wikiPageService;

    AgentToolWikiBackends(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    /**
     * 对照 Go 注入 agentService 的 {@code s.wikiPageService}
     * （interfaces.WikiPageService）——桥到 {@link WikiPageService} 的真实实现。
     *
     * <p>三处契约翻译：</p>
     * <ol>
     *   <li>Go 的 {@code repository.ErrWikiPageNotFound} 在 Java 侧是
     *       {@link WikiPageNotFoundException}，而接缝约定「返回 null = 页不存在」，
     *       故 getPageBySlug 单独吞掉它、其余异常照抛（resolveUniqueWikiPage 会跳过
     *       null 但把异常当致命错误）。</li>
     *   <li>Go 用 {@code types.WithWikiEditSource(ctx, …)} 挂上下文，Java 侧对应
     *       {@link WikiEditContext#callWith}（service 落库时读取）。</li>
     *   <li>Go 的 {@code time.Time} 直接进 {@code json.MarshalIndent}，RFC3339 文本；
     *       IssueView 以字符串承载，故在此格式化（Go 读库得到 UTC location）。</li>
     * </ol>
     */
    WikiPages wikiPages() {
        return new WikiPages() {

            @Override
            public PageView getPageBySlug(String kbId, String slug) {
                try {
                    return toPageView(wikiPageService.getPageBySlug(kbId, slug));
                } catch (WikiPageNotFoundException e) {
                    return null;
                }
            }

            @Override
            public PageView createPage(PageView page, String editSource) {
                WikiPage entity = toEntity(page);
                // Go 的两个调用点（wiki_write_page / wiki_rename_page）都用字段字面量建页，
                // ID 恒为空串 → CreatePage 生成新 UUID。Java 工具经 PageView.copy()
                // 会带来旧 ID，必须在接缝处清掉，否则与旧页撞主键。
                entity.setId(null);
                WikiPage created = WikiEditContext.callWith(editSource,
                        () -> wikiPageService.createPage(entity));
                return toPageView(created);
            }

            @Override
            public void updatePage(PageView page, String editSource) {
                WikiEditContext.callWith(editSource,
                        () -> wikiPageService.updatePage(toEntity(page)));
            }

            @Override
            public void updateAutoLinkedContent(PageView page, String editSource) {
                WikiEditContext.runWith(editSource,
                        () -> wikiPageService.updateAutoLinkedContent(toEntity(page)));
            }

            @Override
            public void deletePage(String kbId, String slug, String editSource) {
                WikiEditContext.runWith(editSource, () -> wikiPageService.deletePage(kbId, slug));
            }

            @Override
            public RepairResult repairContentLinks(String kbId, String slug, String content) {
                try {
                    WikiPageService.RepairResult r =
                            wikiPageService.repairContentLinks(kbId, slug, content);
                    return r == null ? null : new RepairResult(r.content(), r.changed());
                } catch (RuntimeException e) {
                    // Go: if rerr == nil { content = repaired } —— 修复失败永不阻塞写入
                    return null;
                }
            }

            @Override
            public void injectCrossLinks(String kbId, List<String> slugs) {
                wikiPageService.injectCrossLinks(kbId, slugs);
            }

            @Override
            public void rebuildIndexPage(String kbId) {
                wikiPageService.rebuildIndexPage(kbId);
            }

            @Override
            public List<IssueView> listIssues(String kbId, String slug, String status) {
                List<IssueView> out = new ArrayList<>();
                for (WikiPageIssue issue : wikiPageService.listIssues(kbId, slug, status)) {
                    if (issue != null) {
                        out.add(toIssueView(issue));
                    }
                }
                return out;
            }

            @Override
            public IssueView createIssue(IssueView issue) {
                WikiPageIssue entity = new WikiPageIssue();
                entity.setTenantId(issue.tenantId());
                entity.setKnowledgeBaseId(issue.knowledgeBaseId());
                entity.setSlug(issue.slug());
                entity.setIssueType(issue.issueType());
                entity.setDescription(issue.description());
                entity.setSuspectedKnowledgeIds(issue.suspectedKnowledgeIds());
                entity.setStatus(issue.status());
                entity.setReportedBy(issue.reportedBy());
                return toIssueView(wikiPageService.createIssue(entity));
            }

            @Override
            public void updateIssueStatus(String issueId, String status) {
                wikiPageService.updateIssueStatus(issueId, status);
            }

            @Override
            public List<PageView> searchPages(String kbId, String query, int limit) {
                List<PageView> out = new ArrayList<>();
                for (WikiPage page : wikiPageService.searchPages(kbId, query, limit)) {
                    if (page != null) {
                        out.add(toPageView(page));
                    }
                }
                return out;
            }

            @Override
            public IndexOverviewView getIndexView(String kbId, int topK) {
                try {
                    // Go: GetIndexView(ctx, kbID, nil, wikiIndexAgentTopK, "")
                    WikiIndex.Response resp =
                            wikiPageService.getIndexView(kbId, null, topK, "");
                    return toIndexOverviewView(resp);
                } catch (RuntimeException e) {
                    // Go: err != nil || overview == nil 时静默跳过 overview
                    return null;
                }
            }
        };
    }
    /** 对照 types.WikiPage → 工具侧页视图。 */
    static PageView toPageView(WikiPage page) {
        PageView view = PageView.of(page.getKnowledgeBaseId(),
                page.getSlug());
        view.setId(page.getId());
        view.setTenantId(page.getTenantId() == null ? 0L : page.getTenantId());
        view.setTitle(page.getTitle());
        view.setPageType(page.getPageType());
        view.setStatus(page.getStatus());
        view.setContent(page.getContent());
        view.setSummary(page.getSummary());
        view.setAliases(page.getAliases());
        view.setParentSlug(page.getParentSlug());
        view.setFolderId(page.getFolderId());
        view.setSortOrder(page.getSortOrder());
        view.setSourceRefs(page.getSourceRefs());
        view.setChunkRefs(page.getChunkRefs());
        view.setInLinks(page.getInLinks());
        view.setOutLinks(page.getOutLinks());
        view.setPageMetadata(page.getPageMetadata() == null
                || page.getPageMetadata().isNull() ? "" : page.getPageMetadata().toString());
        return view;
    }
    /** 对照工具侧页视图 → types.WikiPage（service 就地补全 ID/Status/Version/OutLinks）。 */
    static WikiPage toEntity(PageView view) {
        WikiPage entity = new WikiPage();
        entity.setId(view.id());
        entity.setTenantId(view.tenantId());
        entity.setKnowledgeBaseId(view.knowledgeBaseId());
        entity.setSlug(view.slug());
        entity.setTitle(view.title());
        entity.setPageType(view.pageType());
        entity.setStatus(view.status());
        entity.setContent(view.content());
        entity.setSummary(view.summary());
        entity.setAliases(view.aliases());
        entity.setParentSlug(view.parentSlug());
        entity.setFolderId(view.folderId());
        entity.setSortOrder(view.sortOrder());
        entity.setSourceRefs(view.sourceRefs());
        entity.setChunkRefs(view.chunkRefs());
        entity.setInLinks(view.inLinks());
        entity.setOutLinks(view.outLinks());
        JsonNode metadata = AgentToolBackends.readJson(view.pageMetadata());
        entity.setPageMetadata(metadata);
        return entity;
    }
    /** 对照 types.WikiPageIssue → 工具侧 issue 视图（时间以 Go RFC3339 文本透传）。 */
    static IssueView toIssueView(WikiPageIssue issue) {
        IssueView view = new IssueView();
        view.setId(issue.getId());
        view.setTenantId(issue.getTenantId() == null ? 0L : issue.getTenantId());
        view.setKnowledgeBaseId(issue.getKnowledgeBaseId());
        view.setSlug(issue.getSlug());
        view.setIssueType(issue.getIssueType());
        view.setDescription(issue.getDescription());
        view.setSuspectedKnowledgeIds(issue.getSuspectedKnowledgeIds());
        view.setStatus(issue.getStatus());
        view.setReportedBy(issue.getReportedBy());
        view.setCreatedAt(goTimeText(issue.getCreatedAt()));
        view.setUpdatedAt(goTimeText(issue.getUpdatedAt()));
        view.setDeletedAtValid(issue.getDeletedAt() != null);
        view.setDeletedAt(goTimeText(issue.getDeletedAt()));
        return view;
    }
    /** 对照 types.WikiIndexResponse → 工具侧 overview 视图。 */
    static IndexOverviewView toIndexOverviewView(WikiIndex.Response resp) {
        if (resp == null) {
            return null;
        }
        List<IndexGroupView> groups = new ArrayList<>();
        for (WikiIndex.Group g : resp.getGroups()) {
            List<IndexEntryView> items = new ArrayList<>();
            for (WikiIndexEntry entry : g.getItems()) {
                items.add(new IndexEntryView(entry.getSlug(), entry.getTitle(),
                        entry.getSummary()));
            }
            groups.add(new IndexGroupView(g.getType(), g.getTotal(), items));
        }
        return new IndexOverviewView(resp.getIntro(), groups);
    }
    /**
     * 对照 Go {@code json.Marshal(time.Time)}（格式串 {@code 2006-01-02T15:04:05.999999999Z07:00}）：
     * GORM 读 timestamptz 得到 UTC location，故按 UTC 渲染；小数秒**尾零连同空小数点一起
     * 去掉**（Java 的 ISO_OFFSET_DATE_TIME 会补齐到 3/6/9 位）；零值写
     * {@code 0001-01-01T00:00:00Z}。
     */
    static String goTimeText(java.time.OffsetDateTime value) {
        if (GoTimeSerializer.isGoZero(value)) {
            return GoTimeSerializer.GO_ZERO_TIME_LITERAL;
        }
        String s = value.atZoneSameInstant(java.time.ZoneOffset.UTC).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        int dot = s.indexOf('.');
        if (dot < 0) {
            return s;
        }
        int fracEnd = dot + 1;
        while (fracEnd < s.length() && Character.isDigit(s.charAt(fracEnd))) {
            fracEnd++;
        }
        int keep = fracEnd;
        while (keep > dot + 1 && s.charAt(keep - 1) == '0') {
            keep--;
        }
        return s.substring(0, dot) + (keep > dot + 1 ? s.substring(dot, keep) : "")
                + s.substring(fracEnd);
    }
}
