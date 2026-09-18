package com.ragagent.datasource.connector.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * 对照 Go {@code gitlab/types_test.go} + {@code parseConfig} / {@code normalizePath} /
 * {@code collapsePaths} 的<b>Go 实录</b>全表。
 *
 * <p>期望值来源：把 {@code types.go} 的函数连同一个本地
 * {@code ErrInvalidConfig} 原样抄进独立 Go 程序跑出来的输出（含每条错误消息）。</p>
 */
class GitLabConfigTest {

    // ── Go types_test.go ────────────────────────────────────────────────

    /** 对照 Go {@code TestParseConfigCollapsesDirectories}。 */
    @Test
    void parseConfigCollapsesDirectories() {
        DataSourceConfig ds = settingsConfig(Map.of("projects", List.of(Map.of(
                "project_id", "team/docs",
                "ref", "main",
                "paths", List.of("docs/guide", "docs", "docs")))));

        GitLabConfig cfg = GitLabConfig.parse(ds);

        assertThat(cfg.projects()).hasSize(1);
        assertThat(cfg.projects().get(0).projectId()).isEqualTo("team/docs");
        assertThat(cfg.projects().get(0).ref()).isEqualTo("main");
        assertThat(cfg.projects().get(0).paths()).containsExactly("docs");
    }

    /** 对照 Go {@code TestParseConfigRootMeansWholeProject}：{@code paths: ["/"]} → 整个项目。 */
    @Test
    void parseConfigRootMeansWholeProject() {
        DataSourceConfig ds = settingsConfig(Map.of("projects", List.of(Map.of(
                "project_id", "42",
                "paths", List.of("/")))));

        GitLabConfig cfg = GitLabConfig.parse(ds);

        assertThat(cfg.projects().get(0).paths()).isNull();
    }

    /** 对照 Go {@code TestNormalizePathRejectsTraversal}。 */
    @Test
    void normalizePathRejectsTraversal() {
        assertThatThrownBy(() -> GitLabConfig.normalizePath("docs/../secrets"))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: invalid repository path");
    }

    /** 对照 Go {@code TestKnowledgeRelativePathPreservesRepositoryTreeBelowProjectAndBranch}。 */
    @Test
    void knowledgeRelativePathPreservesRepositoryTreeBelowProjectAndBranch() {
        assertThat(GitLabConnector.knowledgeRelativePath("knowledge", "feature/login",
                "docs/guide/install.md"))
                .isEqualTo("knowledge-feature-login/docs/guide/install.md");

        // Go 实录（其余形态）
        assertThat(GitLabConnector.knowledgeRelativePath("docs", "main", "README.md"))
                .isEqualTo("docs-main/README.md");
        assertThat(GitLabConnector.knowledgeRelativePath("docs", "", "README.md"))
                .isEqualTo("docs-/README.md");
        assertThat(GitLabConnector.knowledgeRelativePath("", "main", "README.md"))
                .isEqualTo("-main/README.md");
        assertThat(GitLabConnector.knowledgeRelativePath(" docs ", " main ", "a.md"))
                .isEqualTo("docs-main/a.md");
        assertThat(GitLabConnector.knowledgeRelativePath("docs", "main", ""))
                .isEqualTo("docs-main");
        // path.Join = Clean：前导斜杠、"." 与 ".." 都会被归掉
        assertThat(GitLabConnector.knowledgeRelativePath("docs", "main", "/a.md"))
                .isEqualTo("docs-main/a.md");
        assertThat(GitLabConnector.knowledgeRelativePath("docs", "main", "a/../b.md"))
                .isEqualTo("docs-main/b.md");
        assertThat(GitLabConnector.knowledgeRelativePath("docs", "main", "./a.md"))
                .isEqualTo("docs-main/a.md");
        // Go 实录：root = TrimSpace("a-b") + "-" + TrimSpace("c") = "a-b-c"
        assertThat(GitLabConnector.knowledgeRelativePath("a-b", "c", "d/e"))
                .isEqualTo("a-b-c/d/e");
    }

    // ── normalizePath 全表（Go 实录） ───────────────────────────────────

