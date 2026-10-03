package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.wiki.IndexOverviewView;
import com.ragagent.agent.tools.wiki.WikiIndexOverview;
import com.ragagent.agent.tools.wiki.IssueView;
import com.ragagent.agent.tools.wiki.PageView;
import com.ragagent.agent.tools.wiki.WikiPages;
import com.ragagent.agent.tools.wiki.WikiRouteResolver;
import com.ragagent.agent.tools.wiki.WikiContentRewrite;
import com.ragagent.agent.tools.wiki.WikiScope;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.service.page.WikiPageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * wiki 工具族接缝：{@link WikiPages}
 * 桥到真实 {@link WikiPageService}（H2 真库）的映射与契约转换。
 *
 * <p>工具自身的输出字节由 {@code GoRecording45A/B} 夹具钉住；本测试覆盖
 * 此前从未被构造的那一层——桥。三处语义转换是回归高发点：
 * NotFound → null、editSource 经 {@code WikiEditContext}、
 * 时间 → RFC3339Nano 文本。</p>
 */
@SpringBootTest
class AgentToolBackendsWikiTest {

    private static final long TENANT = 10078L;
    private static final String KB = "wiki-bridge-kb";

    @Autowired
    private AgentToolBackends backends;
    @Autowired
    private WikiPageService wikiPageService;
    @Autowired
    private JdbcTemplate jdbc;

