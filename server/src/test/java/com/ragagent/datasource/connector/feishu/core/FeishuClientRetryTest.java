package com.ragagent.datasource.connector.feishu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;

/**
 * 429 尊重 Retry-After、5xx 只重试一次、4xx 不重试、
 * {@code parseRetryAfter} 的 0/负/不可解析三态，外加 token 缓存。
 *
 * <h2>不靠墙钟造时间</h2>
 * <p>用 {@code Retry-After: 0}（客户端把它强制成 100ms 短延迟）让用例跑得快；
 * 再把 {@link FeishuClient#retry5xxDelay} 与
 * {@link FeishuClient#retryBackoff} 在 {@code @BeforeAll} 里压到毫秒级并在
 * {@code @AfterAll} 还原——这两个字段本来就是为测试留的注入缝（见生产代码注释），
 * 断言只看<b>调用次数</b>，不看耗时。</p>
 */
class FeishuClientRetryTest {

    private static final String TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";

    private static FeishuTestServer server;
    private static Duration saved5xxDelay;
    private static List<Duration> savedBackoff;

    @BeforeAll
    static void start() throws IOException {
        FeishuTestSupport.allowLoopback();
        server = new FeishuTestServer();
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"fake-token\",\"expire\":7200}"));

        saved5xxDelay = FeishuClient.retry5xxDelay;
        savedBackoff = FeishuClient.retryBackoff;
        FeishuClient.retry5xxDelay = Duration.ofMillis(5);
        FeishuClient.retryBackoff = List.of(
                Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(5));
    }

    @AfterAll
    static void stop() {
        FeishuClient.retry5xxDelay = saved5xxDelay;
        FeishuClient.retryBackoff = savedBackoff;
        if (server != null) {
            server.close();
        }
        FeishuTestSupport.restoreSsrf();
    }

    private static FeishuClient client() {
        FeishuConfig cfg = new FeishuConfig();
        cfg.setAppId("a");
        cfg.setAppSecret("b");
        cfg.setBaseUrl(server.baseUrl());
        return new FeishuClient(cfg);
    }

    @Test
    @DisplayName("429 → 重试；Retry-After: 0 被强制成 100ms 短延迟，第二次成功")
    void retriesOn429ThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        server.handle("/target-429-ok", (ex, body) -> {
            if (attempts.incrementAndGet() == 1) {
                FeishuTestServer.sendStatusWithHeader(ex, 429, "Retry-After", "0",
                        "{\"code\":99991400,\"msg\":\"rate limited\"}");
                return;
            }
            FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\"}");
        });

        FeishuClient c = client();
        c.doRequest("GET", "/target-429-ok", null, FeishuApiTypes.ApiResponse.class);
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("429 一直返回 → 耗尽重试预算（1 + 3 次），抛 rate limited")
    void retriesOn429ExhaustsRetries() {
        AtomicInteger attempts = new AtomicInteger();
        server.handle("/target-429-always", (ex, body) -> {
            attempts.incrementAndGet();
            FeishuTestServer.sendStatusWithHeader(ex, 429, "Retry-After", "0",
                    "{\"code\":99991400,\"msg\":\"rate limited\"}");
        });

        FeishuClient c = client();
        assertThatThrownBy(() -> c.doRequest("GET", "/target-429-always", null, null))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("feishu rate limited: status=429");
        assertThat(attempts.get()).isEqualTo(4); // 初始 + 3 次重试
    }

    @Test
    @DisplayName("5xx → 只重试一次（1 + 1），抛 server error")
    void retries5xxOnce() {
        AtomicInteger attempts = new AtomicInteger();
        server.handle("/target-5xx", (ex, body) -> {
            attempts.incrementAndGet();
            FeishuTestServer.sendStatus(ex, 500, "{\"code\":1,\"msg\":\"internal error\"}");
        });

        FeishuClient c = client();
        assertThatThrownBy(() -> c.doRequest("GET", "/target-5xx", null, null))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("feishu server error: status=500");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("4xx（非 429）→ 一次都不重试，抛 api error（body 不截断）")
    void fourXxNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        server.handle("/target-400", (ex, body) -> {
            attempts.incrementAndGet();
            FeishuTestServer.sendStatus(ex, 400, "{\"code\":1,\"msg\":\"bad request\"}");
        });

        FeishuClient c = client();
        assertThatThrownBy(() -> c.doRequest("GET", "/target-400", null, null))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("feishu api error: status=400 body={\"code\":1,\"msg\":\"bad request\"}");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("下载路径的 429 也重试")
    void downloadRetriesOn429ThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        server.handle("/dl-429", (ex, body) -> {
            if (attempts.incrementAndGet() == 1) {
                FeishuTestServer.sendStatusWithHeader(ex, 429, "Retry-After", "0", "");
                return;
            }
            FeishuTestServer.sendBytes(ex, "application/octet-stream",
                    "payload-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });

        FeishuClient c = client();
        byte[] data = c.downloadRawBytes("/dl-429");
        assertThat(new String(data, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("payload-bytes");
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("下载路径的 403 不重试")
    void download4xxNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        server.handle("/dl-403", (ex, body) -> {
            attempts.incrementAndGet();
            FeishuTestServer.sendStatus(ex, 403, "");
        });

        FeishuClient c = client();
        assertThatThrownBy(() -> c.downloadRawBytes("/dl-403"))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("download failed: status=403");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("parseRetryAfter（对照 Go 的表驱动用例）")
    void parseRetryAfterCases() {
        Duration fallback = Duration.ofSeconds(5);
        assertThat(FeishuClient.parseRetryAfter("", fallback)).isEqualTo(fallback);
        assertThat(FeishuClient.parseRetryAfter("0", fallback)).isEqualTo(Duration.ofMillis(100));
        assertThat(FeishuClient.parseRetryAfter("-1", fallback)).isEqualTo(Duration.ofMillis(100));
        assertThat(FeishuClient.parseRetryAfter("3", fallback)).isEqualTo(Duration.ofSeconds(3));
        assertThat(FeishuClient.parseRetryAfter("abc", fallback)).isEqualTo(fallback);
        assertThat(FeishuClient.parseRetryAfter(" 2 ", fallback)).isEqualTo(Duration.ofSeconds(2));
        assertThat(FeishuClient.parseRetryAfter("0.5", fallback)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("token 缓存：第二次调用不再打鉴权接口（对照 TestClientTokenCaching）")
    void tokenCaching() {
        AtomicInteger calls = new AtomicInteger();
        FeishuTestServer auth = null;
        try {
            auth = new FeishuTestServer();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        final FeishuTestServer authServer = auth;
        try {
            authServer.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                    "{\"code\":0,\"tenant_access_token\":\"token-" + calls.incrementAndGet()
                            + "\",\"expire\":7200}"));
            FeishuConfig cfg = new FeishuConfig();
            cfg.setAppId("a");
            cfg.setAppSecret("b");
            cfg.setBaseUrl(authServer.baseUrl());
            FeishuClient c = new FeishuClient(cfg);

            String t1 = c.getTenantAccessToken();
            String t2 = c.getTenantAccessToken();
            assertThat(t1).isEqualTo(t2);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            authServer.close();
        }
    }

    @Test
    @DisplayName("鉴权失败（code != 0）→ feishu auth error（分类成 auth_or_permission）")
    void authErrorSurfacesCodeAndMessage() {
        AtomicInteger calls = new AtomicInteger();
        FeishuTestServer auth;
        try {
            auth = new FeishuTestServer();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        try {
            auth.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                    "{\"code\":99991663,\"msg\":\"invalid access token\"}"));
            FeishuConfig cfg = new FeishuConfig();
            cfg.setAppId("a");
            cfg.setAppSecret("b");
            cfg.setBaseUrl(auth.baseUrl());
            FeishuClient c = new FeishuClient(cfg);

            assertThatThrownBy(c::getTenantAccessToken)
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("feishu auth error: code=99991663 msg=invalid access token");
            assertThat(calls.get()).isZero();
        } finally {
            auth.close();
        }
    }

    @Test
    @DisplayName("导出任务永不就绪 → 到达超时后抛错，且错误里带 ticket（对照 Go 的 60s 轮询循环）")
    void exportTimesOutWhenNeverReady() {
        server.handle("/open-apis/drive/v1/export_tasks", (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-x\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-x", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"\",\"file_size\":0,\"job_status\":2,"
                        + "\"job_error_msg\":\"\",\"file_name\":\"\"}}}"));

        Duration savedTimeout = FeishuClient.exportTimeout;
        Duration savedPoll = FeishuClient.exportPollInterval;
        FeishuClient.exportTimeout = Duration.ofMillis(50);
        FeishuClient.exportPollInterval = Duration.ZERO; // 不靠真实 sleep 推进
        try {
            assertThatThrownBy(() -> client().exportAndDownload("obj-x", "docx"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("export task timed out after")
                    .hasMessageContaining("ticket=ticket-x");
        } finally {
            FeishuClient.exportTimeout = savedTimeout;
            FeishuClient.exportPollInterval = savedPoll;
        }
    }

    @Test
    @DisplayName("导出任务就绪后下载：文件名沿用响应里的 file_name")
    void exportDownloadsWhenReady() {
        server.handle("/open-apis/drive/v1/export_tasks", (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-y\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-y", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"ft-y\",\"file_size\":3,\"job_status\":0,"
                        + "\"job_error_msg\":\"\",\"file_name\":\"季度报告.docx\"}}}"));
        server.handle("/open-apis/drive/v1/export_tasks/file/ft-y/download", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        FeishuClient.ExportDownload got = client().exportAndDownload("obj-y", "docx");
        assertThat(got.fileName()).isEqualTo("季度报告.docx");
        assertThat(new String(got.data(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    @DisplayName("导出 file_name 为空时回落成 export + 后缀（对照 Go 的 \"export\" + suffix）")
    void exportFallsBackToDefaultFileName() {
        server.handle("/open-apis/drive/v1/export_tasks", (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-z\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-z", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"ft-z\",\"file_size\":3,\"job_status\":0,"
                        + "\"job_error_msg\":\"\",\"file_name\":\"\"}}}"));
        server.handle("/open-apis/drive/v1/export_tasks/file/ft-z/download", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream", new byte[]{1}));

        assertThat(client().exportAndDownload("obj-z", "sheet").fileName()).isEqualTo("export.xlsx");
    }

    @Test
    @DisplayName("BASE：ConnectorHttp 的超时是 30s（对照 Go 的 30 * time.Second）")
    void requestTimeoutMatchesGo() {
        assertThat(FeishuClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
        assertThat(FeishuClient.MAX_RETRIES).isEqualTo(3);
        assertThat(FeishuClient.MAX_5XX_RETRIES).isEqualTo(1);
        assertThat(FeishuClient.MAX_DOWNLOAD_BYTES).isEqualTo(512L * 1024 * 1024);
    }
}