    @Test
    void normalizePathMatchesGo() {
        assertThat(GitLabConfig.normalizePath("/")).isEmpty();
        assertThat(GitLabConfig.normalizePath("")).isEmpty();
        assertThat(GitLabConfig.normalizePath("  ")).isEmpty();
        assertThat(GitLabConfig.normalizePath("docs")).isEqualTo("docs");
        assertThat(GitLabConfig.normalizePath("/docs/")).isEqualTo("docs");
        assertThat(GitLabConfig.normalizePath("docs/guide")).isEqualTo("docs/guide");
        assertThat(GitLabConfig.normalizePath("docs/")).isEqualTo("docs");
        assertThat(GitLabConfig.normalizePath("/docs")).isEqualTo("docs");
        assertThat(GitLabConfig.normalizePath("a b")).isEqualTo("a b");
        assertThat(GitLabConfig.normalizePath("中文/路径")).isEqualTo("中文/路径");
        assertThat(GitLabConfig.normalizePath("~/x")).isEqualTo("~/x");
        assertThat(GitLabConfig.normalizePath("...")).isEqualTo("...");
        // ⚠️ 裸 ".." 是 Go 的漏网之鱼：Clean("..")==".." 让 Clean(v)!=v 不成立，
        // 而 ".." 既不匹配 Prefix("../") 也不含 "/../" —— 照抄，别"顺手修好"
        assertThat(GitLabConfig.normalizePath("..")).isEqualTo("..");

        assertInvalidRepositoryPath("docs//guide");
        assertInvalidRepositoryPath("docs/../secrets");
        assertInvalidRepositoryPath("../x");
        assertInvalidRepositoryPath("a/../b");
        assertInvalidRepositoryPath("./a");
        assertInvalidRepositoryPath("a/.");
        assertInvalidRepositoryPath(".");
        assertInvalidRepositoryPath("a/./b");
        assertInvalidRepositoryPath("a/..");

        assertThatThrownBy(() -> GitLabConfig.normalizePath("a\\b"))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: path must use forward slashes");
    }

    private static void assertInvalidRepositoryPath(String value) {
        assertThatThrownBy(() -> GitLabConfig.normalizePath(value))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: invalid repository path");
    }

    // ── collapsePaths 全表（Go 实录） ───────────────────────────────────

    @Test
    void collapsePathsMatchesGo() {
        assertThat(GitLabConfig.collapsePaths(null)).isNull();
        assertThat(GitLabConfig.collapsePaths(List.of())).isNull();
        // 含空元素 → 整个置 nil（这就是 paths:["/"] 变成"整个项目"的那一步）
        assertThat(GitLabConfig.collapsePaths(List.of(""))).isNull();
        assertThat(GitLabConfig.collapsePaths(List.of("", "a"))).isNull();
        assertThat(GitLabConfig.collapsePaths(List.of("a", ""))).isNull();

        assertThat(GitLabConfig.collapsePaths(List.of("docs"))).containsExactly("docs");
        assertThat(GitLabConfig.collapsePaths(List.of("docs/guide", "docs", "docs")))
                .containsExactly("docs");
        assertThat(GitLabConfig.collapsePaths(List.of("b", "a"))).containsExactly("a", "b");
        assertThat(GitLabConfig.collapsePaths(List.of("a", "a/b", "a/b/c"))).containsExactly("a");
        assertThat(GitLabConfig.collapsePaths(List.of("a/b", "a"))).containsExactly("a");
        assertThat(GitLabConfig.collapsePaths(List.of("z", "a"))).containsExactly("a", "z");
        // "/" 不是 "" 的父目录（判据是 p == last || p.startsWith(last + "/")）
        assertThat(GitLabConfig.collapsePaths(List.of("docs", "/"))).containsExactly("/", "docs");
        // "ab" 不是 "a" 的子目录
        assertThat(GitLabConfig.collapsePaths(List.of("ab", "a"))).containsExactly("a", "ab");
    }

    // ── parseConfig 错误全表（Go 实录） ─────────────────────────────────

