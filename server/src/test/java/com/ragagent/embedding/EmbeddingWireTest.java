package com.ragagent.embedding;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.embedding.provider.AliyunEmbedder;
import com.ragagent.embedding.provider.AzureOpenAiEmbedder;
import com.ragagent.embedding.provider.GeminiEmbedder;
import com.ragagent.embedding.provider.JinaEmbedder;
import com.ragagent.embedding.provider.NvidiaEmbedder;
import com.ragagent.embedding.provider.OpenAiEmbedder;
import com.ragagent.embedding.provider.VolcengineEmbedder;
import com.ragagent.embedding.provider.WeknoraCloudEmbedder;
import com.ragagent.embedding.provider.ZhipuEmbedder;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ragagent.model.service.ModelRuntimeConfigs;

/**
 * embedding 客户端的 stub server A/B：请求体/路径/头部与 Go 实录（/tmp 录制，
 * server/src/test/resources/wire/*.json）逐字节比对；错误分支（401/429/5xx/SSRF）
 * 对照 Go 判定。测试禁真实网络——全部打 127.0.0.1 stub。
 */
class EmbeddingWireTest {

    private static String whitelistBackupNote;

    @BeforeAll
    static void whitelistOn() {
        // SsrfGuard 白名单是进程级静态：先记一个"已改"标记，@AfterAll 还原（§7.8）
        whitelistBackupNote = "modified-by-EmbeddingWireTest";
        new SsrfGuard().reloadWhitelist("127.0.0.1");
    }

    @AfterAll
    static void whitelistOff() {
        // 还原为空表（默认进程态），避免污染同 JVM 的其它套件
        new SsrfGuard().reloadWhitelist("");
    }

    // ── stub server ──────────────────────────────────────────────────

    record Captured(String method, String path, String query,
                    com.sun.net.httpserver.Headers headers, String body) {
    }

