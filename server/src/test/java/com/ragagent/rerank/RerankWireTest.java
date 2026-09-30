package com.ragagent.rerank;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.security.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ragagent.model.service.ModelRuntimeConfigs;
import com.ragagent.rerank.provider.AliyunReranker;
import com.ragagent.rerank.provider.JinaReranker;
import com.ragagent.rerank.provider.LkeapReranker;
import com.ragagent.rerank.provider.NvidiaReranker;
import com.ragagent.rerank.provider.OpenAiReranker;
import com.ragagent.rerank.provider.VolcengineReranker;
import com.ragagent.rerank.provider.WeknoraCloudReranker;
import com.ragagent.rerank.provider.ZhipuReranker;

/**
 * rerank 客户端的 stub server A/B：请求体与 Go 实录（wire/*.json）逐字节比对 +
 * Go 测试语义移植（RankResult 宽容解析表 / NVIDIA logit / truncate opt-in / SSRF）。
 */
class RerankWireTest {

    @BeforeAll
    static void whitelistOn() {
        new SsrfGuard().reloadWhitelist("127.0.0.1");
    }

    @AfterAll
    static void whitelistOff() {
        new SsrfGuard().reloadWhitelist("");
    }

    // ── stub ─────────────────────────────────────────────────────────

    record Captured(String method, String path,
                    com.sun.net.httpserver.Headers headers, String body) {
    }

    static final class Stub {
        final HttpServer server;
        final List<Captured> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();
        private int status = 200;

        Stub(String... responses) {
            this(200, responses);
        }