    private WikiPages pages;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        pages = backends.wikiPages();
    }

    private PageView view(String slug) {
        PageView v = PageView.of(KB, slug);
        v.setTenantId(TENANT);
        v.setTitle("甲公司");
        v.setPageType("entity");
        v.setStatus("published");
        v.setSummary("摘要");
        v.setContent("正文提到 [[concept/rag]]。");
        v.setAliases(List.of("Acme", "别名甲"));
        v.setSourceRefs(List.of("k1|文档一"));
        return v;
    }

    /** 页不存在（NotFound）在接缝上是 null（resolveUniqueWikiPage 据此跳过该 KB）。 */
    @Test
    void missingPageIsNullExceptionNotFailure() {
        assertThat(pages.getPageBySlug(KB, "entity/ghost")).isNull();
        // 真实 service 走的是抛异常路径——接缝必须把它转成 null
        assertThatThrownBy(() -> wikiPageService.getPageBySlug(KB, "entity/ghost"))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    /** 建页：接缝把 PageView 字段落到库，再读回来一字不差（含 jsonb 数组与 page_metadata）。 */
    @Test
    void createThenReadBackRoundTrips() {
        PageView created = pages.createPage(view("entity/acme-corp"),
                WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        assertThat(created.id()).isNotBlank();

        PageView read = pages.getPageBySlug(KB, "entity/acme-corp");
        assertThat(read).isNotNull();
        assertThat(read.title()).isEqualTo("甲公司");
        assertThat(read.pageType()).isEqualTo("entity");
        assertThat(read.aliases()).containsExactly("Acme", "别名甲");
        assertThat(read.sourceRefs()).containsExactly("k1|文档一");
        // OutLinks 由 service 从正文重解析
        assertThat(read.outLinks()).containsExactly("concept/rag");
        assertThat(read.knowledgeBaseId()).isEqualTo(KB);
        assertThat(read.slug()).isEqualTo("entity/acme-corp");
    }

    /**
     * 重命名的落库路径是新插入（ID 恒为空 → 新 UUID）。
     * Java 工具经 {@code PageView.copy()} 会带来旧 ID，接缝必须清掉，
     * 否则与尚未删除的旧页撞主键。
     */
    @Test
    void createAlwaysAssignsFreshId() {
        PageView original = pages.createPage(view("entity/acme-corp"),
                WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        PageView renamed = original.copy();
        renamed.setSlug("entity/acme-corp-2");

        PageView created = pages.createPage(renamed, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        assertThat(created.id()).isNotEqualTo(original.id());
        assertThat(pages.getPageBySlug(KB, "entity/acme-corp-2")).isNotNull();
        assertThat(pages.getPageBySlug(KB, "entity/acme-corp")).isNotNull();
    }

    /** 写归因：editSource=agent → last_edit_source='agent'。 */
    @Test
    void writePathsStampAgentEditSource() {
        pages.createPage(view("entity/acme-corp"), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        assertThat(jdbc.queryForObject(
                "SELECT last_edit_source FROM wiki_pages WHERE slug = ?", String.class,
                "entity/acme-corp")).isEqualTo("agent");

        // 未带来源的写入按归一化规则落 'pipeline'，说明来源确实是逐调用传递的
        WikiPage plain = new WikiPage();
        plain.setTenantId(TENANT);
        plain.setKnowledgeBaseId(KB);
        plain.setSlug("concept/pipeline");
        plain.setTitle("管道页");
        wikiPageService.createPage(plain);
        assertThat(jdbc.queryForObject(
                "SELECT last_edit_source FROM wiki_pages WHERE slug = ?", String.class,
                "concept/pipeline")).isEqualTo("pipeline");
    }

    /** updatePage：改后的 PageView 落库，读回一致（wiki_write_page / wiki_replace_text 路径）。 */
    @Test
    void updatePagePersistsEditedFields() {
        pages.createPage(view("entity/acme-corp"), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        PageView read = pages.getPageBySlug(KB, "entity/acme-corp");
        read.setContent("改过的正文，没有链接了。");
        read.setTitle("甲公司（改名）");
        pages.updatePage(read, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);

        PageView after = pages.getPageBySlug(KB, "entity/acme-corp");
        assertThat(after.title()).isEqualTo("甲公司（改名）");
        assertThat(after.content()).isEqualTo("改过的正文，没有链接了。");
        assertThat(after.outLinks()).isEmpty();
    }

    /** deletePage 后读不到（Go 软删；接缝契约同 getPageBySlug → null）。 */
    @Test
    void deletePageRemovesFromReadPath() {
        pages.createPage(view("entity/acme-corp"), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        pages.deletePage(KB, "entity/acme-corp", WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        assertThat(pages.getPageBySlug(KB, "entity/acme-corp")).isNull();
    }

    /** searchPages 映射成页视图（wiki_search 的执行面）。 */
    @Test
    void searchPagesMapsToViews() {
        pages.createPage(view("entity/acme-corp"), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        List<PageView> hits = pages.searchPages(KB, "甲公司", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(h -> assertThat(h.slug()).isEqualTo("entity/acme-corp"));
    }

    /**
     * issue 视图：时间以字符串承载，
     * RFC3339Nano 去掉小数秒尾零（Java 的 ISO_OFFSET_DATE_TIME 会补齐到 9 位）。
     */
    @Test
    void issueViewRendersGoTimestampText() {
        jdbc.update("INSERT INTO wiki_page_issues (id, tenant_id, knowledge_base_id, slug, "
                + "issue_type, description, suspected_knowledge_ids, status, reported_by, "
                + "created_at, updated_at) VALUES "
                + "('i1', ?, ?, 'entity/acme-corp', 'out_of_date', '过期了', '[]', "
                + "'pending', 'wiki-researcher-agent', "
                + "'2026-09-01 08:00:00.100000+00', '2026-09-02 08:00:00+00')",
                TENANT, KB);

        List<IssueView> issues = pages.listIssues(KB, "entity/acme-corp", "pending");
        assertThat(issues).hasSize(1);
        IssueView issue = issues.get(0);
        assertThat(issue.id()).isEqualTo("i1");
        assertThat(issue.createdAt()).isEqualTo("2026-09-01T08:00:00.1Z");
        assertThat(issue.updatedAt()).isEqualTo("2026-09-02T08:00:00Z");
        assertThat(issue.deletedAtValid()).isFalse();
        // null 时间 → 0001-01-01T00:00:00Z（历史线格式的零值时间），不输出 null
        assertThat(AgentToolWikiBackends.goTimeText(null))
                .isEqualTo("0001-01-01T00:00:00Z");
        // toGoJsonIndent 的字段序 = 历史线格式的固定声明序
        assertThat(issue.toGoJsonIndent()).startsWith("{\n  \"id\": \"i1\",\n")
                .contains("\"deleted_at\": null\n}");
    }

    /** createIssue：工具侧视图的字段（含 page 带出的 tenant）落库。 */
    @Test
    void createIssuePersistsFields() {
        String id = seedIssue();
        assertThat(id).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT issue_type || '/' || status || '/' || reported_by "
                        + "FROM wiki_page_issues WHERE id = ?", String.class, id))
                .isEqualTo("mixed_entities/pending/wiki-researcher-agent");

        assertThat(pages.listIssues(KB, "", "")).extracting(IssueView::id)
                .containsExactly(id);
    }

    private String seedIssue() {
        IssueView issue = new IssueView();
        issue.setTenantId(TENANT);
        issue.setKnowledgeBaseId(KB);
        issue.setSlug("entity/acme-corp");
        issue.setIssueType("mixed_entities");
        issue.setDescription("混了两个产品");
        issue.setSuspectedKnowledgeIds(List.of("k1"));
        issue.setReportedBy("wiki-researcher-agent");
        issue.setStatus("pending");
        return pages.createIssue(issue).id();
    }

    /** updateIssueStatus（wiki_update_issue 唯一的写面）。 */
    @Test
    void updateIssueStatusPersists() {
        seedIssue();
        String id = pages.listIssues(KB, "", "").get(0).id();
        pages.updateIssueStatus(id, "resolved");
        assertThat(pages.listIssues(KB, "", "resolved")).hasSize(1);
    }

    /** overview 缺失/服务异常时接缝返回 null（service 出错不抛、静默跳过）。 */
    @Test
    void indexOverviewIsBestEffort() {
        pages.createPage(view("entity/acme-corp"), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        IndexOverviewView overview =
                pages.getIndexView(KB, WikiIndexOverview.WIKI_INDEX_AGENT_TOP_K);
        // wiki_pages 里没有 index 行时 getIndex 会建默认页；两条路径都必须是可用视图
        assertThat(overview == null || overview.groups() != null).isTrue();
        assertThat(pages.getIndexView("no-such-kb", WikiIndexOverview.WIKI_INDEX_AGENT_TOP_K)).isNull();
    }

    /** wiki 十件全部可构造（此前恒走 "Unknown tool"），且注册名与工具自报名一致。 */
    @Test
    void wikiFamilyIsConstructible() {
        WikiRouteResolver routes = new WikiRouteResolver();
        List<WikiScope> scopes = List.of(WikiScope.kb(KB));
        for (String name : List.of(ToolDefinitions.TOOL_WIKI_READ_PAGE,
                ToolDefinitions.TOOL_WIKI_SEARCH, ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC,
                ToolDefinitions.TOOL_WIKI_FLAG_ISSUE, ToolDefinitions.TOOL_WIKI_WRITE_PAGE,
                ToolDefinitions.TOOL_WIKI_REPLACE_TEXT, ToolDefinitions.TOOL_WIKI_RENAME_PAGE,
                ToolDefinitions.TOOL_WIKI_DELETE_PAGE, ToolDefinitions.TOOL_WIKI_READ_ISSUE,
                ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE)) {
            var tool = backends.createWikiTool(name, null, scopes, List.of(KB), routes);
            assertThat(tool).as("工具 %s 必须可构造（此前恒走 Unknown tool）", name).isNotNull();
            assertThat(tool.getName()).as("工具名与注册名一致").isEqualTo(name);
        }
        assertThat(backends.createWikiTool("knowledge_search", null, scopes, List.of(KB), routes))
                .as("非 wiki 名留给 createTool").isNull();
    }
}