    @Test
    void parseConfigRejectsMissingSettings() {
        assertThatThrownBy(() -> GitLabConfig.parse(null))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration");
        assertThatThrownBy(() -> GitLabConfig.parse(new DataSourceConfig()))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: settings.projects is required");
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(Map.of())))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: settings.projects is required");
    }

    @Test
    void parseConfigRejectsWrongShapes() {
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(settings("projects", "x"))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: settings.projects must be an array");
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(settings("projects", List.of()))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: at least one project is required");
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(settings("projects", List.of(1)))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: invalid project selection");
    }

    @Test
    void parseConfigRejectsBadProjectIds() {
        assertBadProjectId(Map.of());
        assertBadProjectId(Map.of("project_id", 5));
        assertBadProjectId(Map.of("project_id", "  "));
        assertBadProjectId(Map.of("project_id", " " + "a" + " "), Map.of("project_id", "a"));
    }

    @SafeVarargs
    private static void assertBadProjectId(Map<String, Object>... projects) {
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(settings("projects", List.of(projects)))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: project_id must be unique and non-empty");
    }

    @Test
    void parseConfigRejectsBadPaths() {
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(
                settings("projects", List.of(Map.of("project_id", "a", "paths", "x"))))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: paths must be an array");
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(
                settings("projects", List.of(Map.of("project_id", "a", "paths", List.of(3)))))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: path must be a string");
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(
                settings("projects", List.of(Map.of("project_id", "a", "paths", List.of("docs/../x")))))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: invalid repository path");
        assertThatThrownBy(() -> GitLabConfig.parse(settingsConfig(
                settings("projects", List.of(Map.of("project_id", "a", "paths", List.of("a\\b")))))))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: path must use forward slashes");
    }

    /** {@code ref} 非字符串 → 零值 {@code ""}；缺 {@code paths} 与空数组等价（都 → null）。 */
    @Test
    void parseConfigToleratesNonStringRefAndMissingPaths() {
        GitLabConfig cfg = GitLabConfig.parse(settingsConfig(
                settings("projects", List.of(Map.of("project_id", "a", "ref", 5)))));
        assertThat(cfg.projects().get(0).ref()).isEmpty();
        assertThat(cfg.projects().get(0).paths()).isNull();

        GitLabConfig cfg2 = GitLabConfig.parse(settingsConfig(
                settings("projects", List.of(Map.of("project_id", "team/docs")))));
        assertThat(cfg2.projects().get(0).projectId()).isEqualTo("team/docs");
        assertThat(cfg2.projects().get(0).ref()).isEmpty();
        assertThat(cfg2.projects().get(0).paths()).isNull();
    }

    /** {@code "/"} 与 {@code "docs/guide"}、{@code "docs"} 混排时，空串把整组打成 null。 */
    @Test
    void parseConfigRootPathWinsOverSiblings() {
        GitLabConfig cfg = GitLabConfig.parse(settingsConfig(settings("projects", List.of(Map.of(
                "project_id", "a",
                "ref", " m ",
                "paths", List.of("/", "docs/guide", "docs"))))));
        assertThat(cfg.projects().get(0).ref()).isEqualTo("m");
        assertThat(cfg.projects().get(0).paths()).isNull();
    }

    // ── GoPath ──────────────────────────────────────────────────────────

    /**
     * {@code path.Clean} 与 {@code Path.normalize()} 的分叉点（用 Go 实录钉住）。
     *
     * <p>这些用例的作用是：如果有人把 {@link GoPath#clean} 换成 JDK 的实现，
     * 这里会立刻红——而不是等到某个用户的 {@code paths} 被悄悄放行。</p>
     */
    @Test
    void goPathCleanMatchesGo() {
        assertThat(GoPath.clean("")).isEqualTo(".");
        assertThat(GoPath.clean(".")).isEqualTo(".");
        assertThat(GoPath.clean("a/..")).isEqualTo(".");
        assertThat(GoPath.clean("..")).isEqualTo("..");
        assertThat(GoPath.clean("a/../..")).isEqualTo("..");
        assertThat(GoPath.clean("docs//guide")).isEqualTo("docs/guide");
        assertThat(GoPath.clean("docs/")).isEqualTo("docs");
        assertThat(GoPath.clean("docs/./a")).isEqualTo("docs/a");
        assertThat(GoPath.clean("/a/../b")).isEqualTo("/b");
        assertThat(GoPath.clean("\\a\\b")).isEqualTo("\\a\\b");
        assertThat(GoPath.clean("中文/路径")).isEqualTo("中文/路径");
        assertThat(GoPath.clean("/")).isEqualTo("/");
        assertThat(GoPath.clean("//")).isEqualTo("/");
    }

    /**
     * {@code ".."} 回退的边界（Go 实录）——Go 的 {@code lazybuf} 在回退时
     * 读的是<b>逻辑游标处</b>的字符而不是"当前输出的最后一个字符"，
     * 两者在 {@code "docs-main/a/../b.md"} 这种路径上会分叉
     * （写成后者会多留一个斜杠）。这一组是那个缺陷的回归保护。
     */
    @Test
    void goPathCleanBacktrackingMatchesGo() {
        assertThat(GoPath.clean("docs-main/a/../b.md")).isEqualTo("docs-main/b.md");
        assertThat(GoPath.clean("a/b/../../c")).isEqualTo("c");
        assertThat(GoPath.clean("/a/b/../c")).isEqualTo("/a/c");
        assertThat(GoPath.clean("../../a")).isEqualTo("../../a");
        assertThat(GoPath.clean("a/../../../b")).isEqualTo("../../b");
        assertThat(GoPath.clean("../..")).isEqualTo("../..");
        assertThat(GoPath.clean("a//b//../c")).isEqualTo("a/c");
        assertThat(GoPath.clean("a/b/..")).isEqualTo("a");
        assertThat(GoPath.clean("x/./y/")).isEqualTo("x/y");
        assertThat(GoPath.clean("//a//b//")).isEqualTo("/a/b");
        assertThat(GoPath.clean("./")).isEqualTo(".");
        assertThat(GoPath.clean("中/../a")).isEqualTo("a");
    }

    @Test
    void goPathJoinMatchesGo() {
        assertThat(GoPath.join("docs-main", "README.md")).isEqualTo("docs-main/README.md");
        assertThat(GoPath.join("docs-main")).isEqualTo("docs-main");
        assertThat(GoPath.join("", "")).isEmpty();
        assertThat(GoPath.join("docs-main", "")).isEqualTo("docs-main");
        assertThat(GoPath.join("docs-main", "/a.md")).isEqualTo("docs-main/a.md");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static DataSourceConfig settingsConfig(Map<String, Object> settings) {
        DataSourceConfig ds = new DataSourceConfig();
        ds.setSettings(new LinkedHashMap<>(settings));
        return ds;
    }

    private static Map<String, Object> settings(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
