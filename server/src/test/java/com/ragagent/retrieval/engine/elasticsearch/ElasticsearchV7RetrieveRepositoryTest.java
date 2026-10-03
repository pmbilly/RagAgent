package com.ragagent.retrieval.engine.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.sun.net.httpserver.HttpServer;

/**
 * Elasticsearch v7 检索引擎仓库。
 * 重点验 v7 特有形状：keywords-only 的 Support/分派、PUT _create/{uuid}、带空格的 bulk 动作行、
 * SDK 只告警不失败、数字 settings、singular term 的 move；另钉两处有意修正：
 * 向量命中标 MatchTypeEmbedding、CopyIndices 向量随行带上。
 */
class ElasticsearchV7RetrieveRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "xwrag_default";

    private record Captured(String method, String path, String query, String body) {
    }

    private HttpServer server;
    private String base;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, Resp> responder = c -> Resp.json(200, "{}");

    private record Resp(int status, String body) {
        static Resp json(int status, String body) {
            return new Resp(status, body);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Captured req = new Captured(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    new String(raw, StandardCharsets.UTF_8));
            captured.add(req);
            Resp resp = responder.apply(req);
            byte[] body = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(resp.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stubFreshIndex(int status) {
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(404, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(status, "{\"acknowledged\":true}");
        };
    }

    private ElasticsearchV7RetrieveRepository repo() {
        return new ElasticsearchV7RetrieveRepository(base, INDEX, 0, -1, "", "", null);
    }

    @Test
    @DisplayName("自举：数字 settings（非 v8 的字符串）+ keyword 判定；Support 只有 keywords")
    void bootstrapsWithNumericSettings() {
        stubFreshIndex(200);
        ElasticsearchV7RetrieveRepository r =
                new ElasticsearchV7RetrieveRepository(base, INDEX, 2, 1, "elastic", "pwd", null);

        assertEquals("elasticsearch", r.engineType());
        assertEquals(List.of("keywords"), r.support(), "v7 不带向量（照 Go）");
        assertFalse(r.useKeywordSuffix());
        assertEquals("chunk_id", r.idField("chunk_id"));

        Captured put = captured.get(1);
        assertEquals("PUT", put.method());
        assertEquals("{\"settings\":{\"number_of_shards\":2,\"number_of_replicas\":1}}", put.body(),
                "v7 的 settings 值是数字（v8 是字符串）");
    }

    @Test
    @DisplayName("建索引失败文案固定为 failed to create index <index>；text 映射 → .keyword 后缀")
    void createIndexFailureMessages() {
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(404, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"text\"}}}}}");
            }
            return Resp.json(500, "{\"error\":\"boom\"}");
        };
        ElasticsearchV7RetrieveRepository r = repo();
        assertEquals("failed to create index " + INDEX,
                assertThrows(IllegalStateException.class, r::createIndexIfNotExists).getMessage());
        assertTrue(r.useKeywordSuffix());
        assertEquals("chunk_id.keyword", r.idField("chunk_id"));
    }

    @Test
    @DisplayName("存储估算同 v8 公式；单条写入走 PUT /{index}/_create/{uuid}；空向量报错")
    void savesDocuments() throws Exception {
        stubFreshIndex(201);
        ElasticsearchV7RetrieveRepository r = repo();

        IndexInfo info = new IndexInfo();
        info.sourceId = "s1";
        info.content = "abcd";
        info.chunkId = "c1";
        Map<String, Object> params = Map.of("embedding", Map.of("s1", new float[] {1f, 2f}));
        ElasticsearchV8RetrieveRepository.VectorEmbedding doc =
                ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(info, params);
        assertArrayEquals(new float[] {1f, 2f}, doc.embedding);
        assertEquals(268, r.estimateStorageSize(List.of(info), params));

        r.save(info, params);
        Captured create = captured.stream()
                .filter(c -> c.path().contains("/_create/")).findFirst().orElseThrow();
        assertEquals("PUT", create.method());
        assertTrue(create.path().matches("/" + INDEX
                        + "/_create/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "显式 UUID 文档 ID：" + create.path());
        assertEquals("s1", MAPPER.readTree(create.body()).path("source_id").asText());

        IndexInfo noVector = new IndexInfo();
        noVector.chunkId = "cX";
        assertEquals("empty embedding vector for chunk ID: cX",
                assertThrows(IllegalStateException.class, () -> r.save(noVector, Map.of()))
                        .getMessage());
    }

    @Test
    @DisplayName("批量：动作行 { \"index\" : { \"_id\" : uuid } }（带空格）；errors:true 只告警不失败")
    void batchSaveToleratesPartialErrors() throws Exception {
        stubFreshIndex(200);
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"errors\":true,\"items\":[{\"index\":"
                    + "{\"error\":{\"type\":\"mapper_parsing_exception\"}}}]}");
        };
        ElasticsearchV7RetrieveRepository r = repo();

        IndexInfo a = new IndexInfo();
        a.sourceId = "s1";
        a.chunkId = "c1";
        r.batchSave(List.of(a), Map.of("embedding", Map.of("s1", new float[] {1f})));

        String ndjson = captured.stream().filter(c -> c.path().endsWith("/_bulk"))
                .findFirst().orElseThrow().body();
        String[] lines = ndjson.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("{ \"index\" : { \"_id\" : \""), lines[0]);
        assertTrue(lines[0].endsWith("\" } }"), lines[0]);

        captured.clear();
        r.batchSave(List.of(), Map.of());
        assertTrue(captured.isEmpty(), "空列表跳过");
    }

    @Test
    @DisplayName("删除：手拼 terms；.keyword 后缀生效；空列表跳过")
    void deletesByField() throws Exception {
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"text\"}}}}}");
            }
            return Resp.json(200, "{\"deleted\":2}");
        };
        ElasticsearchV7RetrieveRepository r = repo();

        r.deleteByChunkIdList(List.of("c1"), 0, "");
        JsonNode body = MAPPER.readTree(captured.stream()
                .filter(c -> c.path().endsWith("/_delete_by_query")).findFirst().orElseThrow()
                .body());
        assertEquals("c1", body.path("query").path("terms").path("chunk_id.keyword").get(0)
                .asText());

        captured.clear();
        r.deleteByChunkIdList(List.of(), 0, "");
        assertTrue(captured.isEmpty());
    }

    @Test
    @DisplayName("关键词检索：must=match + filter=基础条件；命中恒标 MatchTypeKeywords；坏命中跳过")
    void keywordsRetrieveSkipsBadHits() throws Exception {
        stubFreshIndex(200);
        ElasticsearchV7RetrieveRepository r = repo();
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"hits\":{\"hits\":["
                    + "{\"_id\":\"d1\",\"_score\":1.2,\"_source\":{\"content\":\"A\","
                    + "\"chunk_id\":\"c1\",\"is_enabled\":false}},"
                    + "{\"_id\":\"d2\",\"_source\":{\"content\":\"B\"}}]}}");
        };

        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "keywords";
        params.query = "你好";
        params.knowledgeBaseIds = List.of("kb1");
        params.topK = 3;
        List<RetrieveResult> results = r.keywordsRetrieve(params);

        assertEquals(1, results.get(0).results().size(), "缺 _score 的命中被跳过（照 Go）");
        assertEquals("d1", results.get(0).results().get(0).id);
        assertEquals(1, results.get(0).results().get(0).matchType);
        assertFalse(results.get(0).results().get(0).isEnabled);

        JsonNode body = MAPPER.readTree(captured.stream()
                .filter(c -> c.path().endsWith("/_search")).findFirst().orElseThrow().body());
        JsonNode bool = body.path("query").path("bool");
        assertEquals("你好", bool.path("must").get(0).path("match").path("content").path("query")
                .asText());
        assertEquals("kb1", bool.path("filter").get(0).path("bool").path("must").get(0)
                .path("terms").path("knowledge_base_id").get(0).asText());

        RetrieveParams vector = new RetrieveParams();
        vector.retrieverType = "vector";
        assertEquals("invalid retriever type: vector",
                assertThrows(IllegalArgumentException.class, () -> r.retrieve(vector)).getMessage(),
                "v7 的 Retrieve 只分派 keywords（照 Go）");
    }

    @Test
    @DisplayName("向量检索可直呼：script 源无空格、min_score 是 float64；命中标 MatchTypeEmbedding（修复 Go 的误标）")
    void vectorRetrieveMarksEmbedding() throws Exception {
        stubFreshIndex(200);
        ElasticsearchV7RetrieveRepository r = repo();
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"hits\":{\"hits\":[{\"_id\":\"d1\",\"_score\":0.9,"
                    + "\"_source\":{\"content\":\"A\",\"chunk_id\":\"c1\"}}]}}");
        };

        RetrieveParams params = new RetrieveParams();
        params.embedding = new float[] {0.5f};
        params.topK = 7;
        params.threshold = 0.25;
        List<RetrieveResult> results = r.vectorRetrieve(params);

        assertEquals(0, results.get(0).results().get(0).matchType,
                "修复后：向量结果标 MatchTypeEmbedding=0（Go v7 会误标 1）");
        JsonNode body = MAPPER.readTree(captured.stream()
                .filter(c -> c.path().endsWith("/_search")).findFirst().orElseThrow().body());
        JsonNode scriptScore = body.path("query").path("script_score");
        assertEquals("cosineSimilarity(params.query_vector,'embedding')",
                scriptScore.path("script").path("source").asText(), "v7 的源串无空格（v8 有）");
        assertEquals(0.25, scriptScore.path("min_score").asDouble(), 1e-9);
        assertEquals(7, body.path("size").asInt());
        assertTrue(body.path("_source").isMissingNode(), "v7 不带 _source 排除（v8 带）");
    }

    @Test
    @DisplayName("改状态/标签：query 是直构 terms（不套 bool）、脚本带 lang；失败文案含状态码")
    void batchUpdatesUseDirectTerms() throws Exception {
        stubFreshIndex(200);
        ElasticsearchV7RetrieveRepository r = repo();

        r.batchUpdateChunkEnabledStatus(Map.of("c1", true));
        JsonNode body = MAPPER.readTree(captured.stream()
                .filter(c -> c.path().endsWith("/_update_by_query")).findFirst().orElseThrow()
                .body());
        assertTrue(body.path("query").path("bool").isMissingNode(), "v7 不套 bool（v8 套）");
        assertEquals("c1", body.path("query").path("terms").path("chunk_id").get(0).asText());
        assertEquals("painless", body.path("script").path("lang").asText());

        responder = req -> Resp.json(500, "{\"error\":\"boom\"}");
        assertEquals("elasticsearch update_by_query failed with status: 500",
                assertThrows(IllegalStateException.class,
                        () -> r.batchUpdateChunkTagID(Map.of("c1", "t1"))).getMessage());
    }

    @Test
    @DisplayName("复制索引：三态 SourceID + 向量随行带上（修复 Go 的丢向量缺陷）")
    void copyIndicesCarriesEmbeddings() throws Exception {
        stubFreshIndex(200);
        List<String> searchBodies = new CopyOnWriteArrayList<>();
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            if (req.path().endsWith("/_search")) {
                searchBodies.add(req.body());
                return Resp.json(200, "{\"hits\":{\"hits\":["
                        + "{\"_id\":\"d1\",\"_score\":1.0,\"_source\":{\"content\":\"A\","
                        + "\"source_id\":\"c1\",\"chunk_id\":\"c1\",\"knowledge_id\":\"k1\","
                        + "\"embedding\":[0.25,0.5]}},"
                        + "{\"_id\":\"d2\",\"_score\":1.0,\"_source\":{\"content\":\"Q\","
                        + "\"source_id\":\"c1-q9\",\"chunk_id\":\"c1\",\"knowledge_id\":\"k1\","
                        + "\"embedding\":[0.7]}}]}}");
            }
            return Resp.json(200, "{\"errors\":false,\"items\":[]}");
        };
        ElasticsearchV7RetrieveRepository r = repo();

        r.copyIndices("kb-old", Map.of("k1", "k-new"), Map.of("c1", "c-new1"), "kb-new", 2, "");

        JsonNode search = MAPPER.readTree(searchBodies.get(0));
        assertEquals(0, search.path("from").asInt());
        assertEquals(500, search.path("size").asInt());
        assertEquals("kb-old", search.path("query").path("bool").path("must").get(0)
                .path("terms").path("knowledge_base_id").get(0).asText());

        String[] lines = captured.stream().filter(c -> c.path().endsWith("/_bulk"))
                .findFirst().orElseThrow().body().split("\n");
        assertEquals(4, lines.length);
        JsonNode doc1 = MAPPER.readTree(lines[1]);
        assertEquals("c-new1", doc1.path("chunk_id").asText());
        assertEquals("c-new1", doc1.path("source_id").asText());
        assertEquals(2, doc1.path("embedding").size(), "普通块带自己的向量（Go 会丢）");
        assertEquals(0.25, doc1.path("embedding").get(0).asDouble(), 1e-6);
        JsonNode doc2 = MAPPER.readTree(lines[3]);
        assertEquals("c-new1-q9", doc2.path("source_id").asText(), "生成问题保留 questionID 段");
        assertEquals(1, doc2.path("embedding").size(), "生成问题也带自己的向量（Go 取不到）");
        assertEquals(0.7, doc2.path("embedding").get(0).asDouble(), 1e-6);
    }

    @Test
    @DisplayName("迁移知识（v7 版）：singular term + lang + refresh=true；完整性校验同 v8")
    void moveKnowledgeIndices() throws Exception {
        stubFreshIndex(200);
        ElasticsearchV7RetrieveRepository r = repo();

        boolean[] complete = {true};
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, complete[0] ? "{\"total\":3,\"updated\":3}"
                    : "{\"total\":3,\"updated\":2,\"failures\":[{\"id\":\"x\"}]}");
        };

        r.moveKnowledgeIndices("kb-src", "kb-tgt", "k1", null, 2, "");
        Captured call = captured.stream().filter(c -> c.path().endsWith("/_update_by_query"))
                .findFirst().orElseThrow();
        assertTrue(call.query() != null && call.query().contains("refresh=true"),
                "refresh=true：" + call.query());
        JsonNode body = MAPPER.readTree(call.body());
        assertEquals("kb-src", body.path("query").path("bool").path("filter").get(0)
                .path("term").path("knowledge_base_id").asText(), "v7 用 singular term");
        assertEquals("kb-tgt", body.path("script").path("params").path("target").asText());
        assertEquals("painless", body.path("script").path("lang").asText(), "v7 带 lang（v8 不带）");

        complete[0] = false;
        assertEquals("move indices was incomplete",
                assertThrows(IllegalStateException.class,
                        () -> r.moveKnowledgeIndices("kb-src", "kb-tgt", "k1", null, 2, "")).getMessage());
    }
}
