package com.ragagent.retrieval.engine.doris;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.doris.DorisRetrieveRepositoryTest.FakeRowSpec;
import com.ragagent.retrieval.engine.doris.DorisRetrieveRepositoryTest.FakeSql;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Doris Stream Load 面（legacy 模式的 partial update）——对照 Go {@code streamload.go}：
 * 端点与请求头（Authorization/format/strip_outer_array/partial_columns/columns/merge_type）、
 * JSON 体（Go json.Marshal 的 map 字母序）、"Publish Timeout" 视为成功、
 * 非 2xx 与失败状态的报文、307 到可信主机的跟随与跨主机拒收、1 MiB 拆批。
 *
 * <p>桩是本地假 FE/BE：断言"发出去的 HTTP 长什么样"（该批无 golden 面，逐请求断言即
 * 本批的字节契约）。</p>
 */
class DorisStreamLoadTest {

    private record Captured(String method, String path, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), "");
        }
    }

    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statusOverrides = new HashMap<>();
    private final Map<String, String> bodyOverrides = new HashMap<>();
    private final Map<String, String> locationOverrides = new HashMap<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT),
                v.isEmpty() ? "" : v.get(0)));
        String path = ex.getRequestURI().getPath();
        byte[] body = ex.getRequestBody().readAllBytes();
        captured.add(new Captured(ex.getRequestMethod(), path, headers,
                new String(body, StandardCharsets.UTF_8)));
        if (locationOverrides.containsKey(path)) {
            ex.getResponseHeaders().set("Location", locationOverrides.get(path));
        }
        ex.getResponseHeaders().set("Content-Type", "application/json");
        int status = statusOverrides.getOrDefault(path, 200);
        String response = bodyOverrides.getOrDefault(path,
                "{\"Status\":\"Success\",\"NumberTotalRows\":1,\"NumberLoadedRows\":1,"
                        + "\"Label\":\"lbl-1\"}");
        ex.sendResponseHeaders(status, response.getBytes(StandardCharsets.UTF_8).length);
        ex.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        ex.close();
    }

    private DorisRetrieveRepository legacyRepoWith(FakeSql sql, String username, String password) {
        DorisStreamLoadClient client = new DorisStreamLoadClient(base, "weknora", username,
                password, null);
        return new DorisRetrieveRepository(sql, client, "weknora", "weknora_embeddings",
                0, 0, DorisCompatMode.LEGACY);
    }

    /** 假 SQL：既有表 DDL = UNIQUE KEY（legacy 探测成立）；chunk 行位置表返回一行 (row-1, c1)。 */
    private static FakeSql legacySql() {
        return legacySql(List.of(new FakeRowSpec(List.of("id", "chunk_id"),
                List.of("row-1", "c1"))));
    }

    private static FakeSql legacySql(List<FakeRowSpec> chunkRowLocations) {
        FakeSql sql = new FakeSql();
        sql.scalarProvider = (q, a) -> 1;
        sql.rowsProvider = (q, a) -> {
            if (q.startsWith("SELECT TABLE_NAME FROM information_schema.tables")) {
                return List.of(new FakeRowSpec(List.of("TABLE_NAME"),
                        List.of("weknora_embeddings_2")));
            }
            if (q.startsWith("SHOW CREATE TABLE")) {
                return List.of(new FakeRowSpec(List.of("Table", "Create Table"),
                        List.of("weknora_embeddings_2", "CREATE TABLE x UNIQUE KEY(id)")));
            }
            if (q.startsWith("SELECT id, chunk_id FROM")) {
                return chunkRowLocations;
            }
            return List.of();
        };
        return sql;
    }

    @Test
    @DisplayName("legacy partial update：端点/请求头/JSON 体（键字母序）逐项对照 Go")
    void partialUpdateWireShape() throws Exception {
        FakeSql sql = legacySql();
        DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");
        repo.batchUpdateChunkEnabledStatus(Map.of("c1", false));

        assertThat(captured).hasSize(1);
        Captured req = captured.get(0);
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.path()).isEqualTo("/api/weknora/weknora_embeddings_2/_stream_load");
        assertThat(req.header("authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("root:pw".getBytes(StandardCharsets.UTF_8)));
        assertThat(req.header("content-type")).isEqualTo("application/json");
        assertThat(req.header("format")).isEqualTo("json");
        assertThat(req.header("strip_outer_array")).isEqualTo("true");
        assertThat(req.header("partial_columns")).isEqualTo("true");
        assertThat(req.header("columns")).isEqualTo("id,is_enabled");
        assertThat(req.header("merge_type")).isEqualTo("APPEND");
        assertThat(req.body()).isEqualTo("[{\"id\":\"row-1\",\"is_enabled\":false}]");
    }

    @Test
    @DisplayName("Stream Load 响应：Success / Publish Timeout 视为成功；失败状态带 status/msg/err_url")
    void responseStatusHandling() throws Exception {
        FakeSql sql = legacySql();
        DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");

        bodyOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load",
                "{\"Status\":\"Publish Timeout\"}");
        repo.batchUpdateChunkEnabledStatus(Map.of("c1", false));

        bodyOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load",
                "{\"Status\":\"Fail\",\"Message\":\"too many filtered rows\","
                        + "\"ErrorURL\":\"http://be:8040/api/_load_error_log\"}");
        assertThatThrownBy(() -> repo.batchUpdateChunkEnabledStatus(Map.of("c1", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("partial update is_enabled in weknora_embeddings_2: stream load failed:"
                        + " status=Fail msg=too many filtered rows"
                        + " err_url=http://be:8040/api/_load_error_log");
    }

    @Test
    @DisplayName("非 2xx：报文原文带回（stream load HTTP <code>: <body>）")
    void httpErrorStatus() {
        FakeSql sql = legacySql();
        DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");
        statusOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load", 401);
        bodyOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load", "unauthorized");
        assertThatThrownBy(() -> repo.batchUpdateChunkEnabledStatus(Map.of("c1", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream load HTTP 401: unauthorized");
    }

    @Test
    @DisplayName("1 MiB 拆批：两行各 ~600KB → 两次 PUT")
    void chunkingSendsTwoRequests() throws Exception {
        FakeSql sql = legacySql(List.of(
                new FakeRowSpec(List.of("id", "chunk_id"), List.of("row-1", "c1")),
                new FakeRowSpec(List.of("id", "chunk_id"), List.of("row-2", "c2"))));
        DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");
        String big = "x".repeat(600 * 1024);
        repo.batchUpdateChunkTagID(Map.of("c1", big, "c2", big));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).header("columns")).isEqualTo("id,tag_id");
        assertThat(captured.get(0).body()).startsWith("[{\"id\":\"row-1\",\"tag_id\":");
    }

    @Test
    @DisplayName("307 到同主机（不同端口）：重发 body 与 Basic 凭据并跟随")
    void redirectFollowedOnSameHost() throws Exception {
        // 目标 BE：另起一台本地假服务
        HttpServer be = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Captured> beCaptured = new CopyOnWriteArrayList<>();
        be.createContext("/", ex -> {
            Map<String, String> headers = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(
                    k.toLowerCase(java.util.Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
            byte[] body = ex.getRequestBody().readAllBytes();
            beCaptured.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    headers, new String(body, StandardCharsets.UTF_8)));
            byte[] resp = "{\"Status\":\"Success\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        be.setExecutor(null);
        be.start();
        try {
            locationOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load",
                    "http://127.0.0.1:" + be.getAddress().getPort() + "/api/weknora/"
                            + "weknora_embeddings_2/_stream_load");
            statusOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load", 307);

            FakeSql sql = legacySql();
            DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");
            repo.batchUpdateChunkEnabledStatus(Map.of("c1", false));

            assertThat(beCaptured).hasSize(1);
            Captured retry = beCaptured.get(0);
            assertThat(retry.method()).isEqualTo("PUT");
            assertThat(retry.header("authorization")).startsWith("Basic ");
            assertThat(retry.body()).isEqualTo("[{\"id\":\"row-1\",\"is_enabled\":false}]");
        } finally {
            be.stop(0);
        }
    }

    @Test
    @DisplayName("跨主机 307：目标非白名单 → 拒转凭据（Go 原文）")
    void crossHostRedirectBlocked() {
        locationOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load",
                "http://example.com/api/weknora/weknora_embeddings_2/_stream_load");
        statusOverrides.put("/api/weknora/weknora_embeddings_2/_stream_load", 307);

        FakeSql sql = legacySql();
        DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");
        assertThatThrownBy(() -> repo.batchUpdateChunkEnabledStatus(Map.of("c1", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream load redirect blocked: target host \"example.com\""
                        + " is not trusted to receive credentials");
    }

    @Test
    @DisplayName("无行可写：partialUpdateRows 短路（不发请求）")
    void emptyRowsShortCircuit() throws Exception {
        FakeSql sql = legacySql(List.of()); // 查不到物理行
        DorisRetrieveRepository repo = legacyRepoWith(sql, "root", "pw");
        repo.batchUpdateChunkEnabledStatus(Map.of("c1", false));
        assertThat(captured).isEmpty();
        assertThat(new ArrayList<>(sql.executed)).isEmpty();
    }
}
