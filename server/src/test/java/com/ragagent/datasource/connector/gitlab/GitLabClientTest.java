package com.ragagent.datasource.connector.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * 对照 Go {@code gitlab/client_test.go}（除配置解析与流式部分，那两块在
 * {@link GitLabConfigTest} / {@link GitLabConnectorTest}）。
 *
 * <p>全部走 {@link GitLabServerStub}，不依赖真实网络。</p>
 */
class GitLabClientTest {

    private static final String USER_PATH = "/api/v4/user";

    @BeforeAll
    static void allowLocalServer() {
        GitLabServerStub.allowLocalServer();
    }

    @AfterAll
    static void restoreSsrfGuard() {
        GitLabServerStub.restoreSsrfGuard();
    }

    // ── Validate ────────────────────────────────────────────────────────

    /** 对照 Go {@code TestConnectorValidateUsesDataSourceCredentials}。 */
    @Test
    void validateUsesDataSourceCredentials() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            if (!USER_PATH.equals(exchange.getRequestURI().getPath())) {
                GitLabServerStub.status(exchange, 404);
                return;
            }
            if (!"per-source-token".equals(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"))) {
                GitLabServerStub.status(exchange, 401);
                return;
            }
            GitLabServerStub.json(exchange, 200, "{\"id\": 42}");
        })) {
            DataSourceConfig ds = credentialsConfig(stub.baseUrl(), "per-source-token");

            new GitLabConnector().validate(ds);

            assertThat(stub.requestPaths).containsExactly(USER_PATH);
            assertThat(stub.privateTokens).containsExactly("per-source-token");
        }
    }

    /** 对照 Go {@code TestConnectorValidateReturnsGitLabAPIError}。 */
    @Test
    void validateReturnsGitLabApiError() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            if (!USER_PATH.equals(exchange.getRequestURI().getPath())) {
                GitLabServerStub.status(exchange, 404);
                return;
            }
            GitLabServerStub.text(exchange, 401, "invalid token");
        })) {
            DataSourceConfig ds = credentialsConfig(stub.baseUrl(), "invalid-token");

            assertThatThrownBy(() -> new GitLabConnector().validate(ds))
                    .isInstanceOfSatisfying(GitLabClient.ApiException.class, err -> {
                        assertThat(err.endpoint()).isEqualTo("/user");
                        assertThat(err.status()).isEqualTo(401);
                        assertThat(err).hasMessage("gitlab API /user: status 401");
                    });
        }
    }

    /** 对照 Go {@code TestConnectorValidateRejectsMissingCredentials}：不触网。 */
    @Test
    void validateRejectsMissingCredentials() {
        DataSourceConfig ds = new DataSourceConfig();
        ds.setCredentials(Map.of("base_url", "https://gitlab.example.com"));

        assertThatThrownBy(() -> new GitLabConnector().validate(ds))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("GitLab platform configuration is missing");
    }

    /** 对照 Go {@code TestConnectorValidateRejectsMissingProjectsOnSave}。 */
    @Test
    void validateRejectsMissingProjectsOnSave() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            if (!USER_PATH.equals(exchange.getRequestURI().getPath())) {
                GitLabServerStub.status(exchange, 404);
                return;
            }
            GitLabServerStub.json(exchange, 200, "{\"id\": 42}");
        })) {
            DataSourceConfig ds = credentialsConfig(stub.baseUrl(), "token");
            ds.setSettings(Map.of("projects", java.util.List.of()));

            assertThatThrownBy(() -> new GitLabConnector().validate(ds))
                    .isInstanceOf(ConnectorException.InvalidConfig.class)
                    .hasMessage("invalid configuration: at least one project is required");
        }
    }

    /** 对照 Go {@code TestConnectorConfiguredDoesNotReuseRegistryClient}。 */
    @Test
    void configuredDoesNotReuseRegistryClient() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            if (!USER_PATH.equals(exchange.getRequestURI().getPath())) {
                GitLabServerStub.status(exchange, 404);
                return;
            }
            GitLabServerStub.json(exchange, 200, "{\"id\": 42}");
        })) {
            GitLabConnector connector = new GitLabConnector();
            connector.validate(credentialsConfig(stub.baseUrl(), "token-a"));
            connector.validate(credentialsConfig(stub.baseUrl(), "token-b"));

            assertThat(stub.privateTokens).containsExactly("token-a", "token-b");
        }
    }

    // ── newClient 归一 ──────────────────────────────────────────────────

    /** 对照 Go {@code TestNewClientNormalizesAPIBaseURL}。 */
    @Test
    void newClientNormalizesApiBaseUrl() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> GitLabServerStub.status(exchange, 404))) {
            GitLabClient client = GitLabClient.newClient(stub.baseUrl() + "/", "token");
            assertThat(client.baseUrl()).isEqualTo(stub.baseUrl() + "/api/v4");
        }
    }

    /**
     * {@code newClient} 的五步归一顺序（期望值取自 Go 实录）。
     *
     * <p>刻意覆盖 {@code /api/v4extra} 这一格：Go 只判 {@code HasSuffix("/api/v4")}，
     * 所以它会变成 {@code .../api/v4extra/api/v4}——"看着像 bug 但要照抄"的那一类。</p>
     */
    @Test
    void newClientNormalizationOrder() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> GitLabServerStub.status(exchange, 404))) {
            String host = stub.baseUrl(); // http://127.0.0.1:<port>
            String port = host.substring("http://127.0.0.1:".length());

            assertThat(GitLabClient.newClient(host, "t").baseUrl()).isEqualTo(host + "/api/v4");
            assertThat(GitLabClient.newClient(host + "/", "t").baseUrl()).isEqualTo(host + "/api/v4");
            assertThat(GitLabClient.newClient(host + "///", "t").baseUrl()).isEqualTo(host + "/api/v4");
            assertThat(GitLabClient.newClient("  " + host + "  ", "t").baseUrl()).isEqualTo(host + "/api/v4");
            assertThat(GitLabClient.newClient(host + "/api/v4", "t").baseUrl()).isEqualTo(host + "/api/v4");
            assertThat(GitLabClient.newClient(host + "/api/v4/", "t").baseUrl()).isEqualTo(host + "/api/v4");
            assertThat(GitLabClient.newClient(host + "/api/v4extra", "t").baseUrl())
                    .isEqualTo(host + "/api/v4extra/api/v4");
            // 无 scheme：ValidateConnectorBaseURL 先用 https:// 校验（白名单放行），之后才补 scheme
            assertThat(GitLabClient.newClient("127.0.0.1:" + port, "t").baseUrl())
                    .isEqualTo("https://127.0.0.1:" + port + "/api/v4");

            assertThatThrownBy(() -> GitLabClient.newClient("", "t"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("GitLab platform configuration is missing");
            assertThatThrownBy(() -> GitLabClient.newClient(host, ""))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("GitLab platform configuration is missing");
            assertThatThrownBy(() -> GitLabClient.newClient(host, "   "))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("GitLab platform configuration is missing");
        }
    }

    // ── projectPath / gitlabFilePathEscape ──────────────────────────────

    /** 对照 Go {@code TestProjectPathEncodesNamespaceWithoutDoubleEscaping} + Go 实录扩充。 */
    @Test
    void projectPathEncodesNamespaceWithoutDoubleEscaping() {
        assertThat(GitLabClient.projectPath("12345")).isEqualTo("12345");
        assertThat(GitLabClient.projectPath("group/project")).isEqualTo("group%2Fproject");
        assertThat(GitLabClient.projectPath("group%2Fproject")).isEqualTo("group%2Fproject");
        assertThat(GitLabClient.projectPath("my group/my project")).isEqualTo("my%20group%2Fmy%20project");

        // 以下期望值全部来自把 Go 源码原样抄进独立程序跑出的实录
        assertThat(GitLabClient.projectPath(" 123 ")).isEqualTo("123");
        assertThat(GitLabClient.projectPath("0")).isEqualTo("0");
        assertThat(GitLabClient.projectPath("-1")).isEqualTo("-1");
        assertThat(GitLabClient.projectPath("007")).isEqualTo("007");
        assertThat(GitLabClient.projectPath("")).isEmpty();
        assertThat(GitLabClient.projectPath("team/docs")).isEqualTo("team%2Fdocs");
        assertThat(GitLabClient.projectPath("group/")).isEqualTo("group%2F");
        assertThat(GitLabClient.projectPath("/proj")).isEqualTo("%2Fproj");
        assertThat(GitLabClient.projectPath("a~b.c_d-e/f")).isEqualTo("a~b.c_d-e%2Ff");
        assertThat(GitLabClient.projectPath("a+b/c")).isEqualTo("a+b%2Fc");
        assertThat(GitLabClient.projectPath("a/./b")).isEqualTo("a%2F.%2Fb");
        assertThat(GitLabClient.projectPath("café/x")).isEqualTo("caf%C3%A9%2Fx");
        // 已经编码过的：先 unescape 再逐段编码，不会变成 %252F
        assertThat(GitLabClient.projectPath("gr%6Fup/pro%6Aect")).isEqualTo("group%2Fproject");
        assertThat(GitLabClient.projectPath("group%252Fproject")).isEqualTo("group%252Fproject");
        // unescape 失败 → 保留原串 → 再逐段编码（% 自身变成 %25）
        assertThat(GitLabClient.projectPath("%zz/x")).isEqualTo("%25zz%2Fx");
        assertThat(GitLabClient.projectPath("a%")).isEqualTo("a%25");
        assertThat(GitLabClient.projectPath("a%b/c")).isEqualTo("a%25b%2Fc");
        assertThat(GitLabClient.projectPath("..")).isEqualTo("..");
    }

    /**
     * 对照 Go {@code TestGitlabFilePathEscape}：只保留 {@code a-zA-Z0-9-_}，
     * 其余逐<b>字节</b>百分号转义、大写十六进制（所以 {@code .} 也要转义）。
     */
    @Test
    void gitlabFilePathEscapeEncodesEveryOtherByte() {
        assertThat(GitLabClient.filePathEscape("docs/internal/中文-file.md"))
                .isEqualTo("docs%2Finternal%2F%E4%B8%AD%E6%96%87-file%2Emd");

        // Go 实录
        assertThat(GitLabClient.filePathEscape("a")).isEqualTo("a");
        assertThat(GitLabClient.filePathEscape("a.b")).isEqualTo("a%2Eb");
        assertThat(GitLabClient.filePathEscape("~/x")).isEqualTo("%7E%2Fx");
        assertThat(GitLabClient.filePathEscape("a b")).isEqualTo("a%20b");
        assertThat(GitLabClient.filePathEscape("é.md")).isEqualTo("%C3%A9%2Emd");
        assertThat(GitLabClient.filePathEscape("a+b")).isEqualTo("a%2Bb");
        assertThat(GitLabClient.filePathEscape("a%b")).isEqualTo("a%25b");
        assertThat(GitLabClient.filePathEscape("-_.~")).isEqualTo("-_%2E%7E");
    }

    // ── tree 分页 ───────────────────────────────────────────────────────

    /** 对照 Go {@code TestTreeFollowsGitLabPagination}：靠响应头 X-Next-Page 推进。 */
    @Test
    void treeFollowsGitLabPagination() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            if (!"/api/v4/projects/1/repository/tree".equals(exchange.getRequestURI().getPath())) {
                GitLabServerStub.status(exchange, 404);
                return;
            }
            String page = GitLabServerStub.queryParam(exchange, "page");
            switch (page) {
                case "1" -> {
                    exchange.getResponseHeaders().set("X-Next-Page", "2");
                    GitLabServerStub.json(exchange, 200,
                            "[{\"name\":\"one.md\",\"type\":\"blob\",\"path\":\"one.md\"}]");
                }
                case "2" -> GitLabServerStub.json(exchange, 200,
                        "[{\"name\":\"two.md\",\"type\":\"blob\",\"path\":\"two.md\"}]");
                default -> GitLabServerStub.text(exchange, 400, "unexpected page");
            }
        })) {
            GitLabClient client = GitLabClient.newClient(stub.baseUrl(), "token");

            var entries = client.tree("1", "main", "");

            assertThat(entries).hasSize(2);
            assertThat(entries.get(1).path()).isEqualTo("two.md");
            // 请求顺序 = 1,2（Go 的 requestedPages 断言）
            assertThat(stub.requestPaths).hasSize(2);
            assertThat(stub.requestQueries.get(0)).contains("page=1");
            assertThat(stub.requestQueries.get(1)).contains("page=2");
            // 逐页推进靠响应头；Go 侧同样只 Set 了 page
            assertThat(stub.requestQueries.get(0)).contains("per_page=100").contains("ref=main");
        }
    }

    // ── raw 的 404 回落 ─────────────────────────────────────────────────

    /** 对照 Go {@code TestRawFallsBackToBase64FileDetail}。 */
    @Test
    void rawFallsBackToBase64FileDetail() throws IOException {
        String rawPath = "/api/v4/projects/18724/repository/files/docs%2Finternal%2Freadme%2Emd/raw";
        String detailPath = "/api/v4/projects/18724/repository/files/docs%2Finternal%2Freadme%2Emd";
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = GitLabServerStub.rawPath(exchange);
            if (rawPath.equals(path)) {
                GitLabServerStub.status(exchange, 404);
            } else if (detailPath.equals(path)) {
                GitLabServerStub.json(exchange, 200,
                        "{\"encoding\":\"base64\",\"content\":\"SGVsbG8sIEdpdExhYiE=\"}");
            } else {
                GitLabServerStub.status(exchange, 404);
            }
        })) {
            GitLabClient client = GitLabClient.newClient(stub.baseUrl(), "token");

            byte[] content = client.raw("18724", "master", "docs/internal/readme.md");

            assertThat(new String(content, java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("Hello, GitLab!");
            assertThat(stub.requestPaths).containsExactly(rawPath, detailPath);
        }
    }

    /** 非 404 的失败<b>不</b>回落，直接包成 {@code gitlab raw file: …}。 */
    @Test
    void rawFailsDirectlyOnNonNotFound() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange ->
                GitLabServerStub.status(exchange, 500))) {
            GitLabClient client = GitLabClient.newClient(stub.baseUrl(), "token");

            assertThatThrownBy(() -> client.raw("1", "main", "a.md"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageStartingWith("gitlab raw file: gitlab API ")
                    .hasMessageEndingWith(": status 500");
            assertThat(stub.requestPaths).hasSize(1);
        }
    }

    /** {@code encoding != "base64"} 的详情响应要报错，而不是把 base64 当正文。 */
    @Test
    void rawRejectsUnsupportedEncoding() throws IOException {
        String rawPath = "/api/v4/projects/1/repository/files/a%2Emd/raw";
        String detailPath = "/api/v4/projects/1/repository/files/a%2Emd";
        try (GitLabServerStub stub = new GitLabServerStub(exchange -> {
            String path = GitLabServerStub.rawPath(exchange);
            if (rawPath.equals(path)) {
                GitLabServerStub.status(exchange, 404);
            } else if (detailPath.equals(path)) {
                GitLabServerStub.json(exchange, 200, "{\"encoding\":\"utf8\",\"content\":\"hi\"}");
            } else {
                GitLabServerStub.status(exchange, 404);
            }
        })) {
            GitLabClient client = GitLabClient.newClient(stub.baseUrl(), "token");

            assertThatThrownBy(() -> client.raw("1", "main", "a.md"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("gitlab file content: unsupported encoding \"utf8\"");
        }
    }

    /** 极端的 404（详情端点也 404）走 {@code gitlab file content: …}。 */
    @Test
    void rawWrapsDetailFailure() throws IOException {
        try (GitLabServerStub stub = new GitLabServerStub(exchange ->
                GitLabServerStub.status(exchange, 404))) {
            GitLabClient client = GitLabClient.newClient(stub.baseUrl(), "token");

            assertThatThrownBy(() -> client.raw("1", "main", "a.md"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageStartingWith("gitlab file content: gitlab API ")
                    .hasMessageEndingWith(": status 404");
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static DataSourceConfig credentialsConfig(String baseUrl, String token) {
        DataSourceConfig ds = new DataSourceConfig();
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("base_url", baseUrl);
        credentials.put("access_token", token);
        ds.setCredentials(credentials);
        return ds;
    }
}
