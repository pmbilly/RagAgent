package com.ragagent.datasource.connector.yuque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 语雀连接器的对等测试（逐条对照 Go {@code yuque/connector_test.go}）。
 *
 * <h2>限速注入 0</h2>
 * <p>生产的 300ms 文档限速与 2s/4s/8s 退避在这里全部注入 0：
 * {@code new YuqueConnector(YuqueRetryPolicy.immediate(), Duration.ZERO)}。
 * 否则每个含 N 篇文档的用例都要等 N×300ms，整套对等测试会从毫秒级涨到分钟级
 * ——这是任务书要求把两者抽成可注入参数的唯一理由。</p>
 */
class YuqueConnectorTest {

    @BeforeAll
    static void allowLoopback() {
        FakeYuque.allowLoopback();
    }

    @AfterAll
    static void restoreSsrf() {
        FakeYuque.restoreSsrf();
    }

    /** 生产语义不变、等待归零的连接器。 */
    private static YuqueConnector connector() {
        return new YuqueConnector(YuqueRetryPolicy.immediate(), Duration.ZERO);
    }

    // ── 基础 ─────────────────────────────────────────────────────────────

    @Test
    void typeIsYuque() {
        assertThat(connector().type()).isEqualTo("yuque");
    }

    @Test
    void resolveResourceAncestorsIsEmpty() {
        assertThat(connector().resolveResourceAncestors(null, List.of("10"))).isEmpty();
    }

