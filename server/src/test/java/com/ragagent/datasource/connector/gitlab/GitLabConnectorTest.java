package com.ragagent.datasource.connector.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 连接器主体：{@code FetchStream} / {@code FetchIncremental} / {@code FetchAll} /
 * {@code ListResources} 与三个纯函数（对照 Go {@code connector.go} 与
 * {@code client_test.go} 的 {@code TestFetchStreamFiltersUnsupportedFilesAndCheckpointsProjects}、
 * {@code TestFetchIncrementalSyncsMultipleProjects}、{@code TestIsSupportedFile}）。
 */
class GitLabConnectorTest {

    @BeforeAll
    static void allowLocalServer() {
        GitLabServerStub.allowLocalServer();
    }

    @AfterAll
    static void restoreSsrfGuard() {
        GitLabServerStub.restoreSsrfGuard();
    }

    // ── 纯函数 ──────────────────────────────────────────────────────────

    /** 对照 Go {@code TestIsSupportedFile} + Go 实录扩充。 */
    @Test
    void isSupportedFileMatchesGo() {
        assertThat(GitLabConnector.isSupportedFile("docs/guide.MD")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("docs/guide.mdx")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("report.pdf")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("src/main.go")).isFalse();
        assertThat(GitLabConnector.isSupportedFile("archive.tar.gz")).isFalse();
        assertThat(GitLabConnector.isSupportedFile("LICENSE")).isFalse();

        // Go 实录：path.Ext 是"最后一个点，且不能越过 /"
        assertThat(GitLabConnector.isSupportedFile(".hidden")).isFalse();
        assertThat(GitLabConnector.isSupportedFile(".md")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("dir/.md")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("a.")).isFalse();
        assertThat(GitLabConnector.isSupportedFile("a.PDF")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("x.Mp3")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("a.MARKDOWN")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("b.MHTML")).isTrue();
        assertThat(GitLabConnector.isSupportedFile("d.jpeg")).isTrue();
        // 扩展名表里没有 .go / .exe / .tar 的兄弟
        assertThat(GitLabConnector.SUPPORTED_FILE_EXTENSIONS).hasSize(26);
    }

    /** 对照 Go {@code inScope}（Go 实录）：根名后面必须跟 {@code "/"} 才算子路径。 */
    @Test
    void inScopeMatchesGo() {
        assertThat(GitLabConnector.inScope("a/b.md", null)).isTrue();
        assertThat(GitLabConnector.inScope("a/b.md", List.of())).isTrue();
        assertThat(GitLabConnector.inScope("a/b.md", List.of("a"))).isTrue();
        assertThat(GitLabConnector.inScope("a/b.md", List.of("a/b.md"))).isTrue();
        assertThat(GitLabConnector.inScope("a", List.of("a"))).isTrue();
        // "ab" 不是 "a" 的子路径；"a/b" 也不是 "a/b.md" 的子路径
        assertThat(GitLabConnector.inScope("a/b.md", List.of("ab"))).isFalse();
        assertThat(GitLabConnector.inScope("a/b.md", List.of("a/b"))).isFalse();
        assertThat(GitLabConnector.inScope("ab/c", List.of("a"))).isFalse();
        assertThat(GitLabConnector.inScope("a/b.md", List.of("", "z"))).isFalse();
    }

    /** 对照 Go {@code splitResourceID}（Go 实录）。 */
    @Test
    void splitResourceIdMatchesGo() {
        assertThat(GitLabConnector.splitResourceId("42")).containsExactly("42", "");
        assertThat(GitLabConnector.splitResourceId("42:docs")).containsExactly("42", "docs");
        assertThat(GitLabConnector.splitResourceId("42:a:b")).containsExactly("42", "a:b");
        assertThat(GitLabConnector.splitResourceId(":x")).containsExactly("", "x");
        assertThat(GitLabConnector.splitResourceId("42:")).containsExactly("42", "");
    }

    // ── FetchStream ─────────────────────────────────────────────────────

