package com.ragagent.wiki.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ragagent.TestSchema;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageConflictException;
import com.ragagent.wiki.domain.WikiPageListRequest;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiRevisionPruneRequest;
import com.ragagent.wiki.mapper.WikiPageRepository;
import com.ragagent.wiki.mapper.WikiFolderRepository;
import com.ragagent.wiki.mapper.WikiPageRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/repository/wiki_page_test.go（643 行，基于 SQLite 内存库）。
 *
 * <p>Java 侧用 H2 内存库（见 {@code src/test/resources/application.yml}），
 * 表结构统一由 {@link TestSchema} 建立。Go 的 SQLite 专用断言（
 * {@code TestList_CategoryPathFilterMatchesOnSQLite} 的 BLOB 比较、
 * {@code TestCountOrphans_SQLiteCountsEmptyInLinks}）在本文件里翻成 H2 语义的等价断言：
 * 覆盖的是同一批坑（JSON 数组列的比较与空判定），只是方言换成 H2。</p>
 */
@SpringBootTest
class WikiPageRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WikiPageRepository repo;

    @Autowired
    private WikiFolderRepository folderRepo;
    @Autowired
    private WikiPageRevisionMapper revisionMapper;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    // ──────────────────────────── 测试夹具 ────────────────────────────

    /** 对照 Go makeWikiPage：title 取 slug 的最后一段，wiki_path = pageType/title */
    private static WikiPage makeWikiPage(String kbId, String slug, String pageType, String status) {
        String title = slug;
        int idx = slug.lastIndexOf('/');
        if (idx >= 0) {
            title = slug.substring(idx + 1);
        }
        WikiPage page = new WikiPage();
        page.setId(UUID.randomUUID().toString());
        page.setTenantId(1L);
        page.setKnowledgeBaseId(kbId);
        page.setSlug(slug);
        page.setTitle(title);
        page.setPageType(pageType);
        page.setStatus(status);
        page.setContent("body of " + slug);
        page.setSummary("summary of " + slug);
        page.setWikiPath(pageType + "/" + title);
        page.setVersion(1);
        return page;
    }

    private static WikiPage makeCategorizedWikiPage(String kbId, String slug, String pageType,
                                                   String status, String... categoryPath) {
        WikiPage page = makeWikiPage(kbId, slug, pageType, status);
        page.setCategoryPath(List.of(categoryPath));
        if (categoryPath.length > 0) {
            page.setWikiPath(pageType + "/" + String.join("/", categoryPath) + "/" + page.getTitle());
            page.setDepth(categoryPath.length);
        }
        return page;
    }

    private static WikiRevisionPruneRequest pruneRequest(String pageId, int keepFromVersion,
                                                        int hardKeepFromVersion) {
        return new WikiRevisionPruneRequest(pageId, keepFromVersion,
                WikiConstants.PRUNABLE_EDIT_SOURCES, hardKeepFromVersion);
    }

    /** 对照 Go makeWikiRevision */
    private static WikiPageRevision makeWikiRevision(WikiPage page, int version, String editSource) {
        WikiPageRevision rev = new WikiPageRevision();
        rev.setId(UUID.randomUUID().toString());
        rev.setTenantId(page.getTenantId());
        rev.setKnowledgeBaseId(page.getKnowledgeBaseId());
        rev.setPageId(page.getId());
        rev.setSlug(page.getSlug());
        rev.setVersion(version);
        rev.setTitle(page.getTitle());
        rev.setPageType(page.getPageType());
        rev.setStatus(page.getStatus());
        rev.setContent("content of v" + version);
        rev.setEditSource(editSource);
        rev.setEditedAt(java.time.OffsetDateTime.now());
        rev.setCreatedAt(java.time.OffsetDateTime.now());
        return rev;
    }

    private long countWikiRevisions(String pageId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wiki_page_revisions WHERE page_id = ?", Long.class, pageId);
        return n == null ? 0 : n;
    }

    // ──────────────────────────── 列表排序 / 过滤 ────────────────────────────

    /**
     * 对照 Go TestList_WikiPathSortReturnsCategorizedPagesFirst：侧边栏是 IDE 式树，
     * 分页发生在仓储层，数据库必须把带 category_path 的页面排在散落的根页面之前，
     * 否则前端根本不知道后面几页里藏着目录。
     */
    @Test
    void listWikiPathSortReturnsCategorizedPagesFirst() {
        repo.create(makeWikiPage("kb-a", "entity/000-root", "entity", "published"));
        repo.create(makeCategorizedWikiPage("kb-a", "entity/999-child", "entity", "published", "zzz-folder"));
        repo.create(makeWikiPage("kb-a", "entity/001-root", "entity", "published"));

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId("kb-a");
        req.setPageType("entity");
        req.setPage(1);
        req.setPageSize(10);
        req.setSortBy("wiki_path");
        req.setSortOrder("asc");

        WikiPageRepository.PageList got = repo.list(req);
        assertThat(got.total()).isEqualTo(3);
        assertThat(got.pages()).hasSize(3);
        assertThat(got.pages().get(0).getSlug()).isEqualTo("entity/999-child");
        assertThat(got.pages().get(1).getSlug()).isEqualTo("entity/000-root");
        assertThat(got.pages().get(2).getSlug()).isEqualTo("entity/001-root");
    }

    /**
     * 对照 Go TestList_CategoryPathFilterMatchesOnSQLite：侧边栏用 category_path +
     * category_depth 加载某个目录的页面；Go 的 SQLite 分支要处理 BLOB 与 TEXT 不相等的问题，
     * Java/H2 分支要处理的是「jsonb 文本带 `", "` 分隔符」的问题（PG 的 jsonb 渲染会插入空格，
     * Jackson 写的是紧凑形式）。
     */
    @Test
    void listCategoryPathFilterMatches() {
        repo.create(makeCategorizedWikiPage("kb-c", "entity/in-ai", "entity", "published", "AI"));
        repo.create(makeCategorizedWikiPage("kb-c", "entity/in-ai-llm", "entity", "published", "AI", "LLM"));
        repo.create(makeCategorizedWikiPage("kb-c", "entity/in-people", "entity", "published", "人物"));
        repo.create(makeWikiPage("kb-c", "entity/root", "entity", "published"));

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId("kb-c");
        req.setPageType("entity");
        req.setCategoryPath(List.of("AI"));
        req.setCategoryDepth(1);
        req.setPage(1);
        req.setPageSize(10);

        WikiPageRepository.PageList got = repo.list(req);
        assertThat(got.total()).isEqualTo(1);
        assertThat(got.pages()).extracting(WikiPage::getSlug).containsExactly("entity/in-ai");

        WikiPageListRequest req2 = new WikiPageListRequest();
        req2.setKnowledgeBaseId("kb-c");
        req2.setPageType("entity");
        req2.setCategoryPath(List.of("人物"));
        req2.setPage(1);
        req2.setPageSize(10);
        WikiPageRepository.PageList got2 = repo.list(req2);
        assertThat(got2.total()).isEqualTo(1);
        assertThat(got2.pages()).extracting(WikiPage::getSlug).containsExactly("entity/in-people");

        // 只按深度过滤：depth=2 只有 entity/in-ai-llm
        WikiPageListRequest req3 = new WikiPageListRequest();
        req3.setKnowledgeBaseId("kb-c");
        req3.setPageType("entity");
        req3.setCategoryDepth(2);
        req3.setPage(1);
        req3.setPageSize(10);
        assertThat(repo.list(req3).pages()).extracting(WikiPage::getSlug)
                .containsExactly("entity/in-ai-llm");

        // folder_id 指针语义：null = 不过滤，"" = 根
        WikiPageListRequest req4 = new WikiPageListRequest();
        req4.setKnowledgeBaseId("kb-c");
        req4.setPageType("entity");
        req4.setFolderId("");
        req4.setPage(1);
        req4.setPageSize(10);
        assertThat(repo.list(req4).total()).isEqualTo(4);
    }

    /** 多类型过滤（"entity,concept"）走 SplitWikiPageTypes → IN */
    @Test
    void listSupportsCommaSeparatedPageTypes() {
        repo.create(makeWikiPage("kb-multi", "entity/a", "entity", "published"));
        repo.create(makeWikiPage("kb-multi", "concept/b", "concept", "published"));
        repo.create(makeWikiPage("kb-multi", "summary/c", "summary", "published"));

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId("kb-multi");
        req.setPageType("entity,concept");
        req.setPageSize(10);
        assertThat(repo.list(req).total()).isEqualTo(2);
    }

    /**
     * List 的 {@code query} 过滤。非 PG 方言走 LIKE 近似分支（H2 测试库即如此）；
     * PG 分支的 {@code to_tsvector @@ plainto_tsquery + ILIKE} 已在真 PG 上用
     * EXPLAIN 校验过语法与类型解析。
     */
    @Test
    void listQueryFilterMatchesTitleContentAndAliases() {
        WikiPage byTitle = makeWikiPage("kb-q", "entity/t", "entity", "published");
        byTitle.setTitle("RAG 入门");
        byTitle.setContent("body");
        repo.create(byTitle);

        WikiPage byContent = makeWikiPage("kb-q", "entity/c", "entity", "published");
        byContent.setTitle("Other");
        byContent.setContent("about RAG pipelines");
        repo.create(byContent);

        WikiPage byAlias = makeWikiPage("kb-q", "entity/a", "entity", "published");
        byAlias.setTitle("Zzz");
        byAlias.setContent("nothing");
        byAlias.setAliases(List.of("RAG"));
        repo.create(byAlias);

        repo.create(makeWikiPage("kb-q", "entity/none", "entity", "published"));

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId("kb-q");
        req.setQuery("RAG");
        req.setPageSize(10);
        assertThat(repo.list(req).total()).isEqualTo(3);
        assertThat(repo.list(req).pages()).extracting(WikiPage::getSlug)
                .containsExactlyInAnyOrder("entity/t", "entity/c", "entity/a");

        // status 过滤与 query 叠加
        WikiPageListRequest req2 = new WikiPageListRequest();
        req2.setKnowledgeBaseId("kb-q");
        req2.setQuery("RAG");
        req2.setStatus("archived");
        req2.setPageSize(10);
        assertThat(repo.list(req2).total()).isZero();
    }

    /** 默认排序是 updated_at DESC，默认分页 20 */
    @Test
    void listDefaultsToUpdatedAtDescAndPageSize20() {
        for (int i = 0; i < 25; i++) {
            repo.create(makeWikiPage("kb-default", "entity/p" + i, "entity", "published"));
        }
        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId("kb-default");
        assertThat(repo.list(req).total()).isEqualTo(25);
        assertThat(repo.list(req).pages()).hasSize(20);
    }

    // ──────────────────────────── 文件夹树 ────────────────────────────

    /**
     * 对照 Go TestFolderTree_CRUDAndChildListing：子目录按 sort_order/name 排序、
     * 按名查找、目录下页面计数、ListDistinctCategoryPaths 反映文件夹路径。
     */
    @Test
    void folderTreeCrudAndChildListing() {
        folderRepo.createFolder(mkFolder("f-ai", "", "AI", "AI", 1));
        folderRepo.createFolder(mkFolder("f-people", "", "人物", "人物", 1));
        folderRepo.createFolder(mkFolder("f-llm", "f-ai", "LLM", "AI/LLM", 2));

        List<WikiFolder> roots = folderRepo.listChildFolders("kb-f", WikiConstants.FOLDER_ROOT_ID);
        assertThat(roots).hasSize(2);

        WikiFolder child = folderRepo.getChildFolderByName("kb-f", "f-ai", "LLM");
        assertThat(child.getId()).isEqualTo("f-llm");

        assertThatThrownBy(() -> folderRepo.getChildFolderByName("kb-f", "f-ai", "Nope"))
                .isInstanceOf(WikiFolderNotFoundException.class);

        // 归档页不计入
        WikiPage pAI = makeCategorizedWikiPage("kb-f", "entity/a1", "entity", "published", "AI");
        pAI.setFolderId("f-ai");
        WikiPage pLLM = makeCategorizedWikiPage("kb-f", "entity/a2", "entity", "published", "AI", "LLM");
        pLLM.setFolderId("f-llm");
        WikiPage pArch = makeCategorizedWikiPage("kb-f", "entity/a3", "entity", "archived", "AI");
        pArch.setFolderId("f-ai");
        repo.create(pAI);
        repo.create(pLLM);
        repo.create(pArch);

        assertThat(folderRepo.countPagesInFolder("kb-f", "f-ai")).isEqualTo(1);

        Map<String, Long> byFolder = folderRepo.countPagesByFolder("kb-f", List.of());
        assertThat(byFolder).containsEntry("f-ai", 1L).containsEntry("f-llm", 1L);

        List<List<String>> paths = folderRepo.listDistinctCategoryPaths("kb-f", 100);
        assertThat(paths).contains(List.of("AI"), List.of("AI", "LLM"), List.of("人物"));

        List<WikiPage> pages = folderRepo.listPagesByFolderIDs("kb-f", List.of("f-ai", "f-llm"));
        assertThat(pages).hasSize(3);

        // 仓储层原子复查空判：并发移动/建子目录不能钻到 service 检查与软删之间
        assertThatThrownBy(() -> folderRepo.deleteFolder("kb-f", "f-ai"))
                .isInstanceOf(WikiFolderNotEmptyException.class);
        folderRepo.deleteFolder("kb-f", "f-people");
        assertThatThrownBy(() -> folderRepo.getFolderByID("kb-f", "f-people"))
                .isInstanceOf(WikiFolderNotFoundException.class);
        assertThatThrownBy(() -> folderRepo.deleteFolder("kb-f", "f-nope"))
                .isInstanceOf(WikiFolderNotFoundException.class);
    }

    private static WikiFolder mkFolder(String id, String parentId, String name, String path, int depth) {
        WikiFolder f = new WikiFolder();
        f.setId(id);
        f.setTenantId(1L);
        f.setKnowledgeBaseId("kb-f");
        f.setParentId(parentId);
        f.setName(name);
        f.setPath(path);
        f.setDepth(depth);
        return f;
    }

    /** 文件夹更新（重命名/搬父）与 ListAllFolders 的 depth/path 排序 */
    @Test
    void folderUpdateAndFullListOrdering() {
        folderRepo.createFolder(mkFolder("f-1", "", "AI", "AI", 1));
        folderRepo.createFolder(mkFolder("f-2", "f-1", "LLM", "AI/LLM", 2));
        folderRepo.createFolder(mkFolder("f-3", "", "Ops", "Ops", 1));

        List<WikiFolder> all = folderRepo.listAllFolders("kb-f");
        assertThat(all).extracting(WikiFolder::getPath).containsExactly("AI", "Ops", "AI/LLM");

        WikiFolder f2 = folderRepo.getFolderByID("kb-f", "f-2");
        f2.setName("LLM 应用");
        f2.setPath("AI/LLM 应用");
        folderRepo.updateFolder(f2);
        assertThat(folderRepo.getFolderByID("kb-f", "f-2").getName()).isEqualTo("LLM 应用");

        WikiFolder stale = mkFolder("f-missing", "", "x", "x", 1);
        assertThatThrownBy(() -> folderRepo.updateFolder(stale))
                .isInstanceOf(WikiFolderNotFoundException.class);
    }

    // ──────────────────────────── 索引瘦投影 / 分页 ────────────────────────────

    /**
     * 对照 Go TestListByTypeLight_ProjectsNarrowColumnsAndExcludesArchived：
     * 索引视图只取 slug/title/summary 等窄列并尊重归档过滤——这正是把该方法
     * 从 ListByType 拆出来的全部意义：索引读取不该为 TEXT 正文付传输代价。
     */
    @Test
    void listByTypeLightProjectsNarrowColumnsAndExcludesArchived() {
        repo.create(makeWikiPage("kb-a", "entity/alpha", "entity", "published"));
        repo.create(makeWikiPage("kb-a", "entity/beta", "entity", "draft"));
        repo.create(makeWikiPage("kb-a", "entity/gamma", "entity", "archived"));
        repo.create(makeWikiPage("kb-a", "concept/delta", "concept", "published"));
        repo.create(makeWikiPage("kb-other", "entity/leaked", "entity", "published"));

        WikiPageRepository.LightList got = repo.listByTypeLight("kb-a", "entity", 50, 0);
        assertThat(got.total()).isEqualTo(2);
        assertThat(got.entries()).hasSize(2);
        assertThat(got.entries().get(0).getSlug()).isEqualTo("entity/alpha");
        assertThat(got.entries().get(0).getTitle()).isEqualTo("alpha");
        assertThat(got.entries().get(0).getSummary()).isEqualTo("summary of entity/alpha");
        assertThat(got.entries().get(1).getSlug()).isEqualTo("entity/beta");
    }

    /** 对照 Go TestListByTypeLight_Pagination：total 跨页稳定 */
    @Test
    void listByTypeLightPagination() {
        for (String s : List.of("entity/a", "entity/b", "entity/c", "entity/d", "entity/e")) {
            repo.create(makeWikiPage("kb-a", s, "entity", "published"));
        }

        WikiPageRepository.LightList page1 = repo.listByTypeLight("kb-a", "entity", 2, 0);
        assertThat(page1.total()).isEqualTo(5);
        assertThat(page1.entries()).extracting("slug").containsExactly("entity/a", "entity/b");

        WikiPageRepository.LightList page2 = repo.listByTypeLight("kb-a", "entity", 2, 2);
        assertThat(page2.total()).isEqualTo(5);
        assertThat(page2.entries()).extracting("slug").containsExactly("entity/c", "entity/d");

        WikiPageRepository.LightList page3 = repo.listByTypeLight("kb-a", "entity", 2, 4);
        assertThat(page3.total()).isEqualTo(5);
        assertThat(page3.entries()).extracting("slug").containsExactly("entity/e");

        // 越界 offset 返回空列表而不是错误——handler 靠这个短路分页尾部
        WikiPageRepository.LightList page4 = repo.listByTypeLight("kb-a", "entity", 2, 10);
        assertThat(page4.total()).isEqualTo(5);
        assertThat(page4.entries()).isEmpty();
    }

    /** 对照 Go TestListByTypeLight_EmptyType_ReturnsZero：count 为 0 时不浪费一次 SELECT */
    @Test
    void listByTypeLightEmptyTypeReturnsZero() {
        WikiPageRepository.LightList got = repo.listByTypeLight("kb-empty", "synthesis", 50, 0);
        assertThat(got.total()).isZero();
        assertThat(got.entries()).isEmpty();
    }

    /** 对照 Go TestListByTypeLight_ClampsLimit：limit 夹在 [1, 200]，0 回落到 50 */
    @Test
    void listByTypeLightClampsLimit() {
        for (int i = 0; i < 250; i++) {
            String slug = "entity/bulk-" + (char) ('a' + (i / 26) % 26) + (char) ('a' + i % 26);
            repo.create(makeWikiPage("kb-cap", slug, "entity", "published"));
        }
        assertThat(repo.listByTypeLight("kb-cap", "entity", 0, 0).entries()).hasSize(50);
        assertThat(repo.listByTypeLight("kb-cap", "entity", 5000, 0).entries()).hasSizeLessThanOrEqualTo(200);
    }

    /** 对照 Go ListByTypeRecent：最近更新的 N 条瘦投影，排除归档 */
    @Test
    void listByTypeRecentExcludesArchived() {
        repo.create(makeWikiPage("kb-r", "summary/a", "summary", "published"));
        repo.create(makeWikiPage("kb-r", "summary/b", "summary", "archived"));
        repo.create(makeWikiPage("kb-r", "entity/c", "entity", "published"));

        assertThat(repo.listByTypeRecent("kb-r", "summary", 10))
                .extracting("slug").containsExactly("summary/a");
    }

    /** 对照 Go TestListPagesCursorExcludesArchivedPages */
    @Test
    void listPagesCursorExcludesArchivedPages() {
        WikiPage a = makeWikiPage("kb-lint", "concept/live-a", "concept", "published");
        a.setId("001");
        WikiPage archived = makeWikiPage("kb-lint", "concept/archived", "concept", "archived");
        archived.setId("002");
        WikiPage b = makeWikiPage("kb-lint", "concept/live-b", "concept", "draft");
        b.setId("003");
        WikiPage other = makeWikiPage("kb-other", "concept/other-kb", "concept", "published");
        other.setId("004");
        for (WikiPage p : List.of(a, archived, b, other)) {
            repo.create(p);
        }

        WikiPageRepository.CursorPage first = repo.listPagesCursor("kb-lint", "", 1);
        assertThat(first.pages()).hasSize(1);
        assertThat(first.pages().get(0).getSlug()).isEqualTo("concept/live-a");
        assertThat(first.nextCursor()).isEqualTo("001");

        WikiPageRepository.CursorPage second = repo.listPagesCursor("kb-lint", first.nextCursor(), 1);
        assertThat(second.pages()).hasSize(1);
        assertThat(second.pages().get(0).getSlug()).isEqualTo("concept/live-b");
        assertThat(second.nextCursor()).isEqualTo("003");

        WikiPageRepository.CursorPage third = repo.listPagesCursor("kb-lint", second.nextCursor(), 1);
        assertThat(third.pages()).isEmpty();
        assertThat(third.nextCursor()).isEmpty();
    }

    // ──────────────────────────── 统计 ────────────────────────────

    /** 对照 Go TestCountOrphans_SQLiteCountsEmptyInLinks（H2 语义等价版） */
    @Test
    void countOrphansCountsEmptyInLinks() {
        WikiPage orphan = makeWikiPage("kb-orphans", "entity/orphan", "entity", "published");
        orphan.setInLinks(List.of());
        WikiPage linked = makeWikiPage("kb-orphans", "entity/linked", "entity", "published");
        linked.setInLinks(List.of("entity/source"));
        WikiPage index = makeWikiPage("kb-orphans", "index", "index", "published");
        index.setInLinks(List.of());
        WikiPage otherKb = makeWikiPage("kb-other", "entity/other", "entity", "published");
        otherKb.setInLinks(List.of());
        for (WikiPage p : List.of(orphan, linked, index, otherKb)) {
            repo.create(p);
        }

        assertThat(repo.countOrphans("kb-orphans")).isEqualTo(1);
    }

    /** 对照 Go TestWikiStatsQueriesExcludeArchivedPages */
    @Test
    void wikiStatsQueriesExcludeArchivedPages() {
        WikiPage live = makeWikiPage("kb-stats", "entity/live", "entity", "published");
        live.setInLinks(List.of("index"));
        live.setOutLinks(List.of("concept/live-target"));
        WikiPage archived = makeWikiPage("kb-stats", "entity/archived", "entity", "archived");
        archived.setInLinks(List.of());
        archived.setOutLinks(List.of("concept/archived-target"));
        WikiPage index = makeWikiPage("kb-stats", "index", "index", "published");
        index.setInLinks(List.of());
        WikiPage otherKb = makeWikiPage("kb-other", "entity/other", "entity", "published");
        for (WikiPage p : List.of(live, archived, index, otherKb)) {
            repo.create(p);
        }

        Map<String, Long> counts = repo.countByType("kb-stats");
        assertThat(counts).containsEntry("entity", 1L).containsEntry("index", 1L);
        assertThat(repo.countOrphans("kb-stats")).isZero();

        List<WikiPage> pages = repo.listAll("kb-stats");
        assertThat(pages).hasSize(2);
        for (WikiPage p : pages) {
            assertThat(p.getStatus()).isNotEqualTo("archived");
            assertThat(p.getKnowledgeBaseId()).isEqualTo("kb-stats");
        }
    }

    /** 对照 Go ListRecentForSuggestions：排除 index 与归档，只取 published 且 title 非空 */
    @Test
    void listRecentForSuggestionsExcludesIndexAndArchived() {
        repo.create(makeWikiPage("kb-sug", "entity/live", "entity", "published"));
        repo.create(makeWikiPage("kb-sug", "index", "index", "published"));
        repo.create(makeWikiPage("kb-sug", "entity/arch", "entity", "archived"));
        WikiPage draft = makeWikiPage("kb-sug", "entity/draft", "entity", "draft");
        repo.create(draft);

        assertThat(repo.listRecentForSuggestions(1L, List.of("kb-sug"), 10))
                .extracting(WikiPage::getSlug).containsExactly("entity/live");
        assertThat(repo.listRecentForSuggestions(1L, List.of(), 10)).isEmpty();
        assertThat(repo.listRecentForSuggestions(1L, List.of("kb-sug"), 0)).isEmpty();
    }

    // ──────────────────────────── 修订历史 ────────────────────────────

    /**
     * 对照 Go TestUpdateWithRevisionRollsBackSnapshotOnVersionConflict：
     * 被拒绝的写入既不能留下涨过的版本号，也不能留下快照——否则历史里会出现
     * 一个"仍然是当前版本"的条目，而且无法回滚。
     */
    @Test
    void updateWithRevisionRollsBackSnapshotOnVersionConflict() {
        WikiPage page = makeWikiPage("kb-tx", "concept/tx", "concept", "published");
        repo.create(page);

        // 模拟"从陈旧读出发的写者"：它以为还是 v1，而行已经走到 v7
        jdbc.update("UPDATE wiki_pages SET version = 7 WHERE id = ?", page.getId());

        WikiPage stale = copyOf(page);
        stale.setVersion(1);
        stale.setContent("loser body");

        assertThatThrownBy(() -> repo.updateWithRevision(stale,
                makeWikiRevision(page, 1, WikiConstants.EDIT_SOURCE_USER)))
                .isInstanceOf(WikiPageConflictException.class);

        assertThat(stale.getVersion()).as("被拒绝的写入不得留下涨过的版本号").isEqualTo(1);
        assertThat(countWikiRevisions(page.getId()))
                .as("快照必须随失败的更新一起回滚，否则历史会列出一个仍是当前版本的条目")
                .isZero();
    }

    /** 对照 Go TestUpdateWithRevisionIgnoresDuplicateSnapshot：并发写者的同版本快照不得报错 */
    @Test
    void updateWithRevisionIgnoresDuplicateSnapshot() {
        WikiPage page = makeWikiPage("kb-dup", "concept/dup", "concept", "published");
        repo.create(page);

        repo.updateWithRevision(page, makeWikiRevision(page, 1, WikiConstants.EDIT_SOURCE_USER));
        assertThat(page.getVersion()).isEqualTo(2);

        WikiPage again = copyOf(page);
        again.setVersion(2);
        repo.updateWithRevision(again, makeWikiRevision(page, 1, WikiConstants.EDIT_SOURCE_USER));
        assertThat(countWikiRevisions(page.getId())).isEqualTo(1);
    }

    /** 页面不存在时是 NotFound 而不是 Conflict */
    @Test
    void updateThrowsNotFoundForMissingPage() {
        WikiPage page = makeWikiPage("kb-x", "concept/missing", "concept", "published");
        assertThatThrownBy(() -> repo.update(page)).isInstanceOf(WikiPageNotFoundException.class);
    }

    /**
     * 对照 Go TestPruneRevisionsKeepsHumanEditsUntilHardCap：
     * v1 是被人埋在一长串管道重写底下的人工编辑——软上限只能删可剪枝来源的快照，
     * 人工/agent 的版本要一直留到硬上限。
     */
    @Test
    void pruneRevisionsKeepsHumanEditsUntilHardCap() {
        WikiPage page = makeWikiPage("kb-prune", "concept/prune", "concept", "published");
        repo.create(page);

        revisionMapper.insert(makeWikiRevision(page, 1, WikiConstants.EDIT_SOURCE_USER));
        revisionMapper.insert(makeWikiRevision(page, 2, WikiConstants.EDIT_SOURCE_AGENT));
        for (int v = 3; v <= 120; v++) {
            revisionMapper.insert(makeWikiRevision(page, v, WikiConstants.EDIT_SOURCE_PIPELINE));
        }

        // 软上限：v71 以下的管道快照被删，两版有作者的留下
        repo.pruneRevisions(pruneRequest(page.getId(),
                121 - WikiConstants.MAX_REVISIONS_PER_PAGE, 0));
        for (int v : List.of(1, 2)) {
            assertThat(repo.getRevision(page.getKnowledgeBaseId(), page.getId(), v))
                    .as("人工/agent 写的 v%d 必须挺过管道churn", v)
                    .isNotNull();
        }
        assertThatThrownBy(() ->
                repo.getRevision(page.getKnowledgeBaseId(), page.getId(), 3))
                .isInstanceOf(WikiPageNotFoundException.class);
        assertThat(countWikiRevisions(page.getId()))
                .isEqualTo(WikiConstants.MAX_REVISIONS_PER_PAGE + 2);

        // 硬上限无视作者
        repo.pruneRevisions(pruneRequest(page.getId(),
                121 - WikiConstants.MAX_REVISIONS_PER_PAGE, 100));
        assertThatThrownBy(() ->
                repo.getRevision(page.getKnowledgeBaseId(), page.getId(), 1))
                .isInstanceOf(WikiPageNotFoundException.class);
        assertThat(countWikiRevisions(page.getId())).isEqualTo(21);
    }

    /** 对照 Go TestDeleteRevisionsByPageOnlyTouchesThatPage（含空 id 护栏） */
    @Test
    void deleteRevisionsByPageOnlyTouchesThatPage() {
        WikiPage victim = makeWikiPage("kb-del", "concept/victim", "concept", "published");
        WikiPage bystander = makeWikiPage("kb-del", "concept/bystander", "concept", "published");
        repo.create(victim);
        repo.create(bystander);
        revisionMapper.insert(makeWikiRevision(victim, 1, WikiConstants.EDIT_SOURCE_USER));
        revisionMapper.insert(makeWikiRevision(bystander, 1, WikiConstants.EDIT_SOURCE_USER));

        repo.deleteRevisionsByPage(victim.getId());
        assertThat(countWikiRevisions(victim.getId())).isZero();
        assertThat(countWikiRevisions(bystander.getId())).isEqualTo(1);

        // 空 id 绝不能变成全表删除
        repo.deleteRevisionsByPage("");
        assertThat(countWikiRevisions(bystander.getId())).isEqualTo(1);
    }

    /** ListRevisions 最新在前、省略 content、附总数 */
    @Test
    void listRevisionsOmitsContentAndCountsTotal() {
        WikiPage page = makeWikiPage("kb-rev", "concept/r", "concept", "published");
        repo.create(page);
        for (int v = 1; v <= 3; v++) {
            WikiPageRevision rev = makeWikiRevision(page, v, WikiConstants.EDIT_SOURCE_PIPELINE);
            rev.setContent("full content of v" + v);
            revisionMapper.insert(rev);
        }

        WikiPageRepository.RevisionList got = repo.listRevisions("kb-rev", page.getId(), 2, 0);
        assertThat(got.total()).isEqualTo(3);
        assertThat(got.revisions()).extracting(WikiPageRevision::getVersion).containsExactly(3, 2);
        assertThat(got.revisions()).allSatisfy(r -> assertThat(r.getContent()).isEmpty());

        // GetRevision 带 content
        assertThat(repo.getRevision("kb-rev", page.getId(), 1).getContent())
                .isEqualTo("full content of v1");
    }

    // ──────────────────────────── jsonb 列往返 ────────────────────────────

    /**
     * Go 的 StringArray Value/Scan 往返（types/wiki_page_test.go 的
     * TestStringArrayValueScan 在 Java 侧的落点）：这里走真实 JDBC 往返，
     * 覆盖 TypeHandler 的写入与读回，包括 aliases / category_path / 各种 links。
     */
    @Test
    void jsonbListColumnsRoundTrip() {
        WikiPage page = makeWikiPage("kb-json", "entity/json", "entity", "published");
        page.setAliases(List.of("别名A", "Alias \"B\"", "100%_raw"));
        page.setCategoryPath(List.of("AI", "LLM 应用"));
        page.setSourceRefs(List.of("doc-1|手册", "doc-2"));
        page.setChunkRefs(List.of("chunk-1"));
        page.setInLinks(List.of("index"));
        page.setOutLinks(List.of("concept/c", "entity/e"));
        page.setDepth(2);
        page.setPageMetadata(null);
        repo.create(page);

        WikiPage got = repo.getBySlug("kb-json", "entity/json");
        assertThat(got.getAliases()).containsExactly("别名A", "Alias \"B\"", "100%_raw");
        assertThat(got.getCategoryPath()).containsExactly("AI", "LLM 应用");
        assertThat(got.getSourceRefs()).containsExactly("doc-1|手册", "doc-2");
        assertThat(got.getChunkRefs()).containsExactly("chunk-1");
        assertThat(got.getInLinks()).containsExactly("index");
        assertThat(got.getOutLinks()).containsExactly("concept/c", "entity/e");
        assertThat(got.getDepth()).isEqualTo(2);
        // page_metadata 未赋值 → 写入 SQL NULL（insertStrategy=ALWAYS），读回 null。
        // 契约依据：Go golden 实录里该键是 "page_metadata":null（不是 {}）——
        // 尽管列默认值是 '{}'，Go 的 nil JSON 会显式写 NULL 覆盖它。
        assertThat(got.getPageMetadata()).isNull();
    }

    /** 未设置的可选 jsonb 列读回空列表（不是 null），与 Go 的非指针零值语义一致 */
    @Test
    void unsetJsonbColumnsReadBackAsEmptyLists() {
        WikiPage page = makeWikiPage("kb-json2", "entity/bare", "entity", "published");
        repo.create(page);
        WikiPage got = repo.getBySlug("kb-json2", "entity/bare");
        assertThat(got.getAliases()).isEmpty();
        assertThat(got.getSourceRefs()).isEmpty();
        assertThat(got.getInLinks()).isEmpty();
        assertThat(got.getCategoryPath()).isEmpty();
        assertThat(got.getParentSlug()).isEmpty();
        assertThat(got.getFolderId()).isEmpty();
        assertThat(got.getLastEditSource()).isEmpty();
    }

    // ──────────────────────────── source_refs 溯源 ────────────────────────────

    /** 两种存储形态（"id" 与 "id|title"）都要命中；无关 id 不得命中 */
    @Test
    void listBySourceRefMatchesBothStorageFormsWithFallbackBranch() {
        // 非 PG 方言走 LIKE 分支（H2 测试库即如此）；这也顺带覆盖了该分支的等价性
        WikiPage bare = makeWikiPage("kb-src", "entity/bare", "entity", "published");
        bare.setSourceRefs(List.of("doc-1"));
        WikiPage legacy = makeWikiPage("kb-src", "entity/legacy", "entity", "published");
        legacy.setSourceRefs(List.of("doc-1|排班手册"));
        WikiPage other = makeWikiPage("kb-src", "entity/other", "entity", "published");
        other.setSourceRefs(List.of("doc-10", "doc-2"));
        WikiPage otherKb = makeWikiPage("kb-src2", "entity/x", "entity", "published");
        otherKb.setSourceRefs(List.of("doc-1"));
        for (WikiPage p : List.of(bare, legacy, other, otherKb)) {
            repo.create(p);
        }

        assertThat(repo.listBySourceRef("kb-src", "doc-1"))
                .extracting(WikiPage::getSlug)
                .containsExactlyInAnyOrder("entity/bare", "entity/legacy");
        assertThat(repo.listSlugsBySourceRef("kb-src", "doc-1"))
                .containsExactlyInAnyOrder("entity/bare", "entity/legacy");
        assertThat(repo.listBySourceRef("kb-src", "doc-10"))
                .extracting(WikiPage::getSlug).containsExactly("entity/other");
    }

    /**
     * 对照 Go ListSummariesByKnowledgeIDs：按"撰写摘要的 knowledge id"取正文，
     * 只认 summary 类型、排除归档，并兼容 "id|title" 旧形态。
     */
    @Test
    void listSummariesByKnowledgeIDsKeysByAuthoringDoc() {
        WikiPage s1 = makeWikiPage("kb-sum", "summary/doc1", "summary", "published");
        s1.setSourceRefs(List.of("doc-1|手册"));
        s1.setContent("summary body 1");
        WikiPage s2 = makeWikiPage("kb-sum", "summary/doc2", "summary", "published");
        s2.setSourceRefs(List.of("doc-2"));
        s2.setContent("summary body 2");
        WikiPage archived = makeWikiPage("kb-sum", "summary/doc3", "summary", "archived");
        archived.setSourceRefs(List.of("doc-3"));
        archived.setContent("summary body 3");
        WikiPage notSummary = makeWikiPage("kb-sum", "entity/doc4", "entity", "published");
        notSummary.setSourceRefs(List.of("doc-4"));
        notSummary.setContent("entity body");
        for (WikiPage p : List.of(s1, s2, archived, notSummary)) {
            repo.create(p);
        }

        Map<String, String> got = repo.listSummariesByKnowledgeIDs("kb-sum",
                List.of("doc-1", "doc-2", "doc-3", "doc-4", "doc-unknown", ""));
        assertThat(got).containsEntry("doc-1", "summary body 1")
                .containsEntry("doc-2", "summary body 2");
        assertThat(got).doesNotContainKeys("doc-3", "doc-4", "doc-unknown");
        assertThat(repo.listSummariesByKnowledgeIDs("kb-sum", List.of())).isEmpty();
    }

    /** ListBySlugs：一次 IN 查询拿瘦投影，缺失的 slug 静默丢弃 */
    @Test
    void listBySlugsReturnsLightProjectionAndDropsMissing() {
        WikiPage a = makeWikiPage("kb-lite", "entity/a", "entity", "published");
        a.setAliases(List.of("A 别名"));
        a.setOutLinks(List.of("concept/c"));
        repo.create(a);
        repo.create(makeWikiPage("kb-lite", "entity/b", "entity", "draft"));

        Map<String, com.ragagent.wiki.domain.WikiPageLite> got =
                repo.listBySlugs("kb-lite", List.of("entity/a", "entity/missing"));
        assertThat(got).containsOnlyKeys("entity/a");
        assertThat(got.get("entity/a").getAliases()).containsExactly("A 别名");
        assertThat(got.get("entity/a").getOutLinks()).containsExactly("concept/c");
        assertThat(got.get("entity/a").getStatus()).isEqualTo("published");

        assertThat(repo.listBySlugs("kb-lite", List.of())).isEmpty();
        assertThat(repo.listBySlugs("kb-lite", null)).isEmpty();
    }

    /** ExistsSlugs / ListAllSlugs：归档视为"已消失" */
    @Test
    void existsSlugsAndListAllSlugsTreatArchivedAsGone() {
        repo.create(makeWikiPage("kb-e", "entity/live", "entity", "published"));
        repo.create(makeWikiPage("kb-e", "entity/draft", "entity", "draft"));
        repo.create(makeWikiPage("kb-e", "entity/arch", "entity", "archived"));

        Map<String, Boolean> exists = repo.existsSlugs("kb-e",
                List.of("entity/live", "entity/draft", "entity/arch", "entity/nope"));
        assertThat(exists).containsEntry("entity/live", true)
                .containsEntry("entity/draft", true)
                .containsEntry("entity/arch", false)
                .containsEntry("entity/nope", false);

        assertThat(repo.listAllSlugs("kb-e"))
                .containsExactlyInAnyOrder("entity/live", "entity/draft");
        assertThat(repo.existsSlugs("kb-e", List.of())).isEmpty();
    }

    /** 规范化标题匹配：去空白 + 小写后相等才命中，且排除归档 */
    @Test
    void findPagesByNormalizedTitlesMatchesWhitespaceFoldedTitles() {
        WikiPage a = makeWikiPage("kb-n", "entity/a", "entity", "published");
        a.setTitle("RAG  Pipeline");
        repo.create(a);
        WikiPage archived = makeWikiPage("kb-n", "entity/b", "entity", "archived");
        archived.setTitle("RAG Pipeline");
        repo.create(archived);
        repo.create(makeWikiPage("kb-n", "concept/c", "concept", "published"));

        assertThat(repo.findPagesByNormalizedTitles("kb-n", "entity", List.of("ragpipeline")))
                .extracting("slug").containsExactly("entity/a");
        assertThat(repo.findPagesByNormalizedTitle("kb-n", "entity", "ragpipeline")).hasSize(1);
        assertThat(repo.findPagesByNormalizedTitles("kb-n", "entity", List.of("", "  "))).isEmpty();
    }

    // ──────────────────────────── 版本语义 ────────────────────────────

    /** UpdateMeta / UpdateAutoLinkedContent 不递增 version（对照 Go 的版本语义） */
    @Test
    void bookkeepingWritesDoNotBumpVersion() {
        WikiPage page = makeWikiPage("kb-v", "concept/v", "concept", "published");
        repo.create(page);
        assertThat(page.getVersion()).isEqualTo(1);

        page.setInLinks(List.of("index"));
        page.setOutLinks(List.of("concept/o"));
        page.setAliases(List.of("别名"));
        repo.updateMeta(page);
        WikiPage afterMeta = repo.getBySlug("kb-v", "concept/v");
        assertThat(afterMeta.getVersion()).isEqualTo(1);
        assertThat(afterMeta.getInLinks()).containsExactly("index");
        assertThat(afterMeta.getAliases()).containsExactly("别名");

        afterMeta.setContent("decorated [[concept/o]]");
        afterMeta.setOutLinks(List.of("concept/o"));
        repo.updateAutoLinkedContent(afterMeta);
        WikiPage afterAuto = repo.getBySlug("kb-v", "concept/v");
        assertThat(afterAuto.getVersion()).isEqualTo(1);
        assertThat(afterAuto.getContent()).isEqualTo("decorated [[concept/o]]");

        // 而 Update 递增版本
        afterAuto.setContent("real edit");
        repo.update(afterAuto);
        assertThat(afterAuto.getVersion()).isEqualTo(2);
        assertThat(repo.getBySlug("kb-v", "concept/v").getVersion()).isEqualTo(2);
    }

    /** Update 走显式列映射，因此"清空字段"也能落库（Go 曾因 struct Updates 跳过零值而丢过清空） */
    @Test
    void updatePersistsClearedFields() {
        WikiPage page = makeWikiPage("kb-clr", "concept/c", "concept", "published");
        repo.create(page);

        WikiPage loaded = repo.getBySlug("kb-clr", "concept/c");
        loaded.setSummary("");
        loaded.setAliases(List.of());
        repo.update(loaded);

        WikiPage after = repo.getBySlug("kb-clr", "concept/c");
        assertThat(after.getSummary()).isEmpty();
        assertThat(after.getAliases()).isEmpty();
        assertThat(after.getVersion()).isEqualTo(2);
    }

    /** 软删除后，每条读路径都看不到该行 */
    @Test
    void softDeleteHidesPageFromEveryReadPath() {
        repo.create(makeWikiPage("kb-d", "entity/gone", "entity", "published"));
        repo.delete("kb-d", "entity/gone");

        assertThatThrownBy(() -> repo.getBySlug("kb-d", "entity/gone"))
                .isInstanceOf(WikiPageNotFoundException.class);
        assertThat(repo.listAll("kb-d")).isEmpty();
        assertThat(repo.countByType("kb-d")).isEmpty();
        assertThat(repo.listPagesCursor("kb-d", "", 10).pages()).isEmpty();
        assertThatThrownBy(() -> repo.delete("kb-d", "entity/gone"))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    /** Search：命中位置分档（title > slug > summary > content），limit 默认 10 / 上限 50 */
    @Test
    void searchRanksByHitLocation() {
        WikiPage titleHit = makeWikiPage("kb-s", "entity/x1", "entity", "published");
        titleHit.setTitle("RAG");
        repo.create(titleHit);

        WikiPage contentHit = makeWikiPage("kb-s", "entity/x2", "entity", "published");
        contentHit.setTitle("Other");
        contentHit.setContent("mentions RAG in body");
        repo.create(contentHit);

        WikiPage archived = makeWikiPage("kb-s", "entity/x3", "entity", "archived");
        archived.setTitle("RAG");
        repo.create(archived);

        List<WikiPage> hits = repo.search("kb-s", "RAG", 10);
        assertThat(hits).extracting(WikiPage::getSlug).containsExactly("entity/x1", "entity/x2");
        assertThat(repo.search("kb-s", "RAG", 0)).hasSize(2);
    }

    /** 页面问题：创建 / 按 slug+status 过滤 / 改状态 */
    @Test
    void pageIssuesCrud() {
        com.ragagent.wiki.domain.WikiPageIssue issue = new com.ragagent.wiki.domain.WikiPageIssue();
        issue.setId(UUID.randomUUID().toString());
        issue.setTenantId(1L);
        issue.setKnowledgeBaseId("kb-i");
        issue.setSlug("entity/a");
        issue.setIssueType("broken_link");
        issue.setDescription("死链");
        issue.setSuspectedKnowledgeIds(List.of("doc-1"));
        issue.setStatus("pending");
        issue.setReportedBy("agent");
        repo.createIssue(issue);

        com.ragagent.wiki.domain.WikiPageIssue done = new com.ragagent.wiki.domain.WikiPageIssue();
        done.setId(UUID.randomUUID().toString());
        done.setTenantId(1L);
        done.setKnowledgeBaseId("kb-i");
        done.setSlug("entity/b");
        done.setIssueType("stale");
        done.setDescription("过期");
        done.setStatus("resolved");
        done.setReportedBy("user");
        repo.createIssue(done);

        assertThat(repo.listIssues("kb-i", "", "")).hasSize(2);
        assertThat(repo.listIssues("kb-i", "entity/a", "")).hasSize(1);
        assertThat(repo.listIssues("kb-i", "", "resolved")).hasSize(1);
        assertThat(repo.listIssues("kb-i", "", "resolved").get(0).getSuspectedKnowledgeIds()).isEmpty();

        repo.updateIssueStatus(issue.getId(), "resolved");
        assertThat(repo.listIssues("kb-i", "entity/a", "").get(0).getStatus()).isEqualTo("resolved");
    }

    /** 浅拷贝：模拟 Go 里的 `stale := *page`（值拷贝语义） */
    private static WikiPage copyOf(WikiPage src) {
        WikiPage p = new WikiPage();
        p.setId(src.getId());
        p.setTenantId(src.getTenantId());
        p.setKnowledgeBaseId(src.getKnowledgeBaseId());
        p.setSlug(src.getSlug());
        p.setTitle(src.getTitle());
        p.setPageType(src.getPageType());
        p.setStatus(src.getStatus());
        p.setContent(src.getContent());
        p.setSummary(src.getSummary());
        p.setAliases(src.getAliases());
        p.setParentSlug(src.getParentSlug());
        p.setFolderId(src.getFolderId());
        p.setCategoryPath(src.getCategoryPath());
        p.setWikiPath(src.getWikiPath());
        p.setDepth(src.getDepth());
        p.setSortOrder(src.getSortOrder());
        p.setSourceRefs(src.getSourceRefs());
        p.setChunkRefs(src.getChunkRefs());
        p.setInLinks(src.getInLinks());
        p.setOutLinks(src.getOutLinks());
        p.setPageMetadata(src.getPageMetadata());
        p.setVersion(src.getVersion());
        p.setLastEditSource(src.getLastEditSource());
        p.setLastEditorId(src.getLastEditorId());
        p.setCreatedAt(src.getCreatedAt());
        p.setUpdatedAt(src.getUpdatedAt());
        return p;
    }
}