    @Test
    void validateSucceeds() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            connector().validate(f.config());
        }
    }

    @Test
    void validateFailsOn401() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 401, Map.of("message", "Unauthorized"));
            assertThatThrownBy(() -> connector().validate(f.config()))
                    .hasMessageContaining("yuque connection failed")
                    .hasMessageContaining("invalid credentials");
        }
    }

    // ── ListResources ───────────────────────────────────────────────────

    /** 对照 Go {@code TestConnector_ListResources_Aggregates}：个人 + 两个团队。 */
    @Test
    void listResourcesAggregatesPersonalAndTeams() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 200, FakeYuque.data(user(1, "alice", "User")));
            f.handleJson("/api/v2/users/alice/repos", 200, FakeYuque.data(List.of(
                    repo(10, "personal", "Personal", "alice/personal", 1))));
            f.handleJson("/api/v2/users/1/groups", 200, FakeYuque.data(List.of(
                    group(100, "team-a"), group(101, "team-b"))));
            f.handleJson("/api/v2/groups/team-a/repos", 200, FakeYuque.data(List.of(
                    repo(20, "ab", "A Book", "team-a/ab", 1))));
            f.handleJson("/api/v2/groups/team-b/repos", 200, FakeYuque.data(List.of(
                    repo(30, "bb", "B Book", "team-b/bb", 0))));

            List<Resource> resources = connector().listResources(f.config(), "");

            assertThat(resources).hasSize(3);
            Resource ab = resources.stream().filter(r -> r.getName().equals("A Book"))
                    .findFirst().orElseThrow();
            assertThat(ab.getExternalId()).isEqualTo("20");
            assertThat(ab.getDescription()).isEqualTo("team-a/ab");
            assertThat(ab.getType()).isEqualTo("book");
            assertThat(ab.getUrl()).isEqualTo(f.baseUrl() + "/team-a/ab");
            assertThat(ab.getMetadata()).containsEntry("book_type", "Book")
                    .containsEntry("public", 1);
            // 未提供 updated_at 的仓库 → Go 零值时间
            assertThat(GoTimeSerializer.isGoZero(ab.getModifiedAt())).isTrue();
        }
    }

    /** 对照 Go {@code TestConnector_ListResources_DedupByID}：同一个仓库在两个来源里只出一条。 */
    @Test
    void listResourcesDedupesById() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 200, FakeYuque.data(user(1, "alice", "User")));
            f.handleJson("/api/v2/users/alice/repos", 200, FakeYuque.data(List.of(
                    repo(99, "shared", "shared", "alice/shared", 1))));
            f.handleJson("/api/v2/users/1/groups", 200, FakeYuque.data(List.of(group(200, "team-x"))));
            f.handleJson("/api/v2/groups/team-x/repos", 200, FakeYuque.data(List.of(
                    repo(99, "shared", "shared", "alice/shared", 1))));

            assertThat(connector().listResources(f.config(), "")).hasSize(1);
        }
    }

    /**
     * 对照 Go {@code TestConnector_ListResources_ContinuesOnGroupFailure}：
     * 某个团队 403 只跳过它，其余照常。
     */
    @Test
    void listResourcesContinuesOnGroupFailure() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 200, FakeYuque.data(user(1, "alice", "User")));
            f.handleJson("/api/v2/users/alice/repos", 200, FakeYuque.data(List.of(
                    repo(10, "me", "Mine", "alice/me", 1))));
            f.handleJson("/api/v2/users/1/groups", 200, FakeYuque.data(List.of(
                    group(100, "team-ok"), group(101, "team-forbidden"))));
            f.handleJson("/api/v2/groups/team-ok/repos", 200, FakeYuque.data(List.of(
                    repo(20, "ok", "OK Book", "team-ok/ok", 1))));
            f.handleJson("/api/v2/groups/team-forbidden/repos", 403, Map.of("message", "forbidden"));

            List<Resource> resources = connector().listResources(f.config(), "");

            assertThat(resources).hasSize(2);
            assertThat(resources).extracting(Resource::getName)
                    .containsExactlyInAnyOrder("Mine", "OK Book");
        }
    }

    /**
     * 用户没加入任何团队时语雀返回 **404**——照抄 Go：当成"没有团队"继续，
     * 个人仓库照常返回。
     */
    @Test
    void listResourcesTreatsGroup404AsNoTeams() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 200, FakeYuque.data(user(1, "alice", "User")));
            f.handleJson("/api/v2/users/alice/repos", 200, FakeYuque.data(List.of(
                    repo(10, "me", "Mine", "alice/me", 1))));
            f.handleJson("/api/v2/users/1/groups", 404, Map.of("message", "not found"));

            List<Resource> resources = connector().listResources(f.config(), "");

            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).getName()).isEqualTo("Mine");
        }
    }

    /** 团队令牌（{@code type=Group}）直接列本团队仓库，不查个人仓库。 */
    @Test
    void listResourcesTeamTokenPath() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 200, FakeYuque.data(user(5, "team-login", "Group")));
            f.handleJson("/api/v2/groups/team-login/repos", 200, FakeYuque.data(List.of(
                    repo(50, "tb", "Team Book", "team-login/tb", 1))));

            List<Resource> resources = connector().listResources(f.config(), "");

            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).getExternalId()).isEqualTo("50");
            assertThat(f.callCount("/api/v2/users/team-login/repos"))
                    .as("personal repos must not be queried for a team token")
                    .isZero();
            assertThat(f.callCount("/api/v2/users/5/groups")).isZero();
        }
    }

    /** ExternalID 的排序是**字符串**序：{@code "10"} 在 {@code "9"} 之前（照抄 Go）。 */
    @Test
    void listResourcesSortsByStringExternalId() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/user", 200, FakeYuque.data(user(1, "alice", "User")));
            f.handleJson("/api/v2/users/alice/repos", 200, FakeYuque.data(List.of(
                    repo(9, "nine", "Nine", "alice/nine", 1),
                    repo(10, "ten", "Ten", "alice/ten", 1))));

            List<Resource> resources = connector().listResources(f.config(), "");
            assertThat(resources).extracting(Resource::getExternalId).containsExactly("10", "9");
        }
    }

    @Test
    void listResourcesChildrenAreEmpty() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            assertThat(connector().listResources(f.config(), "10")).isEmpty();
        }
    }

    // ── FetchAll：过滤 + 正文 ────────────────────────────────────────────

    /** 对照 Go {@code TestConnector_FetchAll_Markdown}（草稿被过滤）。 */
    @Test
    void fetchAllSkipsDrafts() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/7/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(101, "Doc", "1", "Hello", "hello", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(102, "Doc", "0", "Draft", "draft", "2026-04-20T11:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/101", 200, FakeYuque.data(
                    FakeYuque.docDetail(101, "Hello", "markdown", "# Hello\n\nworld",
                            "2026-04-20T10:00:00Z", "alice/demo")));

            List<FetchedItem> items = connector().fetchAll(f.config("7"), List.of("7"));

            assertThat(items).hasSize(1);
            FetchedItem it = items.get(0);
            assertThat(it.getExternalId()).isEqualTo("101");
            assertThat(it.getTitle()).isEqualTo("Hello");
            assertThat(new String(it.getContent(), StandardCharsets.UTF_8)).isEqualTo("# Hello\n\nworld");
            assertThat(it.getContentType()).isEqualTo("text/markdown");
            assertThat(it.getFileName()).isEqualTo("Hello.md");
            assertThat(it.getUrl()).isEqualTo(f.baseUrl() + "/alice/demo/hello");
            assertThat(it.getMetadata().get("channel")).isEqualTo("yuque");
            assertThat(it.getMetadata().get("doc_id")).isEqualTo("101");
            assertThat(it.getMetadata().get("book_id")).isEqualTo("7");
            assertThat(it.getMetadata().get("slug")).isEqualTo("hello");
            assertThat(it.getMetadata().get("creator")).isEqualTo("7");
            assertThat(it.getMetadata().get("word_count")).isEqualTo("42");
            assertThat(it.getUpdatedAt().toInstant())
                    .isEqualTo(java.time.Instant.parse("2026-04-20T10:00:00Z"));
            assertThat(f.callCount("/api/v2/repos/docs/102"))
                    .as("draft must not be fetched")
                    .isZero();
        }
    }

    /** 对照 Go {@code TestConnector_FetchAll_SkipsNonDocTypes}。 */
    @Test
    void fetchAllSkipsNonDocTypes() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/8/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(201, "Sheet", "1", "S", "s", ""),
                    FakeYuque.doc(202, "Board", "1", "B", "b", ""),
                    FakeYuque.doc(203, "Thread", "1", "T", "t", ""),
                    FakeYuque.doc(204, "Doc", "1", "D", "d", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/204", 200, FakeYuque.data(
                    FakeYuque.docDetail(204, "D", "markdown", "text",
                            "2026-04-20T10:00:00Z", "alice/d")));

            List<FetchedItem> items = connector().fetchAll(f.config("8"), List.of("8"));
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getExternalId()).isEqualTo("204");
        }
    }

    /** 空 type / 空 status 视为可接受（Go 的前向兼容分支）。 */
    @Test
    void fetchAllAcceptsEmptyTypeAndStatus() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", 301);
            doc.put("title", "No type");
            doc.put("slug", "nt");
            doc.put("content_updated_at", "2026-04-20T10:00:00Z");
            f.handleJson("/api/v2/repos/9/docs", 200, FakeYuque.docList(List.of(doc)));
            f.handleJson("/api/v2/repos/docs/301", 200, FakeYuque.data(
                    FakeYuque.docDetail(301, "No type", "markdown", "body",
                            "2026-04-20T10:00:00Z", "alice/nt")));

            List<FetchedItem> items = connector().fetchAll(f.config("9"), List.of("9"));
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getExternalId()).isEqualTo("301");
        }
    }

    /** 对照 Go {@code TestConnector_FetchAll_DocDetailError_EmitsPlaceholder}。 */
    @Test
    void docDetailErrorEmitsPlaceholder() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/9/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(301, "Doc", "1", "Broken", "broken", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(302, "Doc", "1", "OK", "ok", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/301", 400, Map.of("message", "broken"));
            f.handleJson("/api/v2/repos/docs/302", 200, FakeYuque.data(
                    FakeYuque.docDetail(302, "OK", "markdown", "ok",
                            "2026-04-20T10:00:00Z", "alice/ok")));

            List<FetchedItem> items = connector().fetchAll(f.config("9"), List.of("9"));

            assertThat(items).as("FetchAll must not abort the batch on a single detail error")
                    .hasSize(2);
            FetchedItem placeholder = items.stream()
                    .filter(i -> i.getMetadata() != null && i.getMetadata().get("error") != null)
                    .findFirst().orElseThrow();
            assertThat(placeholder.getExternalId()).isEqualTo("301");
            assertThat(placeholder.getTitle()).isEqualTo("Broken");
            assertThat(placeholder.getMetadata().get("channel")).isEqualTo("yuque");
            assertThat(placeholder.getMetadata().get("doc_id")).isEqualTo("301");
            assertThat(placeholder.getMetadata().get("book_id")).isEqualTo("9");
            assertThat(placeholder.getMetadata().get("slug")).isEqualTo("broken");
            assertThat(placeholder.getContent()).isNull();
            assertThat(placeholder.getMetadata().get("error")).contains("status=400");
        }
    }

    /** 对照 Go {@code TestConnector_FetchAll_LakeFormatIngestedAsMarkdown}。 */
    @Test
    void lakeFormatIsIngestedAsMarkdown() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/12/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(401, "Doc", "1", "Lake Doc", "lake", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(402, "Doc", "1", "MD Doc", "md", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/401", 200, FakeYuque.data(
                    FakeYuque.docDetail(401, "Lake Doc", "lake", "# lake body as markdown",
                            "2026-04-20T10:00:00Z", "alice/lake")));
            f.handleJson("/api/v2/repos/docs/402", 200, FakeYuque.data(
                    FakeYuque.docDetail(402, "MD Doc", "markdown", "# MD",
                            "2026-04-20T10:00:00Z", "alice/md")));

            List<FetchedItem> items = connector().fetchAll(f.config("12"), List.of("12"));

            assertThat(items).hasSize(2);
            Map<String, String> bodies = new LinkedHashMap<>();
            for (FetchedItem it : items) {
                bodies.put(it.getExternalId(), new String(it.getContent(), StandardCharsets.UTF_8));
                assertThat(it.getContentType()).isEqualTo("text/markdown");
            }
            assertThat(bodies).containsEntry("401", "# lake body as markdown")
                    .containsEntry("402", "# MD");
        }
    }

    /** 对照 Go {@code TestConnector_FetchAll_SkipsUnsupportedFormats}。 */
    @Test
    void unsupportedFormatEmitsPlaceholderWithSkipReason() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/13/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(501, "Doc", "1", "HTML Doc", "h", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(502, "Doc", "1", "OK Doc", "ok", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/501", 200, FakeYuque.data(
                    FakeYuque.docDetail(501, "HTML Doc", "html", "<p>raw html</p>",
                            "2026-04-20T10:00:00Z", "alice/h")));
            f.handleJson("/api/v2/repos/docs/502", 200, FakeYuque.data(
                    FakeYuque.docDetail(502, "OK Doc", "lake", "# ok",
                            "2026-04-20T10:00:00Z", "alice/ok")));

            List<FetchedItem> items = connector().fetchAll(f.config("13"), List.of("13"));
            assertThat(items).hasSize(2);

            FetchedItem html = items.stream().filter(i -> i.getExternalId().equals("501"))
                    .findFirst().orElseThrow();
            assertThat(html.getMetadata().get("skip_reason")).isEqualTo("unsupported format: html");
            assertThat(html.getContent()).isNull();

            FetchedItem ok = items.stream().filter(i -> i.getExternalId().equals("502"))
                    .findFirst().orElseThrow();
            assertThat(new String(ok.getContent(), StandardCharsets.UTF_8)).isEqualTo("# ok");
        }
    }

    // ── 增量：跳过、变更、删除 ───────────────────────────────────────────

    @Test
    void incrementalFirstSyncReturnsEverything() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(2, "Doc", "1", "B", "b", "2026-04-20T11:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));
            f.handleJson("/api/v2/repos/docs/2", 200, FakeYuque.data(
                    FakeYuque.docDetail(2, "B", "markdown", "b", "2026-04-20T11:00:00Z", "alice/b")));

            Connector.FetchIncrementalResult result =
                    connector().fetchIncremental(f.config("10"), null);

            assertThat(result.items()).hasSize(2);
            assertThat(result.cursor()).isNotNull();
            assertThat(result.cursor().getConnectorCursor()).containsKey("book_doc_times");
        }
    }

    /** 对照 Go {@code TestConnector_FetchIncremental_NoChanges}：内容时间没变 → 0 条且不拉详情。 */
    @Test
    void incrementalNoChangesYieldsNothing() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));

            YuqueConnector c = connector();
            DataSourceConfig cfg = f.config("10");
            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            f.resetCalls();

            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            assertThat(second.items()).isEmpty();
            assertThat(f.callCount("/api/v2/repos/docs/1"))
                    .as("unchanged doc must not be re-fetched")
                    .isZero();
        }
    }

    /** 对照 Go {@code TestConnector_FetchIncremental_ReturnsOnlyChanged}。 */
    @Test
    void incrementalReturnsOnlyChanged() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/11/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(2, "Doc", "1", "B", "b", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));
            f.handleJson("/api/v2/repos/docs/2", 200, FakeYuque.data(
                    FakeYuque.docDetail(2, "B", "markdown", "b", "2026-04-20T10:00:00Z", "alice/b")));

            YuqueConnector c = connector();
            DataSourceConfig cfg = f.config("11");
            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);

            f.handleJson("/api/v2/repos/11/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(2, "Doc", "1", "B2", "b", "2026-04-20T12:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/2", 200, FakeYuque.data(
                    FakeYuque.docDetail(2, "B2", "markdown", "b2 updated",
                            "2026-04-20T12:00:00Z", "alice/b")));

            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            assertThat(second.items()).hasSize(1);
            assertThat(second.items().get(0).getExternalId()).isEqualTo("2");
            assertThat(new String(second.items().get(0).getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("b2 updated");
        }
    }

    /** 对照 Go {@code TestConnector_FetchIncremental_DetectsDeletion}。 */
    @Test
    void incrementalDetectsDeletion() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(2, "Doc", "1", "B", "b", "2026-04-20T11:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));
            f.handleJson("/api/v2/repos/docs/2", 200, FakeYuque.data(
                    FakeYuque.docDetail(2, "B", "markdown", "b", "2026-04-20T11:00:00Z", "alice/b")));

            YuqueConnector c = connector();
            DataSourceConfig cfg = f.config("10");
            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);

            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"))));

            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            List<FetchedItem> tombstones = second.items().stream()
                    .filter(FetchedItem::isDeleted).toList();
            assertThat(tombstones).hasSize(1);
            assertThat(tombstones.get(0).getExternalId()).isEqualTo("2");
            assertThat(tombstones.get(0).getSourceResourceId()).isEqualTo("10");
        }
    }

    /** 增量跳过时不会误发删除墓碑（文档还在，只是内容没变）。 */
    @Test
    void incrementalDoesNotTombstoneUnchangedDocs() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));

            YuqueConnector c = connector();
            DataSourceConfig cfg = f.config("10");
            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            assertThat(second.items()).noneMatch(FetchedItem::isDeleted);
        }
    }

    /**
     * 照抄 Go 的 map 零值比较：**没有** {@code content_updated_at} 的文档在增量同步里
     * 会被判成"未变"而跳过（Go 写的是 {@code prevTimes[docIDStr] == d.ContentUpdatedAt}，
     * 键缺失取到 ""），而**全量**同步照常抓。
     */
    @Test
    void incrementalSkipsDocsWithoutContentUpdatedAt() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            Map<String, Object> noTimestamp = new LinkedHashMap<>();
            noTimestamp.put("id", 1);
            noTimestamp.put("type", "Doc");
            noTimestamp.put("status", "1");
            noTimestamp.put("title", "NoTime");
            noTimestamp.put("slug", "nt");
            Map<String, Object> withTimestamp =
                    FakeYuque.doc(2, "Doc", "1", "B", "b", "2026-04-20T10:00:00Z");

            f.handleJson("/api/v2/repos/10/docs", 200,
                    FakeYuque.docList(List.of(noTimestamp, withTimestamp)));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "NoTime", "markdown", "body", "", "alice/nt")));
            f.handleJson("/api/v2/repos/docs/2", 200, FakeYuque.data(
                    FakeYuque.docDetail(2, "B", "markdown", "b",
                            "2026-04-20T10:00:00Z", "alice/b")));

            YuqueConnector c = connector();
            DataSourceConfig cfg = f.config("10");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            assertThat(first.items()).extracting(FetchedItem::getExternalId).containsExactly("1", "2");

            // 第二轮：两篇都没变 → 0 条；尤其"没有时间戳"的那篇不因"键缺失 == ''"被抓出来。
            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());
            assertThat(second.items()).isEmpty();

            // 但全量同步照抓（不受增量比较影响）。
            f.resetCalls();
            List<FetchedItem> full = connector().fetchAll(cfg, List.of("10"));
            assertThat(full).extracting(FetchedItem::getExternalId).containsExactly("1", "2");
        }
    }

    /** 全量同步不产生 cursor（对照 Go 的 {@code if !incremental { return out, nil, nil }}）。 */
    @Test
    void fetchAllHasNoCursor() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));

            assertThat(connector().fetchAll(f.config("10"), List.of("10"))).hasSize(1);
        }
    }

    // ── 参数校验 ────────────────────────────────────────────────────────

    @Test
    void fetchIncrementalRequiresResourceIds() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            assertThatThrownBy(() -> connector().fetchIncremental(f.config(), null))
                    .hasMessageContaining("no resource IDs (book IDs) configured");
        }
    }

    /** 非法 book id 的文案逐字复刻 Go 的 {@code strconv.ParseInt} 错误。 */
    @Test
    void invalidBookIdReplicatesGoParseIntMessage() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            assertThatThrownBy(() -> connector().fetchAll(f.config("abc"), List.of("abc")))
                    .hasMessageContaining("invalid book id \"abc\"")
                    .hasMessageContaining("strconv.ParseInt: parsing \"abc\": invalid syntax");
        }
    }

    @Test
    void parseRejectsMissingApiToken() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            DataSourceConfig cfg = f.config();
            cfg.getCredentials().put("api_token", "");
            assertThatThrownBy(() -> connector().validate(cfg))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("api_token");
        }
    }

    /** 对照 Go {@code TestParseYuqueConfig} 的 nil config 档。 */
    @Test
    void parseRejectsNilConfig() {
        assertThatThrownBy(() -> YuqueConfig.parse(null))
                .isInstanceOf(ConnectorException.InvalidConfig.class);
    }

    /** 默认 base_url 只有作为**字符串**被断言（不写真实公网域名，见约定 §7.5 第 7 条）。 */
    @Test
    void defaultBaseUrlIsThePublicYuqueHost() {
        YuqueConfig cfg = new YuqueConfig();
        assertThat(cfg.baseURL()).isEqualTo("https://www.yuque.com");
    }

    // ── 跳过样本（sampleSkipType / sampleSkipDraft） ─────────────────────

    /**
     * 对照 Go 的日志行：{@code sampleSkipType} / {@code sampleSkipDraft} 只出现在
     * {@code logger.Infof} 里（"id=%d type=%q title=%q" 的第一条样本）。
     * 它们是排障信息而不是控制流，所以这里用日志捕获来钉住。
     */
    @Test
    void skipSamplesAreLogged() throws Exception {
        Logger logger = YuqueClientTest.logbackLoggerOrSkip(YuqueConnector.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/8/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(201, "Sheet", "1", "S", "s", ""),
                    FakeYuque.doc(202, "Sheet", "1", "S2", "s2", ""),
                    FakeYuque.doc(203, "Doc", "0", "Draft", "draft", "2026-04-20T10:00:00Z"),
                    FakeYuque.doc(204, "Doc", "1", "D", "d", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/204", 200, FakeYuque.data(
                    FakeYuque.docDetail(204, "D", "markdown", "text",
                            "2026-04-20T10:00:00Z", "alice/d")));

            connector().fetchAll(f.config("8"), List.of("8"));

            String out = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);

            // 第一条非 Doc 的样本与第一条草稿的样本，逐字对照 Go 的 fmt.Sprintf。
            assertThat(out).contains("skipped_non_doc=2")
                    .contains("skipped_draft=1")
                    .contains("non_doc_sample={id=201 type=\"Sheet\" title=\"S\"}")
                    .contains("draft_sample={id=203 status=\"0\" title=\"Draft\"}");
            assertThat(out).as("only the first sample is recorded")
                    .doesNotContain("id=202");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private static Map<String, Object> user(long id, String login, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("login", login);
        out.put("name", login);
        out.put("type", type);
        return out;
    }

    private static Map<String, Object> group(long id, String login) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("login", login);
        out.put("name", login);
        return out;
    }

    private static Map<String, Object> repo(long id, String slug, String name,
                                            String namespace, int publicFlag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("slug", slug);
        out.put("name", name);
        out.put("type", "Book");
        out.put("namespace", namespace);
        out.put("public", publicFlag);
        return out;
    }

    /** 游标的 {@code book_doc_times} 形状（JSON 往返后是 Map<String,Object>）。 */
    @Test
    @SuppressWarnings("unchecked")
    void cursorCarriesBookDocTimes() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(1, "Doc", "1", "A", "a", "2026-04-20T10:00:00Z"))));
            f.handleJson("/api/v2/repos/docs/1", 200, FakeYuque.data(
                    FakeYuque.docDetail(1, "A", "markdown", "a", "2026-04-20T10:00:00Z", "alice/a")));

            SyncCursor cursor = connector().fetchIncremental(f.config("10"), null).cursor();
            Object times = cursor.getConnectorCursor().get("book_doc_times");
            assertThat(times).isInstanceOf(Map.class);
            Map<String, Object> inner = (Map<String, Object>) ((Map<String, Object>) times).get("10");
            assertThat(inner).containsEntry("1", "2026-04-20T10:00:00Z");
        }
    }
}