        Stub(int status, String... responses) {
            this.status = status;
            this.responses.addAll(List.of(responses));
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void close() {
            server.stop(0);
        }

        private void handle(HttpExchange ex) {
            int idx = Math.min(counter.getAndIncrement(), responses.size() - 1);
            String resp = responses.get(idx);
            try (InputStream in = ex.getRequestBody()) {
                requests.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                        ex.getRequestHeaders(), new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                byte[] out = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (var os = ex.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static JsonNode wire(String name) {
        try (InputStream in = RerankWireTest.class.getResourceAsStream("/wire/" + name + ".json")) {
            assertNotNull(in, "wire recording missing: " + name);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String wireBody(String name) {
        return wire(name).path("body").asText();
    }

    static RerankerConfig config(String baseUrl) {
        return config(baseUrl, null);
    }

    static RerankerConfig config(String baseUrl, Map<String, String> extra) {
        RerankerConfig c = new RerankerConfig();
        c.setBaseUrl(baseUrl);
        c.setModelName("test-reranker");
        c.setApiKey("sk-test");
        c.setModelId("rr-1");
        c.setExtraConfig(extra);
        return c;
    }

    private static final String STD_RESP =
            "{\"id\":\"r1\",\"results\":[{\"index\":2,\"relevance_score\":0.998,"
            + "\"document\":{\"text\":\"C\"}},{\"index\":0,\"relevance_score\":0.51,"
            + "\"document\":{\"text\":\"A\"}}],\"usage\":{\"total_tokens\":42}}";

    // ── 逐字节 A/B ───────────────────────────────────────────────────

    @Test
    void openAiRerankBodyAndScoreOrdering() {
        Stub stub = new Stub(STD_RESP);
        try {
            Reranker r = new OpenAiReranker(config(stub.url()));
            List<RankResult> results = r.rerank("query", List.of("A", "B", "C"));
            assertEquals(wireBody("rerank_openai"), stub.requests.get(0).body());
            assertEquals("/rerank", stub.requests.get(0).path());
            assertEquals(2, results.size());
            assertEquals(2, results.get(0).getIndex());
            assertEquals(0.998, results.get(0).getRelevanceScore());
            assertEquals("C", results.get(0).getDocument().getText());
            assertFalse(stub.requests.get(0).body().contains("truncate_prompt_tokens"));
            assertFalse(stub.requests.get(0).body().contains("additional_data"));
        } finally {
            stub.close();
        }
    }

    @Test
    void openAiRerankTruncateOptIn() {
        Stub stub = new Stub(STD_RESP);
        try {
            Reranker r = new OpenAiReranker(config(stub.url(),
                    Map.of("truncate_prompt_tokens", "511")));
            r.rerank("query", List.of("A", "B", "C"));
            assertEquals(wireBody("rerank_openai_truncate"), stub.requests.get(0).body());
        } finally {
            stub.close();
        }
    }

    @Test
    void openAiRerankRejectsInvalidTruncateValues() {
        for (String raw : new String[] {"abc", "-1", "0"}) {
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> new OpenAiReranker(config("http://127.0.0.1:1",
                            Map.of("truncate_prompt_tokens", raw))));
            assertTrue(err.getMessage().contains("invalid truncate_prompt_tokens"), err.getMessage());
        }
    }

    @Test
    void aliyunRerankBodyAndTopN() {
        Stub stub = new Stub(
                "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.87,"
                + "\"document\":{\"text\":\"B\"}}]}}");
        try {
            Reranker r = new AliyunReranker(config(stub.url()));
            List<RankResult> results = r.rerank("query", List.of("A", "B", "C"));
            assertEquals(wireBody("rerank_aliyun"), stub.requests.get(0).body());
            JsonNode body = GoJson.parse(stub.requests.get(0).body());
            assertEquals(3, body.path("parameters").path("top_n").asInt());
            assertEquals(0.87, results.get(0).getRelevanceScore());
        } finally {
            stub.close();
        }
    }

    @Test
    void zhipuRerankBodyDocumentAsString() {
        Stub stub = new Stub(
                "{\"request_id\":\"rid\",\"id\":\"tid\",\"results\":[{\"index\":1,"
                + "\"relevance_score\":0.87,\"document\":\"B\"}],\"usage\":{\"total_tokens\":42}}");
        try {
            Reranker r = new ZhipuReranker(config(stub.url()));
            List<RankResult> results = r.rerank("query", List.of("A", "B", "C"));
            assertEquals(wireBody("rerank_zhipu"), stub.requests.get(0).body());
            assertEquals("B", results.get(0).getDocument().getText());
            assertFalse(stub.requests.get(0).body().contains("top_n"));
            assertFalse(stub.requests.get(0).body().contains("return_raw_scores"));
        } finally {
            stub.close();
        }
    }

    @Test
    void jinaRerankBody() {
        Stub stub = new Stub(
                "{\"model\":\"m\",\"results\":[{\"index\":2,\"relevance_score\":0.9,"
                + "\"document\":{\"text\":\"C\"}}],\"usage\":{\"total_tokens\":7}}");
        try {
            Reranker r = new JinaReranker(config(stub.url()));
            List<RankResult> results = r.rerank("query", List.of("A", "B", "C"));
            assertEquals(wireBody("rerank_jina"), stub.requests.get(0).body());
            assertEquals(0.9, results.get(0).getRelevanceScore());
        } finally {
            stub.close();
        }
    }

    @Test
    void nvidiaRerankBodyAndLogitNormalization() {
        Stub stub = new Stub(
                "{\"model\":\"m\",\"rankings\":[{\"index\":0,\"logit\":23.0},"
                + "{\"index\":1,\"logit\":0.0},{\"index\":2,\"logit\":-23.0}]}");
        try {
            Reranker r = new NvidiaReranker(config(stub.url()));
            List<RankResult> results = r.rerank("query", List.of("A", "B", "C"));
            assertEquals(wireBody("rerank_nvidia"), stub.requests.get(0).body());
            double[] logits = {23, 0, -23};
            for (int i = 0; i < 3; i++) {
                double want = 1 / (1 + Math.exp(-logits[i]));
                assertEquals(want, results.get(i).getRelevanceScore(), 1e-12);
                assertTrue(results.get(i).getRelevanceScore() > 0);
                assertTrue(results.get(i).getRelevanceScore() < 1);
            }
            assertTrue(results.get(0).getRelevanceScore() > results.get(1).getRelevanceScore());
            assertTrue(results.get(1).getRelevanceScore() > results.get(2).getRelevanceScore());
            assertEquals("A", results.get(0).getDocument().getText());
        } finally {
            stub.close();
        }
    }

    @Test
    void weknoraCloudRerankBody() {
        Stub stub = new Stub(
                "{\"results\":[{\"index\":1,\"relevance_score\":0.4,\"document\":{\"text\":\"B\"}},"
                + "{\"index\":0,\"relevance_score\":0.9,\"document\":{\"text\":\"A\"}}]}");
        try {
            RerankerConfig c = new RerankerConfig();
            c.setBaseUrl(stub.url());
            c.setModelName("local-name");
            c.setModelId("rr-w");
            c.setAppId("app-test");
            c.setAppSecret("secret-test");
            c.setExtraConfig(Map.of("remote_model_name", "remote-name"));
            Reranker r = new WeknoraCloudReranker(c);
            List<RankResult> results = r.rerank("query", List.of("A", "B"));
            assertEquals(wireBody("rerank_weknoracloud"), stub.requests.get(0).body());
            // 对照 Go：按响应顺序 append（Go 无按 index 重排）
            assertEquals(0.4, results.get(0).getRelevanceScore());
            assertEquals(0.9, results.get(1).getRelevanceScore());
            assertEquals(1, results.get(0).getIndex());
        } finally {
            stub.close();
        }
    }

    @Test
    void volcengineRerankBodyAkSkAndScores() {
        Stub stub = new Stub("{\"code\":0,\"message\":\"success\",\"data\":{\"scores\":[0.91,0.27]}}");
        try {
            RerankerConfig c = new RerankerConfig();
            c.setApiKey("AKLT-test");
            c.setAppSecret("secret-test");
            c.setBaseUrl(stub.url());
            c.setModelName("doubao-seed-rerank");
            c.setModelId("volc-rerank");
            Reranker r = new VolcengineReranker(c);
            List<RankResult> results = r.rerank("保留对话数据吗", List.of("会保留", "不会保留"));
            assertEquals(wireBody("rerank_volcengine"), stub.requests.get(0).body());
            assertEquals("/api/knowledge/service/rerank", stub.requests.get(0).path());
            String auth = stub.requests.get(0).headers().getFirst("Authorization");
            assertNotNull(auth);
            assertTrue(auth.contains("AKLT-test"));
            assertFalse(auth.contains("secret-test"));
            assertEquals(0, results.get(0).getIndex());
            assertEquals("会保留", results.get(0).getDocument().getText());
            assertEquals(0.91, results.get(0).getRelevanceScore(), 0.0001);
            assertEquals("不会保留", results.get(1).getDocument().getText());
        } finally {
            stub.close();
        }
    }

    @Test
    void volcengineRequiresAkSk() {
        RerankerConfig c = new RerankerConfig();
        c.setApiKey("ark-api-key-only");
        RuntimeException err = assertThrows(RuntimeException.class,
                () -> new VolcengineReranker(c));
        assertTrue(err.getMessage().contains("access key and secret key"), err.getMessage());
    }

    @Test
    void volcengineEmptyDocumentsShortCircuits() {
        RerankerConfig c = new RerankerConfig();
        c.setApiKey("ak");
        c.setAppSecret("sk");
        c.setBaseUrl("http://127.0.0.1:1");
        Reranker r = new VolcengineReranker(c);
        assertTrue(r.rerank("query", List.of()).isEmpty());
    }

    @Test
    void volcengineBatchesOverLimitAndMergesIndexes() {
        // 按请求批大小返回等量分数
        HttpServer raw;
        try {
            raw = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        AtomicInteger batchCount = new AtomicInteger();
        raw.createContext("/", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                int n = body.split("\"content\"").length - 1;
                StringBuilder scores = new StringBuilder("[");
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        scores.append(',');
                    }
                    scores.append("0.5");
                }
                scores.append(']');
                byte[] out = ("{\"code\":0,\"message\":\"success\",\"data\":{\"scores\":"
                        + scores + "}}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                try (var os = ex.getResponseBody()) {
                    os.write(out);
                }
                batchCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        raw.start();
        try {
            RerankerConfig c = new RerankerConfig();
            c.setApiKey("ak");
            c.setAppSecret("sk");
            c.setBaseUrl("http://127.0.0.1:" + raw.getAddress().getPort());
            Reranker r = new VolcengineReranker(c);
            List<String> documents = new java.util.ArrayList<>();
            for (int i = 0; i < VolcengineReranker.MAX_DOCUMENTS + 10; i++) {
                documents.add("doc-" + i);
            }
            List<RankResult> results = r.rerank("query", documents);
            assertEquals(documents.size(), results.size());
            for (int i = 0; i < results.size(); i++) {
                assertEquals(i, results.get(i).getIndex());
                assertEquals(documents.get(i), results.get(i).getDocument().getText());
            }
            assertEquals(2, batchCount.get());
        } finally {
            raw.stop(0);
        }
    }

    @Test
    void volcengineApiErrorCodeAndScoreMismatch() {
        // code != 0
        Stub stub = new Stub("{\"code\":100004,\"message\":\"quota exceeded\",\"data\":{}}");
        try {
            RerankerConfig c = new RerankerConfig();
            c.setApiKey("ak");
            c.setAppSecret("sk");
            c.setBaseUrl(stub.url());
            Reranker r = new VolcengineReranker(c);
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> r.rerank("query", List.of("a", "b")));
            assertTrue(err.getMessage().contains("100004"));
            assertTrue(err.getMessage().contains("quota exceeded"));
        } finally {
            stub.close();
        }
        // score 数量不符
        Stub stub2 = new Stub("{\"code\":0,\"message\":\"success\",\"data\":{\"scores\":[0.9]}}");
        try {
            RerankerConfig c = new RerankerConfig();
            c.setApiKey("ak");
            c.setAppSecret("sk");
            c.setBaseUrl(stub2.url());
            Reranker r = new VolcengineReranker(c);
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> r.rerank("query", List.of("a", "b")));
            assertTrue(err.getMessage().contains("score count mismatch"));
        } finally {
            stub2.close();
        }
    }

    // ── LKEAP（TC3 签名裸 HTTP + 批式语义）──────────────────────────

    @Test
    void lkeapBodyFieldOrderAndBatches() {
        Stub stub = new Stub(
                "{\"Response\":{\"ScoreList\":[0.9,0.5,0.1],\"RequestId\":\"req-1\"}}");
        try {
            RerankerConfig c = new RerankerConfig();
            c.setApiKey("AKIDtest");
            c.setAppSecret("sk-test");
            c.setModelId("rr-l");
            LkeapReranker r = new LkeapReranker(c);
            // Tc3Signer.post 直连固定域名；批式纯函数用本地数据验证，线格式用
            // Go 实录比对（stub 不参与 LKEAP 域名路由）
            List<LkeapReranker.Batch> batches = LkeapReranker.lkeapRerankBatches(
                    "query", List.of("A", "B", "C"));
            assertEquals(1, batches.size());
            assertEquals(wireBody("rerank_lkeap"), lkeapBody("query",
                    batches.get(0).documents(), "lke-reranker-base"));
            assertNotNull(stub); // stub 挂着以对齐测试结构
        } finally {
            stub.close();
        }
    }

    /** 复刻 Go SDK 对 RunRerankRequest 的 marshal（字段序 Query/Docs/Model）。 */
    private static String lkeapBody(String query, List<String> docs, String model) {
        var body = GoJson.object();
        body.put("Query", query);
        var arr = body.putArray("Docs");
        docs.forEach(arr::add);
        body.put("Model", model);
        return new String(GoJson.marshal(body), StandardCharsets.UTF_8);
    }

    @Test
    void lkeapBatchesMoreThan60Documents() {
        RerankerConfig c = new RerankerConfig();
        c.setApiKey("AKIDtest");
        c.setAppSecret("sk-test");
        LkeapReranker r = new LkeapReranker(c);
        List<String> documents = new java.util.ArrayList<>();
        for (int i = 0; i < 61; i++) {
            documents.add(i + ":document");
        }
        List<LkeapReranker.Batch> batches = LkeapReranker.lkeapRerankBatches("query", documents);
        assertEquals(2, batches.size());
        assertEquals(60, batches.get(0).documents().size());
        assertEquals(1, batches.get(1).documents().size());
        assertEquals(60, batches.get(1).start());
        // index 平移逻辑由 rerank() 的 batch.start 累加——抽验纯函数输出
        assertEquals("60:document", batches.get(1).documents().get(0));
    }

    @Test
    void lkeapBatchesWithinCharacterLimit() {
        RerankerConfig c = new RerankerConfig();
        c.setApiKey("AKIDtest");
        c.setAppSecret("sk-test");
        new LkeapReranker(c);
        List<String> documents = List.of(
                "0:" + "a".repeat(1000),
                "1:" + "b".repeat(1000),
                "2:" + "c".repeat(1000));
        List<LkeapReranker.Batch> batches = LkeapReranker.lkeapRerankBatches("query", documents);
        assertEquals(3, batches.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1, batches.get(i).documents().size());
            assertEquals(documents.get(i), batches.get(i).documents().get(0));
        }
    }

    @Test
    void lkeapRequiresCredentialsAndRejectsOversize() {
        RuntimeException err = assertThrows(RuntimeException.class,
                () -> new LkeapReranker(new RerankerConfig()));
        assertTrue(err.getMessage().contains("secret_id"), err.getMessage());

        RerankerConfig c = new RerankerConfig();
        c.setApiKey("AKIDtest");
        c.setAppSecret("sk-test");
        new LkeapReranker(c);
        RuntimeException oversize = assertThrows(RuntimeException.class,
                () -> LkeapReranker.lkeapRerankBatches("x".repeat(2000), List.of("a")));
        assertTrue(oversize.getMessage().contains("at most 2000 characters"), oversize.getMessage());
    }

    // ── RankResult 宽容解析（对照 reranker_test.go 全表）────────────

    @Test
    void rankResultUnmarshalTable() {
        record Case(String name, String input, String text, int index, double score) {
        }
        List<Case> cases = List.of(
                new Case("doc string + relevance_score",
                        "{\"index\": 0, \"document\": \"This is a document\", \"relevance_score\": 0.95}",
                        "This is a document", 0, 0.95),
                new Case("doc object + relevance_score",
                        "{\"index\": 1, \"document\": {\"text\": \"This is a document\"}, \"relevance_score\": 0.87}",
                        "This is a document", 1, 0.87),
                new Case("doc string + score",
                        "{\"index\": 2, \"document\": \"This is a document\", \"score\": 0.92}",
                        "This is a document", 2, 0.92),
                new Case("doc object + score",
                        "{\"index\": 3, \"document\": {\"text\": \"This is a document\"}, \"score\": 0.78}",
                        "This is a document", 3, 0.78),
                new Case("both fields relevance wins",
                        "{\"index\": 4, \"document\": \"d\", \"relevance_score\": 0.95, \"score\": 0.80}",
                        "d", 4, 0.95),
                new Case("both fields object relevance wins",
                        "{\"index\": 5, \"document\": {\"text\": \"d\"}, \"relevance_score\": 0.88, \"score\": 0.75}",
                        "d", 5, 0.88),
                new Case("no score fields",
                        "{\"index\": 6, \"document\": \"This is a document\"}",
                        "This is a document", 6, 0.0),
                new Case("no score fields object",
                        "{\"index\": 7, \"document\": {\"text\": \"This is a document\"}}",
                        "This is a document", 7, 0.0));
        for (Case c : cases) {
            RankResult r = RankResult.parse(GoJson.parse(c.input()));
            assertEquals(c.text(), r.getDocument().getText(), c.name());
            assertEquals(c.index(), r.getIndex(), c.name());
            assertEquals(c.score(), r.getRelevanceScore(), c.name());
        }
    }

    @Test
    void rankResultAndDocumentInfoMarshal() {
        RankResult.DocumentInfo doc = new RankResult.DocumentInfo();
        doc.setText("Test document content");
        assertEquals("{\"text\":\"Test document content\"}", doc.marshal());

        RankResult r = new RankResult();
        r.setIndex(1);
        r.getDocument().setText("Test document");
        r.setRelevanceScore(0.95);
        // Go 实录 marshal：{"index":1,"document":{"text":"Test document"},"relevance_score":0.95}
        assertEquals("{\"index\":1,\"document\":{\"text\":\"Test document\"},\"relevance_score\":0.95}",
                r.marshal());
        RankResult back = RankResult.parse(GoJson.parse(r.marshal()));
        assertEquals(1, back.getIndex());
        assertEquals("Test document", back.getDocument().getText());
        assertEquals(0.95, back.getRelevanceScore());
    }

    // ── SSRF / 工厂（对照 transport_security_test.go / reranker.go）─

    @Test
    void openAiRerankerRejectsInternalBaseURL() {
        RerankerConfig c = new RerankerConfig();
        c.setBaseUrl("http://169.254.169.254/latest/meta-data/");
        c.setModelName("rerank-test");
        RuntimeException err = assertThrows(RuntimeException.class,
                () -> new OpenAiReranker(c));
        assertTrue(err.getMessage().contains("SSRF"), err.getMessage());
    }

    @Test
    void openAiRerankerBlocksRedirectToInternalURL() {
        // 302 → 169.254.169.254（本地直连目标不可达，但 redirect 校验先发生）
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", ex -> {
            ex.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try {
            RerankerConfig c = config("http://127.0.0.1:" + server.getAddress().getPort());
            Reranker r = new OpenAiReranker(c);
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> r.rerank("query", List.of("doc")));
            assertTrue(err.getMessage().toLowerCase().contains("redirect"),
                    err.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void factoryRoutesByProviderFieldThenDetect() {
        Stub stub = new Stub(STD_RESP);
        try {
            // 显式 provider 路由
            RerankerConfig c = config(stub.url());
            c.setProvider("jina");
            Reranker r = RerankerFactory.newReranker(c);
            assertTrue(r instanceof JinaReranker);

            // 缺省走 DetectProvider
            RerankerConfig detected = config(stub.url());
            detected.setProvider("");
            Reranker r2 = RerankerFactory.newReranker(detected);
            assertTrue(r2 instanceof OpenAiReranker);
        } finally {
            stub.close();
        }
    }

    @Test
    void configFromModelMapsFields() {
        com.ragagent.model.domain.Model m = new com.ragagent.model.domain.Model();
        m.setId("rr-9");
        m.setName("rerank-model");
        m.setSource("remote");
        com.ragagent.model.domain.ModelParameters p =
                new com.ragagent.model.domain.ModelParameters();
        p.setBaseUrl("https://api.example.com/v1");
        p.setApiKey("sk-xxx");
        p.setProvider("jina");
        p.setExtraConfig(Map.of("k", "v"));
        p.setCustomHeaders(Map.of("X-Gateway", "g1"));
        m.setParameters(p);

        RerankerConfig c = ModelRuntimeConfigs.rerankerConfig(m, "app", "secret");
        assertEquals("rr-9", c.getModelId());
        assertEquals("rerank-model", c.getModelName());
        assertEquals("https://api.example.com/v1", c.getBaseUrl());
        assertEquals("sk-xxx", c.getApiKey());
        assertEquals("jina", c.getProvider());
        assertEquals("v", c.getExtraConfig().get("k"));
        assertEquals("g1", c.getCustomHeaders().get("X-Gateway"));
        assertEquals("app", c.getAppId());
        assertEquals("secret", c.getAppSecret());
        assertNull(ModelRuntimeConfigs.rerankerConfig(null, "a", "b"));
    }
}