    /** 响应序列：每次请求依次弹出（供重定向/多形态测试）。 */
    static final class Stub {
        final HttpServer server;
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        final List<String> responses = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger();
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
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Captured(ex.getRequestMethod(),
                        ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(),
                        ex.getRequestHeaders(), body));
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
        try (InputStream in = EmbeddingWireTest.class
                .getResourceAsStream("/wire/" + name + ".json")) {
            assertNotNull(in, "wire recording missing: " + name);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String wireBody(String name) {
        return wire(name).path("body").asText();
    }

    private static List<float[]> embed(Embedder e, String... texts) {
        return e.batchEmbed(List.of(texts));
    }

    // ── 逐字节请求体 A/B ─────────────────────────────────────────────

    @Test
    void openAiDefaultOmitsDimensions() {
        Stub stub = new Stub("{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0},{\"embedding\":[0.4],\"index\":1}]}");
        try {
            Embedder e = new OpenAiEmbedder("sk-test", stub.url(), "text-embedding-3-small",
                    511, 256, "emb-1", null);
            List<float[]> got = embed(e, "hello world", "second text");
            assertEquals(2, got.size());
            assertEquals(wireBody("openai_default"), stub.requests.get(0).body());
            assertEquals("/embeddings", stub.requests.get(0).path());
            assertEquals("Bearer sk-test",
                    stub.requests.get(0).headers().getFirst("Authorization"));
        } finally {
            stub.close();
        }
    }

    @Test
    void openAiOverrideSendsDimensions() {
        Stub stub = new Stub("{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}]}");
        try {
            OpenAiEmbedder e = new OpenAiEmbedder("sk-test", stub.url(),
                    "text-embedding-3-small", 511, 256, "emb-1", null);
            e.setSupportsDimensionOverride(true);
            embed(e, "hello world", "second text");
            assertEquals(wireBody("openai_override"), stub.requests.get(0).body());
        } finally {
            stub.close();
        }
    }

    @Test
    void aliyunMultimodalRequestAndIndexReorder() {
        Stub stub = new Stub(
                "{\"output\":{\"embeddings\":[{\"embedding\":[0.3,0.4],\"text_index\":1},"
                + "{\"embedding\":[0.1,0.2],\"text_index\":0}]},\"usage\":{\"total_tokens\":10}}");
        try {
            Embedder e = new AliyunEmbedder("sk-test", stub.url(), "multimodal-embedding-v1",
                    511, 0, "emb-2", null);
            List<float[]> got = embed(e, "hello world", "second text");
            assertEquals(wireBody("aliyun"), stub.requests.get(0).body());
            assertEquals("/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding",
                    stub.requests.get(0).path());
            // 按 text_index 回填输入顺序
            assertEquals(0.1f, got.get(0)[0]);
            assertEquals(0.3f, got.get(1)[0]);
        } finally {
            stub.close();
        }
    }

    @Test
    void aliyunErrorBodyParsedAsCodeDashMessage() {
        Stub stub = new Stub(500, "{\"code\":\"InvalidApiKey\",\"message\":\"invalid api key\"}");
        try {
            Embedder e = new AliyunEmbedder("sk-test", stub.url(), "m", 511, 0, "id", null);
            EmbeddingHttp.EmbeddingException err = assertThrows(
                    EmbeddingHttp.EmbeddingException.class, () -> embed(e, "x"));
            assertTrue(err.getMessage().contains("API error: InvalidApiKey - invalid api key"),
                    err.getMessage());
            assertEquals(1, stub.requests.size(), "非 2xx 不重试（对照 Go）");
        } finally {
            stub.close();
        }
    }

    @Test
    void volcengineEmbedsOneByOne() {
        Stub stub = new Stub(
                "{\"object\":\"embedding\",\"data\":{\"embedding\":[0.5,0.6]},\"model\":\"m\","
                + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":2}}");
        try {
            Embedder e = new VolcengineEmbedder("sk-test", stub.url(), "doubao-embedding",
                    511, 0, "emb-3", null);
            List<float[]> got = embed(e, "one");
            assertEquals(wireBody("volcengine"), stub.requests.get(0).body());
            assertEquals("/api/v3/embeddings/multimodal", stub.requests.get(0).path());
            assertEquals(0.5f, got.get(0)[0]);
        } finally {
            stub.close();
        }
    }

    @Test
    void volcengineApiErrorCarriesCodeAndMessage() {
        Stub stub = new Stub(401, "{\"error\":{\"code\":\"AuthenticationError\",\"message\":\"bad key\"}}");
        try {
            Embedder e = new VolcengineEmbedder("sk-test", stub.url(), "m", 511, 0, "id", null);
            EmbeddingHttp.EmbeddingException err = assertThrows(
                    EmbeddingHttp.EmbeddingException.class, () -> embed(e, "x"));
            assertTrue(err.getMessage().contains("API error: AuthenticationError - bad key"),
                    err.getMessage());
        } finally {
            stub.close();
        }
    }

    @Test
    void jinaUsesTruncateBooleanAndDimensions() {
        Stub stub = new Stub("{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}]}");
        try {
            JinaEmbedder e = new JinaEmbedder("sk-test", stub.url(), "jina-embeddings-v3",
                    0, 1024, "emb-4", null);
            e.setSupportsDimensionOverride(true);
            embed(e, "hello world", "second text");
            assertEquals(wireBody("jina"), stub.requests.get(0).body());
            assertFalse(stub.requests.get(0).body().contains("truncate_prompt_tokens"),
                    "Jina 不支持 truncate_prompt_tokens");
        } finally {
            stub.close();
        }
    }

    @Test
    void azureUsesDeploymentUrlAndApiKeyHeader() {
        Stub stub = new Stub("{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}]}");
        try {
            AzureOpenAiEmbedder e = new AzureOpenAiEmbedder("sk-test", stub.url(),
                    "ada-deployment", 511, 1536, "emb-5", "2024-10-21", null);
            embed(e, "hello");
            Captured c = stub.requests.get(0);
            assertEquals("/openai/deployments/ada-deployment/embeddings", c.path());
            assertEquals("api-version=2024-10-21", c.query());
            assertEquals("sk-test", c.headers().getFirst("api-key"));
            assertEquals(wireBody("azure_openai"), c.body());
        } finally {
            stub.close();
        }
    }

    @Test
    void nvidiaPassageAndQueryInputTypes() {
        for (String name : new String[] {"nvidia_passage", "nvidia_query"}) {
            Stub stub = new Stub("{\"data\":[{\"embedding\":[0.1],\"index\":0}]}");
            try {
                NvidiaEmbedder e = new NvidiaEmbedder("sk-test", stub.url(),
                        "nvidia/nv-embedqa", 1024, "emb-6", null);
                boolean query = name.endsWith("_query");
                List<float[]> got;
                if (query) {
                    try (EmbedQueryContext.Scope s = EmbedQueryContext.markQuery()) {
                        got = embed(e, "hello");
                    }
                } else {
                    got = embed(e, "hello");
                }
                String body = stub.requests.get(0).body();
                assertEquals(wireBody(name), body);
                assertTrue(body.contains(query ? "\"input_type\":\"query\"" : "\"input_type\":\"passage\""));
            } finally {
                stub.close();
            }
        }
    }

    @Test
    void geminiNativeBatchEndpointAndHeaders() {
        Stub stub = new Stub("{\"embeddings\":[{\"values\":[0.1,0.2]},{\"values\":[0.3,0.4]}]}");
        try {
            GeminiEmbedder e = new GeminiEmbedder("sk-test", stub.url() + "/openai",
                    "gemini-embedding-2", 0, 768, "emb-7", null);
            e.setSupportsDimensionOverride(true);
            List<float[]> got = embed(e, "hello world", "second text");
            Captured c = stub.requests.get(0);
            assertEquals("/models/gemini-embedding-2:batchEmbedContents", c.path());
            assertEquals("sk-test", c.headers().getFirst("x-goog-api-key"));
            assertEquals(wireBody("gemini"), c.body());
            assertEquals(0.4f, got.get(1)[1]);
        } finally {
            stub.close();
        }
    }

    @Test
    void geminiCountsMismatchErrors() {
        Stub stub = new Stub("{\"embeddings\":[{\"values\":[0.1]}]}");
        try {
            Embedder e = new GeminiEmbedder("sk-test", stub.url(), "g", 0, 0, "id", null);
            EmbeddingHttp.EmbeddingException err = assertThrows(
                    EmbeddingHttp.EmbeddingException.class, () -> embed(e, "a", "b"));
            assertTrue(err.getMessage()
                    .contains("Gemini BatchEmbed returned 1 embeddings for 2 inputs"), err.getMessage());
        } finally {
            stub.close();
        }
    }

    @Test
    void zhipuRequestShape() {
        Stub stub = new Stub("{\"data\":[{\"embedding\":[0.1],\"index\":0}]}");
        try {
            Embedder e = new ZhipuEmbedder("sk-test", stub.url(), "embedding-3", 511, 0, "emb-8", null);
            embed(e, "hello world", "second text");
            assertEquals(wireBody("zhipu"), stub.requests.get(0).body());
            assertFalse(stub.requests.get(0).body().contains("encoding_format"),
                    "Go 的 ZhipuEmbedRequest 无 encoding_format 字段");
        } finally {
            stub.close();
        }
    }

    @Test
    void weknoraCloudSignsRemoteModelNameAndValidatesIndexes() {
        Stub stub = new Stub(
                "{\"data\":[{\"index\":1,\"embedding\":[0.3,0.4]},{\"index\":0,\"embedding\":[0.1,0.2]}]}");
        try {
            EmbedderConfig cfg = new EmbedderConfig();
            cfg.setBaseUrl(stub.url());
            cfg.setModelName("local-name");
            cfg.setModelId("emb-9");
            cfg.setAppId("app-test");
            cfg.setAppSecret("secret-test");
            cfg.setDimensions(1024);
            cfg.setSupportsDimensionOverride(true);
            cfg.setExtraConfig(Map.of("remote_model_name", "remote-name"));
            WeknoraCloudEmbedder e = new WeknoraCloudEmbedder(cfg);
            List<float[]> got = embed(e, "hello world", "second text");
            assertEquals(wireBody("weknoracloud"), stub.requests.get(0).body());
            assertEquals("/api/v1/embeddings", stub.requests.get(0).path());
            assertEquals(0.1f, got.get(0)[0]);
            Captured c = stub.requests.get(0);
            // 签名头六件套形状（nonce/timestamp 随机，只验存在与 appid/request-id）
            assertEquals("app-test", c.headers().getFirst("X-APPID"));
            assertNotNull(c.headers().getFirst("X-Signature"));
            assertNotNull(c.headers().getFirst("X-Nonce"));
        } finally {
            stub.close();
        }
    }

    @Test
    void weknoraCloudRejectsMalformedResponses() {
        for (String[] tc : new String[][] {
                {"negative index", "{\"data\":[{\"index\":-1,\"embedding\":[0.1]}]}",
                        "response index -1 out of range"},
                {"index above range", "{\"data\":[{\"index\":1,\"embedding\":[0.1]}]}",
                        "response index 1 out of range"},
                {"duplicate", "{\"data\":[{\"index\":0,\"embedding\":[0.1]},{\"index\":0,\"embedding\":[0.2]}]}",
                        "duplicate response index 0"},
                {"missing", "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}",
                        "missing embedding for input index 1"}}) {
            Stub stub = new Stub(tc[1]);
            try {
                EmbedderConfig cfg = new EmbedderConfig();
                cfg.setBaseUrl(stub.url());
                cfg.setModelName("m");
                cfg.setModelId("id");
                cfg.setAppId("app");
                cfg.setAppSecret("secret");
                WeknoraCloudEmbedder e = new WeknoraCloudEmbedder(cfg);
                boolean missing = tc[2].contains("missing");
                EmbeddingHttp.EmbeddingException err = assertThrows(
                        EmbeddingHttp.EmbeddingException.class,
                        () -> e.batchEmbed(missing ? List.of("a", "b") : List.of("a")));
                assertTrue(err.getMessage().contains(tc[2]),
                        tc[0] + ": " + err.getMessage());
            } finally {
                stub.close();
            }
        }
    }

    // ── 错误分类（对照 Go 判定）─────────────────────────────────────

    @Test
    void openAiErrorTruncatesBodyAt1000() {
        Stub stub = new Stub(500, "{\"error\":\"" + "x".repeat(1200) + "\"}");
        try {
            Embedder e = new OpenAiEmbedder("sk-test", stub.url(), "m", 511, 0, "id", null);
            EmbeddingHttp.EmbeddingException err = assertThrows(
                    EmbeddingHttp.EmbeddingException.class, () -> embed(e, "x"));
            assertTrue(err.getMessage().contains("Http Status 500"), () -> err.getMessage());
            assertTrue(err.getMessage().contains("... (truncated)"));
        } finally {
            stub.close();
        }
    }

    @Test
    void openAiModelErrorText() {
        Stub stub = new Stub("");
        try {
            EmbeddingHttp.EmbeddingException err = assertThrows(
                    EmbeddingHttp.EmbeddingException.class,
                    () -> new OpenAiEmbedder("sk-test", stub.url(), "", 511, 0, "id", null));
            assertEquals("model name is required", err.getMessage());
        } finally {
            stub.close();
        }
    }

    // ── SSRF 与 base URL 校验（对照 transport_test.go）──────────────

    @Test
    void ssrfRejectsLinkLocalMetadataBase() {
        RuntimeException err = assertThrows(RuntimeException.class,
                () -> new OpenAiEmbedder("k", "http://169.254.169.254/latest/meta-data",
                        "m", 511, 0, "id", null));
        assertTrue(err.getMessage().contains("SSRF"), err.getMessage());
    }

    @Test
    void ssrfAllowsEmptyBaseAtConstruction() {
        assertDoesNotThrow(() -> validateOnly(""));
    }

    private static void validateOnly(String url) {
        EmbeddingHttp.validateEmbeddingBaseUrl(url);
    }

    // ── ConfigFromModel（对照 config_from_model_test.go）────────────

    @Test
    void configFromModelMapsAllFields() {
        Model m = new Model();
        m.setId("emb-1");
        m.setName("text-embedding-3-small");
        m.setSource("remote");
        ModelParameters p = new ModelParameters();
        p.setBaseUrl("https://api.example.com/v1");
        p.setApiKey("sk-xxx");
        p.setProvider("openai");
        p.getEmbeddingParameters().setDimension(1536);
        p.getEmbeddingParameters().setTruncatePromptTokens(512);
        p.getEmbeddingParameters().setSupportsDimensionOverride(true);
        p.setExtraConfig(Map.of("region", "us-east"));
        p.setCustomHeaders(Map.of("X-Gateway", "g1"));
        m.setParameters(p);

        EmbedderConfig cfg = ModelRuntimeConfigs.embedderConfig(m, "app", "secret");
        assertEquals("emb-1", cfg.getModelId());
        assertEquals("text-embedding-3-small", cfg.getModelName());
        assertEquals(1536, cfg.getDimensions());
        assertEquals(512, cfg.getTruncatePromptTokens());
        assertTrue(cfg.isSupportsDimensionOverride());
        assertEquals("g1", cfg.getCustomHeaders().get("X-Gateway"));
        assertEquals("us-east", cfg.getExtraConfig().get("region"));
        assertEquals("app", cfg.getAppId());
        assertEquals("secret", cfg.getAppSecret());

        assertNull(cfg.getCustomHeaders() == null ? null : null);
        assertEquals(ModelRuntimeConfigs.embedderConfig(null, "a", "b").getModelId(), "");
    }

    // ── 并发治理（对照 concurrency_wrapper_test.go 的语义）──────────

    @Test
    void concurrencyGovernorGatesBackgroundCallsOnly() throws Exception {
        ConcurrencyGovernor governor = new ConcurrencyGovernor();
        com.ragagent.llm.limiter.LocalLimiter limiter = new com.ragagent.llm.limiter.LocalLimiter();
        governor.setGovernor(limiter, 1);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        Embedder inner = new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[] {1};
            }

            @Override
            public List<float[]> batchEmbed(List<String> texts) {
                int now = inFlight.incrementAndGet();
                maxSeen.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
                inFlight.decrementAndGet();
                return List.of(new float[] {1});
            }

            @Override
            public String getModelName() {
                return "emb-gate";
            }

            @Override
            public int getDimensions() {
                return 1;
            }

            @Override
            public String getModelID() {
                return "emb-gate";
            }
        };
        Embedder gated = EmbedderFactory.wrapEmbeddingConcurrency(inner, 1, governor);

