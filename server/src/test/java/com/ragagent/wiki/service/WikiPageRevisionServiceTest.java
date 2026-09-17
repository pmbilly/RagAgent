package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragagent.TestSchema;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiPageRevisionListResponse;
import com.ragagent.wiki.mapper.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/service/wiki_page_revision_test.go（232 行）的 6 个用例。
 *
 * <p>Go 的 {@code newWikiRevisionTestService} 每个用例开一个独立的 SQLite 内存库；
 * Java 用共享 H2 + {@link TestSchema#resetData}。</p>
 *
 * <p>Go 通过 {@code types.WithWikiEditSource(ctx, src)} 把编辑来源挂到 ctx 上；
 * Java 侧由 {@link WikiEditContext} 用 ThreadLocal 承担同一语义。</p>
 */
@SpringBootTest
class WikiPageRevisionServiceTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WikiPageRepository repo;
    @Autowired
    private WikiPageService svc;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    /**
     * 对照 Go {@code TestUpdateWikiPageSnapshotsSupersededVersion}。
     *
     * <p>要点：只有真的内容变更才快照被取代的版本；快照的作者是<b>被取代版本的</b>
     * 作者而不是取代它的人；纯记账写入不产生快照。</p>
     */
    @Test
    void updateWikiPageSnapshotsSupersededVersion() {
        final String kb = "kb-rev";

        WikiPage created = WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER,
                () -> svc.createPage(newPage(kb, "concept/rag", "RAG", "v1 body")));
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getLastEditSource()).isEqualTo(WikiConstants.EDIT_SOURCE_USER);

        // 第一次真编辑：v1 必须被快照，v2 成为当前版本
        WikiPage edit = WikiPageServiceImpl.copyPage(created);
        edit.setContent("v2 body");
        WikiPage updated = WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_AGENT,
                () -> svc.updatePage(edit));
        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getLastEditSource()).isEqualTo(WikiConstants.EDIT_SOURCE_AGENT);

        WikiPageRevisionListResponse resp = svc.listRevisions(kb, "concept/rag", 50, 0);
        assertThat(resp.getCurrentVersion()).isEqualTo(2);
        assertThat(resp.getTotal()).isEqualTo(1);
        assertThat(resp.getRevisions()).hasSize(1);
        assertThat(resp.getRevisions().get(0).getVersion()).isEqualTo(1);
        // 快照的归属是被取代版本的作者，不是取代它的编辑者
        assertThat(resp.getRevisions().get(0).getEditSource())
                .isEqualTo(WikiConstants.EDIT_SOURCE_USER);
        // 列表模式省略 content 列
        assertThat(resp.getRevisions().get(0).getContent()).isEmpty();

        WikiPageRevision rev = svc.getRevision(kb, "concept/rag", 1);
        assertThat(rev.getContent()).isEqualTo("v1 body");

        // 纯记账写入（内容完全相同）不得产生快照
        WikiPage same = WikiPageServiceImpl.copyPage(updated);
        svc.updatePage(same);
        WikiPageRevisionListResponse resp2 = svc.listRevisions(kb, "concept/rag", 50, 0);
        assertThat(resp2.getTotal()).isEqualTo(1);
        assertThat(resp2.getCurrentVersion()).isEqualTo(2);
    }

    /**
     * 对照 Go {@code TestUpdateWikiPagePersistsClearedFields}：
     * 清空的字段必须真的落库，不能被零值感知的写入路径跳过。
     */
    @Test
    void updateWikiPagePersistsClearedFields() {
        final String kb = "kb-clear";

        WikiPage seed = newPage(kb, "concept/x", "X", "body");
        seed.setSummary("to be cleared");
        WikiPage created = svc.createPage(seed);

        WikiPage edit = WikiPageServiceImpl.copyPage(created);
        edit.setSummary("");
        WikiPage updated = svc.updatePage(edit);
        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getSummary()).isEmpty();

        // 字段完全一致时再次更新必须被当作 no-op（不涨版本）
        WikiPage again = WikiPageServiceImpl.copyPage(updated);
        WikiPage roundTripped = svc.updatePage(again);
        assertThat(roundTripped.getVersion()).isEqualTo(2);
        assertThat(roundTripped.getSummary()).isEmpty();
    }

    /**
     * 对照 Go {@code TestRevertWikiPageToVersion}：回滚以一次新编辑的形式应用，
     * 于是回滚本身也是可撤销的（两个被取代的版本都留了快照）。
     */
    @Test
    void revertWikiPageToVersion() {
        final String kb = "kb-revert";

        WikiPage created = svc.createPage(
                newPage(kb, "entity/acme", "Acme", "original body"));
        assertThat(created.getTitle()).isEqualTo("Acme");

        WikiPage edit = WikiPageServiceImpl.copyPage(created);
        edit.setContent("rewritten body");
        edit.setTitle("Acme Corp");
        svc.updatePage(edit);

        // 回滚到当前版本是调用方失误，必须能与服务端故障区分开（handler → 400）
        assertThatThrownBy(() -> svc.revertPageToVersion(kb, "entity/acme", 2))
                .isInstanceOf(WikiRevertToCurrentVersionException.class)
                .hasMessage("cannot revert to the current version");

        WikiPage reverted = svc.revertPageToVersion(kb, "entity/acme", 1);
        assertThat(reverted.getVersion()).as("revert applies as a fresh edit").isEqualTo(3);
        assertThat(reverted.getContent()).isEqualTo("original body");
        assertThat(reverted.getTitle()).isEqualTo("Acme");
        assertThat(reverted.getLastEditSource()).isEqualTo(WikiConstants.EDIT_SOURCE_REVERT);

        // 两个被取代的版本都留了快照，所以回滚本身可撤销
        WikiPageRevisionListResponse resp = svc.listRevisions(kb, "entity/acme", 50, 0);
        assertThat(resp.getTotal()).isEqualTo(2);
        assertThat(resp.getRevisions().get(0).getVersion()).isEqualTo(2);
        assertThat(svc.getRevision(kb, "entity/acme", 2).getContent())
                .isEqualTo("rewritten body");
    }

    /** 回滚到不存在的版本 → not found（对照 Go 的 GetRevision 错误直传） */
    @Test
    void revertToMissingVersionFails() {
        final String kb = "kb-revert-miss";
        svc.createPage(newPage(kb, "entity/acme", "Acme", "original body"));
        assertThatThrownBy(() -> svc.revertPageToVersion(kb, "entity/acme", 99))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    /** 对照 Go {@code TestWikiPageRevisionsArePruned}：软上限 50 条 */
    @Test
    void wikiPageRevisionsArePruned() {
        final String kb = "kb-prune-rev";
        WikiPage page = svc.createPage(newPage(kb, "concept/hot", "Hot", "v1"));

        int total = WikiConstants.MAX_REVISIONS_PER_PAGE + 5;
        for (int i = 2; i <= total + 1; i++) {
            WikiPage edit = WikiPageServiceImpl.copyPage(page);
            edit.setContent("v" + i);
            page = svc.updatePage(edit);
        }
        assertThat(page.getVersion()).isEqualTo(total + 1);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wiki_page_revisions WHERE page_id = ?", Long.class,
                page.getId());
        assertThat(count).isLessThanOrEqualTo((long) WikiConstants.MAX_REVISIONS_PER_PAGE);

        // 最新的快照活着，最老的已经没了
        assertThat(svc.getRevision(kb, "concept/hot", page.getVersion() - 1)).isNotNull();
        assertThatThrownBy(() -> svc.getRevision(kb, "concept/hot", 1))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    /**
     * 对照 Go {@code TestPipelineChurnDoesNotEvictHumanRevisions}：
     * 被管道反复重写的页面绝不能挤掉当初让历史值得保留的那几次人工编辑。
     */
    @Test
    void pipelineChurnDoesNotEvictHumanRevisions() {
        final String kb = "kb-mixed-rev";

        WikiPage page = WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER,
                () -> svc.createPage(
                        newPage(kb, "concept/hub", "Hub", "handwritten v1")));

        // 再一次人工编辑，然后是一长串管道重写
        WikiPage edit = WikiPageServiceImpl.copyPage(page);
        edit.setContent("handwritten v2");
        page = WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER,
                () -> svc.updatePage(edit));

        for (int i = 0; i < WikiConstants.MAX_REVISIONS_PER_PAGE + 10; i++) {
            WikiPage next = WikiPageServiceImpl.copyPage(page);
            next.setContent("pipeline rewrite " + i);
            page = svc.updatePage(next);
        }

        WikiPageRevision v1 = svc.getRevision(kb, "concept/hub", 1);
        assertThat(v1.getContent())
                .as("the first human version must outlive pipeline churn")
                .isEqualTo("handwritten v1");
        assertThat(svc.getRevision(kb, "concept/hub", 2).getContent())
                .isEqualTo("handwritten v2");

        // 老的管道快照仍被剪掉
        assertThatThrownBy(() -> svc.getRevision(kb, "concept/hub", 3))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    /**
     * 对照 Go {@code TestDeletePageDropsRevisionHistory}：
     * 删除页面不能留下不可达的正文快照。
     */
    @Test
    void deletePageDropsRevisionHistory() {
        final String kb = "kb-del-rev";
        WikiPage page = svc.createPage(newPage(kb, "concept/doomed", "Doomed", "v1"));
        WikiPage edit = WikiPageServiceImpl.copyPage(page);
        edit.setContent("v2");
        page = svc.updatePage(edit);

        Long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wiki_page_revisions WHERE page_id = ?", Long.class,
                page.getId());
        assertThat(before).isEqualTo(1L);

        svc.deletePage(kb, "concept/doomed");

        Long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wiki_page_revisions WHERE page_id = ?", Long.class,
                page.getId());
        assertThat(after)
                .as("a deleted page must not leave unreachable content snapshots behind")
                .isZero();
    }

    // ──────────────────────────── 夹具 ────────────────────────────

    /** 对照 Go 测试里的 {@code &types.WikiPage{Title: ..., Content: ..., Summary: "s1"}} */
    private static WikiPage newPage(String kbId, String slug, String title, String content) {
        WikiPage p = new WikiPage();
        p.setTenantId(1L);
        p.setKnowledgeBaseId(kbId);
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType(WikiConstants.PAGE_TYPE_CONCEPT);
        p.setContent(content);
        p.setSummary("s1");
        return p;
    }
}
