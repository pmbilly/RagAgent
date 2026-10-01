package com.ragagent.wiki.service.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ragagent.TestSchema;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderConflictException;
import com.ragagent.wiki.domain.WikiFolderNode;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.domain.WikiPageListRequest;
import com.ragagent.wiki.domain.WikiStats;
import com.ragagent.wiki.mapper.WikiPageRepository;
import com.ragagent.wiki.mapper.WikiFolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/service/wiki_page_test.go（708 行，基于 SQLite 内存库）。
 *
 * <p>覆盖：纯函数（parseOutLinks / normalizeSlug / stripWikiInlineChunkCitations /
 * normalizeWikiHierarchy / normalizeWikiIndexEntryHierarchy / containsString /
 * removeString）+ 需要真库的用例（PruneEmptyFolderChains / UpdatePage 的别名持久化 /
 * RepairContentLinks / FindPagesByNormalizedTitle / MovePage）。</p>
 *
 * <p>Go 用每个测试独立的 SQLite {@code file::memory:?cache=shared}；Java 用共享的
 * H2 内存库 + {@link TestSchema}（DDL 统一收敛在 TestSchema，禁止在测试类里私建表）。</p>
 *
 * <p>图谱部分（Go {@code TestComputeGraphSubset_*}）单独在
 * {@link WikiGraphCalculatorTest} 里逐分支对照。</p>
 */