        // 后台：限 1（ThreadLocal 不跨线程 → 在每个 worker 内显式 mark，约定 §5）
        Thread[] threads = new Thread[3];
        for (int i = 0; i < 3; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                try (var ignored = com.ragagent.llm.limiter.BackgroundTaskContext.mark()) {
                    gated.batchEmbed(List.of("x"));
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(maxSeen.get() <= 1, "后台 per-model limit=1，max=" + maxSeen.get());

        // 交互式：不受限
        maxSeen.set(0);
        for (int i = 0; i < 3; i++) {
            threads[i] = Thread.ofVirtual().start(() -> gated.batchEmbed(List.of("x")));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(maxSeen.get() >= 2, "交互式不被节流，max=" + maxSeen.get());
    }

    // ── BatchEmbedder（对照 batch.go 语义）──────────────────────────

    @Test
    void batchPoolCarriesResultsAndFirstError() {
        Embedder failing = new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[] {0};
            }

            @Override
            public List<float[]> batchEmbed(List<String> texts) {
                if (texts.get(0).equals("boom")) {
                    throw new EmbeddingHttp.EmbeddingException("provider exploded");
                }
                List<float[]> out = new java.util.ArrayList<>();
                for (String ignored : texts) {
                    out.add(new float[] {1});
                }
                return out;
            }

            @Override
            public String getModelName() {
                return "m";
            }

            @Override
            public int getDimensions() {
                return 1;
            }

            @Override
            public String getModelID() {
                return "m";
            }
        };
        // 正常路径
        Embedder ok = new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[] {0};
            }

