package com.ragagent.datasource.connector.ima;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetAddableKnowledgeBaseListResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetKnowledgeListResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetMediaInfoResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.UrlInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IMA 客户端的 HTTP 行为测试（重试与错误映射，此处集中钉住）。
 *
 * <p>全部打在 {@link Scripted} 这个可编排响应的 {@code 127.0.0.1} stub 上；
 * 退避注入 {@link ImaRetryPolicy#immediate()}，所以 429/5xx 的多轮重试是毫秒级的。</p>
 */
class ImaClientTest {

    @BeforeAll
    static void allowLoopback() {
        FakeIma.allowLoopback();
    }

    @AfterAll
    static void restoreSsrf() {
        FakeIma.restoreSsrf();
    }

    // ── 编排式 stub ──────────────────────────────────────────────────────

    /** 一次响应。 */
    private record Rs(int status, String body, Map<String, String> headers) {
        static Rs of(int status, String body) {
            return new Rs(status, body, Map.of());
        }

        static Rs of(int status, String body, String header, String value) {
            return new Rs(status, body, Map.of(header, value));
        }
    }

    /** 按调用序号依次返回脚本里的响应，用尽后重复最后一条。 */
    private static final class Scripted implements AutoCloseable {
        private final HttpServer server;
        private final List<Rs> script;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> requestBodies = new CopyOnWriteArrayList<>();
        private final List<String> requestPaths = new CopyOnWriteArrayList<>();
        private final List<Map<String, String>> requestHeaders = new CopyOnWriteArrayList<>();

        Scripted(Rs... script) throws IOException {
            this.script = List.of(script);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            int n = calls.getAndIncrement();
            requestPaths.add(ex.getRequestURI().getPath());
            requestBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
                }
            });
            requestHeaders.add(headers);

            Rs r = script.get(Math.min(n, script.size() - 1));
            r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int calls() {
            return calls.get();
        }

        String requestBody(int index) {
            return requestBodies.get(index);
        }

        String lastRequestBody() {
            return requestBodies.get(requestBodies.size() - 1);
        }

        String requestPath(int index) {
            return requestPaths.get(index);
        }

        Map<String, String> requestHeaders(int index) {
            return requestHeaders.get(index);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static ImaClient client(Scripted stub) {
        ImaConfig cfg = new ImaConfig();
        cfg.setClientId("cid");
        cfg.setApiKey("key-super-secret");
        cfg.setBaseUrl(stub.baseUrl());
        return new ImaClient(cfg, ImaRetryPolicy.immediate());
    }

    /** 一个成功的信封（{@code {code:0,msg:"",data:...}}）。 */
    private static String envelope(String dataJson) {
        return "{\"code\":0,\"msg\":\"\",\"data\":" + dataJson + "}";
    }

    // ── 鉴权头与路径 ─────────────────────────────────────────────────────

    @Test
    void sendsBothCredentialHeadersAndTheActionPath() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, envelope("{\"is_end\":true}")))) {
            client(stub).getAddableKnowledgeBaseList("", ImaClient.DEFAULT_PAGE_SIZE);

            assertThat(stub.requestPath(0)).isEqualTo("/openapi/wiki/v1/get_addable_knowledge_base_list");
            Map<String, String> h = stub.requestHeaders(0);
            assertThat(h.get("ima-openapi-clientid")).isEqualTo("cid");
            assertThat(h.get("ima-openapi-apikey")).isEqualTo("key-super-secret");
            assertThat(h.get("user-agent")).isEqualTo(ImaClient.USER_AGENT);
            assertThat(h.get("content-type")).isEqualTo("application/json; charset=utf-8");
        }
    }

    /** 笔记走**另一个命名空间**（{@code /openapi/note/v1}）。 */
    @Test
    void noteContentUsesTheNoteNamespace() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, envelope("{\"content\":\"# note\"}")))) {
            String content = client(stub).getNoteContent("987654321");

            assertThat(content).isEqualTo("# note");
            assertThat(stub.requestPath(0)).isEqualTo("/openapi/note/v1/get_doc_content");
            assertThat(stub.lastRequestBody()).contains("\"note_id\":\"987654321\"")
                    .contains("\"target_content_format\":0");
        }
    }

    /** {@code folder_id} 只在非空时进请求体（root 时必须缺席）。 */
    @Test
    void folderIdIsOnlySentWhenNonEmpty() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, envelope("{\"is_end\":true}")),
                Rs.of(200, envelope("{\"is_end\":true}")))) {
            ImaClient client = client(stub);
            client.getKnowledgeList("kb1", "", "", ImaClient.DEFAULT_PAGE_SIZE);
            client.getKnowledgeList("kb1", "f1", "", ImaClient.DEFAULT_PAGE_SIZE);

            assertThat(stub.requestBody(0)).contains("\"knowledge_base_id\":\"kb1\"")
                    .doesNotContain("folder_id");
            assertThat(stub.requestBody(1)).contains("\"folder_id\":\"f1\"");
        }
    }

    /** 空 ids 不发请求，直接回空列表。 */
    @Test
    void emptyIdsDoesNotCallTheApi() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, envelope("{}")))) {
            assertThat(client(stub).getKnowledgeBase(List.of())).isEmpty();
            assertThat(stub.calls()).isZero();
        }
    }

    /** limit 的夹取：{@code <=0} 或超上限一律回落到各自的上限（50 / 20）。 */
    @Test
    void limitIsClamped() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, envelope("{\"is_end\":true}")),
                Rs.of(200, envelope("{\"is_end\":true}")))) {
            ImaClient client = client(stub);
            client.getAddableKnowledgeBaseList("", 0);
            client.searchKnowledgeBase("", "", 999);

            assertThat(stub.requestBody(0)).contains("\"limit\":50");
            assertThat(stub.requestBody(1)).contains("\"limit\":20");
        }
    }

    // ── 信封拼法 ─────────────────────────────────────────────────────────

    /** {@code retcode/errmsg} 变体必须被当成等价信封（否则错误会被静默读成成功）。 */
    @Test
    void acceptsRetcodeSpellingEndToEnd() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(FakeIma.FakeFile.of(
                    "m1", "T1", ImaFormats.MEDIA_TYPE_MARKDOWN).body("x")));
            f.setEnvelopeStyle(FakeIma.EnvelopeStyle.RETCODE_ERRMSG);

            GetKnowledgeListResp resp =
                    clientFor(f).getKnowledgeList("kb1", "", "", ImaClient.DEFAULT_PAGE_SIZE);
            assertThat(resp.getKnowledgeList()).hasSize(1);
        }
    }

    /** 非零 {@code retcode} 必须被当成错误抛出来（不是静默成功）。 */
    @Test
    void nonzeroRetcodeIsAnError() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, "{\"retcode\":110030,\"errmsg\":\"无权限\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("ima code=110030")
                    .hasMessageContaining("无权限");
        }
    }

    // ── 错误映射矩阵 ─────────────────────────────────────────────────────

    @Test
    void http401BecomesInvalidCredentials() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(401, "{\"msg\":\"unauthorized\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("status=401");
            assertThat(stub.calls()).as("401 must not be retried").isEqualTo(1);
        }
    }

    @Test
    void http403BecomesInvalidCredentials() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(403, "{\"msg\":\"forbidden\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("status=403");
            assertThat(stub.calls()).isEqualTo(1);
        }
    }

    /** {@code 110030}（无权限）映射成凭据错误，让 service 把数据源标成 error。 */
    @Test
    void businessCode110030BecomesInvalidCredentials() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, "{\"code\":110030,\"msg\":\"无权限\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("ima code=110030");
            assertThat(stub.calls()).as("110030 must not be retried").isEqualTo(1);
        }
    }

    /** {@code 110021}（限频）重试后成功。 */
    @Test
    void businessCode110021IsRetried() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, "{\"code\":110021,\"msg\":\"限频\"}"),
                Rs.of(200, envelope("{\"media_type\":7}")))) {
            GetMediaInfoResp resp = client(stub).getMediaInfo("m1");
            assertThat(resp.getMediaType()).isEqualTo(7);
            assertThat(stub.calls()).isEqualTo(2);
        }
    }

    /** 429 用尽重试预算后抛错（1 次初始 + 3 次重试）。 */
    @Test
    void http429ExhaustsRetries() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(429, "{\"msg\":\"slow down\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .hasMessageContaining("ima rate limited: status=429");
            assertThat(stub.calls()).isEqualTo(4);
        }
    }

    /** 5xx 只重试**一次**（{@code max5xxRetries=1}）→ 2 次尝试。 */
    @Test
    void http5xxRetriesExactlyOnce() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(500, "boom"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .hasMessageContaining("ima server error: status=500");
            assertThat(stub.calls()).isEqualTo(2);
        }
    }

    /** 其它非 2xx 不重试，错误文案形如 {@code ima api http error: status=<n>}。 */
    @Test
    void otherHttpErrorsAreNotRetried() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(400, "{\"msg\":\"bad\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .hasMessageContaining("ima api http error: status=400");
            assertThat(stub.calls()).isEqualTo(1);
        }
    }

    /** 其它非零业务码原文透出（含 {@code 110001} 参数非法——那是我方的 bug）。 */
    @Test
    void otherBusinessCodesSurfaceVerbatim() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, "{\"code\":110001,\"msg\":\"参数非法\"}"))) {
            assertThatThrownBy(() -> client(stub).getMediaInfo("m1"))
                    .isInstanceOf(ConnectorException.class)
                    .isNotInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("ima api error: code=110001 msg=参数非法");
            assertThat(stub.calls()).as("110001 must not be retried").isEqualTo(1);
        }
    }

    /** 传输层失败会重试到预算用尽（4 次尝试）。 */
    @Test
    void transportFailureIsRetried() throws Exception {
        // 端口 1 上没有监听者 → 连接被拒。
        ImaConfig cfg = new ImaConfig();
        cfg.setClientId("cid");
        cfg.setApiKey("key");
        cfg.setBaseUrl("http://127.0.0.1:1");
        ImaClient client = new ImaClient(cfg, ImaRetryPolicy.immediate());

        assertThatThrownBy(() -> client.getMediaInfo("m1"))
                .isInstanceOf(ConnectorException.class);
    }

    // ── 下载 ─────────────────────────────────────────────────────────────

    @Test
    void downloadReturnsBodyAndContentType() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(FakeIma.FakeFile.of(
                    "m-img", "Photo", ImaFormats.MEDIA_TYPE_IMAGE)
                    .body("jpeg-bytes").contentType("image/jpeg")));

            UrlInfo url = new UrlInfo();
            url.setUrl(f.baseUrl() + "/dl/m-img");
            ImaClient.DownloadResult result = clientFor(f).downloadUrl(url);

            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("jpeg-bytes");
            assertThat(result.contentType()).isEqualTo("image/jpeg");
        }
    }

    @Test
    void downloadRejectsEmptyUrl() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, ""))) {
            UrlInfo url = new UrlInfo();
            url.setUrl("");
            assertThatThrownBy(() -> client(stub).downloadUrl(url))
                    .hasMessageContaining("empty url");
        }
    }

    /**
     * 下载 URL 来自 API 响应（攻击者可影响），所以先过 SSRF 策略：
     * 直连内网/元数据地址必须被拒，且文案带 {@code media URL rejected:}。
     */
    @Test
    void downloadRejectsSsrfUnsafeTarget() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, ""))) {
            UrlInfo url = new UrlInfo();
            url.setUrl("http://169.254.169.254/latest/meta-data/");
            assertThatThrownBy(() -> client(stub).downloadUrl(url))
                    .hasMessageContaining("media URL rejected");
            assertThat(stub.calls()).isZero();
        }
    }

    @Test
    void downloadNon2xxIsAnError() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(FakeIma.FakeFile.of(
                    "m-x", "X", ImaFormats.MEDIA_TYPE_PDF).body("x").downloadFails()));

            UrlInfo url = new UrlInfo();
            url.setUrl(f.baseUrl() + "/dl/m-x");
            assertThatThrownBy(() -> clientFor(f).downloadUrl(url))
                    .hasMessageContaining("download http error: status=500");
        }
    }

    /** 下载请求要带上 IMA 随 URL 给的鉴权头与统一 UA。 */
    @Test
    void downloadForwardsUrlHeaders() throws Exception {
        try (FakeIma f = new FakeIma()) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Ima-Token", "secret");
            f.setKb("kb1", List.of(FakeIma.FakeFile.of(
                    "m-web", "Web", ImaFormats.MEDIA_TYPE_WEB).body("body").urlHeaders(headers)));

            UrlInfo url = new UrlInfo();
            url.setUrl(f.baseUrl() + "/dl/m-web");
            url.setHeaders(headers);
            clientFor(f).downloadUrl(url);

            // JDK 的 HttpServer 把小写化后的头名交给我们（见 FakeIma.handleDownload）。
            assertThat(f.downloadHeaders("m-web"))
                    .containsEntry("x-ima-token", "secret")
                    .containsEntry("user-agent", ImaClient.USER_AGENT);
        }
    }

    /** 打 FakeIma 的客户端（base_url 指向 stub；ImaClient 自身不做 SSRF 校验）。 */
    private static ImaClient clientFor(FakeIma f) {
        ImaConfig cfg = new ImaConfig();
        cfg.setClientId("cid");
        cfg.setApiKey("key-super-secret");
        cfg.setBaseUrl(f.baseUrl());
        return new ImaClient(cfg, ImaRetryPolicy.immediate());
    }

    /** {@code get_media_info} 缺失 {@code data}（{@code null}）时回零值而不是 NPE。 */
    @Test
    void missingDataYieldsZeroValue() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, "{\"code\":0,\"msg\":\"\",\"data\":null}"))) {
            GetAddableKnowledgeBaseListResp resp =
                    client(stub).getAddableKnowledgeBaseList("", ImaClient.DEFAULT_PAGE_SIZE);
            assertThat(resp.getAddableKnowledgeBaseList()).isNull();
            assertThat(resp.isEnd()).isFalse();
        }
    }

    @Test
    @DisplayName("令牌脱敏后进日志，原始密钥不出现在任何请求之外的地方")
    void credentialsAreNeverLoggedInFull() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, envelope("{\"media_type\":1}")))) {
            client(stub).getMediaInfo("m1");
            assertThat(ImaClient.redact("key-super-secret")).isEqualTo("key-su...cret");
        }
    }
}
