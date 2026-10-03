package com.ragagent.retrieval.engine.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository.VectorEmbedding;
import com.sun.net.httpserver.HttpServer;

/**
 * Elasticsearch v8 检索引擎仓库：
 * 自举（建索引 + 映射探测决定 .keyword 后缀）、文档双向转换与存储估算、写入（单条/NDJSON 批量）、
 * 三种按 terms 删除、script_score 向量检索与 match 关键词检索的<b>请求体形状</b>与响应解析、
 * update_by_query 改状态/标签、CopyIndices 的分页 + 改名 + SourceID 三态。
 *
 * <p>桩是本地 ES 假服务：断言的是"发出去的 JSON 长什么样"（该面无 golden fixture，
 * 逐请求断言即字节契约）。</p>
 */
class ElasticsearchV8RetrieveRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "xwrag_default";

    private record Captured(String method, String path, String query, String body,
                            Map<String, List<String>> headers) {
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
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    new String(raw, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders());
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

    /** 默认桩：HEAD 404（不存在）→ PUT 建索引 → GET 映射 chunk_id=keyword。 */
    private void stubFreshIndex() {
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(404, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"acknowledged\":true}");
        };
    }

    private ElasticsearchV8RetrieveRepository repo(String indexName, int shards, int replicas) {
        return new ElasticsearchV8RetrieveRepository(base, indexName, shards, replicas,
                "elastic", "pwd", null);
    }

    // ── 自举与映射探测 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("自举：HEAD→404 后 PUT 建索引（settings 值转字符串）+ GET _mapping 判定 keyword 不加后缀")
    void bootstrapsIndexAndDetectsKeywordMapping() {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 2, 1);

        assertEquals("elasticsearch", r.engineType());
        assertEquals(List.of("keywords", "vector"), r.support());
        assertFalse(r.useKeywordSuffix());
        assertEquals("chunk_id", r.idField("chunk_id"));

        Captured head = captured.get(0);
        assertEquals("HEAD", head.method());
        assertEquals("/" + INDEX, head.path());
        assertTrue(head.headers().get("Authorization").get(0).startsWith("Basic "),
                "Basic Auth 要带上");

        Captured put = captured.get(1);
        assertEquals("PUT", put.method());
        assertEquals("/" + INDEX, put.path());
        assertEquals("{\"settings\":{\"number_of_shards\":\"2\",\"number_of_replicas\":\"1\"}}",
                put.body());

        assertEquals("GET", captured.get(2).method());
        assertEquals("/" + INDEX + "/_mapping", captured.get(2).path());
    }

    @Test
    @DisplayName("已存在的索引不重建；text 映射 → 全查询带 .keyword 后缀；索引名缺省 xwrag_default")
    void textMappingAddsKeywordSuffix() {
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                    + "{\"chunk_id\":{\"type\":\"text\"}}}}}");
        };
        ElasticsearchV8RetrieveRepository r = repo("", 0, -1);

        assertTrue(r.useKeywordSuffix());
        assertEquals("chunk_id.keyword", r.idField("chunk_id"));
        assertEquals(2, captured.size(), "只应有 HEAD + GET _mapping，不应有 PUT");
        assertEquals("HEAD", captured.get(0).method());
        assertEquals("GET", captured.get(1).method());

        assertEquals("explicit", ElasticsearchV8RetrieveRepository.resolveIndexName("explicit"));
        assertEquals(INDEX,
                ElasticsearchV8RetrieveRepository.resolveIndexName(""));
        assertEquals("elasticsearch address is required",
                assertThrows(IllegalArgumentException.class, () -> new ElasticsearchV8RetrieveRepository(
                        "  ", INDEX, 0, -1, "", "", null)).getMessage());
    }

    // ── 文档转换与存储估算 ──────────────────────────────────────────────────

    @Test
    @DisplayName("存储估算 = 内容字节 + 维度×4 + 250 + (内容+向量)×5/10")
    void estimatesStorageSize() {
        IndexInfo info = new IndexInfo();
        info.sourceId = "s1";
        info.content = "abcd";
        info.chunkId = "c1";
        Map<String, Object> params = Map.of("embedding", Map.of("s1", new float[] {1f, 2f}));
        VectorEmbedding doc = ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(info, params);
        assertArrayEquals(new float[] {1f, 2f}, doc.embedding);
        assertEquals(4 + 8 + 250 + 6, ElasticsearchV8RetrieveRepository.calculateStorageSize(doc));

        ElasticsearchV8RetrieveRepository r = new ElasticsearchV8RetrieveRepository(base, INDEX,
                0, -1, "", "", null, java.net.http.HttpClient.newHttpClient());
        assertEquals(268, r.estimateStorageSize(List.of(info), params));
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("单条写入：POST /{index}/_doc 十字段；空向量报错；chunk_enabled 可覆盖 is_enabled")
    void savesDocuments() throws Exception {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);

        IndexInfo info = new IndexInfo();
        info.content = "内容";
        info.sourceId = "s1";
        info.sourceType = 1;
        info.chunkId = "c1";
        info.knowledgeId = "k1";
        info.knowledgeBaseId = "kb1";
        info.tagId = "t1";
        info.isEnabled = true;
        info.isRecommended = true;

        r.save(info, Map.of("embedding", Map.of("s1", new float[] {0.5f}),
                "chunk_enabled", Map.of("c1", false)));

        Captured post = captured.stream().filter(c -> c.path().endsWith("/_doc"))
                .findFirst().orElseThrow();
        assertEquals("POST", post.method());
        JsonNode body = MAPPER.readTree(post.body());
        assertEquals("内容", body.path("content").asText());
        assertEquals("s1", body.path("source_id").asText());
        assertEquals(1, body.path("source_type").asInt());
        assertEquals("c1", body.path("chunk_id").asText());
        assertEquals("k1", body.path("knowledge_id").asText());
        assertEquals("kb1", body.path("knowledge_base_id").asText());
        assertEquals("t1", body.path("tag_id").asText());
        assertEquals(0.5, body.path("embedding").get(0).asDouble(), 1e-6);
        assertFalse(body.path("is_enabled").asBoolean(), "chunk_enabled 覆盖生效");
        assertTrue(body.path("is_recommended").asBoolean());

        captured.clear();
        IndexInfo noVector = new IndexInfo();
        noVector.chunkId = "cX";
        assertEquals("empty embedding vector for chunk ID: cX",
                assertThrows(IllegalStateException.class,
                        () -> r.save(noVector, Map.of())).getMessage());
        assertTrue(captured.isEmpty(), "空向量不应发起请求");
    }

    @Test
    @DisplayName("批量写入：NDJSON 的 create 行 + 文档行；空列表跳过")
    void batchSaves() throws Exception {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);

        IndexInfo a = new IndexInfo();
        a.sourceId = "s1";
        a.chunkId = "c1";
        a.content = "A";
        IndexInfo b = new IndexInfo();
        b.sourceId = "s2";
        b.chunkId = "c2";
        b.content = "B";

        r.batchSave(List.of(a, b), Map.of("embedding",
                Map.of("s1", new float[] {1f}, "s2", new float[] {2f, 3f})));

        Captured bulk = captured.stream().filter(c -> c.path().endsWith("/_bulk"))
                .findFirst().orElseThrow();
        assertEquals("POST", bulk.method());
        assertEquals("/" + INDEX + "/_bulk", bulk.path());
        String[] lines = bulk.body().split("\n");
        assertEquals(4, lines.length);
        assertEquals("{\"create\":{\"_index\":\"" + INDEX + "\"}}", lines[0]);
        assertEquals("{\"create\":{\"_index\":\"" + INDEX + "\"}}", lines[2]);
        JsonNode doc1 = MAPPER.readTree(lines[1]);
        JsonNode doc2 = MAPPER.readTree(lines[3]);
        assertEquals("s1", doc1.path("source_id").asText());
        assertEquals(1, doc1.path("embedding").size());
        assertEquals(2, doc2.path("embedding").size());

        captured.clear();
        r.batchSave(List.of(), Map.of());
        assertTrue(captured.isEmpty(), "空列表应跳过");
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("三种按 terms 删除（含 .keyword 后缀）；空列表跳过")
    void deletesByTerms() throws Exception {
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"text\"}}}}}");
            }
            return Resp.json(200, "{\"deleted\":1}");
        };
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);
        assertTrue(r.useKeywordSuffix());

        r.deleteByChunkIdList(List.of("c1", "c2"), 0, "");
        r.deleteBySourceIdList(List.of("s1"), 0, "");
        r.deleteByKnowledgeIdList(List.of("k1"), 0, "");

        List<Captured> deletes = captured.stream()
                .filter(c -> c.path().endsWith("/_delete_by_query")).toList();
        assertEquals(3, deletes.size());
        JsonNode first = MAPPER.readTree(deletes.get(0).body());
        assertEquals("c1", first.path("query").path("terms").path("chunk_id.keyword").get(0)
                .asText());
        assertEquals("s1", MAPPER.readTree(deletes.get(1).body())
                .path("query").path("terms").path("source_id.keyword").get(0).asText());
        assertEquals("k1", MAPPER.readTree(deletes.get(2).body())
                .path("query").path("terms").path("knowledge_id.keyword").get(0).asText());

        captured.clear();
        r.deleteByChunkIdList(List.of(), 0, "");
        assertTrue(captured.isEmpty());
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索：script_score + cosineSimilarity + min_score + 过滤条件 + _source 排除；响应解析")
    void vectorRetrieveBuildsScriptScore() throws Exception {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);

        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"hits\":{\"hits\":[{\"_id\":\"doc1\",\"_score\":0.87,"
                    + "\"_source\":{\"content\":\"命中内容\",\"source_id\":\"s1\","
                    + "\"source_type\":0,\"chunk_id\":\"c1\",\"knowledge_id\":\"k1\","
                    + "\"knowledge_base_id\":\"kb1\",\"tag_id\":\"t1\",\"is_enabled\":true}}]}}");
        };

        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "vector";
        params.query = "";
        params.embedding = new float[] {0.1f, 0.2f};
        params.knowledgeBaseIds = List.of("kb1");
        params.excludeChunkIds = List.of("c9");
        params.topK = 10;
        params.threshold = 0.5;

        List<RetrieveResult> results = r.vectorRetrieve(params);
        assertEquals(1, results.size());
        RetrieveResult result = results.get(0);
        assertEquals("elasticsearch", result.retrieverEngineType());
        assertEquals("vector", result.retrieverType());
        assertEquals(1, result.results().size());
        assertEquals("doc1", result.results().get(0).id);
        assertEquals(0.87, result.results().get(0).score, 1e-9);
        assertEquals(0, result.results().get(0).matchType);
        assertEquals("c1", result.results().get(0).chunkId);
        assertEquals("命中内容", result.results().get(0).content);
        assertTrue(result.results().get(0).isEnabled);

        JsonNode body = MAPPER.readTree(captured.stream()
                .filter(c -> c.path().endsWith("/_search")).findFirst().orElseThrow().body());
        JsonNode scriptScore = body.path("query").path("script_score");
        assertEquals("cosineSimilarity(params.query_vector, 'embedding')",
                scriptScore.path("script").path("source").asText());
        assertEquals(0.1, scriptScore.path("script").path("params").path("query_vector").get(0)
                .asDouble(), 1e-6);
        assertEquals(0.5, scriptScore.path("min_score").asDouble(), 1e-6);
        JsonNode filter = scriptScore.path("query").path("bool").path("filter").get(0)
                .path("bool");
        assertEquals("kb1", filter.path("must").get(0).path("terms")
                .path("knowledge_base_id").get(0).asText());
        assertFalse(filter.path("must_not").get(0).path("term").path("is_enabled")
                .path("value").asBoolean());
        assertEquals("c9", filter.path("must_not").get(1).path("terms").path("chunk_id")
                .get(0).asText());
        assertEquals(10, body.path("size").asInt());
        assertEquals("embedding", body.path("_source").path("excludes").get(0).asText());
    }

    @Test
    @DisplayName("关键词检索：bool{must:[match content]}；响应解析 matchType=1；未知检索类型报错")
    void keywordsRetrieveBuildsMatchQuery() throws Exception {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);

        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"hits\":{\"hits\":[{\"_id\":\"doc2\",\"_score\":1.5,"
                    + "\"_source\":{\"content\":\"B\",\"chunk_id\":\"c2\","
                    + "\"knowledge_base_id\":\"kb1\"}}]}}");
        };

        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "keywords";
        params.query = "你好 世界";
        params.knowledgeBaseIds = List.of("kb1");
        params.excludeKnowledgeIds = List.of("k9");
        params.topK = 5;

        List<RetrieveResult> results = r.keywordsRetrieve(params);
        assertEquals(1, results.get(0).results().size());
        assertEquals(1, results.get(0).results().get(0).matchType, "keywords → MatchTypeKeywords=1");
        assertEquals("keywords", results.get(0).retrieverType());

        JsonNode body = MAPPER.readTree(captured.stream()
                .filter(c -> c.path().endsWith("/_search")).findFirst().orElseThrow().body());
        JsonNode bool = body.path("query").path("bool");
        assertEquals("你好 世界", bool.path("must").get(0).path("match").path("content")
                .path("query").asText());
        assertEquals("k9", bool.path("filter").get(0).path("bool").path("must_not").get(1)
                .path("terms").path("knowledge_id").get(0).asText());
        assertEquals(5, body.path("size").asInt());

        RetrieveParams bad = new RetrieveParams();
        bad.retrieverType = "graph";
        assertEquals("invalid retriever type: graph",
                assertThrows(IllegalArgumentException.class, () -> r.retrieve(bad)).getMessage());
    }

    // ── 批量改状态/标签 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("改状态：启用/停用各一次 update_by_query（painless）；改标签：按 tag 分组")
    void batchUpdates() throws Exception {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, "{\"updated\":1}");
        };

        r.batchUpdateChunkEnabledStatus(Map.of("c1", true, "c2", false));
        List<Captured> updates = captured.stream()
                .filter(c -> c.path().endsWith("/_update_by_query")).toList();
        assertEquals(2, updates.size());
        JsonNode enable = MAPPER.readTree(updates.get(0).body());
        assertEquals("ctx._source.is_enabled = true", enable.path("script").path("source").asText());
        assertEquals("painless", enable.path("script").path("lang").asText());
        assertEquals("c1", enable.path("query").path("bool").path("must").get(0)
                .path("terms").path("chunk_id").get(0).asText());
        assertEquals("ctx._source.is_enabled = false",
                MAPPER.readTree(updates.get(1).body()).path("script").path("source").asText());

        captured.clear();
        r.batchUpdateChunkTagID(Map.of("c1", "t1", "c2", "t1", "c3", "t2"));
        List<Captured> tagUpdates = captured.stream()
                .filter(c -> c.path().endsWith("/_update_by_query")).toList();
        assertEquals(2, tagUpdates.size(), "按 tag 分两组");
        // Map.of 无序 → 组间顺序不保证，按 tag 名归集断言
        java.util.Map<String, Integer> groupSizes = new java.util.TreeMap<>();
        for (Captured c : tagUpdates) {
            JsonNode body = MAPPER.readTree(c.body());
            assertEquals("ctx._source.tag_id = params.tag_id",
                    body.path("script").path("source").asText());
            String tag = body.path("script").path("params").path("tag_id").asText();
            groupSizes.put(tag, body.path("query").path("bool").path("must").get(0)
                    .path("terms").path("chunk_id").size());
        }
        assertEquals(java.util.Map.of("t1", 2, "t2", 1), groupSizes);
    }

    // ── 复制索引 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("复制索引：分页检索 → 映射改名 → SourceID 三态 → bulk 写入带目标向量")
    void copyIndices() throws Exception {
        stubFreshIndex();
        List<String> searchCalls = new ArrayList<>();
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            if (req.path().endsWith("/_search")) {
                searchCalls.add(req.body());
                return Resp.json(200, "{\"hits\":{\"hits\":["
                        + "{\"_id\":\"d1\",\"_score\":1.0,\"_source\":{\"content\":\"A\","
                        + "\"source_id\":\"c1\",\"source_type\":0,\"chunk_id\":\"c1\","
                        + "\"knowledge_id\":\"k1\",\"embedding\":[0.25,0.5]}},"
                        + "{\"_id\":\"d2\",\"_score\":1.0,\"_source\":{\"content\":\"Q\","
                        + "\"source_id\":\"c1-q9\",\"source_type\":0,\"chunk_id\":\"c1\","
                        + "\"knowledge_id\":\"k1\",\"embedding\":[0.7]}},"
                        + "{\"_id\":\"d3\",\"_score\":1.0,\"_source\":{\"content\":\"Z\","
                        + "\"source_id\":\"other-shape\",\"source_type\":0,\"chunk_id\":\"c2\","
                        + "\"knowledge_id\":\"k1\"}}]}}");
            }
            return Resp.json(200, "{}");
        };
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);

        r.copyIndices("kb-old", Map.of("k1", "k-new"), Map.of("c1", "c-new1", "c2", "c-new2"),
                "kb-new", 2, "");

        assertEquals(1, searchCalls.size(), "3 条 < 批大小 500 → 只翻一页");
        JsonNode search = MAPPER.readTree(searchCalls.get(0));
        assertEquals(0, search.path("from").asInt());
        assertEquals(500, search.path("size").asInt());
        assertEquals("kb-old", search.path("query").path("bool").path("filter").get(0)
                .path("bool").path("must").get(0).path("terms").path("knowledge_base_id")
                .get(0).asText());

        Captured bulk = captured.stream().filter(c -> c.path().endsWith("/_bulk"))
                .findFirst().orElseThrow();
        String[] lines = bulk.body().split("\n");
        assertEquals(6, lines.length, "3 条文档 → 3 对行");
        JsonNode doc1 = MAPPER.readTree(lines[1]);
        assertEquals("c-new1", doc1.path("chunk_id").asText());
        assertEquals("c-new1", doc1.path("source_id").asText(), "普通块：SourceID = 目标 chunkID");
        assertEquals("k-new", doc1.path("knowledge_id").asText());
        assertEquals("kb-new", doc1.path("knowledge_base_id").asText());
        // 修复后：向量按"目标 SourceID"为键随行带上——doc1（普通块）拿自己的 [0.25,0.5]；
        // doc2（生成问题，目标 SourceID c-new1-q9）也拿自己的 [0.7]
        assertEquals(2, doc1.path("embedding").size());
        assertEquals(0.25, doc1.path("embedding").get(0).asDouble(), 1e-6);

        JsonNode doc2 = MAPPER.readTree(lines[3]);
        assertEquals("c-new1-q9", doc2.path("source_id").asText(),
                "生成问题：保留 questionID 段");
        assertEquals(1, doc2.path("embedding").size(), "生成问题也带自己的向量（Go 取不到）");
        assertEquals(0.7, doc2.path("embedding").get(0).asDouble(), 1e-6);

        JsonNode doc3 = MAPPER.readTree(lines[5]);
        assertTrue(doc3.path("source_id").asText()
                        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "异常形态 → 新 UUID");
        assertTrue(doc3.path("embedding").isMissingNode() || doc3.path("embedding").isNull(),
                "无源向量 → embedding 为 null（照 Go 无 omitempty）");

        captured.clear();
        r.copyIndices("kb-old", Map.of(), Map.of(), "kb-new", 2, "");
        assertTrue(captured.isEmpty(), "空映射直接跳过");

        assertNotNull(ElasticsearchV8RetrieveRepository.resolveIndexName(INDEX));
    }

    // ── 迁移知识（9 例完整性校验） ───────────────────────────────────────────

    @Test
    @DisplayName("迁移知识：terms 数组 + bool.filter + refresh=true + 脚本无 lang；9 例完整性校验")
    void moveKnowledgeIndices() throws Exception {
        stubFreshIndex();
        ElasticsearchV8RetrieveRepository r = repo(INDEX, 0, -1);
        String[] responses = {null};
        responder = req -> {
            if (req.method().equals("HEAD")) {
                return Resp.json(200, "");
            }
            if (req.path().endsWith("/_mapping")) {
                return Resp.json(200, "{\"" + INDEX + "\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}");
            }
            return Resp.json(200, responses[0]);
        };

        Object[][] cases = {
                {"{\"total\":100,\"updated\":100}", false},
                {"{\"total\":0,\"updated\":0}", false},
                {"{\"total\":100,\"updated\":40}", true},
                {"{\"updated\":40}", true},
                {"{\"total\":40}", true},
                {"{}", true},
                {"{\"total\":-1,\"updated\":-1}", true},
                {"{\"total\":1,\"updated\":1,\"version_conflicts\":1}", true},
                {"{\"total\":1,\"updated\":1,\"timed_out\":true}", true},
        };
        for (Object[] c : cases) {
            responses[0] = (String) c[0];
            boolean fails = (boolean) c[1];
            if (fails) {
                assertEquals("move indices was incomplete",
                        assertThrows(IllegalStateException.class,
                                () -> r.moveKnowledgeIndices("source", "target", "doc", null, 3, "file"))
                                .getMessage(), (String) c[0]);
            } else {
                r.moveKnowledgeIndices("source", "target", "doc", null, 3, "file");
            }
        }

        Captured call = captured.stream().filter(c -> c.path().endsWith("/_update_by_query"))
                .findFirst().orElseThrow();
        assertTrue(call.query() != null && call.query().contains("refresh=true"));
        JsonNode body = MAPPER.readTree(call.body());
        JsonNode boolBody = body.path("query").path("bool");
        assertEquals("source", boolBody.path("filter").get(0).path("terms")
                .path("knowledge_base_id").get(0).asText(), "v8 用 terms 数组");
        assertEquals("doc", boolBody.path("filter").get(1).path("terms").path("knowledge_id")
                .get(0).asText());
        assertEquals("ctx._source.knowledge_base_id = params.target; ctx._source.tag_id = ''",
                body.path("script").path("source").asText());
        assertEquals("target", body.path("script").path("params").path("target").asText());
        assertTrue(body.path("script").path("lang").isMissingNode(), "v8 的脚本不带 lang");
    }
}