            @Override
            public List<float[]> batchEmbed(List<String> texts) {
                List<float[]> out = new java.util.ArrayList<>();
                for (String t : texts) {
                    out.add(new float[] {(float) t.length()});
                }
                return out;
            }

            @Override
            public String getModelName() {
                return "m";
            }

            @Override
            public int getDimensions() {
                return 1;
            }

            @Override
            public String getModelID() {
                return "m";
            }
        };
        EmbedderPooler pooler = new BatchEmbedder(4);
        List<float[]> results = pooler.batchEmbedWithPool(ok, List.of("abc", "def", "g"));
        assertEquals(3, results.size());
        assertEquals(3f, results.get(0)[0]);
        assertEquals(1f, results.get(2)[0]);

        EmbeddingHttp.EmbeddingException err = assertThrows(
                EmbeddingHttp.EmbeddingException.class,
                () -> pooler.batchEmbedWithPool(failing, List.of("boom")));
        assertTrue(err.getMessage().contains("provider exploded"), err.getMessage());
    }

    @Test
    void batchPoolCountMismatchMessage() {
        Embedder wrong = new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[] {0};
            }

            @Override
            public List<float[]> batchEmbed(List<String> texts) {
                return List.of(new float[] {1});
            }

            @Override
            public String getModelName() {
                return "m";
            }

            @Override
            public int getDimensions() {
                return 1;
            }

            @Override
            public String getModelID() {
                return "m";
            }
        };
        EmbeddingHttp.EmbeddingException err = assertThrows(
                EmbeddingHttp.EmbeddingException.class,
                () -> new BatchEmbedder(2).batchEmbedWithPool(wrong, List.of("a", "b", "c")));
        // 默认 BATCH_EMBED_SIZE=5 → 3 条一个子批
        assertTrue(err.getMessage().contains("embedding model returned 1 embeddings for 3 inputs"),
                err.getMessage());
    }
}
