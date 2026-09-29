package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiLintIssue;
import com.ragagent.wiki.domain.WikiLintReport;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.mapper.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * wiki_lint.go 的对等测试。
 *
 * <p><b>说明</b>：Go 侧 <b>没有</b> wiki_lint_test.go（上游未写），所以本文件不是
 * 「逐用例对照」而是按 wiki_lint.go 的 6 个检测分支 + AutoFix 的 3 个可修分支
 * 逐条设计的行为钉桩。断言里的文案与 Go 的 {@code fmt.Sprintf} 逐字对齐。</p>
 */
@SpringBootTest
class WikiLintServiceTest {

    /** 足够长、且不含任何实体标题的填充文本（>50 字节，避开 empty_content） */
    private static final String PADDING =
            "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor "
                    + "incididunt ut labore et dolore magna aliqua.";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WikiPageRepository repo;
    @Autowired
    private WikiLintService lint;
    @Autowired
    private WikiPageService wiki;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    // ──────────────────────── KB 校验 ────────────────────────

    /** 对照 Go L100-102：KB 未开 wiki → {@code "KB %s is not a wiki type"} */
    @Test
    void rejectsNonWikiKb() {
        insertKb("kb-nowiki", 1L, false);
        assertThatThrownBy(() -> lint.runLint("kb-nowiki"))
                .isInstanceOf(WikiException.class)
                .hasMessage("KB kb-nowiki is not a wiki type");
    }

    /** 对照 Go L96-99：KB 不存在 → {@code "get KB: ..."} */
    @Test
    void rejectsUnknownKb() {
        assertThatThrownBy(() -> lint.runLint("no-such-kb"))
                .isInstanceOf(WikiException.class)
                .hasMessageContaining("get KB");
    }

    // ──────────────────────── 健康 wiki ────────────────────────

    /**
     * 一个互相链接、正文充足、无陈引用的 wiki：健康分 100，
     * issues 为 {@code null}（对照 Go 的 nil slice），摘要是「健康」文案。
     */
    @Test
    void healthyWikiHasNullIssuesAndFullScore() {
        final String kbId = "kb-healthy";
        insertKb(kbId, 1L, true);
        seedHealthyWiki(kbId);

        WikiLintReport report = lint.runLint(kbId);

        assertThat(report.getIssues()).as("Go 的 nil slice 序列化成 null").isNull();
        assertThat(report.getHealthScore()).isEqualTo(100);
        assertThat(report.getSummary()).isEqualTo("Wiki is healthy! No issues found.");
        assertThat(report.getKnowledgeBaseId()).isEqualTo(kbId);
        assertThat(report.getStats().getTotalPages()).isEqualTo(3);
    }

    // ──────────────────────── 检测分支 ────────────────────────