@SpringBootTest
class WikiPageServiceTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WikiPageRepository repo;

    @Autowired
    private WikiFolderRepository folderRepo;
    @Autowired
    private WikiPageService svc;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    // ──────────────────────────── parseOutLinks ────────────────────────────

    /**
     * 对照 Go {@code TestParseOutLinks} 的表用例。
     *
     * <p>Go 里 {@code svc := &wikiPageService{}} 是个零值 receiver；Java 侧
     * {@code parseOutLinks} 不用实例状态，故为 static。分隔符用 {@code ;}
     * 是因为正文里含 {@code [[a|b]]} 的竖线。</p>
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "single link                 ; See [[entity/acme-corp]] for details.                        ; entity/acme-corp",
            "pipe syntax                 ; See [[entity/acme-corp|Acme Corp]] for details.              ; entity/acme-corp",
            "mixed pipe and bare         ; See [[entity/acme-corp|Acme Corp]] and [[concept/rag]] here. ; entity/acme-corp,concept/rag",
            "link with spaces normalized ; See [[Entity/Acme Corp]] for details.                        ; entity/acme-corp",
    })
    void parseOutLinksTable(String name, String content, String want) {
        assertThat(WikiPageLinkOps.parseOutLinks(content.trim()))
                .isEqualTo(List.of(want.split(",", -1)));
    }

    @Test
    void parseOutLinksMultiple() {
        assertThat(WikiPageLinkOps.parseOutLinks(
                "See [[entity/acme-corp]] and [[concept/rag]] for details."))
                .containsExactly("entity/acme-corp", "concept/rag");
    }

    @Test
    void parseOutLinksDeduplicates() {
        assertThat(WikiPageLinkOps.parseOutLinks(
                "See [[entity/acme-corp]] and also [[entity/acme-corp]] again."))
                .containsExactly("entity/acme-corp");
    }

    @Test
    void parseOutLinksNoLinks() {
        assertThat(WikiPageLinkOps.parseOutLinks("Just plain text without any links."))
                .isEmpty();
    }

    @Test
    void parseOutLinksEmptyContent() {
        assertThat(WikiPageLinkOps.parseOutLinks("")).isEmpty();
    }

    /** 对照 Go 用例 "nested brackets ignored"：{@code [not [a] link]} 不含 {@code [[} */
    @Test
    void parseOutLinksNestedBracketsIgnored() {
        assertThat(WikiPageLinkOps.parseOutLinks("Not a link: [not [a] link]")).isEmpty();
    }

    // ──────────────────────────── normalizeSlug ────────────────────────────

    /** 对照 Go {@code TestNormalizeSlug} */
    @ParameterizedTest
    @CsvSource({
            "Entity/Acme Corp,entity/acme-corp",
            "UPPER-CASE,upper-case",
            "already-ok,already-ok",
    })
    void normalizeSlug(String input, String want) {
        assertThat(WikiPageLinkOps.normalizeSlug(input)).isEqualTo(want);
    }

    @Test
    void normalizeSlugTrims() {
        assertThat(WikiPageLinkOps.normalizeSlug("  hello  ")).isEqualTo("hello");
    }

    @Test
    void normalizeSlugEmpty() {
        assertThat(WikiPageLinkOps.normalizeSlug("")).isEmpty();
    }

    /** 对照 Go {@code slugNamespace}（L1059-1064） */
    @Test
    void slugNamespace() {
        assertThat(WikiPageLinkOps.slugNamespace("summary/abc")).isEqualTo("summary");
        assertThat(WikiPageLinkOps.slugNamespace("noslash")).isEmpty();
        assertThat(WikiPageLinkOps.slugNamespace("a/b/c")).isEqualTo("a");
    }

    // ──────────────────── stripWikiInlineChunkCitations ────────────────────

    /** 对照 Go {@code TestStripWikiInlineChunkCitations} */
    @Test
    void stripWikiInlineChunkCitations() {
        String input = "[**橡皮障夹**](#)**钳**\n\n夹钳是用于夹持橡皮障夹的专用器械[c003]。"
                + "手柄便于操作 [c003]。多个来源[c003, c1000]。";
        String want = "[**橡皮障夹**](#)**钳**\n\n夹钳是用于夹持橡皮障夹的专用器械。"
                + "手柄便于操作。多个来源。";
        assertThat(WikiPageLinkOps.stripWikiInlineChunkCitations(input)).isEqualTo(want);
    }

    /** 对照 Go {@code TestStripWikiInlineChunkCitationsPreservesOrdinaryMarkdown} */
    @Test
    void stripWikiInlineChunkCitationsPreservesOrdinaryMarkdown() {
        String input = "保留 [citation]、[C003]、[c12] 和 [[concept/c003|页面链接]]。";
        assertThat(WikiPageLinkOps.stripWikiInlineChunkCitations(input)).isEqualTo(input);
    }

    // ──────────────────────── normalizeWikiHierarchy ────────────────────────

    /** 对照 Go {@code TestNormalizeWikiHierarchyCleansModelCategoryNoise} */
    @Test
    void normalizeWikiHierarchyCleansModelCategoryNoise() {
        WikiPage page = new WikiPage();
        page.setSlug("concept/ai-knowledge");
        page.setTitle("AI时代的知识困境");
        page.setPageType(WikiConstants.PAGE_TYPE_CONCEPT);
        page.setCategoryPath(new ArrayList<>(
                List.of("概念/爱护花草", " 实体/标牌 ", "摘要", "生态/平台", "多余层级")));

        WikiPageServiceImpl.normalizeWikiHierarchy(page);

        assertThat(page.getCategoryPath()).containsExactly("爱护花草", "标牌", "生态");
        assertThat(page.getDepth()).isEqualTo(3);
        assertThat(page.getWikiPath()).isEqualTo("concept/爱护花草/标牌/生态/AI时代的知识困境");
    }

    /** 对照 Go {@code TestNormalizeWikiHierarchyKeepsFolderBackedPathVerbatim} */
    @Test
    void normalizeWikiHierarchyKeepsFolderBackedPathVerbatim() {
        WikiPage page = new WikiPage();
        page.setSlug("concept/plan");
        page.setTitle("将计就计");
        page.setPageType(WikiConstants.PAGE_TYPE_CONCEPT);
        page.setFolderId("folder-concepts");
        page.setCategoryPath(new ArrayList<>(List.of("概念", " 概念 ", "Concepts", "")));

        WikiPageServiceImpl.normalizeWikiHierarchy(page);

        assertThat(page.getCategoryPath()).containsExactly("概念", "概念", "Concepts");
        assertThat(page.getDepth()).isEqualTo(3);
        assertThat(page.getWikiPath()).isEqualTo("concept/概念/概念/Concepts/将计就计");
    }

    /** 对照 Go {@code TestNormalizeWikiIndexEntryHierarchyCleansModelCategoryNoise} */
    @Test
    void normalizeWikiIndexEntryHierarchyCleansModelCategoryNoise() {
        WikiIndexEntry entry = new WikiIndexEntry();
        entry.setSlug("entity/sign");
        entry.setTitle("爱护花草标牌");
        entry.setCategoryPath(new ArrayList<>(List.of("实体/标牌", "概念", "生态/行为倡导")));

        WikiPageServiceImpl.normalizeWikiIndexEntryHierarchy(entry, WikiConstants.PAGE_TYPE_ENTITY);

        assertThat(entry.getCategoryPath()).containsExactly("标牌", "生态", "行为倡导");
        assertThat(entry.getWikiPath()).isEqualTo("entity/标牌/生态/行为倡导/爱护花草标牌");
    }

    // ──────────────────────── containsString / removeString ────────────────────────

    /** 对照 Go {@code TestContainsString} */
    @Test
    void containsString() {
        List<String> slice = List.of("a", "b", "c");
        assertThat(WikiPageServiceImpl.containsString(slice, "b")).isTrue();
        assertThat(WikiPageServiceImpl.containsString(slice, "d")).isFalse();
        assertThat(WikiPageServiceImpl.containsString(null, "a")).isFalse();
    }

    /** 对照 Go {@code TestRemoveString}：移除<b>所有</b>匹配项 */
    @Test
    void removeString() {
        List<String> slice = List.of("a", "b", "c", "b");
        assertThat(WikiPageServiceImpl.removeString(slice, "b")).containsExactly("a", "c");
        assertThat(WikiPageServiceImpl.removeString(slice, "z")).hasSize(4);
    }

    // ──────────────────────── PruneEmptyFolderChains ────────────────────────

    /**
     * 对照 Go {@code TestPruneEmptyFolderChainsDeletesOnlyEmptyCandidateAncestors}。
     *
     * <p>只删候选链上确实空掉的祖先：有别的占用子节点的祖先保留，
     * 受影响链之外的空文件夹也保留。</p>
     */
    @Test
    void pruneEmptyFolderChainsDeletesOnlyEmptyCandidateAncestors() {
        final String kbId = "kb-prune";
        OffsetDateTime now = OffsetDateTime.now();
        createFolder(kbId, "topic", "", "Topic", "Topic", 1, now);
        createFolder(kbId, "empty-leaf", "topic", "Empty", "Topic/Empty", 2, now);
        createFolder(kbId, "occupied-leaf", "topic", "Occupied", "Topic/Occupied", 2, now);
        createFolder(kbId, "empty-chain", "", "Empty chain", "Empty chain", 1, now);
        createFolder(kbId, "empty-chain-leaf", "empty-chain", "Leaf", "Empty chain/Leaf", 2, now);
        createFolder(kbId, "unrelated-empty", "", "Keep me", "Keep me", 1, now);

        repo.create(page(kbId, "page-1", "entity/occupied", "Occupied",
                WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.STATUS_PUBLISHED,
                "occupied-leaf", now));

        List<String> deleted = svc.pruneEmptyFolderChains(kbId,
                List.of("empty-leaf", "empty-chain-leaf"));
        assertThat(deleted).containsExactlyInAnyOrder(
                "empty-leaf", "empty-chain-leaf", "empty-chain");

        assertThat(folderRepo.getFolderByID(kbId, "topic"))
                .as("ancestor with another occupied child must remain").isNotNull();
        assertThat(folderRepo.getFolderByID(kbId, "occupied-leaf")).isNotNull();
        assertThat(folderRepo.getFolderByID(kbId, "unrelated-empty"))
                .as("empty folders outside the affected chains must remain").isNotNull();
    }

    /** 空入参 → 返回 null（对照 Go {@code return nil, nil}） */
    @Test
    void pruneEmptyFolderChainsEmptyInput() {
        assertThat(svc.pruneEmptyFolderChains("kb-prune", List.of())).isNull();
        assertThat(svc.pruneEmptyFolderChains("kb-prune", null)).isNull();
    }

    // ──────────────────── UpdatePage 的别名持久化 ────────────────────

    /** 对照 Go {@code TestUpdateWikiPagePersistsAndClearsAliases} */
    @Test
    void updateWikiPagePersistsAndClearsAliases() {
        final String kbId = "kb-alias";
        WikiPage page = svc.createPage(newPage(kbId, "concept/alias", "Alias",
                WikiConstants.PAGE_TYPE_CONCEPT, "summary", "content", List.of("old")));

        page.setAliases(new ArrayList<>(List.of("new", "alternate")));
        WikiPage updated = svc.updatePage(page);
        assertThat(updated.getAliases()).containsExactly("new", "alternate");
        assertThat(updated.getVersion()).isEqualTo(2);

        updated.setAliases(new ArrayList<>());
        WikiPage cleared = svc.updatePage(updated);
        assertThat(cleared.getAliases()).isEmpty();
        assertThat(cleared.getVersion()).isEqualTo(3);

        WikiPage stored = svc.getPageBySlug(kbId, "concept/alias");
        assertThat(stored.getAliases()).isEmpty();
    }

    // ──────────────────────────── RepairContentLinks ────────────────────────────

    /** 对照 Go {@code TestRepairContentLinks}（3 个子用例合一，避免重复建库） */
    @Test
    void repairContentLinks() {
        final String kbId = "kb-repair";
        OffsetDateTime now = OffsetDateTime.now();

        // 真摘要页（干净的 UUID slug）+ 一个干扰摘要 + 一个实体
        String realSummary = "summary/07a20bb1-a662-47cf-9929-06fb5d5b5b5e";
        seedPage(kbId, realSummary, "Weknora 试错记录.md - Summary",
                WikiConstants.PAGE_TYPE_SUMMARY, now);
        seedPage(kbId, "summary/fcadcaab-e094-4037-b71c-9edfdcdba058", "Another Doc - Summary",
                WikiConstants.PAGE_TYPE_SUMMARY, now);
        seedPage(kbId, "entity/mongodb", "MongoDB", WikiConstants.PAGE_TYPE_ENTITY, now);

        // 子用例 1：被弄花的 UUID 摘要链接经 bigram 修复
        // 最后一组里多插了一个十六进制位——精确查找会 404
        String mangled = "See [[summary/07a20bb1-a662-47cf-9929-06fb14d5b14b14e"
                + "|Weknora 试错记录.md - Summary]] for context.";
        WikiPageService.RepairResult r1 = svc.repairContentLinks(kbId, "synthesis/notes", mangled);
        assertThat(r1.changed()).as("expected the mangled summary link to be rewritten").isTrue();
        assertThat(r1.content()).contains("[[" + realSummary + "|Weknora 试错记录.md - Summary]]");
        assertThat(r1.content()).doesNotContain("06fb14d5b14b14e");

        // 子用例 2：活跃链接原样不动
        String fine = "Fine: [[entity/mongodb]] and [[" + realSummary + "]].";
        WikiPageService.RepairResult r2 = svc.repairContentLinks(kbId, "synthesis/notes", fine);
        assertThat(r2.changed()).isFalse();
        assertThat(r2.content()).isEqualTo(fine);

        // 子用例 3：解析不了的死链原样保留，绝不剥离
        String pending = "Pending: [[entity/some-brand-new-topic|New Topic]].";
        WikiPageService.RepairResult r3 = svc.repairContentLinks(kbId, "synthesis/notes", pending);
        assertThat(r3.changed()).isFalse();
        assertThat(r3.content())
                .as("unresolvable links must be preserved verbatim, not stripped")
                .isEqualTo(pending);
    }

    /** 空正文 / 无出链时是 no-op */
    @Test
    void repairContentLinksShortCircuits() {
        assertThat(svc.repairContentLinks("kb-repair", "x", "   ").changed()).isFalse();
        assertThat(svc.repairContentLinks("kb-repair", "x", "no links here").changed()).isFalse();
    }

    /** 指向自身的链接不被重写（对照 Go {@code if norm == selfSlug} 分支） */
    @Test
    void repairContentLinksSkipsSelfSlug() {
        final String kbId = "kb-repair-self";
        OffsetDateTime now = OffsetDateTime.now();
        seedPage(kbId, "synthesis/notes", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS, now);
        String content = "Self: [[synthesis/notes]].";
        WikiPageService.RepairResult r = svc.repairContentLinks(kbId, "synthesis/notes", content);
        assertThat(r.changed()).isFalse();
        assertThat(r.content()).isEqualTo(content);
    }

    // ──────────────────── FindPagesByNormalizedTitle ────────────────────

    /** 对照 Go {@code TestFindPagesByNormalizedTitleMatchesWhitespace} */
    @Test
    void findPagesByNormalizedTitleMatchesWhitespace() {
        final String kbId = "kb-id";
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(page(kbId, "page-kong", "entity/confucius", "孔 子",
                WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.STATUS_PUBLISHED, null, now));
        repo.create(page(kbId, "page-fable", "concept/yuyan", "《寓言》",
                WikiConstants.PAGE_TYPE_CONCEPT, WikiConstants.STATUS_PUBLISHED, null, now));

        List<WikiPageLite> pages =
                svc.findPagesByNormalizedTitle(kbId, WikiConstants.PAGE_TYPE_ENTITY, "孔子");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).getSlug()).isEqualTo("entity/confucius");

        assertThat(svc.findPagesByNormalizedTitle(kbId, WikiConstants.PAGE_TYPE_CONCEPT, "寓言"))
                .as("punctuation-significant titles must stay distinct")
                .isEmpty();

        repo.create(page(kbId, "page-mencius", "entity/mencius", "孟子",
                WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.STATUS_PUBLISHED, null, now));
        List<WikiPageLite> batched = svc.findPagesByNormalizedTitles(
                kbId, WikiConstants.PAGE_TYPE_ENTITY, List.of("孔子", "孟子", "孔子"));
        assertThat(batched).hasSize(2);
        assertThat(batched.stream().map(WikiPageLite::getSlug).toList())
                .containsExactlyInAnyOrder("entity/confucius", "entity/mencius");
    }

    // ──────────────────── MovePage + ListPages ────────────────────

    /**
     * 对照 Go {@code TestMovePageIntoTypeLabelNamedFolderKeepsHierarchy}：
     * 移动到「概念」这种类型标签同名文件夹后，层级必须保留，且经 ListPages
     * 读回时也不能被清洗掉（目录树正是靠这些字段定位页面）。
     */
    @Test
    void movePageIntoTypeLabelNamedFolderKeepsHierarchy() {
        final String kbId = "kb-move";
        OffsetDateTime now = OffsetDateTime.now();

        WikiFolder folder = svc.createFolder(kbId, 1L, WikiConstants.FOLDER_ROOT_ID, "概念");
        repo.create(page(kbId, "page-plan", "concept/plan", "将计就计",
                WikiConstants.PAGE_TYPE_CONCEPT, WikiConstants.STATUS_PUBLISHED, null, now));

        WikiPage moved = svc.movePage(kbId, "concept/plan", folder.getId());
        assertThat(moved.getFolderId()).isEqualTo(folder.getId());

        WikiPage stored = repo.getBySlug(kbId, "concept/plan");
        assertThat(stored.getFolderId()).isEqualTo(folder.getId());
        assertThat(stored.getCategoryPath()).containsExactly("概念");
        assertThat(stored.getDepth()).isEqualTo(1);
        assertThat(stored.getWikiPath()).isEqualTo("concept/概念/将计就计");

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId(kbId);
        req.setFolderId(folder.getId());
        List<WikiPage> listed = svc.listPages(req).getPages();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getCategoryPath()).containsExactly("概念");
        assertThat(listed.get(0).getDepth()).isEqualTo(1);
        assertThat(listed.get(0).getWikiPath()).isEqualTo("concept/概念/将计就计");
    }

    // ──────────────────── 文件夹树补充用例 ────────────────────

    /** CreateFolder：同级重名 → {@code WikiFolderConflictException} */
    @Test
    void createFolderRejectsDuplicateSiblingName() {
        svc.createFolder("kb-f", 1L, WikiConstants.FOLDER_ROOT_ID, "AI");
        assertThatThrownBy(() -> svc.createFolder("kb-f", 1L, WikiConstants.FOLDER_ROOT_ID, "AI"))
                .isInstanceOf(WikiFolderConflictException.class);
    }

    /** CreateFolder：未知父节点 → not found */
    @Test
    void createFolderRejectsUnknownParent() {
        assertThatThrownBy(() -> svc.createFolder("kb-f", 1L, "no-such-parent", "AI"))
                .isInstanceOf(com.ragagent.wiki.domain.WikiFolderNotFoundException.class);
    }

    /** CreateFolder：空名 / 含分隔符 → {@code WikiException} */
    @Test
    void createFolderValidatesName() {
        assertThatThrownBy(() -> svc.createFolder("kb-f", 1L, "", "  "))
                .isInstanceOf(WikiException.class)
                .hasMessage("folder name is required");
        assertThatThrownBy(() -> svc.createFolder("kb-f", 1L, "", "a/b"))
                .isInstanceOf(WikiException.class)
                .hasMessageContaining("must not contain a path separator");
    }

    /** DeleteFolder：非空 → {@code WikiFolderNotEmptyException} */
    @Test
    void deleteFolderRejectsNonEmpty() {
        String kbId = "kb-f2";
        WikiFolder parent = svc.createFolder(kbId, 1L, "", "Parent");
        svc.createFolder(kbId, 1L, parent.getId(), "Child");
        assertThatThrownBy(() -> svc.deleteFolder(kbId, parent.getId()))
                .isInstanceOf(WikiFolderNotEmptyException.class);

        // 先删子再删父才成功
        WikiFolder child = folderRepo.getChildFolderByName(kbId, parent.getId(), "Child");
        svc.deleteFolder(kbId, child.getId());
        svc.deleteFolder(kbId, parent.getId());
        assertThatThrownBy(() -> folderRepo.getFolderByID(kbId, parent.getId()))
                .isInstanceOf(com.ragagent.wiki.domain.WikiFolderNotFoundException.class);
    }

    /** RenameOrMoveFolder：移进自己的后代 → 拒绝（防成环） */
    @Test
    void renameOrMoveFolderRejectsCycle() {
        String kbId = "kb-f3";
        WikiFolder parent = svc.createFolder(kbId, 1L, "", "Parent");
        WikiFolder child = svc.createFolder(kbId, 1L, parent.getId(), "Child");
        assertThatThrownBy(() -> svc.renameOrMoveFolder(kbId, parent.getId(), "",
                child.getId(), true))
                .isInstanceOf(WikiException.class)
                .hasMessage("cannot move a folder into its own descendant");
    }

    /** RenameOrMoveFolder：改名重算整棵子树的 path/depth 与页面的缓存路径 */
    @Test
    void renameFolderRecomputesSubtreeAndPages() {
        final String kbId = "kb-f4";
        OffsetDateTime now = OffsetDateTime.now();
        WikiFolder parent = svc.createFolder(kbId, 1L, "", "Old");
        WikiFolder child = svc.createFolder(kbId, 1L, parent.getId(), "Leaf");
        repo.create(page(kbId, "p1", "entity/p1", "P1", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now));
        svc.movePage(kbId, "entity/p1", child.getId());
        assertThat(repo.getBySlug(kbId, "entity/p1").getCategoryPath())
                .containsExactly("Old", "Leaf");

        svc.renameOrMoveFolder(kbId, parent.getId(), "New", "", false);

        assertThat(folderRepo.getFolderByID(kbId, parent.getId()).getPath()).isEqualTo("New");
        assertThat(folderRepo.getFolderByID(kbId, parent.getId()).getDepth()).isEqualTo(1);
        assertThat(folderRepo.getFolderByID(kbId, child.getId()).getPath()).isEqualTo("New/Leaf");
        assertThat(folderRepo.getFolderByID(kbId, child.getId()).getDepth()).isEqualTo(2);
        WikiPage p1 = repo.getBySlug(kbId, "entity/p1");
        assertThat(p1.getCategoryPath()).containsExactly("New", "Leaf");
        assertThat(p1.getWikiPath()).isEqualTo("entity/New/Leaf/P1");
    }

    /** RenameOrMoveFolder：无变化时是 no-op（原样返回同一行） */
    @Test
    void renameOrMoveFolderNoop() {
        String kbId = "kb-f4b";
        WikiFolder f = svc.createFolder(kbId, 1L, "", "Same");
        WikiFolder again = svc.renameOrMoveFolder(kbId, f.getId(), "", "", false);
        assertThat(again.getId()).isEqualTo(f.getId());
        assertThat(again.getPath()).isEqualTo("Same");
    }

    /** FindOrCreateFolderPath：逐层建出缺失的中间文件夹，返回叶子 id 与清洗后的路径 */
    @Test
    void findOrCreateFolderPathCreatesMissingIntermediate() {
        String kbId = "kb-f5";
        WikiPageService.FindOrCreateResult r =
                svc.findOrCreateFolderPath(kbId, 1L, List.of("AI", "RAG"));
        assertThat(r.path()).containsExactly("AI", "RAG");
        assertThat(r.folderId()).isNotEmpty();
        assertThat(folderRepo.getFolderByID(kbId, r.folderId()).getPath()).isEqualTo("AI/RAG");

        // 二次调用必须复用，不重复创建
        assertThat(svc.findOrCreateFolderPath(kbId, 1L, List.of("AI", "RAG")).folderId())
                .isEqualTo(r.folderId());
    }

    /** FindOrCreateFolderPath：空路径解析到根（""），路径为 null（对照 Go nil slice） */
    @Test
    void findOrCreateFolderPathEmptyIsRoot() {
        WikiPageService.FindOrCreateResult r = svc.findOrCreateFolderPath("kb-f5", 1L, List.of());
        assertThat(r.folderId()).isEmpty();
        assertThat(r.path()).isNull();
    }

    /** applyFolderToPage：引用未知文件夹 → 硬错误（绝不静默放错位置） */
    @Test
    void movePageToUnknownFolderFails() {
        final String kbId = "kb-f6";
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(page(kbId, "p1", "entity/p1", "P1", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now));
        assertThatThrownBy(() -> svc.movePage(kbId, "entity/p1", "no-such-folder"))
                .isInstanceOf(WikiException.class)
                .hasMessageContaining("wiki page references unknown folder");
    }

    // ──────────────────── ListChildFolders 递归计数 ────────────────────

    /**
     * PageCount 是递归的：父文件夹反映其下所有内容；空文件夹只在请求多个类型
     * （合并视图）时列出。
     */
    @Test
    void listChildFoldersCountsRecursively() {
        final String kbId = "kb-f7";
        OffsetDateTime now = OffsetDateTime.now();
        WikiFolder parent = svc.createFolder(kbId, 1L, "", "Topic");
        WikiFolder leaf = svc.createFolder(kbId, 1L, parent.getId(), "Leaf");
        svc.createFolder(kbId, 1L, "", "Empty");

        repo.create(page(kbId, "p1", "entity/x", "X", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, leaf.getId(), now));

        List<WikiFolderNode> nodes =
                svc.listChildFolders(kbId, "", List.of(WikiConstants.PAGE_TYPE_ENTITY));
        // 单类型视图：空容器不列出
        assertThat(nodes).extracting(WikiFolderNode::getFolder)
                .extracting(WikiFolder::getName).containsExactly("Topic");
        assertThat(nodes.get(0).getPageCount()).as("页数含整棵子树").isEqualTo(1);
        assertThat(nodes.get(0).isHasChildren()).isTrue();

        // 合并视图（多个类型）：完全空的容器也列出
        List<WikiFolderNode> merged = svc.listChildFolders(kbId, "",
                List.of(WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.PAGE_TYPE_CONCEPT));
        assertThat(merged).extracting(WikiFolderNode::getFolder)
                .extracting(WikiFolder::getName).containsExactlyInAnyOrder("Topic", "Empty");
    }

    // ──────────────────── GetIndex / GetIndexView ────────────────────

    /** GetIndex：不存在时建默认索引页（租户 id 取自 KB 行） */
    @Test
    void getIndexCreatesDefaultPage() {
        insertKb("kb-idx", 1L, true);
        WikiPage index = svc.getIndex("kb-idx");
        assertThat(index.getSlug()).isEqualTo("index");
        assertThat(index.getPageType()).isEqualTo(WikiConstants.PAGE_TYPE_INDEX);
        assertThat(index.getStatus()).isEqualTo(WikiConstants.STATUS_PUBLISHED);
        assertThat(index.getVersion()).isEqualTo(1);
        assertThat(index.getTenantId()).isEqualTo(1L);
        assertThat(index.getContent()).isEqualTo(WikiPageServiceImpl.DEFAULT_INDEX_CONTENT);
        // 再取一次返回同一行，不再新建
        assertThat(svc.getIndex("kb-idx").getId()).isEqualTo(index.getId());
    }

    /** GetIndex：KB 不存在 → 报错（对照 Go {@code get knowledge base: ...}） */
    @Test
    void getIndexFailsForUnknownKb() {
        assertThatThrownBy(() -> svc.getIndex("no-such-kb"))
                .isInstanceOf(WikiException.class)
                .hasMessageContaining("get knowledge base");
    }

    /** GetIndexView：每类型分组 + 游标只在还有余量时给出 */
    @Test
    void getIndexViewGroupsAndCursor() {
        final String kbId = "kb-idx2";
        insertKb(kbId, 1L, true);
        svc.getIndex(kbId);
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < 3; i++) {
            repo.create(page(kbId, "e" + i, "entity/e" + i, "E" + i,
                    WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.STATUS_PUBLISHED, null, now));
        }

        WikiIndex.Response view =
                svc.getIndexView(kbId, List.of(WikiConstants.PAGE_TYPE_ENTITY), 2, "");
        assertThat(view.getGroups()).hasSize(1);
        WikiIndex.Group g = view.getGroups().get(0);
        assertThat(g.getType()).isEqualTo(WikiConstants.PAGE_TYPE_ENTITY);
        assertThat(g.getTotal()).isEqualTo(3);
        assertThat(g.getItems()).hasSize(2);
        // 返回了满一页且后面还有余量 → 给出 cursor
        assertThat(g.getNextCursor()).isEqualTo("2");

        WikiIndex.Group g2 = svc.getIndexView(kbId, List.of(WikiConstants.PAGE_TYPE_ENTITY), 2, "2")
                .getGroups().get(0);
        assertThat(g2.getItems()).hasSize(1);
        // 短页 → 流结束，不给 cursor
        assertThat(g2.getNextCursor()).isEmpty();
    }

    /** GetIndexView：不传 pageTypes 时默认覆盖全部内容类型 */
    @Test
    void getIndexViewDefaultsToAllContentTypes() {
        final String kbId = "kb-idx4";
        insertKb(kbId, 1L, true);
        WikiIndex.Response view = svc.getIndexView(kbId, List.of(), 50, "");
        assertThat(view.getGroups()).extracting(WikiIndex.Group::getType)
                .containsExactlyElementsOf(WikiPageServiceImpl.WIKI_INDEX_CONTENT_PAGE_TYPES);
        // intro 回落到索引页正文（建默认页时正文非空）
        assertThat(view.getIntro()).isEqualTo(WikiPageServiceImpl.DEFAULT_INDEX_CONTENT);
        assertThat(view.getVersion()).isEqualTo(1);
    }

    /** GetIndexView：非法 cursor → {@code invalid cursor} */
    @Test
    void getIndexViewRejectsBadCursor() {
        insertKb("kb-idx3", 1L, true);
        assertThatThrownBy(() -> svc.getIndexView("kb-idx3", List.of(), 50, "abc"))
                .isInstanceOf(WikiException.class)
                .hasMessageContaining("invalid cursor");
        assertThatThrownBy(() -> svc.getIndexView("kb-idx3", List.of(), 50, "-1"))
                .isInstanceOf(WikiException.class)
                .hasMessageContaining("invalid cursor");
    }

    // ──────────────────── 链接双向维护 ────────────────────

    /** CreatePage/UpdatePage 维护目标页入链；DeletePage 摘掉入链 */
    @Test
    void inLinksAreMaintainedBidirectionally() {
        final String kbId = "kb-links";
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(page(kbId, "t1", "entity/t1", "T1", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now));
        repo.create(page(kbId, "t2", "entity/t2", "T2", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now));

        WikiPage src = newPage(kbId, "synthesis/s", "S", WikiConstants.PAGE_TYPE_SYNTHESIS,
                "", "See [[entity/t1]] and [[entity/t2]].", List.of());
        svc.createPage(src);

        assertThat(repo.getBySlug(kbId, "entity/t1").getInLinks())
                .containsExactly("synthesis/s");
        assertThat(repo.getBySlug(kbId, "entity/t2").getInLinks())
                .containsExactly("synthesis/s");

        // 改正文去掉对 t2 的引用：t2 的入链被摘掉，t1 保持
        src.setContent("Now only [[entity/t1]].");
        svc.updatePage(src);
        assertThat(repo.getBySlug(kbId, "entity/t1").getInLinks())
                .containsExactly("synthesis/s");
        assertThat(repo.getBySlug(kbId, "entity/t2").getInLinks()).isEmpty();

        // 删除源页面：t1 的入链也摘掉
        svc.deletePage(kbId, "synthesis/s");
        assertThat(repo.getBySlug(kbId, "entity/t1").getInLinks()).isEmpty();
    }

    /** UpdateAutoLinkedContent：正文与出链更新但<b>版本号不动</b> */
    @Test
    void updateAutoLinkedContentKeepsVersion() {
        final String kbId = "kb-auto";
        WikiPage page = svc.createPage(newPage(kbId, "concept/a", "A",
                WikiConstants.PAGE_TYPE_CONCEPT, "s", "v1 body", List.of()));
        assertThat(page.getVersion()).isEqualTo(1);

        page.setContent("v1 body with [[entity/x]] added");
        svc.updateAutoLinkedContent(page);

        WikiPage stored = repo.getBySlug(kbId, "concept/a");
        assertThat(stored.getVersion()).as("机器侧链接修饰不动版本号").isEqualTo(1);
        assertThat(stored.getContent()).contains("[[entity/x]]");
        assertThat(stored.getOutLinks()).containsExactly("entity/x");
    }

    /** RebuildLinks：全量重解析（含清掉过期的入链） */
    @Test
    void rebuildLinksRebuildsEveryInLink() {
        final String kbId = "kb-rebuild";
        OffsetDateTime now = OffsetDateTime.now();
        // 故意写入一条过期的入链
        WikiPage t1 = page(kbId, "t1", "entity/t1", "T1", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now);
        t1.setInLinks(new ArrayList<>(List.of("stale/slug")));
        repo.create(t1);
        String s1id = "s1";
        repo.create(page(kbId, s1id, "synthesis/s1", "S1", WikiConstants.PAGE_TYPE_SYNTHESIS,
                WikiConstants.STATUS_PUBLISHED, null, now));
        WikiPage s1 = repo.getBySlug(kbId, "synthesis/s1");
        s1.setContent("Link: [[entity/t1]]");
        repo.update(s1);

        svc.rebuildLinks(kbId);

        assertThat(repo.getBySlug(kbId, "entity/t1").getInLinks())
                .containsExactly("synthesis/s1");
    }

    /** GetStats：计数口径（排除归档）+ 总链接数；端口缺席时照 Go 的 nil 分支 */
    @Test
    void getStatsAggregatesCounts() {
        final String kbId = "kb-stats";
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(page(kbId, "t1", "entity/t1", "T1", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now));
        repo.create(page(kbId, "t2", "entity/t2", "T2", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_ARCHIVED, null, now));
        WikiPage s = page(kbId, "s1", "synthesis/s1", "S1", WikiConstants.PAGE_TYPE_SYNTHESIS,
                WikiConstants.STATUS_PUBLISHED, null, now);
        s.setOutLinks(new ArrayList<>(List.of("entity/t1")));
        repo.create(s);

        WikiStats stats = svc.getStats(kbId);
        assertThat(stats.getTotalPages()).as("归档页不计入").isEqualTo(2);
        assertThat(stats.getTotalLinks()).isEqualTo(1);
        assertThat(stats.getPagesByType()).containsEntry(WikiConstants.PAGE_TYPE_ENTITY, 1L);
        // ⚠️ recentUpdates 走的是 List（repository L352-427）——该查询只在请求显式给了
        // status 时才过滤，GetStats 的请求不带 status，因此归档页也会出现（对照 Go）
        assertThat(stats.getRecentUpdates()).hasSize(3);
        assertThat(stats.getPendingTasks()).isZero();
        assertThat(stats.isActive()).isFalse();
    }

    /** 直通读取方法的语义（含 cursor / slug 集合 / 归档排除） */
    @Test
    void passThroughReads() {
        final String kbId = "kb-pass";
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(page(kbId, "t1", "entity/t1", "Alpha", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_PUBLISHED, null, now));
        repo.create(page(kbId, "t2", "entity/t2", "Beta", WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.STATUS_ARCHIVED, null, now));

        assertThat(svc.listAllPages(kbId)).hasSize(1);
        assertThat(svc.listAllSlugs(kbId)).containsExactly("entity/t1");
        assertThat(svc.countByType(kbId)).containsEntry(WikiConstants.PAGE_TYPE_ENTITY, 1L);
        // ⚠️ ListByType 在 Go 里<b>不带</b> archived 过滤（repository L430-439 只按
        // kb + page_type 过滤），所以归档页也在结果里
        assertThat(svc.listByType(kbId, WikiConstants.PAGE_TYPE_ENTITY)).hasSize(2);
        assertThat(svc.listBySlugs(kbId, List.of("entity/t1", "entity/t2")))
                .containsKey("entity/t1");
        assertThat(svc.existsSlugs(kbId, List.of("entity/t1", "entity/t2")))
                .containsEntry("entity/t1", true)
                .containsEntry("entity/t2", false);
        assertThat(svc.listPagesCursor(kbId, "", 10).pages()).hasSize(1);
        assertThat(svc.searchPages(kbId, "Alpha", 10)).hasSize(1);
        assertThat(svc.listDistinctCategoryPaths(kbId, 10)).isEmpty();
        assertThat(svc.findPagesByNormalizedTitle(kbId, WikiConstants.PAGE_TYPE_ENTITY, "alpha"))
                .hasSize(1);
        // RebuildIndexPage 在 Go 里故意是 no-op，Java 照抄
        svc.rebuildIndexPage(kbId);
    }

    /** 页面问题的创建 / 列表 / 状态更新 */
    @Test
    void issuesCrud() {
        WikiPageIssue issue = new WikiPageIssue();
        issue.setTenantId(1L);
        issue.setKnowledgeBaseId("kb-issue");
        issue.setSlug("entity/t1");
        issue.setIssueType("contradiction");
        issue.setDescription("d");
        issue.setStatus("pending");
        issue.setReportedBy("user-1");
        WikiPageIssue saved = svc.createIssue(issue);
        assertThat(saved.getId()).isNotEmpty();

        assertThat(svc.listIssues("kb-issue", "", "pending")).hasSize(1);
        svc.updateIssueStatus(saved.getId(), "resolved");
        assertThat(svc.listIssues("kb-issue", "", "pending")).isEmpty();
        assertThat(svc.listIssues("kb-issue", "", "resolved")).hasSize(1);
    }

    // ──────────────────────────── 夹具 ────────────────────────────

    private static WikiPage newPage(String kbId, String slug, String title, String pageType,
                                    String summary, String content, List<String> aliases) {
        WikiPage p = new WikiPage();
        p.setTenantId(1L);
        p.setKnowledgeBaseId(kbId);
        p.setSlug(slug);
        p.setTitle(title);
        p.setSummary(summary);
        p.setContent(content);
        p.setPageType(pageType);
        p.setAliases(new ArrayList<>(aliases));
        return p;
    }

    private static WikiPage page(String kbId, String id, String slug, String title,
                                 String pageType, String status, String folderId,
                                 OffsetDateTime now) {
        WikiPage p = new WikiPage();
        p.setId(id);
        p.setTenantId(1L);
        p.setKnowledgeBaseId(kbId);
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType(pageType);
        p.setStatus(status);
        if (folderId != null) {
            p.setFolderId(folderId);
        }
        p.setVersion(1);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    private void seedPage(String kbId, String slug, String title, String pageType,
                          OffsetDateTime now) {
        repo.create(page(kbId, UUID.randomUUID().toString(), slug, title, pageType,
                WikiConstants.STATUS_PUBLISHED, null, now));
    }

    private void createFolder(String kbId, String id, String parentID, String name, String path,
                              int depth, OffsetDateTime now) {
        WikiFolder f = new WikiFolder();
        f.setId(id);
        f.setTenantId(1L);
        f.setKnowledgeBaseId(kbId);
        f.setParentId(parentID);
        f.setName(name);
        f.setPath(path);
        f.setDepth(depth);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        folderRepo.createFolder(f);
    }

    /** 直接插一行 KB（默认索引页需要 KB 行提供 tenant id） */
    private void insertKb(String kbId, long tenantId, boolean wikiEnabled) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, indexing_strategy) "
                        + "VALUES (?, ?, ?, 'document', ?)",
                kbId, kbId, tenantId,
                "{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":"
                        + wikiEnabled + ",\"graph_enabled\":false}");
    }
}