    /**
     * 对照 Go {@code TestFetchStreamFiltersUnsupportedFilesAndCheckpointsProjects}：
     * 未在受支持扩展名表里的 blob 不 emit，也<b>不会</b>被读正文。
     */
    @Test
    void fetchStreamFiltersUnsupportedFilesAndCheckpointsProjects() throws IOException {
        List<String> rawRequests = new ArrayList<>();
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200,
                        "{\"id\":1,\"name\":\"docs\",\"path_with_namespace\":\"group/docs\","
                                + "\"web_url\":\"https://gitlab.test/group/docs\","
                                + "\"default_branch\":\"main\"}");
                case "/api/v4/projects/1/repository/commits/main" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"commit-1\"}");
                case "/api/v4/projects/1/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"README.md\",\"type\":\"blob\",\"path\":\"README.md\"},"
                                + "{\"name\":\"server.go\",\"type\":\"blob\",\"path\":\"server.go\"},"
                                + "{\"name\":\"payload.exe\",\"type\":\"blob\",\"path\":\"payload.exe\"}]");
                default -> {
                    if (GitLabServerStub.rawPath(exchange).contains("/repository/files/")) {
                        rawRequests.add(GitLabServerStub.rawPath(exchange));
                        GitLabServerStub.text(exchange, 200, "# readme");
                    } else {
                        GitLabServerStub.status(exchange, 404);
                    }
                }
            }
        })) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));
            RecordingHandler handler = new RecordingHandler();

            SyncCursor next = connector.fetchStream(ds, null, handler);

            assertThat(handler.items).hasSize(1);
            assertThat(handler.items.get(0).getFileName()).isEqualTo("docs-main/README.md");
            assertThat(rawRequests).hasSize(1);
            assertThat(rawRequests.get(0)).contains("README%2Emd");

            assertThat(handler.checkpoints).hasSize(1);
            assertThat(next).isNotNull();
            assertThat(projects(next)).containsEntry("1", "commit-1");

            // checkpoint 的 cursor 形状与返回值一致
            assertThat(projects(handler.checkpoints.get(0))).containsEntry("1", "commit-1");
            assertThat(handler.checkpoints.get(0).getConnectorCursor()).containsKey("raw");
        }
    }

    /**
     * {@code previous == ""} 走全树枚举；{@code previous != head} 走 compare，
     * 三支（deleted / renamed / added）都要复刻。
     */
    @Test
    void fetchStreamUsesCompareWhenHeadChanged() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200,
                        "{\"id\":1,\"name\":\"docs\",\"path_with_namespace\":\"group/docs\","
                                + "\"web_url\":\"https://gitlab.test/group/docs\","
                                + "\"default_branch\":\"main\"}");
                case "/api/v4/projects/1/repository/commits/main" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"head-2\"}");
                case "/api/v4/projects/1/repository/compare" -> GitLabServerStub.json(exchange, 200,
                        "{\"diffs\":["
                                + "{\"old_path\":\"gone.md\",\"new_path\":\"gone.md\",\"deleted_file\":true},"
                                + "{\"old_path\":\"old/name.md\",\"new_path\":\"new/name.md\",\"renamed_file\":true},"
                                + "{\"old_path\":\"added.md\",\"new_path\":\"added.md\",\"new_file\":true},"
                                + "{\"old_path\":\"code.go\",\"new_path\":\"code.go\",\"new_file\":true}"
                                + "]}");
                default -> {
                    if (GitLabServerStub.rawPath(exchange).contains("/repository/files/")) {
                        GitLabServerStub.text(exchange, 200, "body");
                    } else {
                        GitLabServerStub.status(exchange, 404);
                    }
                }
            }
        })) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));
            RecordingHandler handler = new RecordingHandler();

            SyncCursor next = connector.fetchStream(ds, cursorWith("1", "old-1"), handler);

            // deleted_file → 一条删除；renamed_file → 老路径一条删除 + 新路径一条新增；
            // new_file → 新增；code.go 不受支持 → 一条都不发
            assertThat(handler.items).hasSize(4);
            assertThat(handler.items.get(0).getExternalId()).endsWith(":1:main:gone.md");
            assertThat(handler.items.get(0).isDeleted()).isTrue();
            assertThat(handler.items.get(1).getExternalId()).endsWith(":1:main:old/name.md");
            assertThat(handler.items.get(1).isDeleted()).isTrue();
            assertThat(handler.items.get(2).getExternalId()).endsWith(":1:main:new/name.md");
            assertThat(handler.items.get(2).isDeleted()).isFalse();
            assertThat(handler.items.get(3).getExternalId()).endsWith(":1:main:added.md");
            assertThat(projects(next)).containsEntry("1", "head-2");
        }
    }

    /** {@code compare_timeout} → 回落整树枚举（不是报错）。 */
    @Test
    void fetchStreamFallsBackToFullTreeOnCompareTimeout() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(compareTimeoutRoute());
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));
            RecordingHandler handler = new RecordingHandler();

            connector.fetchStream(ds, cursorWith("1", "old-1"), handler);

            assertThat(handler.items).hasSize(1);
            assertThat(handler.items.get(0).getFileName()).isEqualTo("docs-main/README.md");
        }
    }

    /** compare 直接失败（历史被改写）→ 同样回落整树枚举。 */
    @Test
    void fetchStreamFallsBackToFullTreeOnCompareFailure() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200,
                        projectJson());
                case "/api/v4/projects/1/repository/commits/main" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"head-2\"}");
                case "/api/v4/projects/1/repository/compare" ->
                        GitLabServerStub.status(exchange, 500);
                case "/api/v4/projects/1/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"README.md\",\"type\":\"blob\",\"path\":\"README.md\"}]");
                default -> {
                    if (GitLabServerStub.rawPath(exchange).contains("/repository/files/")) {
                        GitLabServerStub.text(exchange, 200, "# readme");
                    } else {
                        GitLabServerStub.status(exchange, 404);
                    }
                }
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));
            RecordingHandler handler = new RecordingHandler();

            connector.fetchStream(ds, cursorWith("1", "old-1"), handler);

            assertThat(handler.items).hasSize(1);
        }
    }

    /**
     * commit 没变时<b>什么都不发</b>，但 {@code checkpoint} 仍然被调一次
     * （Go 的 switch 没有 default，但循环体的 checkpoint 在 switch 之外）。
     */
    @Test
    void fetchStreamStillCheckpointsWhenHeadUnchanged() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200, projectJson());
                case "/api/v4/projects/1/repository/commits/main" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"same\"}");
                default -> GitLabServerStub.status(exchange, 404);
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));
            RecordingHandler handler = new RecordingHandler();

            SyncCursor next = connector.fetchStream(ds, cursorWith("1", "same"), handler);

            assertThat(handler.items).isEmpty();
            assertThat(handler.checkpoints).hasSize(1);
            assertThat(projects(next)).containsEntry("1", "same");
        }
    }

    /**
     * {@code FetchStream} 的 {@code next.Projects} <b>先拷贝 prev</b>——
     * 这次配置里没有的项目，其 SHA 不会被抹掉（与 {@code FetchIncremental} 不同）。
     */
    @Test
    void fetchStreamKeepsProjectsFromPreviousCursor() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200, projectJson());
                case "/api/v4/projects/1/repository/commits/main" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"same\"}");
                default -> GitLabServerStub.status(exchange, 404);
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));

            RecordingHandler handler = new RecordingHandler();
            SyncCursor next = connector.fetchStream(ds, cursorWith("1", "same", "9", "other"), handler);

            assertThat(projects(next)).containsEntry("1", "same").containsEntry("9", "other");
            assertThat(projects(handler.checkpoints.get(0)))
                    .containsEntry("1", "same").containsEntry("9", "other");
        }
    }

    // ── FetchIncremental / FetchAll ─────────────────────────────────────

    /** 对照 Go {@code TestFetchIncrementalSyncsMultipleProjects}（含"第二次同步 0 条"）。 */
    @Test
    void fetchIncrementalSyncsMultipleProjects() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200,
                        "{\"id\":1,\"name\":\"project-one\",\"path_with_namespace\":\"group/project-one\","
                                + "\"web_url\":\"https://gitlab.test/group/project-one\","
                                + "\"default_branch\":\"master\"}");
                case "/api/v4/projects/2" -> GitLabServerStub.json(exchange, 200,
                        "{\"id\":2,\"name\":\"project-two\",\"path_with_namespace\":\"group/project-two\","
                                + "\"web_url\":\"https://gitlab.test/group/project-two\","
                                + "\"default_branch\":\"master\"}");
                case "/api/v4/projects/1/repository/commits/master" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"commit-1\"}");
                case "/api/v4/projects/2/repository/commits/master" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"commit-2\"}");
                case "/api/v4/projects/1/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"one.md\",\"type\":\"blob\",\"path\":\"one.md\"}]");
                case "/api/v4/projects/2/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"two.md\",\"type\":\"blob\",\"path\":\"two.md\"}]");
                default -> {
                    String raw = GitLabServerStub.rawPath(exchange);
                    if (raw.startsWith("/api/v4/projects/1/repository/files/one%2Emd/raw")) {
                        GitLabServerStub.text(exchange, 200, "one");
                    } else if (raw.startsWith("/api/v4/projects/2/repository/files/two%2Emd/raw")) {
                        GitLabServerStub.text(exchange, 200, "two");
                    } else {
                        GitLabServerStub.status(exchange, 404);
                    }
                }
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = new DataSourceConfig();
            Map<String, Object> credentials = new LinkedHashMap<>();
            credentials.put("base_url", stub.baseUrl());
            credentials.put("access_token", "token");
            ds.setCredentials(credentials);
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("projects", List.of(
                    Map.of("project_id", "1", "ref", "master", "paths", List.of()),
                    Map.of("project_id", "2", "ref", "master", "paths", List.of())));
            ds.setSettings(settings);

            Connector.FetchIncrementalResult first = connector.fetchIncremental(ds, null);
            assertThat(first.items()).hasSize(2);
            assertThat(projects(first.cursor())).containsEntry("1", "commit-1")
                    .containsEntry("2", "commit-2");
            assertThat(first.cursor().getConnectorCursor()).containsKey("raw");

            // head 没变 → 一条都不抓；Go 的 nil slice 对应 Java 的 null
            Connector.FetchIncrementalResult second = connector.fetchIncremental(ds, first.cursor());
            assertThat(second.items()).isNullOrEmpty();
        }
    }

    /** {@code FetchAll} 的 ref 回落：selection 的 ref 为空时用 {@code default_branch}。 */
    @Test
    void fetchAllFallsBackToDefaultBranch() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200, projectJson());
                case "/api/v4/projects/1/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"README.md\",\"type\":\"blob\",\"path\":\"README.md\"}]");
                default -> {
                    if (GitLabServerStub.rawPath(exchange).contains("/repository/files/")) {
                        GitLabServerStub.text(exchange, 200, "# readme");
                    } else {
                        GitLabServerStub.status(exchange, 404);
                    }
                }
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));

            List<FetchedItem> items = connector.fetchAll(ds, null);

            assertThat(items).hasSize(1);
            assertThat(items.get(0).getMetadata()).containsEntry("gitlab_ref", "main");
            // tree 用的是选择里的 project_id 原文；raw 用的是 API 返回的数字 ID
            assertThat(stub.requestPaths).contains("/api/v4/projects/1/repository/tree",
                    "/api/v4/projects/1/repository/files/README%2Emd/raw");
        }
    }

    /** 一条都没抓到时返回 {@code null}（对照 Go 的 nil slice，不是空列表）。 */
    @Test
    void fetchAllReturnsNullWhenNothingMatched() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200, projectJson());
                case "/api/v4/projects/1/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"main.go\",\"type\":\"blob\",\"path\":\"main.go\"}]");
                default -> GitLabServerStub.status(exchange, 404);
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));

            assertThat(connector.fetchAll(ds, null)).isNull();
        }
    }

    // ── ListResources ───────────────────────────────────────────────────

    /** {@code parent == ""} 列项目；非空则列该项目默认分支下的直接子目录。 */
    @Test
    void listResourcesWalksProjectsAndDirectories() throws IOException {
        GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects" -> GitLabServerStub.json(exchange, 200,
                        "[{\"id\":7,\"name\":\"docs\",\"path_with_namespace\":\"group/docs\","
                                + "\"web_url\":\"https://gitlab.test/group/docs\","
                                + "\"default_branch\":\"main\"}]");
                case "/api/v4/projects/7" -> GitLabServerStub.json(exchange, 200, projectJson());
                case "/api/v4/projects/7/repository/tree" -> {
                    // GitLab 的 tree 返回的 path 是<b>仓库内全路径</b>，
                    // 所以子目录的 path 带上了请求里的 dir 前缀
                    if ("docs".equals(GitLabServerStub.queryParam(exchange, "path"))) {
                        GitLabServerStub.json(exchange, 200,
                                "[{\"name\":\"guide\",\"type\":\"tree\",\"path\":\"docs/guide\"}]");
                    } else {
                        GitLabServerStub.json(exchange, 200,
                                "[{\"name\":\"docs\",\"type\":\"tree\",\"path\":\"docs\"},"
                                        + "{\"name\":\"README.md\",\"type\":\"blob\",\"path\":\"README.md\"}]");
                    }
                }
                default -> GitLabServerStub.status(exchange, 404);
            }
        });
        try (stub) {
            GitLabConnector connector = new GitLabConnector();
            DataSourceConfig ds = projectConfig(stub.baseUrl(), Map.of("project_id", "1",
                    "paths", List.of()));

            List<Resource> projects = connector.listResources(ds, "");
            assertThat(projects).hasSize(1);
            assertThat(projects.get(0).getExternalId()).isEqualTo("7");
            assertThat(projects.get(0).getType()).isEqualTo("project");
            assertThat(projects.get(0).getUrl()).isEqualTo("https://gitlab.test/group/docs");
            assertThat(projects.get(0).isHasChildren()).isTrue();

            // 子资源 ID 的形状是 "<项目数字 ID>:<treeEntry.path>"
            // （parent "7:docs" 里的 "docs" 只当 tree 的 path 参数用）
            List<Resource> children = connector.listResources(ds, "7:docs");
            assertThat(children).hasSize(1);
            assertThat(children.get(0).getExternalId()).isEqualTo("7:docs/guide");
            assertThat(children.get(0).getName()).isEqualTo("guide");
            assertThat(children.get(0).getType()).isEqualTo("directory");
            assertThat(children.get(0).getParentId()).isEqualTo("7:docs");
            // 非空 parent 时 tree 要带上 path 参数
            assertThat(stub.requestQueries.get(stub.requestQueries.size() - 1)).contains("path=docs");

            assertThat(connector.resolveResourceAncestors(ds, List.of("7:docs"))).isEmpty();
        }
    }

    // ── cursor ──────────────────────────────────────────────────────────

    /** cursor 形状（Go 实录）：{@code {"projects":…,"raw":"{\"projects\":{…}}"}}。 */
    @Test
    void gitLabCursorShapeMatchesGo() {
        Map<String, String> projects = new LinkedHashMap<>();
        projects.put("2", "c2");
        projects.put("1", "c1");

        SyncCursor cursor = GitLabConnector.gitLabCursor(projects);

        // 键序按 Go 的 map 规则排序（1 在 2 前）
        assertThat(cursor.getConnectorCursor().keySet()).containsExactly("projects", "raw");
        assertThat(cursor.getConnectorCursor().get("raw"))
                .isEqualTo("{\"projects\":{\"1\":\"c1\",\"2\":\"c2\"}}");
        assertThat(cursor.getLastSyncTime()).isNotNull();

        // 往返
        assertThat(GitLabConnector.decodeCursorProjects(cursor))
                .containsEntry("1", "c1").containsEntry("2", "c2");
    }

    /** cursor 解码的容错（Go 实录）：缺失 / 类型不对都回空 map，已解出的部分保留。 */
    @Test
    void decodeCursorProjectsMatchesGo() {
        assertThat(GitLabConnector.decodeCursorProjects(null)).isEmpty();

        SyncCursor empty = new SyncCursor();
        assertThat(GitLabConnector.decodeCursorProjects(empty)).isEmpty();

        assertThat(GitLabConnector.decodeCursorProjects(cursorWith())).isEmpty();

        // "projects" 是字符串 → 类型不符 → 空 map
        assertThat(GitLabConnector.decodeCursorProjects(cursorRaw("projects", "x"))).isEmpty();

        // 第一个值类型不对 → 一条都解不出（Go 在 SetMapIndex 之前就返回了）
        Map<String, Object> badFirst = new LinkedHashMap<>();
        badFirst.put("a", 1);
        badFirst.put("b", "x");
        assertThat(GitLabConnector.decodeCursorProjects(cursorRaw("projects", badFirst))).isEmpty();

        // 第二个值类型不对 → 第一条保留
        Map<String, Object> badSecond = new LinkedHashMap<>();
        badSecond.put("a", "x");
        badSecond.put("b", 1);
        assertThat(GitLabConnector.decodeCursorProjects(cursorRaw("projects", badSecond)))
                .containsExactly(java.util.Map.entry("a", "x"));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static String projectJson() {
        return "{\"id\":1,\"name\":\"docs\",\"path_with_namespace\":\"group/docs\","
                + "\"web_url\":\"https://gitlab.test/group/docs\",\"default_branch\":\"main\"}";
    }

    private static GitLabServerStub.Route compareTimeoutRoute() {
        return exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/api/v4/projects/1" -> GitLabServerStub.json(exchange, 200, projectJson());
                case "/api/v4/projects/1/repository/commits/main" ->
                        GitLabServerStub.json(exchange, 200, "{\"id\":\"head-2\"}");
                case "/api/v4/projects/1/repository/compare" -> GitLabServerStub.json(exchange, 200,
                        "{\"diffs\":[],\"compare_timeout\":true}");
                case "/api/v4/projects/1/repository/tree" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"README.md\",\"type\":\"blob\",\"path\":\"README.md\"}]");
                default -> {
                    if (GitLabServerStub.rawPath(exchange).contains("/repository/files/")) {
                        GitLabServerStub.text(exchange, 200, "# readme");
                    } else {
                        GitLabServerStub.status(exchange, 404);
                    }
                }
            }
        };
    }

    private static DataSourceConfig projectConfig(String baseUrl, Map<String, Object> project) {
        DataSourceConfig ds = new DataSourceConfig();
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("base_url", baseUrl);
        credentials.put("access_token", "token");
        ds.setCredentials(credentials);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("projects", List.of(project));
        ds.setSettings(settings);
        return ds;
    }

    private static SyncCursor cursorWith(String... pairs) {
        Map<String, Object> connectorCursor = new LinkedHashMap<>();
        connectorCursor.put("projects", mapOf(pairs));
        connectorCursor.put("raw", "{\"projects\":{}}");
        SyncCursor cursor = new SyncCursor();
        cursor.setConnectorCursor(connectorCursor);
        return cursor;
    }

    private static SyncCursor cursorRaw(String key, Object value) {
        Map<String, Object> connectorCursor = new LinkedHashMap<>();
        connectorCursor.put(key, value);
        SyncCursor cursor = new SyncCursor();
        cursor.setConnectorCursor(connectorCursor);
        return cursor;
    }

    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> projects(SyncCursor cursor) {
        return (Map<String, String>) cursor.getConnectorCursor().get("projects");
    }

    /** 对照 Go 测试里的 {@code gitLabStreamRecorder}（假 handler）。 */
    private static final class RecordingHandler implements StreamHandler {

        final List<FetchedItem> items = new ArrayList<>();
        final List<SyncCursor> checkpoints = new ArrayList<>();

        @Override
        public void emit(FetchedItem item) {
            items.add(item);
        }

        @Override
        public void checkpoint(SyncCursor cursor) {
            checkpoints.add(cursor);
        }
    }
}