    /** 检查 1：没有入链的页面（index 页除外）→ orphan_page / warning / 不可修 */
    @Test
    void detectsOrphanPage() {
        final String kbId = "kb-orphan";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "entity/lonely", "Lonely", WikiConstants.PAGE_TYPE_ENTITY,
                PADDING, List.of(), List.of(), List.of()));

        WikiLintReport report = lint.runLint(kbId);
        WikiLintIssue issue = findIssue(report, WikiLintIssue.ORPHAN_PAGE, "entity/lonely");
        assertThat(issue).isNotNull();
        assertThat(issue.getSeverity()).isEqualTo(WikiLintIssue.SEVERITY_WARNING);
        assertThat(issue.getDescription())
                .isEqualTo("Page 'Lonely' has no inbound links — it's disconnected from the wiki");
        assertThat(issue.isAutoFixable()).isFalse();
        assertThat(issue.getTargetSlug()).isEmpty();
    }

    /** index 页天然是根页面，永远不算孤儿 */
    @Test
    void indexPageIsNeverOrphan() {
        final String kbId = "kb-idx-orphan";
        insertKb(kbId, 1L, true);
        wiki.getIndex(kbId);
        WikiLintReport report = lint.runLint(kbId);
        if (report.getIssues() != null) {
            assertThat(report.getIssues())
                    .extracting(WikiLintIssue::getType)
                    .doesNotContain(WikiLintIssue.ORPHAN_PAGE);
        }
    }

    /** 检查 2：出链指向不存在的 slug → broken_link / error / 可修，target 是死链目标 */
    @Test
    void detectsBrokenLink() {
        final String kbId = "kb-broken";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "synthesis/s", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS,
                PADDING + " See [[entity/ghost]].",
                List.of("entity/ghost"), List.of(), List.of()));

        WikiLintReport report = lint.runLint(kbId);
        WikiLintIssue issue = findIssue(report, WikiLintIssue.BROKEN_LINK, "synthesis/s");
        assertThat(issue).isNotNull();
        assertThat(issue.getSeverity()).isEqualTo(WikiLintIssue.SEVERITY_ERROR);
        assertThat(issue.getTargetSlug()).isEqualTo("entity/ghost");
        assertThat(issue.getDescription())
                .isEqualTo("Page 'Notes' links to [[entity/ghost]] which does not exist");
        assertThat(issue.isAutoFixable()).isTrue();
    }

    /** 检查 3：正文过短 → empty_content / warning / 可修；阈值按 UTF-8 <b>字节</b>数 */
    @Test
    void detectsEmptyContentByUtf8ByteLength() {
        final String kbId = "kb-empty";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "entity/tiny", "Tiny", WikiConstants.PAGE_TYPE_ENTITY,
                "短", List.of(), List.of(), List.of()));

        WikiLintReport report = lint.runLint(kbId);
        WikiLintIssue issue = findIssue(report, WikiLintIssue.EMPTY_CONTENT, "entity/tiny");
        assertThat(issue).isNotNull();
        assertThat(issue.getDescription())
                .isEqualTo("Page 'Tiny' has very little content (3 chars)");
        assertThat(issue.isAutoFixable()).isTrue();

        // 边界：正好 50 个 ASCII 字节不触发（判据是 < 50）
        final String kbId2 = "kb-empty-boundary";
        insertKb(kbId2, 1L, true);
        repo.create(page(kbId2, "entity/exact", "Exact", WikiConstants.PAGE_TYPE_ENTITY,
                "x".repeat(50), List.of(), List.of(), List.of()));
        WikiLintReport report2 = lint.runLint(kbId2);
        assertThat(findIssue(report2, WikiLintIssue.EMPTY_CONTENT, "entity/exact")).isNull();
    }

    /** 检查 4：source_refs 指向已软删的文档 → stale_ref / error / 可修 */
    @Test
    void detectsStaleSourceRef() {
        final String kbId = "kb-stale";
        insertKb(kbId, 1L, true);
        // kid-live 存活，kid-dead 软删
        insertKnowledge(kbId, "kid-live", null);
        insertKnowledge(kbId, "kid-dead", OffsetDateTime.now());
        repo.create(page(kbId, "summary/a", "A Summary", WikiConstants.PAGE_TYPE_SUMMARY,
                PADDING, List.of(), List.of(),
                List.of("kid-live|Live", "kid-dead|Dead")));

        WikiLintReport report = lint.runLint(kbId);
        List<WikiLintIssue> stale = issuesOfType(report, WikiLintIssue.STALE_REF);
        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).getTargetSlug()).isEqualTo("kid-dead");
        assertThat(stale.get(0).getDescription())
                .isEqualTo("Page 'A Summary' references deleted knowledge kid-dead");
        assertThat(stale.get(0).isAutoFixable()).isTrue();
    }

    /** 检查 5：正文提到实体标题却没链过去 → missing_cross_ref / info / 不可修 */
    @Test
    void detectsMissingCrossReference() {
        final String kbId = "kb-cross";
        insertKb(kbId, 1L, true);
        // 实体页本身有入链（避免孤儿噪声），正文里提到 "MongoDB" 但没链
        repo.create(page(kbId, "synthesis/s", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS,
                PADDING + " We use MongoDB heavily. See [[entity/hub]].",
                List.of("entity/hub"), List.of("entity/hub"), List.of()));
        repo.create(page(kbId, "entity/hub", "Hub", WikiConstants.PAGE_TYPE_ENTITY,
                PADDING, List.of(), List.of("synthesis/s"), List.of()));
        repo.create(page(kbId, "entity/mongodb", "MongoDB", WikiConstants.PAGE_TYPE_ENTITY,
                PADDING, List.of(), List.of("synthesis/s"), List.of()));

        WikiLintReport report = lint.runLint(kbId);
        WikiLintIssue issue = findIssue(report, WikiLintIssue.MISSING_CROSS_REF, "synthesis/s");
        assertThat(issue).isNotNull();
        assertThat(issue.getTargetSlug()).isEqualTo("entity/mongodb");
        assertThat(issue.getSeverity()).isEqualTo(WikiLintIssue.SEVERITY_INFO);
        assertThat(issue.isAutoFixable()).isFalse();
        assertThat(issue.getDescription())
                .isEqualTo("Page 'Notes' mentions 'MongoDB' but doesn't link to [[entity/mongodb]]");
    }

    /** 已经链过去的就不再报 missing_cross_ref */
    @Test
    void linkedMentionIsNotReported() {
        final String kbId = "kb-cross-linked";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "synthesis/s", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS,
                PADDING + " We use [[entity/mongodb]] heavily.",
                List.of("entity/mongodb"), List.of("entity/mongodb"), List.of()));
        repo.create(page(kbId, "entity/mongodb", "MongoDB", WikiConstants.PAGE_TYPE_ENTITY,
                PADDING, List.of(), List.of("synthesis/s"), List.of()));

        WikiLintReport report = lint.runLint(kbId);
        assertThat(findIssue(report, WikiLintIssue.MISSING_CROSS_REF, "synthesis/s")).isNull();
    }

    /** 摘要与计数：{@code "Found N issues: E errors, W warnings, S suggestions."} */
    @Test
    void summaryCountsBySeverity() {
        final String kbId = "kb-summary";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "synthesis/s", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS,
                PADDING + " See [[entity/ghost]].",
                List.of("entity/ghost"), List.of(), List.of()));
        repo.create(page(kbId, "entity/lonely", "Lonely", WikiConstants.PAGE_TYPE_ENTITY,
                PADDING, List.of(), List.of(), List.of()));

        WikiLintReport report = lint.runLint(kbId);
        int errors = countSeverity(report, WikiLintIssue.SEVERITY_ERROR);
        int warnings = countSeverity(report, WikiLintIssue.SEVERITY_WARNING);
        int infos = countSeverity(report, WikiLintIssue.SEVERITY_INFO);
        assertThat(errors).isEqualTo(1);
        assertThat(warnings).isEqualTo(2);
        assertThat(infos).isZero();
        assertThat(report.getSummary()).isEqualTo("Found " + report.getIssues().size()
                + " issues: " + errors + " errors, " + warnings + " warnings, "
                + infos + " suggestions.");

        // 打分（对照 Go L274-306）：
        //   孤儿 2/2 = 100% > 50 → -25
        //   死链 1 条 × 5 → -5
        //   总链接数 1 ≠ 0 → 不扣 -15
        //   空内容 0 条 → 不扣
        assertThat(report.getHealthScore()).isEqualTo(100 - 25 - 5);
    }

    /** 健康分不为负；页数为 0 时不扣分 */
    @Test
    void healthScoreFloorAndEmptyKb() {
        final String kbId = "kb-zero";
        insertKb(kbId, 1L, true);
        WikiLintReport report = lint.runLint(kbId);
        assertThat(report.getHealthScore()).isEqualTo(100);
        assertThat(report.getStats().getTotalPages()).isZero();
    }

    // ──────────────────────── AutoFix ────────────────────────

    /** AutoFix 死链：{@code [[broken-slug]]} 换成纯文本，引用文字保住 */
    @Test
    void autoFixRewritesBrokenLinkToPlainText() {
        final String kbId = "kb-fix-broken";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "synthesis/s", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS,
                PADDING + " See [[entity/ghost]] here.",
                List.of("entity/ghost"), List.of(), List.of()));

        int fixed = lint.autoFix(kbId);
        assertThat(fixed).isEqualTo(1);

        WikiPage after = repo.getBySlug(kbId, "synthesis/s");
        assertThat(after.getContent()).contains("See entity/ghost here.");
        assertThat(after.getContent()).doesNotContain("[[entity/ghost]]");
        // 机器侧链接修饰不动版本号
        assertThat(after.getVersion()).isEqualTo(1);
    }

    /** AutoFix 空内容：归档而不是删除，且不碰索引页 */
    @Test
    void autoFixArchivesEmptyPageButNotIndex() {
        final String kbId = "kb-fix-empty";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "entity/tiny", "Tiny", WikiConstants.PAGE_TYPE_ENTITY,
                "短", List.of(), List.of(), List.of()));
        // 短正文的索引页：AutoFix 必须跳过它
        repo.create(page(kbId, "index", "Index", WikiConstants.PAGE_TYPE_INDEX,
                "短", List.of(), List.of(), List.of()));

        int fixed = lint.autoFix(kbId);
        assertThat(fixed).isEqualTo(1);

        assertThat(repo.getBySlug(kbId, "entity/tiny").getStatus())
                .isEqualTo(WikiConstants.STATUS_ARCHIVED);
        assertThat(repo.getBySlug(kbId, "index").getStatus())
                .isEqualTo(WikiConstants.STATUS_PUBLISHED);
    }

    /** AutoFix 陈引：页面没有别的存活来源 → 直接删掉（留孤儿摘要页更糟） */
    @Test
    void autoFixStaleRefDeletesPageWithNoLiveSources() {
        final String kbId = "kb-fix-stale-del";
        insertKb(kbId, 1L, true);
        repo.create(page(kbId, "summary/a", "A Summary", WikiConstants.PAGE_TYPE_SUMMARY,
                PADDING, List.of(), List.of(), List.of("kid-dead|Dead")));

        int fixed = lint.autoFix(kbId);
        assertThat(fixed).isEqualTo(1);
        assertThatThrownBy(() -> repo.getBySlug(kbId, "summary/a"))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    /** AutoFix 陈引：还有别的存活来源 → 只摘掉死引用，页面留下（不动版本号） */
    @Test
    void autoFixStaleRefStripsRefAndKeepsPage() {
        final String kbId = "kb-fix-stale-strip";
        insertKb(kbId, 1L, true);
        insertKnowledge(kbId, "kid-live", null);
        repo.create(page(kbId, "summary/a", "A Summary", WikiConstants.PAGE_TYPE_SUMMARY,
                PADDING, List.of(), List.of(), List.of("kid-dead|Dead", "kid-live|Live")));

        int fixed = lint.autoFix(kbId);
        assertThat(fixed).isEqualTo(1);

        WikiPage after = repo.getBySlug(kbId, "summary/a");
        assertThat(after.getSourceRefs()).containsExactly("kid-live|Live");
        assertThat(after.getVersion()).as("UpdatePageMeta 不动版本号").isEqualTo(1);
    }

    /** 没有可修问题时不调用任何写路径（返回 0） */
    @Test
    void autoFixOnHealthyWikiIsNoop() {
        final String kbId = "kb-fix-healthy";
        insertKb(kbId, 1L, true);
        seedHealthyWiki(kbId);
        assertThat(lint.autoFix(kbId)).isZero();
    }

    // ──────────────────────── 夹具 / 工具 ────────────────────────

    /**
     * 造一个互相链接、正文充足、无陈引用的健康 wiki：
     * <pre>
     *   synthesis/notes -> entity/alpha, entity/beta
     *   entity/alpha    -> synthesis/notes
     *   entity/beta     -> （无出链，但被 notes 链到）
     * </pre>
     */
    private void seedHealthyWiki(String kbId) {
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(page(kbId, "synthesis/notes", "Notes", WikiConstants.PAGE_TYPE_SYNTHESIS,
                PADDING + " See [[entity/alpha]] and [[entity/beta]].",
                List.of("entity/alpha", "entity/beta"), List.of("entity/alpha"), List.of()));
        repo.create(page(kbId, "entity/alpha", "Alpha", WikiConstants.PAGE_TYPE_ENTITY,
                "Alpha page. " + PADDING + " Back to [[synthesis/notes]].",
                List.of("synthesis/notes"), List.of("synthesis/notes"), List.of()));
        repo.create(page(kbId, "entity/beta", "Beta", WikiConstants.PAGE_TYPE_ENTITY,
                "Beta page. " + PADDING,
                List.of(), List.of("synthesis/notes"), List.of()));
    }

    private static WikiLintIssue findIssue(WikiLintReport report, String type, String pageSlug) {
        if (report.getIssues() == null) {
            return null;
        }
        for (WikiLintIssue issue : report.getIssues()) {
            if (issue.getType().equals(type) && issue.getPageSlug().equals(pageSlug)) {
                return issue;
            }
        }
        return null;
    }

    private static List<WikiLintIssue> issuesOfType(WikiLintReport report, String type) {
        List<WikiLintIssue> out = new ArrayList<>();
        if (report.getIssues() != null) {
            for (WikiLintIssue issue : report.getIssues()) {
                if (issue.getType().equals(type)) {
                    out.add(issue);
                }
            }
        }
        return out;
    }

    private static int countSeverity(WikiLintReport report, String severity) {
        int n = 0;
        if (report.getIssues() != null) {
            for (WikiLintIssue issue : report.getIssues()) {
                if (issue.getSeverity().equals(severity)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static WikiPage page(String kbId, String slug, String title, String pageType,
                                 String content, List<String> outLinks, List<String> inLinks,
                                 List<String> sourceRefs) {
        WikiPage p = new WikiPage();
        p.setId(java.util.UUID.randomUUID().toString());
        p.setTenantId(1L);
        p.setKnowledgeBaseId(kbId);
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType(pageType);
        p.setStatus(WikiConstants.STATUS_PUBLISHED);
        p.setContent(content);
        p.setOutLinks(new ArrayList<>(outLinks));
        p.setInLinks(new ArrayList<>(inLinks));
        p.setSourceRefs(new ArrayList<>(sourceRefs));
        p.setVersion(1);
        OffsetDateTime now = OffsetDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    private void insertKb(String kbId, long tenantId, boolean wikiEnabled) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, indexing_strategy) "
                        + "VALUES (?, ?, ?, 'document', ?)",
                kbId, kbId, tenantId,
                "{\"vectorEnabled\":false,\"keywordEnabled\":false,\"wikiEnabled\":"
                        + wikiEnabled + ",\"graphEnabled\":false}");
    }

    /** {@code deletedAt} 非空表示软删——陈旧引用检测的目标 */
    private void insertKnowledge(String kbId, String kid, OffsetDateTime deletedAt) {
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                        + "source, deleted_at) VALUES (?, 1, ?, 'file', ?, 'file', ?)",
                kid, kbId, kid, deletedAt);
    }
}
